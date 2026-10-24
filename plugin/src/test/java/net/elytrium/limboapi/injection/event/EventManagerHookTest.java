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

package net.elytrium.limboapi.injection.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ListMultimap;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.proxy.connection.client.LoginInboundConnection;
import com.velocitypowered.proxy.event.VelocityEventManager;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.elytrium.limboapi.LimboAPI;
import net.elytrium.limboapi.Settings;
import net.elytrium.limboapi.injection.login.LoginListener;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.Logger;

class EventManagerHookTest {

  private final LimboAPI plugin = mock(LimboAPI.class);
  private final LoginListener loginListener = mock(LoginListener.class);
  private final PluginManager pluginManager = mock(PluginManager.class);
  private final VelocityEventManager eventManager = new VelocityEventManager(this.pluginManager);
  private final EventManagerHook hook = new EventManagerHook(this.plugin, this.eventManager);
  private Settings.MAIN oldMain;
  private MockedStatic<LimboAPI> staticPlugin;

  @BeforeEach
  void setup() {
    this.staticPlugin = mockStatic(LimboAPI.class);
    this.staticPlugin.when(LimboAPI::getLogger).thenReturn(mock(Logger.class));
    when(this.plugin.getLoginListener()).thenReturn(this.loginListener);
    this.oldMain = Settings.IMP.MAIN;
    Settings.IMP.MAIN = new Settings.MAIN();
    Settings.IMP.MAIN.PRE_LIMBO_PROFILE_REQUEST_PLUGINS = new ArrayList<>();
  }

  @AfterEach
  void teardown() {
    Settings.IMP.MAIN = this.oldMain;
    if (this.staticPlugin != null) {
      this.staticPlugin.close();
    }
  }

  @Test
  void failedLoginHookTerminatesOriginalEventExceptionally() throws Throwable {
    this.registerHook();
    doThrow(new IllegalStateException("login hook failed")).when(this.loginListener).hookLoginSession(any());
    AtomicInteger remainingHandlers = new AtomicInteger();
    this.register("ordinary", GameProfileRequestEvent.class, (short) 0, event -> {
      remainingHandlers.incrementAndGet();
      return null;
    });

    GameProfileRequestEvent event = this.profileEvent();
    CompletableFuture<GameProfileRequestEvent> future = this.eventManager.fire(event);

    assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
    assertEquals(0, remainingHandlers.get());
    verify((LoginInboundConnection) event.getConnection()).disconnect(any());
  }

  @Test
  void handledKickTerminatesFutureWithoutRunningVanillaOrLaterHandlers() {
    this.registerKickHook();
    AtomicInteger laterHandlers = new AtomicInteger();
    this.register("later", KickedFromServerEvent.class, Short.MIN_VALUE, event -> {
      laterHandlers.incrementAndGet();
      return null;
    });
    Player player = mock(Player.class);
    KickedFromServerEvent event = this.kickEvent(player, KickedFromServerEvent.DisconnectPlayer.create(Component.text("restart")));
    when(this.plugin.getKickCallback(player)).thenReturn(kick -> true);

    CompletableFuture<KickedFromServerEvent> future = this.eventManager.fire(event);
    AtomicInteger vanillaHandling = new AtomicInteger();
    CompletableFuture<Void> vanillaFuture = future.thenAccept(ignored -> vanillaHandling.incrementAndGet());

    assertTrue(future.isCancelled());
    assertTrue(vanillaFuture.isCompletedExceptionally());
    assertEquals(0, vanillaHandling.get());
    assertEquals(0, laterHandlers.get());
  }

  @Test
  void absentOrFailedKickCallbackAllowsNormalKickHandling() throws Exception {
    this.registerKickHook();
    Player player = mock(Player.class);
    KickedFromServerEvent event = this.kickEvent(player, KickedFromServerEvent.DisconnectPlayer.create(Component.text("restart")));

    assertEquals(event, this.eventManager.fire(event).get(1, TimeUnit.SECONDS));

    when(this.plugin.getKickCallback(player)).thenReturn(kick -> {
      throw new IllegalStateException("callback failed");
    });
    assertEquals(event, this.eventManager.fire(event).get(1, TimeUnit.SECONDS));
  }

  @Test
  void securityPluginDisconnectResultCannotBeOverriddenByReconnect() throws Exception {
    this.registerKickHook();
    Player player = mock(Player.class);
    KickedFromServerEvent event = this.kickEvent(player, KickedFromServerEvent.RedirectPlayer.create(mock(RegisteredServer.class)));
    KickedFromServerEvent.ServerKickResult denied = KickedFromServerEvent.DisconnectPlayer.create(Component.text("resource pack required"));
    this.register("forcepack", KickedFromServerEvent.class, (short) -32767, kick -> {
      kick.setResult(denied);
      return null;
    });
    when(this.plugin.getKickCallback(player)).thenReturn(kick -> true);

    assertEquals(event, this.eventManager.fire(event).get(1, TimeUnit.SECONDS));

    assertEquals(denied, event.getResult());
    verify(this.plugin, never()).getKickCallback(player);
  }

