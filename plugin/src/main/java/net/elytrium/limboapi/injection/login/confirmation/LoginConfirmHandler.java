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

import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.LoginAcknowledgedPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import net.elytrium.commons.utils.reflection.ReflectionException;
import net.elytrium.limboapi.LimboAPI;
import net.elytrium.limboapi.Settings;

public class LoginConfirmHandler implements MinecraftSessionHandler {

  private static final boolean BACKPRESSURE_LOG =
      Boolean.getBoolean("velocity.log-server-backpressure");

  private static final MethodHandle TEARDOWN_METHOD;
  private static final int MAX_QUEUED_PACKETS = 1024;

  private final LimboAPI plugin;
  private final CompletableFuture<Object> confirmation = new CompletableFuture<>();
  private final Queue<MinecraftPacket> queuedPackets = new ArrayDeque<>();
  private final MinecraftConnection connection;
  private ConnectedPlayer player;
  private long queuedBytes;
  private boolean disconnected;
  private boolean rejected;

  public LoginConfirmHandler(LimboAPI plugin, MinecraftConnection connection) {
    this.plugin = plugin;
    this.connection = connection;
  }

  public void setPlayer(ConnectedPlayer player) {
    this.player = player;
  }

  public boolean isDone() {
    return this.confirmation.isDone();
  }

  public CompletableFuture<Void> thenRun(Runnable runnable) {
    return this.confirmation.thenRun(runnable);
  }

  public void waitForConfirmation(Runnable runnable) {
    this.thenRun(() -> {
      try {
        runnable.run();
      } catch (Throwable throwable) {
        LimboAPI.getLogger().error("Failed to confirm transition for " + this.player, throwable);
        this.releaseQueuedPackets();
        this.connection.close(true);
        return;
      }

      try {
        ChannelHandlerContext ctx = this.connection.getChannel().pipeline().context(this.connection);
        MinecraftPacket packet;
        while ((packet = this.queuedPackets.poll()) != null) {
          if (this.disconnected || this.connection.isClosed()) {
            ReferenceCountUtil.release(packet);
          } else {
            // MinecraftConnection.channelRead consumes this retained reference, even when handling throws.
            this.connection.channelRead(ctx, packet);
          }
        }
      } catch (Throwable throwable) {
        LimboAPI.getLogger().error("Failed to process packet queue for " + this.player, throwable);
        this.connection.close(true);
      } finally {
        this.releaseQueuedPackets();
      }
    });
  }

  @Override
  public boolean handle(LoginAcknowledgedPacket packet) {
    if (this.disconnected || this.rejected) {
      return true;
    }
    this.plugin.setState(this.connection, StateRegistry.CONFIG);
    this.confirmation.complete(this);
    return true;
  }

  @Override
  public void handleGeneric(MinecraftPacket packet) {
    // As Velocity/LimboAPI can easly skip packets due to random delays, packets should be queued
    if (!this.disconnected && !this.rejected && this.connection.getState() == StateRegistry.CONFIG) {
      int bytes = packet instanceof ByteBufHolder holder ? holder.content().readableBytes() : 0;
      if (this.queuedPackets.size() >= MAX_QUEUED_PACKETS
          || this.queuedBytes + bytes > Settings.IMP.MAIN.MAX_MULTI_GENERIC_PACKET_LENGTH) {
        this.releaseQueuedPackets();
        this.confirmation.cancel(false);
        this.rejected = true;
        this.connection.close(true);
        return;
      }
      this.queuedPackets.add(ReferenceCountUtil.retain(packet));
      this.queuedBytes += bytes;
    }
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    this.connection.close(true);
  }

  @Override
  public void writabilityChanged() {
    if (BACKPRESSURE_LOG) {
      if (this.connection.getChannel().isWritable()) {
        LimboAPI.getLogger().info("{} is writable, will auto-read", this.player);
      } else {
        LimboAPI.getLogger().info("{} is not writable, not auto-reading", this.player);
      }
    }
  }

  @Override
  public void disconnected() {
    if (this.disconnected) {
      this.releaseQueuedPackets();
      return;
    }
    this.disconnected = true;
    this.confirmation.cancel(false);
    try {
      if (this.player != null) {
        if (this.connection.isClosed()) {
          this.plugin.releasePlayer(this.player);
        }
        try {
          TEARDOWN_METHOD.invokeExact(this.player);
        } catch (Throwable e) {
          throw new ReflectionException(e);
        }
      }
    } finally {
      this.releaseQueuedPackets();
    }
  }

  private void releaseQueuedPackets() {
    MinecraftPacket packet;
    while ((packet = this.queuedPackets.poll()) != null) {
      ReferenceCountUtil.release(packet);
    }
    this.queuedBytes = 0;
  }

  static {
    try {
      TEARDOWN_METHOD = MethodHandles.privateLookupIn(ConnectedPlayer.class, MethodHandles.lookup())
          .findVirtual(ConnectedPlayer.class, "teardown", MethodType.methodType(void.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ReflectionException(e);
    }
  }
}
