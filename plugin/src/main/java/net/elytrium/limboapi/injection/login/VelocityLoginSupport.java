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

import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.client.AuthSessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.elytrium.commons.utils.reflection.ReflectionException;

/** Bridges the synchronous upstream and asynchronous CTD login contracts without blocking Netty. */
public final class VelocityLoginSupport {

  private static final MethodHandle CAN_REGISTER;
  private static final MethodHandle REGISTER;
  private static final MethodHandle FORWARDING_MODE;
  private static final MethodHandle TEARDOWN;
  private static final MethodHandle MARK_LOGIN_EVENT;
  private static final MethodHandle REGISTRY;
  private static final MethodHandle FINALIZE_LOGIN;
  private static final MethodHandle AUTH_PLAYER;
  private static final MethodHandle LOGIN_COMPLETED;
  private static final MethodHandle FULLY_CONNECTED;
  private static final MethodHandle IS_FULLY_CONNECTED;
  private static final MethodHandle CLUSTER;
  private static final MethodHandle CLUSTER_ADMISSION;

  private VelocityLoginSupport() {
  }

  public static boolean canRegister(VelocityServer server, ConnectedPlayer player) throws Throwable {
    // CTD performs this check atomically during asynchronous registration, after authentication.
    return CAN_REGISTER == null || (boolean) CAN_REGISTER.invokeExact(server, player);
  }

  public static CompletableFuture<Boolean> register(VelocityServer server, ConnectedPlayer player) {
    try {
      Object result = REGISTER.invokeExact(server, player);
      if (result instanceof CompletionStage<?> stage) {
        return stage.thenApply(Boolean.class::cast).toCompletableFuture();
      }
      return CompletableFuture.completedFuture((Boolean) result);
    } catch (Throwable throwable) {
      return CompletableFuture.failedFuture(throwable);
    }
  }

  public static boolean isForwardingDisabled(VelocityConfiguration configuration) throws Throwable {
    // CTD moved the enum into its public API; binding the old return descriptor fails at runtime.
    return "NONE".equals(((Enum<?>) (Object) FORWARDING_MODE.invokeExact(configuration)).name());
  }

  public static void teardown(ConnectedPlayer player) {
    try {
      TEARDOWN.invokeExact(player);
    } catch (Throwable throwable) {
      throw new ReflectionException(throwable);
    }
  }

  public static void markLoginEventFired(ConnectedPlayer player) {
    try {
      if (MARK_LOGIN_EVENT != null) {
        MARK_LOGIN_EVENT.invokeExact(player);
      }
    } catch (Throwable throwable) {
      throw new ReflectionException(throwable);
    }
  }

  public static void finalizeLogin(VelocityServer server, ConnectedPlayer player) {
    try {
      if (FINALIZE_LOGIN != null) {
        Object registry = REGISTRY.invokeExact(server);
        FINALIZE_LOGIN.invokeExact(registry, player);
      }
    } catch (Throwable throwable) {
      throw new ReflectionException(throwable);
    }
  }

  public static void associateAuthPlayer(AuthSessionHandler handler, ConnectedPlayer player) {
    try {
      if (AUTH_PLAYER != null) {
        AUTH_PLAYER.invokeExact(handler, player);
      }
    } catch (Throwable throwable) {
      throw new ReflectionException(throwable);
    }
  }

  public static boolean hasCompletedLogin(ConnectedPlayer player) {
    try {
      return LOGIN_COMPLETED != null && (boolean) LOGIN_COMPLETED.invokeExact(player);
    } catch (Throwable throwable) {
      throw new ReflectionException(throwable);
    }
  }

  /** Honors CTD's cross-proxy duplicate policy before publishing native queue presence. */
  public static boolean acceptLogin(VelocityServer server, ConnectedPlayer player) {
    try {
      if (IS_FULLY_CONNECTED == null || CLUSTER_ADMISSION == null) {
        throw new IllegalStateException("Unsupported CTD cluster admission contract");
      }
      if ((boolean) IS_FULLY_CONNECTED.invokeExact(player)) {
        return true;
      }
      Object cluster = CLUSTER.invokeExact(server);
      if (!(boolean) CLUSTER_ADMISSION.invokeExact(cluster, player)) {
        return false;
      }
      FULLY_CONNECTED.invokeExact(player);
      return true;
    } catch (Throwable throwable) {
      throw new ReflectionException(throwable);
    }
  }

