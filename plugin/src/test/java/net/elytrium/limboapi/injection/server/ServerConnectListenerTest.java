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

package net.elytrium.limboapi.injection.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.event.VelocityEventManager;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import net.elytrium.limboapi.LimboAPI;
import net.elytrium.limboapi.server.LimboPlayerImpl;
import net.elytrium.limboapi.server.LimboSessionHandlerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServerConnectListenerTest {

  private final LimboAPI plugin = mock(LimboAPI.class);
  private final ConnectedPlayer player = mock(ConnectedPlayer.class);
  private final MinecraftConnection connection = mock(MinecraftConnection.class);
  private final LimboSessionHandlerImpl session = mock(LimboSessionHandlerImpl.class);
  private final LimboPlayerImpl limboPlayer = mock(LimboPlayerImpl.class);
  private final CompletableFuture<Void> preparation = new CompletableFuture<>();
  private ServerPreConnectEvent event;
  private ServerConnectListener listener;

  @BeforeEach
  void setUp() {
    when(this.player.getConnection()).thenReturn(this.connection);
    when(this.connection.getActiveSessionHandler()).thenReturn(this.session);
    when(this.session.getLimboPlayer()).thenReturn(this.limboPlayer);
    when(this.limboPlayer.prepareForServerConnection()).thenReturn(this.preparation);
    this.event = new ServerPreConnectEvent(this.player, mock(RegisteredServer.class));
    this.listener = new ServerConnectListener(this.plugin);
  }

  @Test
  void holdsTheOriginalNativeEventUntilConfigAckAndNeverCreatesSecondRequest() {
    Continuation continuation = mock(Continuation.class);
    EventTask task = this.listener.onServerPreConnect(this.event);
    task.execute(continuation);
    verify(continuation, never()).resume();
    this.preparation.complete(null);
    verify(continuation).resume();
    assertTrue(this.event.getResult().isAllowed());
    verify(this.player, never()).createConnectionRequest(any());
  }

  @Test
  void otherPluginsDeniedResultIsPreserved() {
    this.event.setResult(ServerPreConnectEvent.ServerResult.denied());
    assertNull(this.listener.onServerPreConnect(this.event));
    verify(this.limboPlayer, never()).prepareForServerConnection();
  }

  @Test
  void incompleteLoginQueueCannotBeBypassed() {
    when(this.plugin.hasLoginQueue(this.player)).thenReturn(true);
    assertNull(this.listener.onServerPreConnect(this.event));
    assertTrue(this.event.getResult().getServer().isEmpty());
    verify(this.limboPlayer, never()).prepareForServerConnection();
  }

  @Test
  void failedPreparationCompletesAsNativeCancellation() {
    Continuation continuation = mock(Continuation.class);
    EventTask task = this.listener.onServerPreConnect(this.event);
    task.execute(continuation);
    this.preparation.completeExceptionally(new IllegalStateException("failed transition"));
    verify(continuation).resume();
    assertTrue(this.event.getResult().getServer().isEmpty());
    verify(continuation, never()).resumeWithException(any());
  }

  @Test
  void normalNativeConnectionsNeedNoLimboWork() {
    when(this.connection.getActiveSessionHandler()).thenReturn(mock(MinecraftSessionHandler.class));
    assertNull(this.listener.onServerPreConnect(this.event));
    verify(this.limboPlayer, never()).prepareForServerConnection();
  }

  private VelocityEventManager nativeEventManager() {
    PluginManager plugins = mock(PluginManager.class);
    PluginContainer container = mock(PluginContainer.class);
    PluginDescription description = mock(PluginDescription.class);
    when(description.getId()).thenReturn("limboapi");
    when(description.getVersion()).thenReturn(Optional.of("test"));
    when(container.getDescription()).thenReturn(description);
    ExecutorService executor = mock(ExecutorService.class);
    doAnswer(
            call -> {
              call.<Runnable>getArgument(0).run();
              return null;
            })
        .when(executor)
        .execute(any(Runnable.class));
    when(container.getExecutorService()).thenReturn(executor);
    when(plugins.ensurePluginContainer(any())).thenReturn(container);
    when(plugins.fromInstance(any())).thenReturn(Optional.of(container));
    VelocityEventManager manager = new VelocityEventManager(plugins);
    manager.register(new Object(), this.listener);
    return manager;
  }

  @Test
  void actualCtdEventPipelineWaitsForTheSameNativeRequest() throws Exception {
    VelocityEventManager manager = this.nativeEventManager();
    CompletableFuture<ServerPreConnectEvent> future = manager.fire(this.event);
    assertFalse(future.isDone());
    this.preparation.complete(null);
    assertSame(this.event, future.get(1, TimeUnit.SECONDS));
    verify(this.player, never()).createConnectionRequest(any());
  }

  @Test
  void actualCtdPipelineHonorsAuthenticationAndPackDenialsBeforeHandoff() throws Exception {
    VelocityEventManager manager = this.nativeEventManager();
    manager.register(
        new Object(),
        ServerPreConnectEvent.class,
        (short) 0,
        event -> event.setResult(ServerPreConnectEvent.ServerResult.denied()));
    assertSame(this.event, manager.fire(this.event).get(1, TimeUnit.SECONDS));
    assertTrue(this.event.getResult().getServer().isEmpty());
    verify(this.limboPlayer, never()).prepareForServerConnection();
  }
}
