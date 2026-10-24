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

package net.elytrium.limboapi.injection.login;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.AuthSessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.client.PlayerRegistry;
import com.velocitypowered.proxy.event.VelocityEventManager;
import com.velocitypowered.proxy.protocol.StateRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.elytrium.limboapi.LimboAPI;
import net.elytrium.limboapi.injection.event.EventManagerHook;
import net.elytrium.limboapi.injection.login.confirmation.LoginConfirmHandler;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.Logger;

class LoginTasksQueueTest {

  private final LimboAPI plugin = mock(LimboAPI.class);
  private final VelocityServer server = mock(VelocityServer.class);
  private final ConnectedPlayer player = mock(ConnectedPlayer.class);
  private final MinecraftConnection connection = mock(MinecraftConnection.class);
  private final VelocityEventManager eventManager = mock(VelocityEventManager.class);
  private final LoginConfirmHandler confirmation = mock(LoginConfirmHandler.class);
  private final EmbeddedChannel channel = new EmbeddedChannel();
  private final CompletableFuture<Boolean> registration = new CompletableFuture<>();
  private MockedStatic<LimboAPI> staticPlugin;
  private LoginTasksQueue queue;

  @BeforeEach
  void setUp() throws Exception {
    this.staticPlugin = mockStatic(LimboAPI.class);
    this.staticPlugin.when(LimboAPI::getLogger).thenReturn(mock(Logger.class));
    when(this.server.getEventManager()).thenReturn(this.eventManager);
    when(this.player.getConnection()).thenReturn(this.connection);
    when(this.connection.getChannel()).thenReturn(this.channel);
    when(this.connection.eventLoop()).thenReturn(this.channel.eventLoop());
    when(this.connection.getState()).thenReturn(StateRegistry.CONFIG);
    when(this.connection.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_21_4);
    when(this.connection.getActiveSessionHandler()).thenReturn(this.confirmation);
    when(this.server.registerConnection(this.player)).thenReturn(this.registration);
    when(this.eventManager.fire(any(LoginEvent.class))).thenAnswer(call -> CompletableFuture.completedFuture(call.getArgument(0)));
    AuthSessionHandler handler = mock(AuthSessionHandler.class);
    Field hash = AuthSessionHandler.class.getDeclaredField("serverIdHash");
    hash.setAccessible(true);
    hash.set(handler, "test");
    this.queue = new LoginTasksQueue(this.plugin, handler, this.server, this.player, mock(InboundConnection.class), new ArrayDeque<>());
  }

  @AfterEach
  void tearDown() {
    this.staticPlugin.close();
    this.channel.finishAndReleaseAll();
  }

  private void initialize() throws Exception {
    Method method = LoginTasksQueue.class.getDeclaredMethod("initialize", MinecraftConnection.class);
    method.setAccessible(true);
    method.invoke(this.queue, this.connection);
    this.channel.runPendingTasks();
  }

  @Test
  void asynchronousRegistrationWaitsForResultAndResumesOnPlayerLoop() throws Exception {
    this.initialize();
    verify(this.server).registerConnection(this.player);
    verify(this.confirmation, never()).waitForConfirmation(any());
    this.registration.complete(true);
    this.channel.runPendingTasks();
    verify(this.confirmation).waitForConfirmation(any());
    verify(this.player, never()).disconnect0(any(Component.class), anyBoolean());
  }

  @Test
  void rejectedRegistrationNeverConnectsBackend() throws Exception {
    this.initialize();
    this.registration.complete(false);
    this.channel.runPendingTasks();
    verify(this.player).disconnect0(any(Component.class), anyBoolean());
    verify(this.confirmation, never()).waitForConfirmation(any());
  }

  @Test
  void registrationFailureClosesConnection() throws Exception {
    this.initialize();
    this.registration.completeExceptionally(new IllegalStateException("registry failure"));
    this.channel.runPendingTasks();
    verify(this.connection).close();
    verify(this.confirmation, never()).waitForConfirmation(any());
  }

  @Test
  void lateRegistrationDoesNotReviveDisconnectedPlayer() throws Exception {
    this.initialize();
    when(this.connection.isClosed()).thenReturn(true);
    this.registration.complete(true);
    this.channel.runPendingTasks();
    verify(this.confirmation, never()).waitForConfirmation(any());
    verify(this.plugin).releasePlayer(this.player);
  }

  @Test
  void registrationBridgeDoesNotBlockOrReplaceTheResult() {
    CompletableFuture<Boolean> result = VelocityLoginSupport.register(this.server, this.player);
    assertFalse(result.isDone());
    this.registration.complete(true);
    assertTrue(result.join());
  }

  @Test
  void repeatedNextCannotFireProfileAndInitializeTwice() {
    EventManagerHook hook = mock(EventManagerHook.class);
    when(this.plugin.getEventManagerHook()).thenReturn(hook);
    when(this.player.getGameProfile()).thenReturn(new GameProfile(UUID.randomUUID(), "Tester", List.of()));
    when(hook.fireProfileRequestEvent(any())).thenReturn(new CompletableFuture<>());
    this.queue.next();
    this.queue.next();
    this.channel.runPendingTasks();
    verify(hook, times(1)).fireProfileRequestEvent(any());
  }

  @Test
  void loginMarksDisconnectLifecycleBeforeFiringLoginEvent() throws Exception {
    this.initialize();
    this.registration.complete(true);
    this.channel.runPendingTasks();
    Method method = ConnectedPlayer.class.getDeclaredMethod("markLoginEventFired");
    method.setAccessible(true);
    method.invoke(verify(this.player));
  }

  @Test
  void finalizationReleasesCtdLoginIdentityLock() throws Exception {
    PlayerRegistry registry = mock(PlayerRegistry.class);
    when(this.server.getPlayerRegistry()).thenReturn(registry);
    Method method = VelocityLoginSupport.class.getMethod("finalizeLogin", VelocityServer.class, ConnectedPlayer.class);
    method.invoke(null, this.server, this.player);
    verify(registry).finalizeLogin(this.player);
  }

  @Test
  void brokenPermissionProviderClosesLoginAndReleasesReferences() {
    EventManagerHook hook = mock(EventManagerHook.class);
    when(this.plugin.getEventManagerHook()).thenReturn(hook);
    when(this.player.getGameProfile()).thenReturn(new GameProfile(UUID.randomUUID(), "Tester", List.of()));
    when(hook.fireProfileRequestEvent(any())).thenAnswer(call -> CompletableFuture.completedFuture(call.getArgument(0)));
    when(this.eventManager.fire(any(PermissionsSetupEvent.class))).thenAnswer(call -> {
      PermissionsSetupEvent event = call.getArgument(0);
      event.setProvider(subject -> {
        throw new IllegalStateException("provider failure");
      });
      return CompletableFuture.completedFuture(event);
    });
    this.queue.next();
    this.channel.runPendingTasks();
    verify(this.connection).close();
    verify(this.plugin).releasePlayer(this.player);
  }
}
