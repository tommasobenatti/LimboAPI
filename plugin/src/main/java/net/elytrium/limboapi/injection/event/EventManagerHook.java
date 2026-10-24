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

import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.collect.ListMultimap;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.proxy.connection.client.LoginInboundConnection;
import com.velocitypowered.proxy.event.VelocityEventManager;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.function.Function;
import net.elytrium.commons.utils.reflection.ReflectionException;
import net.elytrium.limboapi.LimboAPI;
import net.elytrium.limboapi.Settings;
import net.kyori.adventure.text.Component;

@SuppressWarnings("unchecked")
public class EventManagerHook {

  private static final Field HANDLERS_BY_TYPE_FIELD;
  private static final Field HANDLERS_CACHE_FIELD;
  private static final Field HANDLERS_LOCK_FIELD;
  private static final Class<?> HANDLER_REGISTRATION_CLASS;
  private static final Comparator<Object> HANDLER_COMPARATOR;
  private static final MethodHandle PLUGIN_FIELD;
  private static final MethodHandle FIRE_METHOD;
  private static final MethodHandle FUTURE_FIELD;

  private final Set<GameProfileRequestEvent> proceededEvents = ConcurrentHashMap.newKeySet();
  private final Map<KickedFromServerEvent, KickedFromServerEvent.ServerKickResult> initialKickResults =
      Collections.synchronizedMap(new WeakHashMap<>());
  private final LimboAPI plugin;
  private final VelocityEventManager eventManager;

  private volatile Object handlerRegistrations = Array.newInstance(HANDLER_REGISTRATION_CLASS, 0);

  public EventManagerHook(LimboAPI plugin, VelocityEventManager eventManager) {
    this.plugin = plugin;
    this.eventManager = eventManager;
  }

  @Subscribe(order = PostOrder.FIRST)
  public EventTask onGameProfileRequest(GameProfileRequestEvent event) {
    if (this.proceededEvents.remove(event)) {
      return null;
    }

    CompletableFuture<GameProfileRequestEvent> fireFuture = new CompletableFuture<>();
    CompletableFuture<GameProfileRequestEvent> hookFuture = fireFuture.thenApply(modifiedEvent -> {
      try {
        this.plugin.getLoginListener().hookLoginSession(modifiedEvent);
        return modifiedEvent;
      } catch (Throwable e) {
        throw new ReflectionException(e);
      }
    });
    hookFuture.whenComplete((result, cause) -> {
      if (cause != null) {
        LimboAPI.getLogger().error("Failed to handle GameProfileRequestEvent", cause);
        try {
          if (event.getConnection() instanceof LoginInboundConnection inbound) {
            inbound.disconnect(Component.translatable("velocity.error.internal-server-connection-error"));
          }
        } catch (Throwable disconnectFailure) {
          cause.addSuppressed(disconnectFailure);
        }
      }
    });

    Object registrations = this.handlerRegistrations;
    if (Array.getLength(registrations) != 0) {
      try {
        FIRE_METHOD.invoke(this.eventManager, fireFuture, event, 0, false, registrations);
      } catch (Throwable e) {
        fireFuture.completeExceptionally(e);
      }
    } else {
      fireFuture.complete(event);
    }

    // The original login pipeline must stop here; remaining subscribers run after the limbo tasks.
    return EventTask.withContinuation(continuation -> hookFuture.whenComplete((result, cause) -> {
      CompletableFuture<GameProfileRequestEvent> future = continuationFuture(continuation);
      if (future != null) {
        if (cause == null) {
          future.complete(result);
        } else {
          future.completeExceptionally(cause);
        }
      }
    }));
  }

  @Subscribe(order = PostOrder.CUSTOM, priority = Short.MAX_VALUE, async = false)
  public void captureKickResult(KickedFromServerEvent event) {
    this.initialKickResults.put(event, event.getResult());
  }

  @Subscribe(order = PostOrder.CUSTOM, priority = Short.MIN_VALUE)
  public EventTask onKickedFromServer(KickedFromServerEvent event) {
    KickedFromServerEvent.ServerKickResult initialResult = this.initialKickResults.remove(event);
    if (initialResult == null || initialResult != event.getResult()) {
      // A plugin's explicit disconnect or redirect takes precedence over automatic reconnection.
      return null;
    }
    try {
      Function<KickedFromServerEvent, Boolean> callback = this.plugin.getKickCallback(event.getPlayer());
      if (callback != null && Boolean.TRUE.equals(callback.apply(event))) {
        // Completing normally would execute Velocity's kick result and disconnect the limbo player.
        // Cancellation skips that result while releasing every dependent future and its player references.
        return EventTask.withContinuation(continuation -> {
          CompletableFuture<KickedFromServerEvent> future = continuationFuture(continuation);
          if (future != null) {
            future.cancel(false);
          }
        });
      }
    } catch (Throwable throwable) {
      LimboAPI.getLogger().error("Failed to handle KickCallback, ignoring its result", throwable);
    }

    return null;
  }