  static {
    try {
      MethodHandles.Lookup lookup = MethodHandles.publicLookup();
      MethodHandle check;
      try {
        check = lookup.findVirtual(VelocityServer.class, "canRegisterConnection",
            MethodType.methodType(boolean.class, ConnectedPlayer.class));
      } catch (NoSuchMethodException ignored) {
        check = null;
      }
      CAN_REGISTER = check;
      REGISTER = lookup.unreflect(VelocityServer.class.getMethod("registerConnection", ConnectedPlayer.class))
          .asType(MethodType.methodType(Object.class, VelocityServer.class, ConnectedPlayer.class));
      FORWARDING_MODE = lookup.unreflect(VelocityConfiguration.class.getMethod("getPlayerInfoForwardingMode"))
          .asType(MethodType.methodType(Object.class, VelocityConfiguration.class));
      TEARDOWN = MethodHandles.privateLookupIn(ConnectedPlayer.class, MethodHandles.lookup())
          .findVirtual(ConnectedPlayer.class, "teardown", MethodType.methodType(void.class));
      MethodHandle mark;
      MethodHandle registry;
      MethodHandle finish;
      MethodHandle authPlayer;
      MethodHandle completed;
      MethodHandle fullyConnected;
      MethodHandle isFullyConnected;
      MethodHandle cluster;
      MethodHandle admission;
      try {
        mark = MethodHandles.privateLookupIn(ConnectedPlayer.class, MethodHandles.lookup())
            .findVirtual(ConnectedPlayer.class, "markLoginEventFired", MethodType.methodType(void.class));
        java.lang.reflect.Method getter = VelocityServer.class.getMethod("getPlayerRegistry");
        registry = lookup.unreflect(getter).asType(MethodType.methodType(Object.class, VelocityServer.class));
        finish = lookup.unreflect(getter.getReturnType().getMethod("finalizeLogin", ConnectedPlayer.class))
            .asType(MethodType.methodType(void.class, Object.class, ConnectedPlayer.class));
        completed = MethodHandles.privateLookupIn(ConnectedPlayer.class, MethodHandles.lookup())
            .findVirtual(ConnectedPlayer.class, "isLoginCompleted", MethodType.methodType(boolean.class));
        fullyConnected = lookup.findVirtual(ConnectedPlayer.class, "fullyConnected", MethodType.methodType(void.class));
        isFullyConnected = lookup.findVirtual(ConnectedPlayer.class, "isFullyConnected", MethodType.methodType(boolean.class));
        java.lang.reflect.Method clusterGetter = VelocityServer.class.getMethod("getClusterPlayerService");
        cluster = lookup.unreflect(clusterGetter).asType(MethodType.methodType(Object.class, VelocityServer.class));
        admission = lookup.unreflect(clusterGetter.getReturnType().getMethod("onPlayerConnect", ConnectedPlayer.class))
            .asType(MethodType.methodType(boolean.class, Object.class, ConnectedPlayer.class));
      } catch (NoSuchMethodException ignored) {
        mark = null;
        registry = null;
        finish = null;
        completed = null;
        fullyConnected = null;
        isFullyConnected = null;
        cluster = null;
        admission = null;
      }
      try {
        authPlayer = MethodHandles.privateLookupIn(AuthSessionHandler.class, MethodHandles.lookup())
            .findSetter(AuthSessionHandler.class, "connectedPlayer", ConnectedPlayer.class);
      } catch (NoSuchFieldException ignored) {
        authPlayer = null;
      }
      MARK_LOGIN_EVENT = mark;
      REGISTRY = registry;
      FINALIZE_LOGIN = finish;
      AUTH_PLAYER = authPlayer;
      LOGIN_COMPLETED = completed;
      FULLY_CONNECTED = fullyConnected;
      IS_FULLY_CONNECTED = isFullyConnected;
      CLUSTER = cluster;
      CLUSTER_ADMISSION = admission;
    } catch (ReflectiveOperationException exception) {
      throw new ReflectionException(exception);
    }
  }
}
