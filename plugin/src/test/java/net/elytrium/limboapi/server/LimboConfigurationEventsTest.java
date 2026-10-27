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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.player.configuration.PlayerEnterConfigurationEvent;
import com.velocitypowered.api.event.player.configuration.PlayerEnteredConfigurationEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.client.ClientPlaySessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.event.VelocityEventManager;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.config.StartUpdatePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.elytrium.limboapi.LimboAPI;
import net.elytrium.limboapi.Settings;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.protocol.PreparedPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LimboConfigurationEventsTest {

  private final EmbeddedChannel channel = new EmbeddedChannel();
  private final LimboAPI plugin = mock(LimboAPI.class);
  private final LimboImpl limbo = mock(LimboImpl.class);
  private final ConnectedPlayer player = mock(ConnectedPlayer.class);
  private final MinecraftConnection connection = mock(MinecraftConnection.class);
  private final VelocityServer proxy = mock(VelocityServer.class);
  private final VelocityEventManager events = mock(VelocityEventManager.class);
  private final LimboSessionHandler callback = mock(LimboSessionHandler.class);
  private final AtomicReference<StateRegistry> state = new AtomicReference<>(StateRegistry.PLAY);
  private final AtomicReference<MinecraftSessionHandler> active = new AtomicReference<>();
  private final List<PlayerEnterConfigurationEvent> enters = new ArrayList<>();
  private final List<PlayerEnteredConfigurationEvent> entered = new ArrayList<>();
  private final List<CompletableFuture<PlayerEnterConfigurationEvent>> pending = new ArrayList<>();
  private LimboSessionHandlerImpl session;

  @BeforeEach
  void setUp() {
    Settings.IMP.MAIN = new Settings.MAIN();
    Settings.IMP.MAIN.LOGGING_ENABLED = false;
    when(this.plugin.getServer()).thenReturn(this.proxy);
    when(this.proxy.getEventManager()).thenReturn(this.events);
    when(this.player.getConnection()).thenReturn(this.connection);
    when(this.player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_21_4);
    when(this.connection.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_21_4);
    when(this.connection.eventLoop()).thenReturn(this.channel.eventLoop());
    when(this.connection.getChannel()).thenReturn(this.channel);
    when(this.connection.isClosed()).thenAnswer(call -> !this.channel.isOpen());
    when(this.connection.getState()).thenAnswer(call -> this.state.get());
    when(this.connection.getActiveSessionHandler()).thenAnswer(call -> this.active.get());
    when(this.connection.write(any())).thenReturn(this.channel.newSucceededFuture());
    when(this.limbo.getReadTimeout()).thenReturn(30000);
    doAnswer(
            call -> {
              this.state.set(call.getArgument(1));
              this.active.set(call.getArgument(2));
              return null;
            })
        .when(this.plugin)
        .setActiveSessionHandler(any(), any(), any());
    doAnswer(
            call -> {
              this.state.set(call.getArgument(0));
              this.active.set(call.getArgument(1));
              return null;
            })
        .when(this.connection)
        .setActiveSessionHandler(any(), any());
    when(this.events.fire(any(PlayerEnterConfigurationEvent.class)))
        .thenAnswer(
            call -> {
              PlayerEnterConfigurationEvent event = call.getArgument(0);
              CompletableFuture<PlayerEnterConfigurationEvent> future = new CompletableFuture<>();
              this.enters.add(event);
              this.pending.add(future);
              return future;
            });
    doAnswer(
            call -> {
              this.entered.add(call.getArgument(0));
              return null;
            })
        .when(this.events)
        .fireAndForget(any(PlayerEnteredConfigurationEvent.class));
    this.session =
        new LimboSessionHandlerImpl(
            this.plugin,
            this.limbo,
            this.player,
            this.callback,
            StateRegistry.PLAY,
            mock(ClientPlaySessionHandler.class),
            null,
            () -> "test");
    this.active.set(this.session);
  }

  @AfterEach
  void close() {
    for (CompletableFuture<?> future : this.pending) {
      future.cancel(false);
    }
    this.channel.runPendingTasks();
    this.session.release();
    this.channel.finishAndReleaseAll();
  }

  private void allowEntry(int index) {
    this.pending.get(index).complete(this.enters.get(index));
    this.channel.runPendingTasks();
  }

  @Test
  void nativeEnterListenersFinishBeforeStartUpdateAndEnteredWaitsForTheAck() {
    AtomicBoolean handedOff = new AtomicBoolean();
    this.session.onSpawn();
    this.session.disconnect(() -> this.session.disconnectToConfig(() -> handedOff.set(true)));
    assertEquals(1, this.enters.size());
    assertSame(this.player, this.enters.getFirst().player());
    this.channel.runPendingTasks();
    verify(this.connection, never()).write(StartUpdatePacket.INSTANCE);
    assertTrue(this.entered.isEmpty());
    assertFalse(handedOff.get());

    this.allowEntry(0);
    verify(this.connection).write(StartUpdatePacket.INSTANCE);
    assertTrue(this.entered.isEmpty());
    assertFalse(handedOff.get());

    this.session.handle(FinishedUpdatePacket.INSTANCE);
    assertEquals(StateRegistry.CONFIG, this.state.get());
    assertEquals(1, this.entered.size());
    assertSame(this.player, this.entered.getFirst().player());
    assertTrue(handedOff.get());
  }

  @Test
  void duplicateStartsShareOneNativeEntryAndOneStartPacket() {
    Runnable send = mock(Runnable.class);
    this.session.startConfiguration(send);
    this.session.startConfiguration(send);
    assertEquals(1, this.pending.size());
    this.allowEntry(0);
    this.session.startConfiguration(send);
    verify(send).run();
    assertEquals(1, this.pending.size());
  }

  @Test
  void limboEntryAndLaterExitEachNotifyOneRealConfigurationTransition() {
    Runnable enteringLimbo = mock(Runnable.class);
    this.session.startConfiguration(enteringLimbo);
    this.allowEntry(0);
    this.session.handle(FinishedUpdatePacket.INSTANCE);
    verify(this.limbo)
        .spawnPlayerLocal(this.callback.getClass(), this.session, this.player, this.connection);
    verify(enteringLimbo).run();

    // The limbo's own CONFIG exchange has returned this same client to PLAY.
    this.state.set(StateRegistry.PLAY);
    this.session.onSpawn();
    AtomicBoolean handedOff = new AtomicBoolean();
    this.session.disconnect(() -> this.session.disconnectToConfig(() -> handedOff.set(true)));
    assertEquals(2, this.pending.size());
    this.allowEntry(1);
    this.session.handle(FinishedUpdatePacket.INSTANCE);
    assertEquals(2, this.entered.size());
    assertTrue(handedOff.get());
  }

  @Test
  void replacementOwnerDuringAnAwaitedEntryIsNeverWrittenOrClosed() {
    Runnable send = mock(Runnable.class);
    this.session.startConfiguration(send);
    MinecraftSessionHandler replacement = mock(MinecraftSessionHandler.class);
    this.active.set(replacement);
    this.allowEntry(0);
    verify(send, never()).run();
    verify(this.connection, never()).close();
    verify(this.plugin, never()).setActiveSessionHandler(any(), any(), any());
    assertSame(replacement, this.active.get());
    assertTrue(this.entered.isEmpty());
  }

  @Test
  void physicalDisconnectCancelsAnAwaitedEntryWithoutResumingIt() {
    Runnable send = mock(Runnable.class);
    this.session.startConfiguration(send);
    this.channel.close();
    this.session.disconnected();
    assertTrue(this.pending.getFirst().isCancelled());
    this.channel.runPendingTasks();
    verify(send, never()).run();
    verify(this.connection, never()).close();
    verify(this.plugin).releasePlayer(this.player);
    assertTrue(this.entered.isEmpty());
  }

  @Test
  void failedNativeEntryClosesOnlyItsStillOwnedConnection() {
    Runnable send = mock(Runnable.class);
    this.session.startConfiguration(send);
    this.pending.getFirst().completeExceptionally(new IllegalStateException("failed entry"));
    this.channel.runPendingTasks();
    verify(this.connection).close();
    verify(send, never()).run();
    assertTrue(this.entered.isEmpty());
  }

  @Test
  void failedNativeEntryCannotCloseTheReplacementOwner() {
    Runnable send = mock(Runnable.class);
    this.session.startConfiguration(send);
    this.active.set(mock(MinecraftSessionHandler.class));
    this.pending.getFirst().completeExceptionally(new IllegalStateException("failed entry"));
    this.channel.runPendingTasks();
    verify(this.connection, never()).close();
    verify(send, never()).run();
  }

  @Test
  void acknowledgementBeforeStartUpdateCannotSpawnOrCompleteAHandoff() {
    Runnable send = mock(Runnable.class);
    this.session.startConfiguration(send);
    this.session.handle(FinishedUpdatePacket.INSTANCE);
    this.channel.runPendingTasks();
    verify(this.connection).close();
    verify(this.limbo, never()).spawnPlayerLocal(any(), any(), any(), any());
    verify(send, never()).run();
    assertTrue(this.entered.isEmpty());
  }

  @Test
  void ownershipChangedByAnEnteredListenerPreventsAStaleSpawn() {
    this.session.startConfiguration(() -> {});
    this.allowEntry(0);
    MinecraftSessionHandler replacement = mock(MinecraftSessionHandler.class);
    doAnswer(
            call -> {
              this.active.set(replacement);
              return null;
            })
        .when(this.events)
        .fireAndForget(any(PlayerEnteredConfigurationEvent.class));
    this.session.handle(FinishedUpdatePacket.INSTANCE);
    verify(this.limbo, never()).spawnPlayerLocal(any(), any(), any(), any());
    assertSame(replacement, this.active.get());
  }

  private LimboImpl localEntry(boolean shouldRejoin) throws ReflectiveOperationException {
    LimboImpl local = mock(LimboImpl.class);
    Field pluginField = LimboImpl.class.getDeclaredField("plugin");
    pluginField.setAccessible(true);
    pluginField.set(local, this.plugin);
    Field rejoinField = LimboImpl.class.getDeclaredField("shouldRejoin");
    rejoinField.setAccessible(true);
    rejoinField.setBoolean(local, shouldRejoin);
    local.localStateRegistry = StateRegistry.PLAY;
    doCallRealMethod().when(local).spawnPlayerLocal(any(), any(), any(), any());
    return local;
  }

  @Test
  void realLimboEntryWaitsForTheNativeEventBeforeItsPreparedStartPacket()
      throws ReflectiveOperationException {
    LimboImpl local = this.localEntry(true);
    PreparedPacket transition = mock(PreparedPacket.class);
    local.configTransitionPackets = transition;
    local.spawnPlayerLocal(this.callback.getClass(), this.session, this.player, this.connection);
    assertEquals(1, this.pending.size());
    verify(this.connection, never()).write(transition);
    this.allowEntry(0);
    verify(this.connection).write(transition);
  }

  @Test
  void initialLoginAlreadyInConfigDoesNotInventConfigurationEntryEvents()
      throws ReflectiveOperationException {
    LimboImpl local = this.localEntry(true);
    this.state.set(StateRegistry.CONFIG);
    local.spawnPlayerLocal(this.callback.getClass(), this.session, this.player, this.connection);
    assertTrue(this.enters.isEmpty());
    assertTrue(this.entered.isEmpty());
    verify(this.events, never()).fire(any());
    verify(this.events, never()).fireAndForget(any());
    verify(local, never()).onSpawn(any(), any(), any(), any());
  }

  @Test
  void noRejoinLimboKeepsPlayWithoutSyntheticConfigurationEvents()
      throws ReflectiveOperationException {
    LimboImpl local = this.localEntry(false);
    local.spawnPlayerLocal(this.callback.getClass(), this.session, this.player, this.connection);
    assertTrue(this.enters.isEmpty());
    assertTrue(this.entered.isEmpty());
    assertEquals(StateRegistry.PLAY, this.state.get());
    verify(local).onSpawn(this.callback.getClass(), this.connection, this.player, this.session);
    verify(this.events, never()).fire(any());
    verify(this.events, never()).fireAndForget(any());
  }
}
