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

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/** Adds missing schema keys without reserializing administrator-owned YAML. */
public final class SafeConfigUpdater {

  private final Path requested;
  private final Path target;
  private final byte[] original;
  private final String contents;

  private SafeConfigUpdater(Path requested, Path target, byte[] original, String contents) {
    this.requested = requested;
    this.target = target;
    this.original = original;
    this.contents = contents;
  }

  /** Loads and validates a candidate before committing any changes to the original file. */
  public static void update(Path path, DefaultsWriter defaultsWriter, Predicate<Path> validator) throws IOException {
    Path parent = parent(path.toAbsolutePath().normalize());
    Files.createDirectories(parent);
    Path defaults = privateTemporary(parent);
    try {
      defaultsWriter.write(defaults);
      SafeConfigUpdater update = load(path, Files.readString(defaults, StandardCharsets.UTF_8));
      Path candidate = privateTemporary(parent);
      try {
        Files.writeString(candidate, update.contents, StandardCharsets.UTF_8);
        if (!validator.test(candidate)) {
          throw new IOException("Configuration validation failed: " + path.getFileName());
        }
        update.save();
      } finally {
        Files.deleteIfExists(candidate);
      }
    } finally {
      Files.deleteIfExists(defaults);
    }
  }

  public static SafeConfigUpdater load(Path path, String defaults) throws IOException {
    return load(path, defaults, Set.of());
  }

