/*
 * Copyright (C) 2021 - 2025 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.limboapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {

  @TempDir
  Path directory;

  @Test
  void pristineDefaultsAreCompleteAndIncludeNloginBeforeLimbo() throws Exception {
    Path path = this.directory.resolve("defaults.yml");
    Settings.writeDefaults(path);
    Settings settings = new Settings();
    assertTrue(settings.loadCandidate(path));
    assertEquals(List.of("floodgate", "geyser", "nlogin"), settings.MAIN.PRE_LIMBO_PROFILE_REQUEST_PLUGINS);
    assertEquals(256, settings.MAIN.MAX_CHAT_MESSAGE_LENGTH);
    assertTrue(settings.MAIN.MESSAGES.TOO_BIG_PACKET.contains("Your client"));
    assertTrue(settings.MAIN.MESSAGES.TOO_BIG_PACKET.startsWith(settings.PREFIX + "\n\n"));
  }

  @Test
  void acceptsUnknownAdministrativeKeysAndPreservesEmptyListsAndTranslationsOnDisk() throws Exception {
    Path path = this.directory.resolve("config.yml");
    String original = "# Custom header\nunknown: [one, two]\nprefix: 'Tradotto'\nmain:\n"
        + "  custom-map: {value: 1}\n  pre-limbo-profile-request-plugins: []\n  messages:\n    time-out: 'Attesa terminata'\n";
    Files.writeString(path, original);
    Settings settings = new Settings();
    SafeConfigUpdater.update(path, Settings::writeDefaults, settings::loadCandidate);
    assertEquals(List.of(), settings.MAIN.PRE_LIMBO_PROFILE_REQUEST_PLUGINS);
    assertEquals("Attesa terminata", settings.MAIN.MESSAGES.TIME_OUT);
    assertEquals("Tradotto", settings.PREFIX);
    String updated = Files.readString(path);
    assertTrue(updated.contains("unknown: [one, two]"));
    assertTrue(updated.contains("custom-map: {value: 1}"));
    assertTrue(updated.contains("pre-limbo-profile-request-plugins: []"));
    assertTrue(updated.contains("# Custom header"));
    SafeConfigUpdater.update(path, Settings::writeDefaults, new Settings()::loadCandidate);
    assertEquals(updated, Files.readString(path));
  }

  @Test
  void rejectsKnownTypeErrorsIncludingOverflowAndBadListElements() throws Exception {
    Path path = this.directory.resolve("config.yml");
    for (String invalid : List.of("main: false\n", "serializer: DOES_NOT_EXIST\n", "prefix: []\n",
        "main:\n  check-for-updates: 'no'\n", "main:\n  max-chat-message-length: '256'\n",
        "main:\n  max-chat-message-length: 2147483648\n", "main:\n  max-chat-message-length: 4.5\n",
        "main:\n  pre-limbo-profile-request-plugins: [nlogin, 42]\n", "main:\n  messages: []\n")) {
      Files.writeString(path, invalid);
      assertFalse(new Settings().loadCandidate(path), invalid);
      assertEquals(invalid, Files.readString(path));
    }
  }

  @Test
  void anExplicitEmptySchemaMapKeepsWorkingDefaultsWithoutReinsertingItsFields() throws Exception {
    Path path = this.directory.resolve("config.yml");
    Files.writeString(path, "main: {}\n");
    Settings settings = new Settings();
    SafeConfigUpdater.update(path, Settings::writeDefaults, settings::loadCandidate);
    assertEquals(256, settings.MAIN.MAX_CHAT_MESSAGE_LENGTH);
    assertEquals("main: {}", Files.readString(path).lines().findFirst().orElseThrow());
  }

  @Test
  void doesNotAddNloginToAnExistingAdministratorPluginList() throws Exception {
    Path path = this.directory.resolve("config.yml");
    Files.writeString(path, "main:\n  pre-limbo-profile-request-plugins: [custom-plugin]\n");
    Settings settings = new Settings();
    SafeConfigUpdater.update(path, Settings::writeDefaults, settings::loadCandidate);
    assertEquals(List.of("custom-plugin"), settings.MAIN.PRE_LIMBO_PROFILE_REQUEST_PLUGINS);
    assertTrue(Files.readString(path).contains("pre-limbo-profile-request-plugins: [custom-plugin]"));
  }
}
