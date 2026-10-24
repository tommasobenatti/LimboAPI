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

package net.elytrium.limboapi.injection.login.confirmation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import net.elytrium.limboapi.LimboAPI;
import net.elytrium.limboapi.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class LoginConfirmHandlerTest {

  @BeforeEach
  void setUp() throws ReflectiveOperationException {
    Settings.IMP.MAIN = new Settings.MAIN();
    Field field = LimboAPI.class.getDeclaredField("LOGGER");
    field.setAccessible(true);
    field.set(null, mock(Logger.class));
  }

  @Test
  void releasesRetainedPacketsExactlyOnceOnRepeatedDisconnect() {
    MinecraftConnection connection = mock(MinecraftConnection.class);
    when(connection.getState()).thenReturn(StateRegistry.CONFIG);
    LoginConfirmHandler handler = new LoginConfirmHandler(mock(LimboAPI.class), connection);
    PluginMessagePacket packet = new PluginMessagePacket("test:payload", Unpooled.buffer().writeByte(1));
    handler.handleGeneric(packet);
    packet.release();
    handler.disconnected();
    assertEquals(0, packet.refCnt());
    assertDoesNotThrow(handler::disconnected);
  }

  @Test
  void boundsBacklogAndReleasesItOnOverflow() {
    MinecraftConnection connection = mock(MinecraftConnection.class);
    when(connection.getState()).thenReturn(StateRegistry.CONFIG);
    LoginConfirmHandler handler = new LoginConfirmHandler(mock(LimboAPI.class), connection);
    PluginMessagePacket first = null;
    for (int i = 0; i < 1025; ++i) {
      PluginMessagePacket packet = new PluginMessagePacket("test:payload", Unpooled.buffer().writeByte(1));
      if (first == null) {
        first = packet;
      }
      handler.handleGeneric(packet);
      packet.release();
    }
    assertEquals(0, first.refCnt());
    verify(connection).close(true);
    handler.disconnected();
  }

  @Test
  void disconnectCompletesWaitingConfirmationExceptionally() {
    LoginConfirmHandler handler = new LoginConfirmHandler(mock(LimboAPI.class), mock(MinecraftConnection.class));
    AtomicBoolean callbackRan = new AtomicBoolean();
    var waiting = handler.thenRun(() -> callbackRan.set(true));
    handler.disconnected();
    assertTrue(waiting.isCompletedExceptionally());
    assertFalse(callbackRan.get());
  }

  @Test
  void closedConnectionCleansPluginStateBeforeVelocityLoginEvent() {
    MinecraftConnection connection = mock(MinecraftConnection.class);
    when(connection.isClosed()).thenReturn(true);
    LimboAPI plugin = mock(LimboAPI.class);
    ConnectedPlayer player = mock(ConnectedPlayer.class);
    LoginConfirmHandler handler = new LoginConfirmHandler(plugin, connection);
    handler.setPlayer(player);
    handler.disconnected();
    handler.disconnected();
    verify(plugin).releasePlayer(player);
  }

  @Test
  void liveStateSwitchPreservesPluginLoginState() {
    LimboAPI plugin = mock(LimboAPI.class);
    ConnectedPlayer player = mock(ConnectedPlayer.class);
    LoginConfirmHandler handler = new LoginConfirmHandler(plugin, mock(MinecraftConnection.class));
    handler.setPlayer(player);
    handler.disconnected();
    verify(plugin, never()).releasePlayer(player);
  }

  @Test
  void boundsRetainedBytesAndDoesNotRetainAfterRejection() {
    Settings.IMP.MAIN.MAX_MULTI_GENERIC_PACKET_LENGTH = 4096;
    MinecraftConnection connection = mock(MinecraftConnection.class);
    when(connection.getState()).thenReturn(StateRegistry.CONFIG);
    LoginConfirmHandler handler = new LoginConfirmHandler(mock(LimboAPI.class), connection);
    PluginMessagePacket first = new PluginMessagePacket("test:payload", Unpooled.buffer().writeZero(4096));
    handler.handleGeneric(first);
    first.release();
    PluginMessagePacket excess = new PluginMessagePacket("test:payload", Unpooled.buffer().writeByte(1));
    handler.handleGeneric(excess);
    handler.handleGeneric(excess);
    excess.release();
    assertEquals(0, first.refCnt());
    assertEquals(0, excess.refCnt());
    verify(connection).close(true);
  }

  @Test
  void failedTransitionReleasesPacketsWithoutReplayingThem() {
    MinecraftConnection connection = mock(MinecraftConnection.class);
    when(connection.getState()).thenReturn(StateRegistry.CONFIG);
    LoginConfirmHandler handler = new LoginConfirmHandler(mock(LimboAPI.class), connection);
    PluginMessagePacket packet = new PluginMessagePacket("test:payload", Unpooled.buffer().writeByte(1));
    handler.handleGeneric(packet);
    packet.release();
    handler.waitForConfirmation(() -> {
      throw new IllegalStateException("test failure");
    });
    handler.handle(new com.velocitypowered.proxy.protocol.packet.LoginAcknowledgedPacket());
    assertEquals(0, packet.refCnt());
    verify(connection).close(true);
  }
}
