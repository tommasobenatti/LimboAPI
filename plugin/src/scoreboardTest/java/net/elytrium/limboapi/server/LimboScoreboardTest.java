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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.TextHolder;
import com.velocitypowered.api.event.player.configuration.PlayerEnterConfigurationEvent;
import com.velocitypowered.api.event.player.configuration.PlayerFinishConfigurationEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.scoreboard.DisplaySlot;
import com.velocitypowered.api.scoreboard.ScoreboardManager;
import com.velocitypowered.proxy.ScoreboardEventSource;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ClientPlaySessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.data.RawTextHolderProvider;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.scoreboard.DisplayObjectivePacket;
import com.velocitypowered.proxy.protocol.packet.scoreboard.ObjectivePacket;
import com.velocitypowered.proxy.scoreboard.VelocityScoreboard;
import com.velocitypowered.proxy.scoreboard.VelocityScoreboardManager;
import com.velocitypowered.proxy.scoreboard.downstream.DownstreamScoreboard;
import com.velocitypowered.scoreboardapi.ServerSwitchManager;
import com.velocitypowered.scoreboardapi.VelocityScoreboardAPI;
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
import net.elytrium.limboapi.api.player.LimboPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LimboScoreboardTest {
  private final EmbeddedChannel channel = new EmbeddedChannel();
  private final MinecraftConnection connection = mock(MinecraftConnection.class);
  private final ConnectedPlayer player = mock(ConnectedPlayer.class);
  private final LimboAPI plugin = mock(LimboAPI.class);
  private final VelocityServer proxy =
      mock(VelocityServer.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
  private final AtomicReference<StateRegistry> state = new AtomicReference<>(StateRegistry.PLAY);
  private final AtomicReference<MinecraftSessionHandler> active = new AtomicReference<>();
  private final List<Object> packets = new ArrayList<>();
  private final List<Object> events = new ArrayList<>();
  private VelocityScoreboard scoreboard;
  private ServerSwitchManager switches;
  private LimboSessionHandlerImpl session;

  @BeforeAll
  static void initializeTextProvider() {
    new RawTextHolderProvider();
  }

  @BeforeEach
  void setUp() {
    Settings.IMP.MAIN = new Settings.MAIN();
    Settings.IMP.MAIN.LOGGING_ENABLED = false;
    when(this.plugin.getServer()).thenReturn(this.proxy);
    when(this.player.getConnection()).thenReturn(this.connection);
    when(this.player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_21_4);
    when(this.connection.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_21_4);
    when(this.connection.eventLoop()).thenReturn(this.channel.eventLoop());
    when(this.connection.getChannel()).thenReturn(this.channel);
    when(this.connection.getState()).thenAnswer(call -> this.state.get());
    when(this.connection.getActiveSessionHandler()).thenAnswer(call -> this.active.get());
    doAnswer(
            call -> {
              this.state.set(call.getArgument(1));
              this.active.set(call.getArgument(2));
              return null;
            })
        .when(this.plugin)
        .setActiveSessionHandler(any(), any(), any());
    when(this.connection.write(any()))
        .thenAnswer(
            call -> {
              this.packets.add(call.getArgument(0));
              return this.channel.newSucceededFuture();
            });
    ScoreboardEventSource eventSource = mock(ScoreboardEventSource.class);
    DownstreamScoreboard downstream = new DownstreamScoreboard(eventSource, this.player);
    this.scoreboard = new VelocityScoreboard(eventSource, this.player, downstream);
    VelocityScoreboardManager manager = mock(VelocityScoreboardManager.class);
    when(manager.getBackendScoreboard(this.player)).thenReturn(downstream);
    when(manager.getProxyScoreboard(this.player)).thenReturn(this.scoreboard);
    ScoreboardManager.setInstance(manager);
    this.switches = new ServerSwitchManager(mock(VelocityScoreboardAPI.class));
    when(this.proxy.getEventManager().fire(any()))
        .thenAnswer(
            call -> {
              Object event = call.getArgument(0);
              this.events.add(event);
              if (event instanceof PlayerEnterConfigurationEvent enter) {
                this.switches.onConfigStart(enter);
              }
              return CompletableFuture.completedFuture(event);
            });
    LimboImpl limbo = mock(LimboImpl.class);
    when(limbo.getReadTimeout()).thenReturn(30000);
    this.session =
        new LimboSessionHandlerImpl(
            this.plugin,
            limbo,
            this.player,
            mock(LimboSessionHandler.class),
            StateRegistry.PLAY,
            mock(ClientPlaySessionHandler.class),
            null,
            () -> "test");
    this.active.set(this.session);
    LimboPlayer limboPlayer = mock(LimboPlayer.class);
    when(limboPlayer.getScheduledExecutor()).thenReturn(this.channel.eventLoop());
    this.session.onConfig(limboPlayer);
    this.session.onSpawn();
  }

  @AfterEach
  void close() throws Exception {
    if (this.session != null) {
      this.session.release();
    }
    this.channel.finishAndReleaseAll();
    Field singleton = ScoreboardManager.class.getDeclaredField("INSTANCE");
    singleton.setAccessible(true);
    singleton.set(null, null);
  }

  private void completeBackendReturn() {
    AtomicBoolean prepared = new AtomicBoolean();
    this.session.disconnect(() -> this.session.disconnectToConfig(() -> prepared.set(true)));
    this.channel.runPendingTasks();
    this.session.handle(FinishedUpdatePacket.INSTANCE);
    this.channel.runPendingTasks();
    assertTrue(prepared.get());
    // Modern Minecraft discards its previous scoreboard during reconfiguration.
    this.packets.clear();
    this.switches.onConfigFinish(
        new PlayerFinishConfigurationEvent(this.player, mock(VelocityServerConnection.class)));
  }

  @Test
  void nativeFinishRestoresTheExistingSidebarWithoutAToggle() {
    var objective =
        this.scoreboard.registerObjective(
            this.scoreboard
                .objectiveBuilder("kingdoms")
                .title(TextHolder.of("Kingdoms"))
                .displaySlot(DisplaySlot.SIDEBAR));
    this.completeBackendReturn();
    assertSame(objective, this.scoreboard.getObjective(DisplaySlot.SIDEBAR));
    assertTrue(
        this.packets.stream()
            .anyMatch(
                packet ->
                    packet instanceof ObjectivePacket objectivePacket
                        && objectivePacket.getAction()
                            == ObjectivePacket.ObjectiveAction.REGISTER));
    assertTrue(
        this.packets.stream()
            .anyMatch(
                packet ->
                    packet instanceof DisplayObjectivePacket display
                        && display.getPosition() == DisplaySlot.SIDEBAR
                        && display.getObjectiveName().equals("kingdoms")));
  }

  @Test
  void voluntarilyHiddenSidebarRemainsHidden() {
    this.completeBackendReturn();
    assertTrue(this.scoreboard.getObjectives().isEmpty());
    assertFalse(this.packets.stream().anyMatch(DisplayObjectivePacket.class::isInstance));
  }

  @Test
  void exitNotifiesNativeFreezeExactlyOnceBeforeSendingStartUpdate() {
    this.session.disconnect(() -> this.session.disconnectToConfig(() -> {}));
    this.session.disconnectToConfig(() -> {});
    this.channel.runPendingTasks();
    assertEquals(
        1, this.events.stream().filter(PlayerEnterConfigurationEvent.class::isInstance).count());
  }
}
