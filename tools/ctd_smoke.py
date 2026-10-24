#!/usr/bin/env python3
"""Disposable loopback CTD smoke check. Never reads production plugin settings."""

import argparse
import hashlib
import http.server
import io
import json
import os
from pathlib import Path
import re
import select
import shutil
import socket
import socketserver
import struct
import subprocess
import threading
import time
import uuid
import zipfile


HERE = Path(__file__).resolve().parent
JAVA = Path(os.environ.get('JAVA_HOME', '')) / 'bin/java' if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or 'java')
CTD = None
FORCEPACK = None
NLOGIN = None


def vint(number):
    result = bytearray()
    while True:
        value = number & 127
        number >>= 7
        result.append(value | (128 if number else 0))
        if not number:
            return bytes(result)


def string(value):
    raw = value.encode()
    return vint(len(raw)) + raw


def exact(stream, length):
    result = bytearray()
    while len(result) < length:
        chunk = stream.recv(length - len(result))
        if not chunk:
            raise EOFError('connection closed')
        result.extend(chunk)
    return bytes(result)


def read_vint(stream):
    result = 0
    for index in range(5):
        value = exact(stream, 1)[0]
        result |= (value & 127) << (index * 7)
        if not value & 128:
            return result
    raise ValueError('oversized VarInt')


def take_vint(data, offset=0):
    value = 0
    for index in range(5):
        byte = data[offset + index]
        value |= (byte & 127) << (7 * index)
        if not byte & 128:
            return value, offset + index + 1
    raise ValueError('oversized VarInt')


def take_string(data, offset=0):
    length, offset = take_vint(data, offset)
    return data[offset:offset + length].decode(), offset + length


def packet(stream, identifier, payload=b''):
    body = vint(identifier) + payload
    stream.sendall(vint(len(body)) + body)


def read_packet(stream):
    length = read_vint(stream)
    if length > 8 * 1024 * 1024:
        raise ValueError('unexpected packet larger than 8 MiB')
    body = exact(stream, length)
    identifier, offset = take_vint(body)
    return identifier, body[offset:]


class Backend(socketserver.ThreadingTCPServer):
    allow_reuse_address = False
    daemon_threads = True


class BackendHandler(socketserver.BaseRequestHandler):
    def handle(self):
        stream = self.request
        stream.settimeout(75)
        try:
            identifier, handshake = read_packet(stream)
            protocol, offset = take_vint(handshake)
            _, offset = take_string(handshake, offset)
            state, _ = take_vint(handshake, offset + 2)
            if identifier != 0:
                return
            if state == 1:
                read_packet(stream)
                response = {'version': {'name': 'smoke-backend', 'protocol': 765},
                            'players': {'max': 10, 'online': 0}, 'description': {'text': 'smoke-backend'}}
                packet(stream, 0, string(json.dumps(response)))
                identifier, payload = read_packet(stream)
                if identifier == 1:
                    packet(stream, 1, payload)
                return
            identifier, login = read_packet(stream)
            name, _ = take_string(login)
            self.server.events.append({'backend_login': name, 'protocol': protocol})
            if protocol != 765:
                return
            player_id = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode()).digest(), version=3)
            packet(stream, 2, player_id.bytes + string(name) + vint(0))
            identifier, _ = read_packet(stream)
            self.server.events.append({'backend_ack': identifier})
            if identifier != 3:
                return
            # Minimal fake backend deliberately supplies no world. This exercises CONFIG events only.
            packet(stream, 2)
            identifier, _ = read_packet(stream)
            while identifier != 2:
                identifier, _ = read_packet(stream)
            self.server.events.append({'backend_config_finished': identifier})
            # Velocity may acknowledge backend CONFIG before the client CONFIG event is released.
            # Keep this fixture connection open until the proxy closes it; never end a held client session early.
            while True:
                read_packet(stream)
        except (EOFError, OSError, ValueError, IndexError) as error:
            self.server.events.append({'backend_end': str(error)})


