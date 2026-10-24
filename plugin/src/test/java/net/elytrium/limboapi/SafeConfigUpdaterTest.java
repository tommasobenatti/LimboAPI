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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeConfigUpdaterTest {

  @TempDir
  Path directory;

  @Test
  void addsMissingKeysOnDiskWithoutReformattingCustomizationsAndIsIdempotent() throws Exception {
    Path path = this.directory.resolve("config.yml");
    String original = "# My settings\nmain:\n  text: 'Italiano' # keep\n  plugins: []\n# Other section\nextra: 12\n";
    String defaults = "main:\n  text: English\n  plugins: [nlogin]\n  # Added option\n  timeout: 5000\nnew-section:\n  enabled: true\n";
    Files.writeString(path, original);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r-----"));

    SafeConfigUpdater.load(path, defaults).save();

    String updated = Files.readString(path);
    assertTrue(updated.startsWith("# My settings\nmain:\n  text: 'Italiano' # keep\n  plugins: []\n"));
    assertTrue(updated.contains("  # Added option\n  timeout: 5000\n# Other section\nextra: 12\n"));
    assertTrue(updated.endsWith("new-section:\n  enabled: true\n"));
    assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(path));
    try (var files = Files.list(this.directory.resolve("backups"))) {
      Path backup = files.findFirst().orElseThrow();
      assertEquals(original, Files.readString(backup));
      assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(backup));
    }

    SafeConfigUpdater.load(path, defaults).save();
    assertEquals(updated, Files.readString(path));
    try (var files = Files.list(this.directory.resolve("backups"))) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void keepsEmptyAndEditedCollectionsIncludingDeletedMapEntries() throws Exception {
    Path path = this.directory.resolve("config.yml");
    String original = "packages:\n  custom: 8\nlevels: {}\ntitles:\n- title: Custom\n";
    Files.writeString(path, original);
    String defaults = "packages:\n  standard: 10\nlevels:\n  default: 1\ntitles:\n- title: One\n- title: Two\nadded: true\n";
    SafeConfigUpdater.load(path, defaults, Set.of("packages", "levels")).save();
    assertEquals(original + "added: true\n", Files.readString(path));
  }

  @Test
  void refusesInvalidDuplicateOrWronglyTypedYamlBeforeWriting() throws Exception {
    Path path = this.directory.resolve("config.yml");
    for (String invalid : new String[]{"value: [unterminated\n", "value: 1\nvalue: 2\n", "main: false\n"}) {
      Files.writeString(path, invalid);
      assertThrows(IOException.class, () -> SafeConfigUpdater.load(path, "main:\n  added: true\n"));
      assertEquals(invalid, Files.readString(path));
      assertFalse(Files.exists(this.directory.resolve("backups")));
    }
  }

  @Test
  void refusesConcurrentEditsAndRetargetedSymlinks() throws Exception {
    Path path = this.directory.resolve("config.yml");
    Files.writeString(path, "original: true\n");
    SafeConfigUpdater pending = SafeConfigUpdater.load(path, "added: true\n");
    Files.writeString(path, "admin-edited: true\n");
    assertThrows(IOException.class, pending::save);
    assertEquals("admin-edited: true\n", Files.readString(path));

    Path alternate = this.directory.resolve("alternate.yml");
    Files.writeString(alternate, "alternate: true\n");
    Path link = this.directory.resolve("link.yml");
    Files.createSymbolicLink(link, path);
    SafeConfigUpdater linked = SafeConfigUpdater.load(link, "added: true\n");
    Files.delete(link);
    Files.createSymbolicLink(link, alternate);
    assertThrows(IOException.class, linked::save);
    assertEquals("alternate: true\n", Files.readString(alternate));
  }

  @Test
  void backupFailureCannotReplaceOriginalAndCleansTemporaryFiles() throws Exception {
    Path path = this.directory.resolve("config.yml");
    String original = "original: true\n";
    Files.writeString(path, original);
    Files.writeString(this.directory.resolve("backups"), "blocked");
    SafeConfigUpdater pending = SafeConfigUpdater.load(path, "added: true\n");
    assertThrows(IOException.class, pending::save);
    assertEquals(original, Files.readString(path));
    try (var files = Files.list(this.directory)) {
      assertFalse(files.anyMatch(file -> file.toString().endsWith(".tmp")));
    }
  }

  @Test
  void createsNewConfigsAndPreservesSymlinks() throws Exception {
    Path path = this.directory.resolve("nested/config.yml");
    String defaults = "# Default comments\nmain:\n  added: true\n";
    SafeConfigUpdater.load(path, defaults).save();
    assertEquals(defaults, Files.readString(path));
    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
    Path link = this.directory.resolve("link.yml");
    Files.createSymbolicLink(link, path);
    SafeConfigUpdater.load(link, defaults + "extra: true\n").save();
    assertTrue(Files.isSymbolicLink(link));
    assertTrue(Files.readString(path).endsWith("extra: true\n"));
  }

  @Test
  void validatesMergedCandidateBeforeTouchingOriginal() throws Exception {
    Path path = this.directory.resolve("config.yml");
    String original = "interval: -1\n";
    Files.writeString(path, original);
    assertThrows(IOException.class, () -> SafeConfigUpdater.update(path,
        defaults -> Files.writeString(defaults, "interval: 1000\nnew: true\n"), candidate -> false));
    assertEquals(original, Files.readString(path));
    assertFalse(Files.exists(this.directory.resolve("backups")));
    try (var files = Files.list(this.directory)) {
      assertEquals(Set.of(path), files.collect(java.util.stream.Collectors.toSet()));
    }
  }

  @Test
  void handlesSharedInsertionOffsetsUnicodeCrLfAndMissingFinalNewlines() throws Exception {
    Path path = this.directory.resolve("config.yml");
    Files.writeString(path, "# 🙂 customized\r\nmain:\r\n  text: 'città 🐱'");
    SafeConfigUpdater.load(path, "main:\n  text: English\n  added: true\nother: false\n").save();
    assertEquals("# 🙂 customized\r\nmain:\r\n  text: 'città 🐱'\r\n  added: true\r\nother: false\r\n", Files.readString(path));
  }

  @Test
  void extendsInlineSchemaMappingsWithoutReformattingExistingValues() throws Exception {
    Path path = this.directory.resolve("config.yml");
    Files.writeString(path, "main: {text: 'Custom', unknown: 4} # inline settings\n");
    SafeConfigUpdater.load(path, "main:\n  text: English\n  added:\n    nested: true\n").save();
    assertEquals("main: {text: 'Custom', unknown: 4, added: {nested: true}} # inline settings\n", Files.readString(path));
  }

  @Test
  void refusesMalformedUtf8WithoutReplacingItWithReplacementCharacters() throws Exception {
    Path path = this.directory.resolve("config.yml");
    byte[] invalid = new byte[]{'a', ':', ' ', (byte) 0xc3, '\n'};
    Files.write(path, invalid);
    assertThrows(IOException.class, () -> SafeConfigUpdater.load(path, "added: true\n"));
    org.junit.jupiter.api.Assertions.assertArrayEquals(invalid, Files.readAllBytes(path));
  }

  @Test
  void addsSchemaFieldsWithinExistingCollectionEntriesWithoutRestoringDeletedEntries() throws Exception {
    Path path = this.directory.resolve("config.yml");
    Files.writeString(path, "packages:\n  kept:\n    price: 25\n");
    SafeConfigUpdater.load(path, "packages:\n  kept:\n    price: 10\n    added: true\n  deleted:\n    price: 20\n", Set.of("packages")).save();
    assertEquals("packages:\n  kept:\n    price: 25\n    added: true\n", Files.readString(path));
  }
}