  /** Existing lists, empty maps and explicitly designated maps are administrator-owned collections. */
  public static SafeConfigUpdater load(Path path, String defaults, Set<String> collections) throws IOException {
    Path requested = path.toAbsolutePath().normalize();
    boolean exists = Files.exists(requested, LinkOption.NOFOLLOW_LINKS);
    Path target = exists ? requested.toRealPath() : requested;
    byte[] original = exists ? Files.readAllBytes(target) : null;
    MappingNode template = document(defaults);
    if (!exists) {
      return new SafeConfigUpdater(requested, target, null, defaults);
    }

    String current = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(original)).toString();
    MappingNode root = document(current);
    List<Insertion> insertions = new ArrayList<>();
    merge(root, template, "", current, defaults, collections, insertions);
    StringBuilder merged = new StringBuilder(current);
    Map<Integer, StringBuilder> grouped = new TreeMap<>(Comparator.reverseOrder());
    for (Insertion insertion : insertions) {
      grouped.computeIfAbsent(insertion.position(), ignored -> new StringBuilder()).append(insertion.text());
    }
    for (var insertion : grouped.entrySet()) {
      int position = insertion.getKey();
      String newline = current.contains("\r\n") ? "\r\n" : "\n";
      String prefix = position == current.length() && position > 0 && current.charAt(position - 1) != '\n' ? newline : "";
      merged.insert(position, prefix + insertion.getValue());
    }
    String result = merged.toString();
    document(result);
    return new SafeConfigUpdater(requested, target, original, result);
  }

  private static MappingNode document(String text) throws IOException {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setProcessComments(true);
    Yaml yaml = new Yaml(new SafeConstructor(options));
    try {
      // Construction checks duplicate keys and unsupported tags without instantiating Java classes.
      yaml.load(text);
      Node root = yaml.compose(new StringReader(text));
      if (root == null) {
        return new MappingNode(org.yaml.snakeyaml.nodes.Tag.MAP, List.of(), DumperOptions.FlowStyle.BLOCK);
      }
      if (!(root instanceof MappingNode mapping)) {
        throw new IOException("Configuration root must be a YAML mapping");
      }
      return mapping;
    } catch (YAMLException e) {
      throw new IOException("Invalid configuration YAML", e);
    }
  }

  private static void merge(MappingNode current, MappingNode defaults, String parent, String currentText,
                            String defaultText, Set<String> collections, List<Insertion> insertions) throws IOException {
    Map<String, NodeTuple> existing = entries(current);
    StringBuilder additions = new StringBuilder();
    List<NodeTuple> missing = new ArrayList<>();
    for (var entry : entries(defaults).entrySet()) {
      String key = entry.getKey();
      String path = parent.isEmpty() ? key : parent + "." + key;
      NodeTuple old = existing.get(key);
      Node value = entry.getValue().getValueNode();
      if (old == null) {
        if (!collections.contains(parent)) {
          int indentation = current.getValue().isEmpty() ? 0 : current.getValue().getFirst().getKeyNode().getStartMark().getColumn();
          additions.append(block(entry.getValue(), defaultText, indentation));
          missing.add(entry.getValue());
        }
      } else if (value instanceof MappingNode template) {
        if (!(old.getValueNode() instanceof MappingNode target)) {
          throw new IOException("Expected YAML mapping at " + path);
        }
        if (!target.getValue().isEmpty()) {
          merge(target, template, path, currentText, defaultText, collections, insertions);
        }
      }
    }
    if (!additions.isEmpty()) {
      if (current.getFlowStyle() == DumperOptions.FlowStyle.FLOW) {
        StringWriter writer = new StringWriter();
        new Yaml().serialize(new MappingNode(org.yaml.snakeyaml.nodes.Tag.MAP, missing, DumperOptions.FlowStyle.FLOW), writer);
        String serialized = writer.toString().trim();
        int position = currentText.offsetByCodePoints(0, current.getEndMark().getIndex()) - 1;
        String separator = existing.isEmpty() ? "" : ", ";
        insertions.add(new Insertion(position, separator + serialized.substring(1, serialized.length() - 1)));
        return;
      }
      int position = current.getValue().isEmpty() ? currentText.length() : contentEnd(current, currentText);
      String newline = currentText.contains("\r\n") ? "\r\n" : "\n";
      insertions.add(new Insertion(position, additions.toString().replace("\n", newline)));
    }
  }

  private static Map<String, NodeTuple> entries(MappingNode node) throws IOException {
    Map<String, NodeTuple> entries = new LinkedHashMap<>();
    for (NodeTuple tuple : node.getValue()) {
      if (!(tuple.getKeyNode() instanceof ScalarNode scalar) || "<<".equals(scalar.getValue())) {
        throw new IOException("Only simple configuration keys are supported");
      }
      if (entries.put(scalar.getValue(), tuple) != null) {
        throw new IOException("Duplicate configuration key: " + scalar.getValue());
      }
    }
    return entries;
  }

  private static String block(NodeTuple tuple, String text, int indentation) {
    Node key = tuple.getKeyNode();
    int beginning = text.offsetByCodePoints(0, key.getStartMark().getIndex());
    if (key.getBlockComments() != null && !key.getBlockComments().isEmpty()) {
      beginning = text.offsetByCodePoints(0, key.getBlockComments().getFirst().getStartMark().getIndex());
    }
    beginning = text.lastIndexOf('\n', Math.max(0, beginning - 1)) + 1;
    int end = contentEnd(tuple.getValueNode(), text);
    int sourceIndent = key.getStartMark().getColumn();
    StringBuilder result = new StringBuilder();
    for (String line : text.substring(beginning, end).split("\\R")) {
      result.append(" ".repeat(indentation));
      result.append(line.substring(Math.min(sourceIndent, line.length()))).append('\n');
    }
    return result.toString();
  }

  private static int contentEnd(Node node, String text) {
    if (node instanceof MappingNode mapping && !mapping.getValue().isEmpty()
        && mapping.getFlowStyle() == DumperOptions.FlowStyle.BLOCK) {
      return contentEnd(mapping.getValue().getLast().getValueNode(), text);
    }
    if (node instanceof SequenceNode sequence && !sequence.getValue().isEmpty()
        && sequence.getFlowStyle() == DumperOptions.FlowStyle.BLOCK) {
      return contentEnd(sequence.getValue().getLast(), text);
    }
    int end = text.offsetByCodePoints(0, node.getEndMark().getIndex());
    if (node.getEndMark().getColumn() == 0) {
      return end;
    }
    int newline = text.indexOf('\n', end);
    return newline < 0 ? text.length() : newline + 1;
  }

  public void save() throws IOException {
    if (this.original != null && Arrays.equals(this.original, this.contents.getBytes(StandardCharsets.UTF_8))) {
      this.verifyUnchanged();
      return;
    }
    Files.createDirectories(parent(this.target));
    this.verifyUnchanged();
    Path temporary = privateTemporary(parent(this.target));
    try {
      Files.writeString(temporary, this.contents, StandardCharsets.UTF_8);
      if (this.original != null) {
        this.backup();
        if (Files.getFileStore(this.target).supportsFileAttributeView("posix")) {
          Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(this.target));
        }
      }
      this.verifyUnchanged();
      Files.move(temporary, this.target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private void verifyUnchanged() throws IOException {
    boolean exists = Files.exists(this.requested, LinkOption.NOFOLLOW_LINKS);
    if (this.original == null ? exists : !exists || !this.requested.toRealPath().equals(this.target)
        || !Arrays.equals(this.original, Files.readAllBytes(this.target))) {
      throw new IOException("Configuration changed during reload: " + this.requested.getFileName());
    }
  }

  private void backup() throws IOException {
    Path directory = parent(this.requested).resolve("backups");
    Files.createDirectories(directory, privateMode(parent(directory), true));
    if (Files.isSymbolicLink(directory)) {
      throw new IOException("Configuration backup directory must not be a symbolic link");
    }
    if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
    Path temporary = privateTemporary(directory);
    try {
      Files.write(temporary, this.original);
      Path backup = directory.resolve(this.requested.getFileName() + "." + digest(this.original) + ".bak");
      if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(backup) || !Arrays.equals(this.original, Files.readAllBytes(backup))) {
          throw new IOException("Invalid existing configuration backup");
        }
        if (Files.getFileStore(backup).supportsFileAttributeView("posix")) {
          Files.setPosixFilePermissions(backup, PosixFilePermissions.fromString("rw-------"));
        }
      } else {
        Files.move(temporary, backup, StandardCopyOption.ATOMIC_MOVE);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static Path privateTemporary(Path directory) throws IOException {
    return Files.createTempFile(directory, ".config-", ".tmp", privateMode(directory, false));
  }

  private static Path parent(Path path) throws IOException {
    Path parent = path.getParent();
    if (parent == null) {
      throw new IOException("Configuration requires a parent directory");
    }
    return parent;
  }

  private static FileAttribute<?>[] privateMode(Path directory, boolean isDirectory) throws IOException {
    return Files.getFileStore(directory).supportsFileAttributeView("posix")
        ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(isDirectory ? "rwx------" : "rw-------"))}
        : new FileAttribute<?>[0];
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private record Insertion(int position, String text) { }

  @FunctionalInterface
  public interface DefaultsWriter {

    void write(Path path) throws IOException;
  }
}
