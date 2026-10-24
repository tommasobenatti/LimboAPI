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

package net.elytrium.limboapi.server;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.velocitypowered.api.network.ProtocolVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.elytrium.limboapi.server.item.SimpleItemComponentManager;
import net.elytrium.limboapi.server.world.SimpleBlock;
import net.elytrium.limboapi.server.world.SimpleBlockEntity;
import net.elytrium.limboapi.server.world.SimpleItem;
import net.elytrium.limboapi.server.world.SimpleTagManager;
import org.junit.jupiter.api.Test;

class MappingInitializationTest {

  @Test
  void initializesActualMappingResourcesWithProxyGson() {
    ProtocolVersion version = ProtocolVersion.MINECRAFT_1_20_5;
    assertAll("Mapping registry startup",
        () -> {
          SimpleItemComponentManager components = new SimpleItemComponentManager();
          assertEquals(5, components.getId("minecraft:custom_name", version));
          assertEquals(0, components.getId("minecraft:custom_data", version));
        },
        () -> {
          SimpleBlock.init();
          assertEquals(1, SimpleBlock.fromModernID("minecraft:stone").getBlockID(version));
          assertEquals(SimpleBlock.fromModernID("minecraft:oak_log", Map.of("axis", "y")).getID(version),
              SimpleBlock.fromModernID("minecraft:oak_log").getID(version));
        },
        () -> {
          SimpleItem.init();
          SimpleItem stone = SimpleItem.fromModernID("minecraft:stone");
          assertEquals(1, stone.getID(version));
          assertSame(stone, SimpleItem.fromLegacyID(1));
        },
        () -> {
          SimpleBlockEntity.init();
          assertEquals(1, SimpleBlockEntity.fromModernID("minecraft:chest").getID(version));
        },
        () -> {
          SimpleTagManager.init();
          Map<String, Map<String, int[]>> tags = SimpleTagManager.getUpdateTagsPacket(version).toVelocityTags();
          assertEquals(List.of("minecraft:block", "minecraft:entity_type", "minecraft:fluid", "minecraft:game_event", "minecraft:item"),
              new ArrayList<>(tags.keySet()));
          assertEquals("minecraft:acacia_logs", tags.get("minecraft:block").keySet().iterator().next());
          assertArrayEquals(new int[] {3, 4}, tags.get("minecraft:fluid").get("minecraft:lava"));
          assertFalse(tags.get("minecraft:item").isEmpty());
        });
  }
}
