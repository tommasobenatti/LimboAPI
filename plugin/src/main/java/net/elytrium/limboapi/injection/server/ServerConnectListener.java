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

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import net.elytrium.limboapi.LimboAPI;
import net.elytrium.limboapi.server.LimboPlayerImpl;
import net.elytrium.limboapi.server.LimboSessionHandlerImpl;

/**
 * Lets CTD and other authenticated connection initiators leave a limbo using their original
 * request.
 */
public final class ServerConnectListener {

  private final LimboAPI plugin;

  public ServerConnectListener(LimboAPI plugin) {
    this.plugin = plugin;
  }

  @Subscribe(order = PostOrder.CUSTOM, priority = Short.MIN_VALUE)
  public EventTask onServerPreConnect(ServerPreConnectEvent event) {
    if (event.getResult().getServer().isEmpty()
        || !(event.getPlayer() instanceof ConnectedPlayer player)
        || !(player.getConnection().getActiveSessionHandler()
            instanceof LimboSessionHandlerImpl session)) {
      return null;
    }
    if (this.plugin.hasLoginQueue(player)
        || !(session.getLimboPlayer() instanceof LimboPlayerImpl limboPlayer)) {
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      return null;
    }
    try {
      return EventTask.resumeWhenComplete(
          limboPlayer
              .prepareForServerConnection()
              .handle(
                  (unused, failure) -> {
                    if (failure != null) {
                      event.setResult(ServerPreConnectEvent.ServerResult.denied());
                    }
                    return null;
                  }));
    } catch (RuntimeException exception) {
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      return null;
    }
  }
}