class PackHandler(http.server.BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.send_response(200)
        self.send_header('Content-Length', str(len(self.server.pack)))
        self.send_header('Content-Type', 'application/zip')
        self.end_headers()

    def do_GET(self):
        self.do_HEAD()
        self.wfile.write(self.server.pack)

    def log_message(self, *_):
        pass


class LocalHTTPServer(http.server.ThreadingHTTPServer):
    def server_bind(self):
        socketserver.TCPServer.server_bind(self)
        self.server_name = 'localhost'
        self.server_port = self.server_address[1]


def replace_setting(text, section, name, value):
    current = ''
    output = []
    for line in text.splitlines(keepends=True):
        if line.strip().startswith('['):
            current = line.strip()[1:-1]
        if current == section and re.match(r'^\s*' + re.escape(name) + r'\s*=', line):
            line = name + ' = ' + value + '\n'
        output.append(line)
    return ''.join(output)


def section(text, name, replacement):
    pattern = r'(?ms)^\[' + re.escape(name) + r'\]\s*\n.*?(?=^\[|\Z)'
    return re.sub(pattern, '[' + name + ']\n' + replacement + '\n', text)


def prepare(runtime, args, proxy_port, backend_port, http_port, pack):
    plugins = runtime / 'plugins'
    plugins.mkdir(parents=True)
    sources = [(args.limboapi, 'limboapi.jar'), (args.reconnect, 'limboreconnect.jar'), (FORCEPACK, 'forcepack.jar')]
    if args.with_nlogin:
        sources.append((NLOGIN, 'nlogin.jar'))
    manifest = {}
    for source, destination in sources:
        source = Path(source).resolve(strict=True)
        shutil.copyfile(source, plugins / destination)
        manifest[destination] = {'source': str(source), 'sha256': hashlib.sha256(source.read_bytes()).hexdigest()}

    fixture_classes = runtime / 'fixture-classes'
    fixture_classes.mkdir()
    subprocess.run([str(JAVA.parent / 'javac'), '--release', '25', '-proc:none', '-classpath', str(CTD),
                    '-d', str(fixture_classes), str(HERE / 'SmokeReload.java')], check=True)
    fixture_description = {'id': 'mcexp-smoke-reload', 'name': 'Disposable smoke reload fixture', 'version': '1',
                           'main': 'mcexp.smoke.SmokeReload', 'dependencies': [{'id': 'limboapi', 'optional': False}]}
    (fixture_classes / 'velocity-plugin.json').write_text(json.dumps(fixture_description))
    with zipfile.ZipFile(plugins / 'smoke-reload-fixture.jar', 'w') as archive:
        for source in fixture_classes.rglob('*'):
            if source.is_file():
                archive.write(source, source.relative_to(fixture_classes))

    with zipfile.ZipFile(CTD) as archive:
        config = archive.read('default-velocity.toml').decode()
    changes = [('', 'bind', json.dumps(f'127.0.0.1:{proxy_port}')), ('', 'online-mode', 'false'),
               ('', 'offline-mode-encryption', 'false'), ('', 'force-key-authentication', 'false'),
               ('', 'enforce-chat-signing', 'false'), ('', 'player-info-forwarding-mode', '"none"'),
               ('advanced', 'compression-threshold', '-1'), ('advanced', 'login-ratelimit', '0'),
               ('advanced', 'read-timeout', '75000'),
               ('advanced', 'login-timeout', '15000'), ('advanced', 'haproxy-protocol', 'false'),
               ('queue', 'enabled', 'false'), ('redis', 'enabled', 'false'), ('query', 'enabled', 'false')]
    for group, key, value in changes:
        config = replace_setting(config, group, key, value)
    config = section(config, 'servers', f'smoke-backend = "127.0.0.1:{backend_port}"\ntry = ["smoke-backend"]')
    config = section(config, 'forced-hosts', '')
    config = section(config, 'slash-servers', '')
    config = section(config, 'playercaps', '')
    config = re.sub(r'(?ms)^\[server-links\].*\Z', '', config)
    (runtime / 'velocity.toml').write_text(config)

    limbo = plugins / 'limboapi'
    limbo.mkdir()
    (limbo / 'config.yml').write_text('main:\n  check-for-updates: false\n  compatibility-mode: true\n'
                                     '  prepare-min-version: "1_20_2"\n  prepare-max-version: "1_21_4"\n'
                                     '  pre-limbo-profile-request-plugins: [floodgate, geyser, nlogin]\n')
    reconnect = plugins / 'limboreconnect'
    reconnect.mkdir()
    (reconnect / 'config.yml').write_text('use-limbo: true\ndebug: true\n')

    with zipfile.ZipFile(FORCEPACK) as archive:
        force = archive.read('config.toml').decode()
    local_url = f'http://127.0.0.1:{http_port}/pack.zip'
    force = re.sub(r'https://www\.convallyria\.com/files/BlankPack\.zip', local_url, force)
    force = force.replace('118AFFFC54CDCD308702F81BA24E03223F15FE5F', hashlib.sha1(pack).hexdigest())
    force = re.sub(r'(?ms)^\[groups\].*?(?=^\[servers\])', '[groups]\n\n', force)
    force = force.replace('example-server', 'smoke-backend')
    for group, key, value in [('', 'verify-resource-packs', 'false'), ('', 'debug', 'true'),
                              ('', 'use-configuration-phase', 'true'), ('', 'bypass-permission', 'false'),
                              ('unload-pack', 'enable', 'false')]:
        force = replace_setting(force, group, key, value)
    forcepack = plugins / 'forcepack'
    forcepack.mkdir()
    (forcepack / 'config.toml').write_text(force)
    bstats = plugins / 'bStats'
    bstats.mkdir()
    (bstats / 'config.yml').write_text('enabled: false\nserverUuid: "00000000-0000-0000-0000-000000000000"\n')
    (bstats / 'config.txt').write_text('enabled=false\nserver-uuid=00000000-0000-0000-0000-000000000000\n'
                                      'log-errors=false\nlog-sent-data=false\nlog-response-status-text=false\n')
    if args.with_nlogin:
        # This is a pristine bundled template, never a settings file from an existing server.
        with zipfile.ZipFile(NLOGIN) as loader:
            bundled_plugin = loader.read('com/nickuc/login/loader/plugin.jar')
        with zipfile.ZipFile(io.BytesIO(bundled_plugin)) as archive:
            routing = archive.read('com/nickuc/login/config/proxy/proxy_en.yml').decode()
        routing = routing.replace("    - 'lobby'", "    - 'smoke-backend'")
        routing = routing.replace('check-ack-message: true', 'check-ack-message: false')
        nlogin_proxy = plugins / 'nlogin' / 'proxy'
        nlogin_proxy.mkdir(parents=True)
        (nlogin_proxy / 'config.yml').write_text(routing)
    (runtime / 'inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')


def client(proxy_port, report, hold_config_seconds=0):
    with socket.create_connection(('127.0.0.1', proxy_port), timeout=5) as stream:
        stream.settimeout(5)
        packet(stream, 0, vint(765) + string('localhost') + struct.pack('>H', proxy_port) + vint(1))
        packet(stream, 0)
        identifier, payload = read_packet(stream)
        response, _ = take_string(payload)
        report['status_response'] = {'packet': identifier, 'version': json.loads(response)['version']}
    with socket.create_connection(('127.0.0.1', proxy_port), timeout=5) as stream:
        stream.settimeout(5)
        packet(stream, 0, vint(765) + string('localhost') + struct.pack('>H', proxy_port) + vint(2))
        packet(stream, 0, string('Smoke765') + uuid.UUID('00000000-0000-0000-0000-000000000765').bytes)
        stage = 'login'
        deadline = time.monotonic() + 20
        report['client_packets'] = []
        while time.monotonic() < deadline:
            identifier, payload = read_packet(stream)
            report['client_packets'].append({'state': stage, 'id': identifier, 'bytes': len(payload)})
            if stage == 'login':
                if identifier == 2:
                    report['login_success'] = True
                    packet(stream, 3)
                    stage = 'config'
                    settings = string('en_us') + b'\x0a' + vint(0) + b'\x01\x7f' + vint(1) + b'\x00\x01'
                    packet(stream, 0, settings)
                    packet(stream, 1, string('minecraft:brand') + string('SmokeHarness'))
                elif identifier == 1:
                    report['authentication_challenge'] = True
                    return
                elif identifier == 0:
                    report['login_disconnected'] = True
                    return
                elif identifier == 3:
                    raise ValueError('unexpected compression; test requires compression-threshold=-1')
            elif stage == 'config':
                if identifier == 7:  # CTD CONFIG ResourcePackRequestPacket for 1.20.3/1.20.4
                    pack_id = payload[:16]
                    url, offset = take_string(payload, 16)
                    hash_value, _ = take_string(payload, offset)
                    if not url.startswith('http://127.0.0.1:'):
                        raise ValueError('resource pack URL escaped loopback')
                    report['resource_pack'] = {'uuid': str(uuid.UUID(bytes=pack_id)), 'url': url, 'sha1': hash_value}
                    packet(stream, 5, pack_id + vint(3))
                    if hold_config_seconds:
                        started = time.monotonic()
                        until = started + hold_config_seconds
                        next_settings = started
                        held_packets = []
                        while time.monotonic() < until:
                            if time.monotonic() >= next_settings:
                                # Ordinary CONFIG client settings keep this same connection active.
                                packet(stream, 0, settings)
                                next_settings = time.monotonic() + 4
                            if select.select([stream], [], [], min(1, max(0, until - time.monotonic())))[0]:
                                held_id, held_payload = read_packet(stream)
                                held_packets.append(held_id)
                                if held_id in (3, 4):
                                    packet(stream, held_id, held_payload)
                                elif held_id == 1:
                                    report['config_disconnected_during_hold'] = True
                                    report['same_connection_hold_seconds'] = time.monotonic() - started
                                    return
                        report['same_connection_hold_seconds'] = time.monotonic() - started
                        report['same_connection_hold_completed'] = True
                        report['packets_received_during_hold'] = held_packets
                        deadline += hold_config_seconds
                    for status in (4, 0):  # DOWNLOADED, SUCCESSFUL; ACCEPTED was sent before the optional hold.
                        packet(stream, 5, pack_id + vint(status))
                        time.sleep(0.1)
                    report['pack_statuses_sent'] = ['ACCEPTED', 'DOWNLOADED', 'SUCCESSFUL']
                elif identifier == 2:
                    packet(stream, 2)
                    report['config_finished'] = True
                    stream.shutdown(socket.SHUT_WR)
                    stream.settimeout(2)
                    try:
                        while stream.recv(8192):
                            pass
                    except socket.timeout:
                        pass
                    return
                elif identifier in (3, 4):
                    packet(stream, identifier, payload)
                elif identifier == 1:
                    report['config_disconnected'] = True
                    return


def exercise_reloads(process, runtime, report):
    files = [runtime / 'plugins/limboapi/config.yml', runtime / 'plugins/limboreconnect/config.yml']
    marker = '# Smoke administrator comment: preserve across reloads\nsmoke-extra-empty-list: []\n'
    for path in files:
        with path.open('a') as stream:
            stream.write('\n' + marker)
    report['reloads'] = []
    for number in (1, 2):
        before = {str(path.relative_to(runtime)): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
        process.stdin.write('smoke-api-reload\nlimboreconnect reload\n')
        process.stdin.flush()
        time.sleep(2)
        after = {str(path.relative_to(runtime)): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
        kept = all(marker in path.read_text() for path in files)
        record = {'number': number, 'sha256_before': before, 'sha256_after': after,
                  'unchanged': before == after, 'comment_and_empty_unknown_list_preserved': kept}
        report['reloads'].append(record)
        print(json.dumps({'reload': record}), flush=True)


def main():
    global JAVA, CTD, FORCEPACK, NLOGIN
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, default=JAVA)
    parser.add_argument('--ctd', type=Path, required=True)
    parser.add_argument('--forcepack', type=Path, required=True)
    parser.add_argument('--nlogin', type=Path)
    parser.add_argument('--limboapi', type=Path, required=True)
    parser.add_argument('--reconnect', type=Path, required=True)
    parser.add_argument('--with-nlogin', action='store_true')
    parser.add_argument('--startup-only', action='store_true')
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--hold-config-seconds', type=float, default=0)
    args = parser.parse_args()
    JAVA = args.java.resolve()
    CTD = args.ctd.resolve()
    FORCEPACK = args.forcepack.resolve()
    NLOGIN = args.nlogin.resolve() if args.nlogin else None
    if args.with_nlogin and NLOGIN is None:
        parser.error('--with-nlogin requires --nlogin pointing to a locally owned jar')
    if args.hold_config_seconds < 0 or args.hold_config_seconds > 45:
        parser.error('--hold-config-seconds must be between 0 and 45 for this bounded fixture')
    for path in [JAVA, CTD, FORCEPACK, args.limboapi, args.reconnect] + ([NLOGIN] if args.with_nlogin else []):
        if not path.is_file():
            parser.error('Missing required input: ' + str(path))
    os.umask(0o077)
    runtime = HERE / ('runtime-' + time.strftime('%Y%m%dT%H%M%S') + ('-nlogin' if args.with_nlogin else ''))
    runtime.mkdir(mode=0o700)
    backend = Backend(('127.0.0.1', 0), BackendHandler)
    backend.events = []
    pack_http = LocalHTTPServer(('127.0.0.1', 0), PackHandler)
    content = io.BytesIO()
    with zipfile.ZipFile(content, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('pack.mcmeta', json.dumps({'pack': {'pack_format': 22, 'description': 'Disposable smoke fixture'}}))
    pack_http.pack = content.getvalue()
    reserve = socket.socket()
    reserve.bind(('127.0.0.1', 0))
    proxy_port = reserve.getsockname()[1]
    prepare(runtime, args, proxy_port, backend.server_address[1], pack_http.server_address[1], pack_http.pack)
    report = {'runtime': str(runtime), 'proxy_bind': f'127.0.0.1:{proxy_port}', 'nlogin_included': args.with_nlogin,
              'backend_bind': backend.server_address, 'pack_http_bind': pack_http.server_address,
              'limits': ['No premium account, license, production settings, database or credentials copied.',
                         'Synthetic protocol765 client; no resource rendering, auth success or playable world asserted.',
                         'Fake backend is local and supplies only login and CONFIG finish packets.']}
    print(f'Prepared private disposable runtime: {runtime}', flush=True)
    if args.prepare_only:
        reserve.close()
        backend.server_close()
        pack_http.server_close()
        (runtime / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        return
    for server in (backend, pack_http):
        threading.Thread(target=server.serve_forever, daemon=True).start()
    reserve.close()
    process = None
    try:
        with (runtime / 'console.log').open('w') as log:
            process = subprocess.Popen([str(JAVA), '-Xms128m', '-Xmx768m', '-jar', str(CTD)],
                                       cwd=runtime, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
            report['pid'] = process.pid
            deadline = time.monotonic() + 50
            while time.monotonic() < deadline:
                log.flush()
                text = (runtime / 'console.log').read_text(errors='replace')
                if 'Done (' in text:
                    report['startup_done'] = True
                    print('CTD reached Done; plugin initialization logs captured.', flush=True)
                    break
                if process.poll() is not None:
                    report['early_exit'] = process.returncode
                    break
                time.sleep(0.2)
            if report.get('startup_done') and not args.startup_only:
                report['attempts'] = []
                final_label = 'after-same-session-hold' if args.hold_config_seconds else 'after-identity-timer'
                final_delay = 0 if args.hold_config_seconds else 36
                for label, delay in [('initial', 0), ('repeat', 2), (final_label, final_delay)]:
                    if label == 'repeat':
                        exercise_reloads(process, runtime, report)
                    if delay:
                        print(f'Waiting {delay}s before {label} connection to inspect retained login lifecycle.', flush=True)
                        time.sleep(delay)
                    attempt = {'label': label}
                    try:
                        client(proxy_port, attempt, args.hold_config_seconds if label == 'initial' else 0)
                        time.sleep(1)
                    except (OSError, EOFError, ValueError, IndexError) as error:
                        attempt['client_end'] = str(error)
                    report['attempts'].append(attempt)
                    print(json.dumps(attempt), flush=True)
    finally:
        if process is not None and process.poll() is None:
            try:
                process.stdin.write('shutdown\n')
                process.stdin.flush()
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            finally:
                process.stdin.close()
        backend.shutdown()
        pack_http.shutdown()
        backend.server_close()
        pack_http.server_close()
        report['backend_events'] = backend.events
        text = (runtime / 'console.log').read_text(errors='replace')
        report['binary_errors'] = re.findall(r'(?m)^.*(?:NoSuchMethodError|NoSuchFieldError|ClassNotFoundException|'
                                            r'NoClassDefFoundError|ExceptionInInitializerError|IllegalReferenceCountException).*$' , text)
        report['forcepack_success_observed'] = 'sent status: SUCCESSFUL' in text
        report['api_reload_completions'] = text.count('SMOKE LimboAPI reload complete')
        report['reconnect_reload_completions'] = text.count('LimboReconnect reloaded')
        report['nlogin_profile_hook_active'] = 'Hooking all GameProfileRequestEvent events from nlogin' in text
        report['upstream_version_check_failed'] = 'Error contacting GitHub for version comparison' in text
        report['process_exit'] = process.returncode if process is not None else None
        (runtime / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2), flush=True)


if __name__ == '__main__':
    main()