  @Test
  void anotherPluginsRedirectResultCannotBeOverriddenByReconnect() throws Exception {
    this.registerKickHook();
    Player player = mock(Player.class);
    KickedFromServerEvent event = this.kickEvent(player, KickedFromServerEvent.DisconnectPlayer.create(Component.text("restart")));
    KickedFromServerEvent.ServerKickResult redirect = KickedFromServerEvent.RedirectPlayer.create(mock(RegisteredServer.class));
    this.register("nlogin", KickedFromServerEvent.class, (short) -32767, kick -> {
      kick.setResult(redirect);
      return null;
    });
    when(this.plugin.getKickCallback(player)).thenReturn(kick -> true);

    assertEquals(event, this.eventManager.fire(event).get(1, TimeUnit.SECONDS));

    assertEquals(redirect, event.getResult());
    verify(this.plugin, never()).getKickCallback(player);
  }

  @Test
  void unchangedDefaultRedirectResultAllowsReconnectToHandleTheKick() {
    this.registerKickHook();
    Player player = mock(Player.class);
    KickedFromServerEvent event = this.kickEvent(player, KickedFromServerEvent.RedirectPlayer.create(mock(RegisteredServer.class)));
    when(this.plugin.getKickCallback(player)).thenReturn(kick -> true);

    assertTrue(this.eventManager.fire(event).isCancelled());
  }

  @Test
  void removingPreLimboPluginFromSettingsRestoresItsHandlersAndReloadIsIdempotent() throws Exception {
    this.register("nlogin", GameProfileRequestEvent.class, (short) -32767, event -> null);
    Settings.IMP.MAIN.PRE_LIMBO_PROFILE_REQUEST_PLUGINS = List.of("nlogin");
    this.hook.reloadHandlers();
    assertEquals(0, this.profileHandlers().size());

    Settings.IMP.MAIN.PRE_LIMBO_PROFILE_REQUEST_PLUGINS = List.of();
    this.hook.reloadHandlers();
    this.hook.reloadHandlers();

    assertEquals(1, this.profileHandlers().size());
  }

  @Test
  void movingPreLimboHandlersInvalidatesPreviouslyBakedVelocityCache() throws Exception {
    this.register("nlogin", GameProfileRequestEvent.class, (short) -32767, event -> null);
    assertTrue(this.eventManager.hasSubscribers(GameProfileRequestEvent.class));
    Settings.IMP.MAIN.PRE_LIMBO_PROFILE_REQUEST_PLUGINS = List.of("nlogin");

    this.hook.reloadHandlers();

    assertFalse(this.eventManager.hasSubscribers(GameProfileRequestEvent.class));
  }

  @Test
  void preLimboAuthenticationRunsOnceAndProfileReplayPreservesTheOriginalConnection() throws Throwable {
    this.registerHook();
    AtomicInteger authenticationHandlers = new AtomicInteger();
    GameProfile authenticatedProfile = new GameProfile(UUID.randomUUID(), "Player", List.of());
    this.register("nlogin", GameProfileRequestEvent.class, (short) -32767, event -> {
      authenticationHandlers.incrementAndGet();
      event.setGameProfile(authenticatedProfile);
      return null;
    });
    AtomicInteger remainingHandlers = new AtomicInteger();
    this.register("ordinary", GameProfileRequestEvent.class, (short) 0, event -> {
      remainingHandlers.incrementAndGet();
      return null;
    });
    Settings.IMP.MAIN.PRE_LIMBO_PROFILE_REQUEST_PLUGINS = List.of("nlogin");
    this.hook.reloadHandlers();
    GameProfileRequestEvent initialEvent = this.profileEvent();
    this.eventManager.fire(initialEvent).get(1, TimeUnit.SECONDS);

    assertEquals(authenticatedProfile, initialEvent.getGameProfile());
    assertEquals(0, remainingHandlers.get());
    GameProfileRequestEvent replay = new GameProfileRequestEvent(initialEvent.getConnection(), authenticatedProfile, false);
    assertEquals(replay, this.fireProfileRequestEvent(replay).get(1, TimeUnit.SECONDS));

    assertEquals(initialEvent.getConnection(), replay.getConnection());
    assertEquals(1, authenticationHandlers.get());
    assertEquals(1, remainingHandlers.get());
    verify(this.loginListener, times(1)).hookLoginSession(initialEvent);
  }

