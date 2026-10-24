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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.AuthSessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.player.resourcepack.ResourcePackResponseBundle;
import com.velocitypowered.proxy.connection.player.resourcepack.handler.ModernResourcePackHandler;
import com.velocitypowered.proxy.connection.player.resourcepack.handler.ResourcePackHandler;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;
import com.velocitypowered.proxy.protocol.packet.ResourcePackResponsePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.ScheduledFuture;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.elytrium.limboapi.LimboAPI;
import net.elytrium.limboapi.Settings;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.player.LimboPlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LimboSessionHandlerImplTest {

  private LimboAPI plugin;
  private LimboImpl limbo;
  private ConnectedPlayer player;
  private MinecraftConnection connection;
  private LimboSessionHandler callback;
  private LimboSessionHandlerImpl session;

  @BeforeEach
  void setUp() {
    Settings.IMP.MAIN = new Settings.MAIN();
    Settings.IMP.MAIN.LOGGING_ENABLED = false;
    this.plugin = mock(LimboAPI.class);
    this.limbo = mock(LimboImpl.class);
    this.player = mock(ConnectedPlayer.class);
    this.connection = mock(MinecraftConnection.class);
    this.callback = mock(LimboSessionHandler.class);
    when(this.player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_21_4);
    when(this.player.getConnection()).thenReturn(this.connection);
    this.session = new LimboSessionHandlerImpl(this.plugin, this.limbo, this.player,
        this.callback, StateRegistry.CONFIG, mock(AuthSessionHandler.class), null, () -> "test");
  }

  @Test
  void forwardsResourcePackStatusThroughVelocityInBothStates() {
    ResourcePackHandler packs = mock(ModernResourcePackHandler.class);
    when(this.player.resourcePackHandler()).thenReturn(packs);
    when(packs.onResourcePackResponse(any())).thenReturn(true);
    UUID id = UUID.randomUUID();
    for (StateRegistry state : new StateRegistry[] {StateRegistry.CONFIG, StateRegistry.PLAY}) {
      when(this.connection.getState()).thenReturn(state);
      ResourcePackResponsePacket packet = new ResourcePackResponsePacket(id, "hash",
          PlayerResourcePackStatusEvent.Status.SUCCESSFUL);
      assertTrue(packet.handle(this.session));
    }
    ArgumentCaptor<ResourcePackResponseBundle> bundle = ArgumentCaptor.forClass(ResourcePackResponseBundle.class);
    verify(packs, times(2)).onResourcePackResponse(bundle.capture());
    assertEquals(id, bundle.getValue().uuid());
    assertEquals("hash", bundle.getValue().hash());
    assertEquals(PlayerResourcePackStatusEvent.Status.SUCCESSFUL, bundle.getValue().status());
  }

  @Test
  void releaseCountsSessionOnceEvenBeforeConfigFinishes() {
    this.session.release();
    this.session.release();
    verify(this.limbo).onDisconnect();
    verify(this.callback, never()).onDisconnect();
  }

  @Test
  void closedConnectionCleansPluginStateBeforeVelocityLoginEvent() {
    when(this.connection.isClosed()).thenReturn(true);
    this.session.disconnected();
    this.session.disconnected();
    verify(this.plugin).releasePlayer(this.player);
  }

  @Test
  void liveStateSwitchPreservesPluginLoginState() {
    EmbeddedChannel channel = new EmbeddedChannel();
    when(this.connection.getChannel()).thenReturn(channel);
    try {
      this.session.disconnected();
      verify(this.plugin, never()).releasePlayer(this.player);
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void releaseCancelsAllTasksAndNotifiesOnce() throws ReflectiveOperationException {
    this.setField("loaded", true);
    ScheduledFuture<?> keepAlive = mock(ScheduledFuture.class);
    ScheduledFuture<?> respawn = mock(ScheduledFuture.class);
    ScheduledFuture<?> timeout = mock(ScheduledFuture.class);
    this.setField("keepAliveTask", keepAlive);
    this.setField("respawnTask", respawn);
    this.setField("chatSessionTimeoutTask", timeout);
    this.session.release();
    this.session.release();
    verify(keepAlive).cancel(false);
    verify(respawn).cancel(false);
    verify(timeout).cancel(false);
    verify(this.limbo).onDisconnect();
    verify(this.callback).onDisconnect();
  }

  @Test
  void keepAliveWaitsForClientToEnterPlay() {
    LimboPlayer limboPlayer = mock(LimboPlayer.class);
    ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    AtomicReference<Runnable> tick = new AtomicReference<>();
    when(limboPlayer.getScheduledExecutor()).thenReturn(executor);
    when(this.limbo.getReadTimeout()).thenReturn(30000);
    when(executor.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
        .thenAnswer(invocation -> {
          tick.set(invocation.getArgument(0));
          return mock(ScheduledFuture.class);
        });
    this.session.onConfig(limboPlayer);
    tick.get().run();
    verify(this.connection, never()).write(any(KeepAlivePacket.class));
    this.session.onSpawn();
    tick.get().run();
    verify(this.connection).write(any(KeepAlivePacket.class));
    this.session.release();
    assertDoesNotThrow(tick.get()::run);
  }

  @Test
  void limitsBurstsWithoutAccumulatingBytesForTheWholeSession() throws ReflectiveOperationException {
    when(this.plugin.getPackets()).thenReturn(mock(CachedPackets.class));
    Settings.IMP.MAIN.MAX_MULTI_GENERIC_PACKET_LENGTH = 4096;
    ByteBuf packet = Unpooled.buffer().writeZero(2048);
    try {
      this.session.handleUnknown(packet);
      this.session.handleUnknown(packet);
      this.setField("genericWindowStarted", System.nanoTime() - TimeUnit.SECONDS.toNanos(2));
      this.session.handleUnknown(packet);
      this.session.handleUnknown(packet);
      verify(this.connection, never()).closeWith(isNull());
      this.session.handleUnknown(packet);
      verify(this.connection).closeWith(isNull());
    } finally {
      packet.release();
    }
  }

  private void setField(String name, Object value) throws ReflectiveOperationException {
    Field field = LimboSessionHandlerImpl.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(this.session, value);
  }
}
