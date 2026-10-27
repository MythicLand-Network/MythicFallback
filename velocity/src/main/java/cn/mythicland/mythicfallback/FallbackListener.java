package cn.mythicland.mythicfallback;

import cn.mythicland.framework.proxy.ServerShutdownNotice;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class FallbackListener {
    public static final MinecraftChannelIdentifier SHUTDOWN_CHANNEL =
            MinecraftChannelIdentifier.from(ServerShutdownNotice.CHANNEL);

    private final ReconnectService reconnect;
    private volatile Settings settings;
    private final Map<UUID, FallbackPlan> nextServers = new ConcurrentHashMap<>();
    private final Map<UUID, ShutdownConfirmation> shutdowns = new ConcurrentHashMap<>();

    public FallbackListener(ProxyServer proxy, FallbackConfig config, ReconnectService reconnect) {
        this.reconnect = reconnect;
        this.settings = settings(proxy, config);
    }

    static Settings settings(ProxyServer proxy, FallbackConfig config) {
        List<RegisteredServer> servers = config.fallbackServers().stream()
                .map(name -> proxy.getServer(name).orElseThrow(() ->
                        new IllegalArgumentException("Unknown fallback-servers entry: " + name)))
                .toList();
        for (String name : config.excludedServers()) {
            if (proxy.getServer(name).isEmpty()) {
                throw new IllegalArgumentException("Unknown excluded-servers entry: " + name);
            }
        }
        return new Settings(config, servers);
    }

    void updateSettings(Settings settings) {
        this.settings = settings;
    }

    @Subscribe(order = PostOrder.EARLY)
    public EventTask onKick(KickedFromServerEvent event) {
        if (event.kickedDuringServerConnect()) return null;
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        FallbackPlan next = this.nextServers.get(playerId);
        String source = event.getServer().getServerInfo().getName();
        Component reason = event.getServerKickReason().orElse(null);
        ServerConnection current = player.getCurrentServer().orElse(null);
        if (next == null) {
            if (this.settings.config().excludes(source)) {
                this.disconnect(event);
                return null;
            }
            if (current == null) return null;
            if (!current.getServer().equals(event.getServer())) return null;
            this.reconnect.cancel(player);
        }
        if (this.isConnectionFailure(reason)) {
            ShutdownConfirmation confirmation = this.shutdowns.get(playerId);
            if (next == null && confirmation != null && confirmation.connection() == current
                    && confirmation.received().getNow(false)) {
                this.reconnect.shutdown(player, event.getServer());
            }
            this.redirect(event, next);
            return null;
        }
        if (current == null || !current.getServer().equals(event.getServer())) {
            this.disconnect(event);
            return null;
        }

        ShutdownConfirmation confirmation = this.confirmation(current);
        return EventTask.resumeWhenComplete(confirmation.received().completeOnTimeout(false, 1, TimeUnit.SECONDS)
                .thenAccept(received -> {
                    this.shutdowns.remove(playerId, confirmation);
                    if (received && player.getCurrentServer().orElse(null) == current) {
                        if (next == null) this.reconnect.shutdown(player, event.getServer());
                        this.redirect(event, next);
                    } else this.disconnect(event);
                }));
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onPluginMessage(PluginMessageEvent event) {
        if (!SHUTDOWN_CHANNEL.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) return;
        Player player = connection.getPlayer();
        if (event.getTarget() != player || player.getCurrentServer().orElse(null) != connection) return;
        if (!ServerShutdownNotice.matches(event.getData())) return;
        this.confirmation(connection).received().complete(true);
    }

    private ShutdownConfirmation confirmation(ServerConnection connection) {
        return this.shutdowns.compute(connection.getPlayer().getUniqueId(), (playerId, previous) ->
                previous != null && previous.connection() == connection ? previous
                        : new ShutdownConfirmation(connection, new CompletableFuture<>()));
    }

    private void redirect(KickedFromServerEvent event, FallbackPlan next) {
        String source = event.getServer().getServerInfo().getName();
        List<RegisteredServer> servers = next == null ? this.settings.servers() : next.servers();
        for (int index = next == null ? 0 : next.index(); index < servers.size(); index++) {
            RegisteredServer target = servers.get(index);
            if (target.getServerInfo().getName().equalsIgnoreCase(source)) continue;
            this.nextServers.put(event.getPlayer().getUniqueId(), new FallbackPlan(servers, index + 1));
            this.reconnect.fallback(event.getPlayer(), target);
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(target, Component.empty()));
            return;
        }
        this.disconnect(event);
    }

    private boolean isConnectionFailure(Component reason) {
        if (reason == null) return true;
        return reason instanceof TranslatableComponent translated
                && translated.key().equals("velocity.error.internal-server-connection-error");
    }

    private void disconnect(KickedFromServerEvent event) {
        this.reconnect.cancel(event.getPlayer());
        this.nextServers.remove(event.getPlayer().getUniqueId());
        event.setResult(KickedFromServerEvent.DisconnectPlayer.create(event.getServerKickReason()
                .orElse(Component.translatable("velocity.error.internal-server-connection-error"))));
    }

    @Subscribe
    public void onConnected(ServerConnectedEvent event) {
        this.nextServers.remove(event.getPlayer().getUniqueId());
        this.clearShutdown(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        this.nextServers.remove(event.getPlayer().getUniqueId());
        this.clearShutdown(event.getPlayer().getUniqueId());
    }

    public void close() {
        this.shutdowns.keySet().forEach(this::clearShutdown);
        this.nextServers.clear();
    }

    private void clearShutdown(UUID playerId) {
        ShutdownConfirmation confirmation = this.shutdowns.remove(playerId);
        if (confirmation != null) confirmation.received().complete(false);
    }

    private record ShutdownConfirmation(ServerConnection connection, CompletableFuture<Boolean> received) {
    }

    record Settings(FallbackConfig config, List<RegisteredServer> servers) {}

    private record FallbackPlan(List<RegisteredServer> servers, int index) {}
}