  public CompletableFuture<GameProfileRequestEvent> fireProfileRequestEvent(GameProfileRequestEvent event) {
    this.proceededEvents.add(event);
    try {
      CompletableFuture<GameProfileRequestEvent> future = this.eventManager.fire(event);
      future.whenComplete((result, cause) -> this.proceededEvents.remove(event));
      return future;
    } catch (Throwable e) {
      this.proceededEvents.remove(event);
      throw e;
    }
  }

  private static <E> CompletableFuture<E> continuationFuture(Continuation continuation) {
    try {
      return (CompletableFuture<E>) FUTURE_FIELD.invoke(continuation);
    } catch (Throwable e) {
      throw new ReflectionException(e);
    }
  }

  @SuppressWarnings("rawtypes")
  public void reloadHandlers() throws IllegalAccessException {
    Lock writeLock = ((ReadWriteLock) HANDLERS_LOCK_FIELD.get(this.eventManager)).writeLock();
    writeLock.lock();
    try {
      ListMultimap<Class<?>, ?> handlersMap = (ListMultimap<Class<?>, ?>) HANDLERS_BY_TYPE_FIELD.get(this.eventManager);
      List allHandlers = new ArrayList<>(handlersMap.get(GameProfileRequestEvent.class));
      Object oldRegistrations = this.handlerRegistrations;
      for (int i = 0; i < Array.getLength(oldRegistrations); ++i) {
        Object handler = Array.get(oldRegistrations, i);
        if (!allHandlers.contains(handler)) {
          allHandlers.add(handler);
        }
      }

      List preEvents = new ArrayList<>();
      List newHandlers = new ArrayList<>();
      for (Object handler : allHandlers) {
        PluginContainer pluginContainer = (PluginContainer) PLUGIN_FIELD.invoke(handler);
        String id = pluginContainer.getDescription().getId();
        if (Settings.IMP.MAIN.PRE_LIMBO_PROFILE_REQUEST_PLUGINS.contains(id)) {
          LimboAPI.getLogger().info("Hooking all GameProfileRequestEvent events from {} ", id);
          preEvents.add(handler);
        } else {
          newHandlers.add(handler);
        }
      }

      preEvents.sort(HANDLER_COMPARATOR);
      Object registrations = Array.newInstance(HANDLER_REGISTRATION_CLASS, preEvents.size());
      for (int i = 0; i < preEvents.size(); ++i) {
        Array.set(registrations, i, preEvents.get(i));
      }

      handlersMap.replaceValues(GameProfileRequestEvent.class, newHandlers);
      this.handlerRegistrations = registrations;
      ((Cache<Class<?>, ?>) HANDLERS_CACHE_FIELD.get(this.eventManager)).invalidate(GameProfileRequestEvent.class);
    } catch (Throwable e) {
      throw new ReflectionException(e);
    } finally {
      writeLock.unlock();
    }
  }

  static {
    try {
      HANDLERS_BY_TYPE_FIELD = VelocityEventManager.class.getDeclaredField("handlersByType");
      HANDLERS_BY_TYPE_FIELD.setAccessible(true);
      HANDLERS_CACHE_FIELD = VelocityEventManager.class.getDeclaredField("handlersCache");
      HANDLERS_CACHE_FIELD.setAccessible(true);
      HANDLERS_LOCK_FIELD = VelocityEventManager.class.getDeclaredField("lock");
      HANDLERS_LOCK_FIELD.setAccessible(true);
      Field comparatorField = VelocityEventManager.class.getDeclaredField("HANDLER_COMPARATOR");
      comparatorField.setAccessible(true);
      HANDLER_COMPARATOR = (Comparator<Object>) comparatorField.get(null);

      HANDLER_REGISTRATION_CLASS = Class.forName("com.velocitypowered.proxy.event.VelocityEventManager$HandlerRegistration");
      PLUGIN_FIELD = MethodHandles.privateLookupIn(HANDLER_REGISTRATION_CLASS, MethodHandles.lookup())
          .findGetter(HANDLER_REGISTRATION_CLASS, "plugin", PluginContainer.class);

      Class<?> continuationTaskClass = Class.forName("com.velocitypowered.proxy.event.VelocityEventManager$ContinuationTask");
      FUTURE_FIELD = MethodHandles.privateLookupIn(continuationTaskClass, MethodHandles.lookup())
          .findGetter(continuationTaskClass, "future", CompletableFuture.class);

      // The desired 5-argument fire method is private, and its 5th argument is the array of the private class,
      // so we can't pass it into the Class#getDeclaredMethod(Class...) method.
      Method fireMethod = Arrays.stream(VelocityEventManager.class.getDeclaredMethods())
          .filter(method -> method.getName().equals("fire") && method.getParameterCount() == 5)
          .findFirst()
          .orElseThrow();
      fireMethod.setAccessible(true);
      FIRE_METHOD = MethodHandles.privateLookupIn(VelocityEventManager.class, MethodHandles.lookup())
          .findVirtual(VelocityEventManager.class, "fire", MethodType.methodType(
              void.class,
              CompletableFuture.class,
              Object.class,
              int.class,
              boolean.class,
              Array.newInstance(HANDLER_REGISTRATION_CLASS, 0).getClass()
          ));
    } catch (NoSuchFieldException | ClassNotFoundException | IllegalAccessException | NoSuchMethodException e) {
      throw new ReflectionException(e);
    }
  }
}
