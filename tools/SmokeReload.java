package mcexp.smoke;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

/** Disposable test command for the existing public LimboAPI.reload() entry point. */
public final class SmokeReload implements SimpleCommand {
  private final ProxyServer server;

  @Inject
  public SmokeReload(ProxyServer server) {
    this.server = server;
  }

  @Subscribe
  public void initialize(ProxyInitializeEvent event) {
    this.server.getCommandManager().register("smoke-api-reload", this);
  }

  @Override
  public void execute(Invocation invocation) {
    try {
      Object plugin = this.server.getPluginManager().getPlugin("limboapi")
          .flatMap(container -> container.getInstance()).orElseThrow();
      plugin.getClass().getMethod("reload").invoke(plugin);
      invocation.source().sendMessage(Component.text("SMOKE LimboAPI reload complete"));
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("Smoke API reload failed", exception);
    }
  }
}
