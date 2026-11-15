package cn.mythicland.mythicfallback;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class AdmissionQueue {
    private final ProxyServer proxy;
    private final Object plugin;
    private final Map<Player, Waiting> queue = new LinkedHashMap<>();
    private volatile FallbackConfig.Admission config;
    private ScheduledTask releaseTask;
    private long nextReleaseNanos = System.nanoTime();
    private boolean closed;

    AdmissionQueue(ProxyServer proxy, Object plugin, FallbackConfig.Admission config) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.updateConfig(config);
    }

    synchronized void updateConfig(FallbackConfig.Admission config) {
        for (String name : config.servers()) {
            if (this.proxy.getServer(name).isEmpty()) {
                throw new IllegalArgumentException("Unknown admission.servers entry: " + name);
            }
        }
        this.config = config;
    }

    // Run immediately before MythicLogin's LATE authentication and the final reconnect checks.
    @Subscribe(priority = -16383)
    public EventTask onPreConnect(ServerPreConnectEvent event) {
        RegisteredServer target = event.getResult().getServer().orElse(null);
        if (target == null || !this.config.limits(target.getServerInfo().getName())) return null;
        ServerConnection current = event.getPlayer().getCurrentServer().orElse(null);
        return EventTask.withContinuation(continuation -> this.enqueue(new Waiting(event, current, continuation)));
    }

    private void enqueue(Waiting waiting) {
        synchronized (this) {
            Player player = waiting.event().getPlayer();
            if (!this.closed && waiting.isCurrent() && !this.queue.containsKey(player)) {
                this.queue.put(player, waiting);
                if (this.releaseTask == null) this.scheduleRelease();
                return;
            }
        }
        waiting.deny();
    }

    private void scheduleRelease() {
        long delay = Math.max(0, this.nextReleaseNanos - System.nanoTime());
        this.releaseTask = this.proxy.getScheduler().buildTask(this.plugin, this::release)
                .delay(delay, TimeUnit.NANOSECONDS)
                .schedule();
    }

    private void release() {
        List<Waiting> batch = new ArrayList<>();
        List<Waiting> cancelled = new ArrayList<>();
        synchronized (this) {
            this.releaseTask = null;
            if (this.closed) return;
            var waiting = this.queue.values().iterator();
            int paced = 0;
            while (waiting.hasNext()) {
                Waiting next = waiting.next();
                boolean limited = this.config.limits(next.target().getServerInfo().getName());
                if (limited && paced >= this.config.batchSize()) break;
                waiting.remove();
                if (!next.isCurrent()) {
                    cancelled.add(next);
                    continue;
                }
                if (limited) paced++;
                batch.add(next);
            }
            if (paced > 0) {
                this.nextReleaseNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(this.config.intervalMillis());
            }
            if (!this.queue.isEmpty()) this.scheduleRelease();
        }
        cancelled.forEach(Waiting::deny);
        for (Waiting waiting : batch) {
            if (waiting.isCurrent()) waiting.continuation().resume();
            else waiting.deny();
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        this.cancel(event.getPlayer());
    }

    @Subscribe
    public void onConnected(ServerConnectedEvent event) {
        this.cancel(event.getPlayer());
    }

    private void cancel(Player player) {
        Waiting waiting;
        synchronized (this) {
            waiting = this.queue.remove(player);
        }
        if (waiting != null) waiting.deny();
    }

    void close() {
        List<Waiting> waiting;
        synchronized (this) {
            this.closed = true;
            if (this.releaseTask != null) this.releaseTask.cancel();
            waiting = List.copyOf(this.queue.values());
            this.queue.clear();
        }
        waiting.forEach(Waiting::deny);
    }

    private record Waiting(ServerPreConnectEvent event, ServerConnection current, Continuation continuation) {
        private RegisteredServer target() {
            return this.event.getResult().getServer().orElseThrow();
        }

        private boolean isCurrent() {
            Player player = this.event.getPlayer();
            return player.isActive() && player.getCurrentServer().orElse(null) == this.current;
        }

        private void deny() {
            this.event.setResult(ServerPreConnectEvent.ServerResult.denied());
            this.continuation.resume();
        }
    }
}
