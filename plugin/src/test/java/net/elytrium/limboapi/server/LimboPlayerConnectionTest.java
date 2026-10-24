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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.client.ClientConfigSessionHandler;
import com.velocitypowered.proxy.connection.client.ClientPlaySessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.StateRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import net.elytrium.limboapi.LimboAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LimboPlayerConnectionTest {

  private final LimboAPI plugin = mock(LimboAPI.class);
  private final ConnectedPlayer player = mock(ConnectedPlayer.class);
  private final MinecraftConnection connection = mock(MinecraftConnection.class);
  private final LimboSessionHandlerImpl session = mock(LimboSessionHandlerImpl.class);
  private final EmbeddedChannel channel = new EmbeddedChannel();
  private final AtomicReference<MinecraftSessionHandler> active = new AtomicReference<>();
  private final AtomicReference<Runnable> configAcknowledged = new AtomicReference<>();
  private LimboPlayerImpl limboPlayer;

  @BeforeEach
  void setUp() throws Exception {
    when(this.player.getConnection()).thenReturn(this.connection);
    when(this.player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_21_4);
    when(this.connection.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_21_4);
    when(this.connection.eventLoop()).thenReturn(this.channel.eventLoop());
    when(this.connection.getChannel()).thenReturn(this.channel);
    this.active.set(this.session);
    when(this.connection.getActiveSessionHandler()).thenAnswer(call -> this.active.get());
    VelocityServer proxy = mock(VelocityServer.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(proxy.getConfiguration().getReadTimeout()).thenReturn(25000);
    when(this.plugin.getServer()).thenReturn(proxy);
    when(this.plugin.getInitialID(this.player)).thenReturn(UUID.randomUUID());
    this.setLoginCompleted(true);
    doAnswer(
            call -> {
              ((Runnable) call.getArgument(0)).run();
              return null;
            })
        .when(this.session)
        .disconnect(any(Runnable.class));
    doAnswer(
            call -> {
              this.configAcknowledged.set(call.getArgument(0));
              return null;
            })
        .when(this.session)
        .disconnectToConfig(any(Runnable.class));
    doAnswer(
            call -> {
              this.active.set(call.getArgument(1));
              return null;
            })
        .when(this.connection)
        .setActiveSessionHandler(any(StateRegistry.class), any());
    this.limboPlayer = new LimboPlayerImpl(this.plugin, mock(LimboImpl.class), this.player);
  }

  @AfterEach
  void close() {
    this.channel.finishAndReleaseAll();
  }

  @Test
  void waitsForConfigAckWithoutOpeningAnotherConnection() {
    final CompletableFuture<Void> ready = this.limboPlayer.prepareForServerConnection();
    this.channel.runPendingTasks();
    assertFalse(ready.isDone());
    assertFalse(this.limboPlayer.isServerConnectionPrepared());
    verify(this.player, never()).createConnectionRequest(any());
    this.configAcknowledged.get().run();
    assertTrue(ready.isDone());
    assertFalse(ready.isCompletedExceptionally());
    assertTrue(this.active.get() instanceof ClientConfigSessionHandler);
    assertTrue(this.limboPlayer.isServerConnectionPrepared());
    verify(this.player, never()).createConnectionRequest(any());
  }

  @Test
  void coalescesConcurrentPreparationAndDetectsOtherHandlers() {
    CompletableFuture<Void> first = this.limboPlayer.prepareForServerConnection();
    assertSame(first, this.limboPlayer.prepareForServerConnection());
    this.channel.runPendingTasks();
    this.configAcknowledged.get().run();
    this.active.set(mock(MinecraftSessionHandler.class));
    assertFalse(this.limboPlayer.isServerConnectionPrepared());
  }

  @Test
  void closingChannelFinishesPendingHandoff() {
    final CompletableFuture<Void> ready = this.limboPlayer.prepareForServerConnection();
    this.channel.runPendingTasks();
    this.channel.close();
    this.channel.runPendingTasks();
    assertTrue(ready.isDone());
    assertTrue(ready.isCompletedExceptionally());
  }

  @Test
  void doesNotTransferBeforeAuthenticationFinishes() {
    when(this.plugin.hasLoginQueue(this.player)).thenReturn(true);
    final CompletableFuture<Void> ready = this.limboPlayer.prepareForServerConnection();
    this.channel.runPendingTasks();
    assertTrue(ready.isCompletedExceptionally());
    verify(this.session, never()).disconnect(any(Runnable.class));
  }

  private void setLoginCompleted(boolean value) throws Exception {
    Method method = ConnectedPlayer.class.getDeclaredMethod("isLoginCompleted");
    method.setAccessible(true);
    method.invoke(doReturn(value).when(this.player));
  }

  @Test
  void absentLoginQueueDoesNotMeanLoginEventsAreComplete() throws Exception {
    this.setLoginCompleted(false);
    final CompletableFuture<Void> ready = this.limboPlayer.prepareForServerConnection();
    this.channel.runPendingTasks();
    assertTrue(ready.isCompletedExceptionally());
    verify(this.session, never()).disconnect(any(Runnable.class));
  }

  @Test
  void cancelledTransitionDoesNotLeaveReleasedLimboOpen() {
    final CompletableFuture<Void> ready = this.limboPlayer.prepareForServerConnection();
    this.channel.runPendingTasks();
    ready.cancel(false);
    this.channel.runPendingTasks();
    verify(this.connection).close();
  }

  @Test
  void failureOwnershipIsCheckedOnEventLoop() {
    final CompletableFuture<Void> ready = this.limboPlayer.prepareForServerConnection();
    this.channel.runPendingTasks();
    ready.completeExceptionally(new TimeoutException());
    verify(this.connection, never()).close();
    this.active.set(mock(MinecraftSessionHandler.class));
    this.channel.runPendingTasks();
    verify(this.connection, never()).close();
  }

  @Test
  void oldDirectRequestCannotEjectReplacementHandler() {
    this.limboPlayer.disconnect(mock(RegisteredServer.class));
    this.channel.runPendingTasks();
    this.configAcknowledged.get().run();
    this.active.set(mock(MinecraftSessionHandler.class));
    this.channel.runPendingTasks();
    verify(this.player, never()).createConnectionRequest(any());
  }

  @Test
  void legacyPreparedHandlerRemembersAlreadySpawnedWorld() throws Exception {
    when(this.player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_8);
    when(this.connection.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_8);
    when(this.plugin.isLimboJoined(this.player)).thenReturn(true);
    this.limboPlayer = new LimboPlayerImpl(this.plugin, mock(LimboImpl.class), this.player);
    final CompletableFuture<Void> ready = this.limboPlayer.prepareForServerConnection();
    this.channel.runPendingTasks();
    assertFalse(ready.isCompletedExceptionally());
    assertTrue(ready.isDone());
    assertTrue(this.active.get() instanceof ClientPlaySessionHandler);
    Field spawned = ClientPlaySessionHandler.class.getDeclaredField("spawned");
    spawned.setAccessible(true);
    assertTrue(spawned.getBoolean(this.active.get()));
  }

  @Test
  void cancellationWhileWaitingForPlayConfirmationFinishesOwnedTransition() {
    doAnswer(call -> null).when(this.session).disconnect(any(Runnable.class));
    final CompletableFuture<Void> ready = this.limboPlayer.prepareForServerConnection();
    this.channel.runPendingTasks();
    ready.cancel(false);
    this.channel.runPendingTasks();
    verify(this.connection).close();
  }

  @Test
  void directApiCannotBypassPendingLoginEvents() throws Exception {
    this.setLoginCompleted(false);
    this.limboPlayer.disconnect(mock(RegisteredServer.class));
    this.channel.runPendingTasks();
    verify(this.session, never()).disconnect(any(Runnable.class));
    verify(this.player, never()).createConnectionRequest(any());
  }
}