  @Test
  void concurrentConnectionWithSameProfileCannotConsumeAnotherEventsBypassMarker() throws Throwable {
    this.registerHook();
    GameProfileRequestEvent replay = this.profileEvent();
    AtomicReference<Continuation> replayContinuation = new AtomicReference<>();
    this.register("early", GameProfileRequestEvent.class, Short.MAX_VALUE,
        event -> event == replay ? EventTask.withContinuation(replayContinuation::set) : null);
    final CompletableFuture<GameProfileRequestEvent> replayFuture = this.fireProfileRequestEvent(replay);
    GameProfileRequestEvent otherConnection = new GameProfileRequestEvent(mock(InboundConnection.class), replay.getGameProfile(), false);

    this.eventManager.fire(otherConnection).get(1, TimeUnit.SECONDS);
    verify(this.loginListener, times(1)).hookLoginSession(otherConnection);
    replayContinuation.get().resume();

    assertEquals(replay, replayFuture.get(1, TimeUnit.SECONDS));
    verify(this.loginListener, times(1)).hookLoginSession(any());
  }

  @Test
  void cancellingProfileReplayClearsItsBypassBeforeAnotherDispatch() throws Throwable {
    this.registerHook();
    AtomicBoolean pause = new AtomicBoolean(true);
    this.register("early", GameProfileRequestEvent.class, Short.MAX_VALUE,
        event -> pause.getAndSet(false) ? EventTask.withContinuation(ignored -> {}) : null);
    GameProfileRequestEvent replay = this.profileEvent();
    CompletableFuture<GameProfileRequestEvent> future = this.fireProfileRequestEvent(replay);
    assertFalse(future.isDone());

    future.cancel(false);

    assertEquals(replay, this.eventManager.fire(replay).get(1, TimeUnit.SECONDS));
    verify(this.loginListener, times(1)).hookLoginSession(replay);
  }

  @Test
  void preLimboAuthenticationMaintainsVelocityPriorityOrder() throws Exception {
    this.registerHook();
    List<String> order = new ArrayList<>();
    this.register("nlogin", GameProfileRequestEvent.class, (short) -32767, event -> {
      order.add("nlogin");
      return null;
    });
    this.register("floodgate", GameProfileRequestEvent.class, (short) 16383, event -> {
      order.add("floodgate");
      return null;
    });
    Settings.IMP.MAIN.PRE_LIMBO_PROFILE_REQUEST_PLUGINS = List.of("nlogin", "floodgate");

    this.hook.reloadHandlers();
    this.eventManager.fire(this.profileEvent()).get(1, TimeUnit.SECONDS);

    assertEquals(List.of("floodgate", "nlogin"), order);
  }

  private void registerHook() {
    this.register("limboapi", GameProfileRequestEvent.class, (short) 32766, this.hook::onGameProfileRequest);
  }

  private void registerKickHook() {
    this.register("limboapi", KickedFromServerEvent.class, Short.MAX_VALUE, event -> {
      this.hook.captureKickResult(event);
      return null;
    });
    this.register("limboapi", KickedFromServerEvent.class, Short.MIN_VALUE, this.hook::onKickedFromServer);
  }

  private <E> void register(String id, Class<E> type, short order, Function<E, EventTask> handler) {
    Object pluginInstance = new Object();
    PluginContainer container = mock(PluginContainer.class);
    PluginDescription description = mock(PluginDescription.class);
    when(description.getId()).thenReturn(id);
    when(description.getVersion()).thenReturn(Optional.of("test"));
    when(container.getDescription()).thenReturn(description);
    ExecutorService executor = mock(ExecutorService.class);
    doAnswer(invocation -> {
      invocation.<Runnable>getArgument(0).run();
      return null;
    }).when(executor).execute(any(Runnable.class));
    when(container.getExecutorService()).thenReturn(executor);
    when(this.pluginManager.ensurePluginContainer(pluginInstance)).thenReturn(container);
    when(this.pluginManager.fromInstance(pluginInstance)).thenReturn(Optional.of(container));
    this.eventManager.register(pluginInstance, type, order, new EventHandler<>() {
      @Override
      public void execute(E event) {
        throw new AssertionError("Velocity must execute asynchronous event handlers");
      }

      @Override
      public EventTask executeAsync(E event) {
        return handler.apply(event);
      }
    });
  }

  private GameProfileRequestEvent profileEvent() {
    return new GameProfileRequestEvent(mock(LoginInboundConnection.class), new GameProfile(UUID.randomUUID(), "Player", List.of()), false);
  }

  private KickedFromServerEvent kickEvent(Player player, KickedFromServerEvent.ServerKickResult result) {
    return new KickedFromServerEvent(player, mock(RegisteredServer.class), Component.text("restart"), false, result);
  }

  @SuppressWarnings("unchecked")
  private CompletableFuture<GameProfileRequestEvent> fireProfileRequestEvent(GameProfileRequestEvent event) throws Exception {
    return (CompletableFuture<GameProfileRequestEvent>) EventManagerHook.class
        .getMethod("fireProfileRequestEvent", GameProfileRequestEvent.class).invoke(this.hook, event);
  }

  @SuppressWarnings("unchecked")
  private List<?> profileHandlers() throws Exception {
    Field field = VelocityEventManager.class.getDeclaredField("handlersByType");
    field.setAccessible(true);
    return ((ListMultimap<Class<?>, ?>) field.get(this.eventManager)).get(GameProfileRequestEvent.class);
  }
}
