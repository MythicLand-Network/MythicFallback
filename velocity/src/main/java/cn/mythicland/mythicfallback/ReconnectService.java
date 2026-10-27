package cn.mythicland.mythicfallback;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.*;
import java.util.concurrent.TimeUnit;

public final class ReconnectService {
    private final ProxyServer proxy;
    private final Object plugin;
    private final Map<UUID, ReturnRequest> requests = new HashMap<>();
    private final Map<Player, List<ReturnRequest>> connecting = new IdentityHashMap<>();
    private final Set<RegisteredServer> probing = new HashSet<>();
    private FallbackConfig config;
    private volatile ReconnectMessages messages;
    private ScheduledTask pollTask;
    private boolean closed;

    public ReconnectService(ProxyServer proxy, Object plugin, FallbackConfig config) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.config = config;
    }

    public void updateMessages(ReconnectMessages messages) {
        this.messages = messages;
    }

    public synchronized void updateConfig(FallbackConfig config) {
        FallbackConfig.Reconnect previous = this.config.reconnect();
        this.config = config;
        if (previous.enabled() != config.reconnect().enabled() || previous.checkSeconds() != config.reconnect().checkSeconds()) {
            if (this.pollTask != null) this.pollTask.cancel();
            this.pollTask = null;
            this.start();
        }
    }

    public synchronized boolean send(Player player, RegisteredServer target) {
        ServerConnection current = player.getCurrentServer().orElse(null);
        if (this.closed || !player.isActive() || current == null || this.request(player) != null) return false;
        if (current.getServer().equals(target)) return false;
        ReturnRequest request = new ReturnRequest(player, target);
        request.manual = true;
        request.waiting = current;
        request.destination = current.getServer();
        this.requests.put(player.getUniqueId(), request);
        this.prompt(request);
        return true;
    }

    public synchronized void start() {
        if (!this.config.reconnect().enabled()) return;
        this.pollTask = this.proxy.getScheduler().buildTask(this.plugin, this::poll)
                .delay(this.config.reconnect().checkSeconds(), TimeUnit.SECONDS)
                .repeat(this.config.reconnect().checkSeconds(), TimeUnit.SECONDS)
                .schedule();
    }

    public synchronized void shutdown(Player player, RegisteredServer origin) {
        this.cancel(player);
        if (this.closed || !this.config.reconnect().enabled() || !player.isActive()) return;
        if (this.config.excludes(origin.getServerInfo().getName())) return;
        this.requests.put(player.getUniqueId(), new ReturnRequest(player, origin));
    }

    public synchronized void fallback(Player player, RegisteredServer target) {
        ReturnRequest request = this.request(player);
        if (request == null) return;
        if (!this.config.isWaitingServer(target.getServerInfo().getName()) || request.waiting != null) {
            this.cancel(player);
            return;
        }
        request.destination = target;
        request.fallbackPending = true;
    }

    @Subscribe(order = PostOrder.FIRST)
    public synchronized void onPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        ReturnRequest attempt = this.connecting.getOrDefault(player, List.of()).stream()
                .filter(request -> request.connectEvent == null && event.getOriginalServer().equals(request.origin))
                .findFirst().orElse(null);
        if (attempt != null) {
            attempt.connectEvent = event;
            if (!this.isWaiting(attempt)) event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }
        ReturnRequest request = this.request(player);
        if (request == null) return;
        if (request.fallbackPending && event.getOriginalServer().equals(request.destination)) {
            request.fallbackPending = false;
            return;
        }
        this.cancel(player);
    }

    @Subscribe(order = PostOrder.LAST)
    public synchronized void onPreConnectResult(ServerPreConnectEvent event) {
        ReturnRequest attempt = this.connecting.getOrDefault(event.getPlayer(), List.of()).stream()
                .filter(request -> request.connectEvent == event)
                .findFirst().orElse(null);
        if (attempt == null) return;
        if (!this.isWaiting(attempt)) event.setResult(ServerPreConnectEvent.ServerResult.denied());
        if (this.request(event.getPlayer()) == attempt
                && event.getResult().getServer().filter(attempt.origin::equals).isEmpty()) {
            this.cancel(event.getPlayer());
        }
    }

    @Subscribe
    public synchronized void onConnected(ServerConnectedEvent event) {
        ReturnRequest request = this.request(event.getPlayer());
        if (request == null) return;
        if (request.connecting && request.connectEvent != null && request.origin.equals(event.getServer())
                && event.getPreviousServer().filter(request.waiting.getServer()::equals).isPresent()) {
            request.player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(this.messages.connected()));
        }
        if (request.waiting != null || !event.getServer().equals(request.destination)) {
            this.cancel(event.getPlayer());
        }
    }

    @Subscribe
    public synchronized void onPostConnect(ServerPostConnectEvent event) {
        ReturnRequest request = this.request(event.getPlayer());
        if (request == null) return;
        ServerConnection current = event.getPlayer().getCurrentServer().orElse(null);
        if (current == null || !current.getServer().equals(request.destination)) {
            this.cancel(event.getPlayer());
            return;
        }
        request.waiting = current;
        request.fallbackPending = false;
    }

    @Subscribe
    public synchronized void onDisconnect(DisconnectEvent event) {
        this.cancel(event.getPlayer());
        this.connecting.remove(event.getPlayer());
    }

    public synchronized boolean accept(Player player, String token) {
        ReturnRequest request = this.request(player);
        if (request == null || !request.token.equals(token) || request.transferTask == null || request.consented) return false;
        if (!this.isWaiting(request)) return false;
        request.transferTask.cancel();
        this.accept(request);
        return true;
    }

    public synchronized boolean reject(Player player, String token) {
        ReturnRequest request = this.request(player);
        if (request == null || !request.token.equals(token)) return false;
        this.cancel(player);
        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(this.messages.requestRejected()));
        return true;
    }

    public synchronized void cancel(Player player) {
        ReturnRequest request = this.request(player);
        if (request == null) return;
        this.requests.remove(player.getUniqueId());
        if (request.transferTask != null) request.transferTask.cancel();
    }

    public synchronized void poll() {
        if (this.closed) return;
        Map<RegisteredServer, List<ReturnRequest>> servers = new HashMap<>();
        for (ReturnRequest request : List.copyOf(this.requests.values())) {
            if (request.waiting == null) continue;
            if (!this.isWaiting(request)) {
                this.cancel(request.player);
                continue;
            }
            if (request.consented || request.transferTask != null || request.connecting) continue;
            servers.computeIfAbsent(request.origin, ignored -> new ArrayList<>()).add(request);
        }
        servers.forEach((server, waiting) -> {
            if (!this.probing.add(server)) return;
            server.ping().orTimeout(this.config.reconnect().checkSeconds(), TimeUnit.SECONDS)
                    .whenComplete((ping, failure) -> this.probed(server, waiting, failure == null));
        });
    }

    private synchronized void probed(RegisteredServer server, List<ReturnRequest> waiting, boolean available) {
        this.probing.remove(server);
        if (this.closed) return;
        for (ReturnRequest request : waiting) {
            if (!this.isWaiting(request) || request.consented || request.transferTask != null || request.connecting) continue;
            if (!available) {
                request.successfulPings = 0;
                continue;
            }
            if (++request.successfulPings < 2) continue;
            this.prompt(request);
        }
    }

    private void prompt(ReturnRequest request) {
        request.player.sendMessage(this.requestMessage(request));
        request.waiting.sendPluginMessage(MinecraftChannelIdentifier.from(TransferNotification.CHANNEL), new byte[]{1});
        request.transferTask = this.proxy.getScheduler().buildTask(this.plugin, () -> this.accept(request))
                .delay(this.config.reconnect().waitSeconds(), TimeUnit.SECONDS)
                .schedule();
    }

    private Component requestMessage(ReturnRequest request) {
        ReconnectMessages messages = this.messages;
        LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();
        Component accept = serializer.deserialize(messages.acceptText())
                .clickEvent(ClickEvent.runCommand("/mythicfallback accept " + request.token))
                .hoverEvent(HoverEvent.showText(serializer.deserialize(messages.acceptHover())));
        Component reject = serializer.deserialize(messages.rejectText())
                .clickEvent(ClickEvent.runCommand("/mythicfallback reject " + request.token))
                .hoverEvent(HoverEvent.showText(serializer.deserialize(messages.rejectHover())));
        var message = Component.text();
        List<String> lines = messages.request();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) message.appendNewline();
            String line = lines.get(i)
                    .replace("{server}", request.origin.getServerInfo().getName())
                    .replace("{seconds}", String.valueOf(this.config.reconnect().waitSeconds()));
            var matcher = java.util.regex.Pattern.compile("\\{accept}|\\{reject}").matcher(line);
            int position = 0;
            while (matcher.find()) {
                message.append(serializer.deserialize(line.substring(position, matcher.start())));
                message.append(matcher.group().equals("{accept}") ? accept : reject);
                position = matcher.end();
            }
            message.append(serializer.deserialize(line.substring(position)));
        }
        return message.build();
    }

    private synchronized void accept(ReturnRequest request) {
        if (request.consented) return;
        request.transferTask = null;
        if (!this.isWaiting(request)) return;
        request.consented = true;
        this.connect(request);
    }

    private void connect(ReturnRequest request) {
        request.attempts++;
        request.connectEvent = null;
        request.connecting = true;
        this.connecting.computeIfAbsent(request.player, ignored -> new ArrayList<>()).add(request);
        request.player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(this.messages.connecting()));
        request.player.createConnectionRequest(request.origin).connect()
                .whenComplete((result, failure) -> this.completed(request, result, failure));
    }

    private synchronized void completed(
            ReturnRequest request,
            ConnectionRequestBuilder.Result result,
            Throwable failure
    ) {
        request.connecting = false;
        List<ReturnRequest> attempts = this.connecting.get(request.player);
        if (attempts != null) {
            attempts.remove(request);
            if (attempts.isEmpty()) this.connecting.remove(request.player);
        }
        if (!this.isWaiting(request)) return;
        if (failure != null || result.getStatus() == ConnectionRequestBuilder.Status.SERVER_DISCONNECTED
                || result.getStatus() == ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS) {
            ComponentLike server = Argument.string("server", request.origin.getServerInfo().getName());
            if (failure != null) {
                request.player.sendMessage(Component.translatable("velocity.error.connecting-server-error", NamedTextColor.RED, server));
            } else if (result.getStatus() == ConnectionRequestBuilder.Status.SERVER_DISCONNECTED) {
                Component reason = result.getReasonComponent()
                        .orElse(Component.translatable("velocity.error.internal-server-connection-error"));
                request.player.sendMessage(Component.translatable("velocity.error.cant-connect", NamedTextColor.RED, server, reason));
            } else {
                request.player.sendMessage(Component.translatable("velocity.error.already-connecting", NamedTextColor.RED));
            }
            if (request.attempts >= 3) {
                this.cancel(request.player);
                request.player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(this.messages.reconnectRejected()));
            } else {
                request.transferTask = this.proxy.getScheduler().buildTask(this.plugin, () -> this.retry(request))
                        .delay(this.config.reconnect().checkSeconds(), TimeUnit.SECONDS).schedule();
            }
            return;
        }
        this.cancel(request.player);
    }

    private synchronized void retry(ReturnRequest request) {
        request.transferTask = null;
        if (this.isWaiting(request) && !request.connecting) this.connect(request);
    }

    private ReturnRequest request(Player player) {
        ReturnRequest request = this.requests.get(player.getUniqueId());
        return request != null && request.player == player ? request : null;
    }

    private boolean isWaiting(ReturnRequest request) {
        return !this.closed && this.request(request.player) == request && request.waiting != null
                && request.player.isActive() && request.player.getCurrentServer().orElse(null) == request.waiting
                && (request.manual || this.config.isWaitingServer(request.waiting.getServer().getServerInfo().getName()));
    }

    public synchronized void close() {
        this.closed = true;
        if (this.pollTask != null) this.pollTask.cancel();
        for (ReturnRequest request : List.copyOf(this.requests.values())) this.cancel(request.player);
        this.probing.clear();
        this.connecting.clear();
    }

    private static final class ReturnRequest {
        private final Player player;
        private final RegisteredServer origin;
        private final String token = UUID.randomUUID().toString();
        private RegisteredServer destination;
        private boolean fallbackPending;
        private boolean manual;
        private ServerConnection waiting;
        private ScheduledTask transferTask;
        private int successfulPings;
        private int attempts;
        private boolean consented;
        private boolean connecting;
        private ServerPreConnectEvent connectEvent;

        private ReturnRequest(Player player, RegisteredServer origin) {
            this.player = player;
            this.origin = origin;
        }
    }
}
