package cn.mythicland.mythicfallback;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconnectServiceTest {
    @TempDir Path directory;
    private final ProxyServer proxy = mock(ProxyServer.class);
    private final Scheduler scheduler = mock(Scheduler.class);
    private final Player player = mock(Player.class);
    private final RegisteredServer game = server("ThePit", 25568);
    private final RegisteredServer lobby = server("Lobby", 25567);
    private final RegisteredServer login = server("Login", 25566);
    private final List<Runnable> scheduled = new ArrayList<>();
    private final ConnectionRequestBuilder connection = mock(ConnectionRequestBuilder.class);
    private final FallbackConfig config = new FallbackConfig(List.of("Lobby", "Login"), List.of("Login"),
            new FallbackConfig.Reconnect(true, 5, 10));
    private final ReconnectService service = new ReconnectService(this.proxy, this, this.config);
    private ServerConnection current;

    @BeforeEach
    void setUp() {
        this.service.updateMessages(ReconnectMessages.load(this.directory, this.getClass().getClassLoader()));
        when(this.player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(this.player.isActive()).thenReturn(true);
        when(this.player.getCurrentServer()).thenAnswer(call -> Optional.ofNullable(this.current));
        when(this.player.createConnectionRequest(this.game)).thenReturn(this.connection);
        when(this.proxy.getScheduler()).thenReturn(this.scheduler);
        when(this.scheduler.buildTask(eq(this), any(Runnable.class))).thenAnswer(call -> {
            this.scheduled.add(call.getArgument(1));
            Scheduler.TaskBuilder builder = mock(Scheduler.TaskBuilder.class, RETURNS_SELF);
            when(builder.schedule()).thenReturn(mock(ScheduledTask.class));
            return builder;
        });
        when(this.game.ping()).thenAnswer(call -> CompletableFuture.completedFuture(mock(ServerPing.class)));
        when(this.connection.connect()).thenAnswer(call -> {
            ServerPreConnectEvent event = new ServerPreConnectEvent(this.player, this.game);
            this.service.onPreConnect(event);
            this.service.onPreConnectResult(event);
            assertTrue(event.getResult().isAllowed());
            return CompletableFuture.completedFuture(result(ConnectionRequestBuilder.Status.SUCCESS));
        });
    }

    @Test
    void commandShowsUsageAndCompletesArgumentsForAdministrators() {
        CommandSource admin = mock(CommandSource.class);
        when(admin.hasPermission("mythicfallback.admin")).thenReturn(true);
        SimpleCommand.Invocation invocation = mock(SimpleCommand.Invocation.class);
        when(invocation.source()).thenReturn(admin);
        when(invocation.alias()).thenReturn("mfb");
        ReconnectCommand command = new ReconnectCommand(this.proxy, this.service, () -> {});
        when(invocation.arguments()).thenReturn(new String[0]);
        command.execute(invocation);
        verify(admin).sendMessage(Component.text("用法: /mfb send player <玩家名> <目标服务器>", NamedTextColor.RED));
        verify(admin).sendMessage(Component.text("用法: /mfb send server <来源服务器> <目标服务器>", NamedTextColor.RED));
        verify(admin).sendMessage(Component.text("用法: /mfb reload", NamedTextColor.RED));
        assertEquals(List.of("reload", "send"), command.suggest(invocation));
        when(invocation.arguments()).thenReturn(new String[]{"send", ""});
        assertEquals(List.of("player", "server"), command.suggest(invocation));
        when(this.player.getUsername()).thenReturn("Alice");
        when(this.proxy.getAllPlayers()).thenReturn(List.of(this.player));
        when(this.proxy.getAllServers()).thenReturn(List.of(this.game, this.lobby));
        when(invocation.arguments()).thenReturn(new String[]{"send", "player", "al"});
        assertEquals(List.of("Alice"), command.suggest(invocation));
        when(invocation.arguments()).thenReturn(new String[]{"send", "server", "l"});
        assertEquals(List.of("Lobby"), command.suggest(invocation));
        when(invocation.arguments()).thenReturn(new String[]{"send", "player", "Alice", "th"});
        assertEquals(List.of("ThePit"), command.suggest(invocation));
        when(admin.hasPermission("mythicfallback.admin")).thenReturn(false);
        assertTrue(command.suggest(invocation).isEmpty());
    }

    @Test
    void changingProbeIntervalReschedulesPollingAndDisablingStopsIt() {
        this.service.start();
        assertEquals(1, this.scheduled.size());
        this.service.updateConfig(new FallbackConfig(List.of("Lobby", "Login"), List.of("Login"),
                new FallbackConfig.Reconnect(true, 9, 10)));
        assertEquals(2, this.scheduled.size());
        this.service.updateConfig(new FallbackConfig(List.of("Lobby", "Login"), List.of("Login"),
                new FallbackConfig.Reconnect(false, 9, 10)));
        assertEquals(2, this.scheduled.size());
        this.current = this.connectionTo(this.game);
        this.service.shutdown(this.player, this.game);
        this.service.fallback(this.player, this.lobby);
        this.service.poll();
        verify(this.game, never()).ping();
    }

    @Test
    void reloadedSettingsApplyToNewRequestsWithoutCancellingThePendingOne() {
        ReconnectMessages original = ReconnectMessages.load(this.directory, this.getClass().getClassLoader());
        this.service.updateMessages(new ReconnectMessages(List.of("{seconds}", "{accept} {reject}"),
                original.acceptText(), original.acceptHover(), original.rejectText(), original.rejectHover(),
                original.connecting(), original.connected(), original.requestRejected(), original.reconnectRejected()));
        this.current = this.connectionTo(this.lobby);
        assertTrue(this.service.send(this.player, this.game));
        String token = this.button("reject").clickEvent().value().split(" ")[2];
        this.service.updateConfig(new FallbackConfig(List.of("Lobby", "Login"), List.of("Login"),
                new FallbackConfig.Reconnect(true, 5, 25)));
        assertTrue(this.service.reject(this.player, token));
        assertTrue(this.service.send(this.player, this.game));
        ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
        verify(this.player, atLeastOnce()).sendMessage(messages.capture());
        String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(messages.getValue());
        assertTrue(text.contains("25"));
    }

    @Test
    void adminCanSendToPlayerOrWholeServerIncludingLoginServer() {
        this.current = this.connectionTo(this.lobby);
        when(this.proxy.getServer("ThePit")).thenReturn(Optional.of(this.game));
        when(this.proxy.getServer("Lobby")).thenReturn(Optional.of(this.lobby));
        when(this.proxy.getPlayer("Alice")).thenReturn(Optional.of(this.player));
        when(this.lobby.getPlayersConnected()).thenReturn(List.of(this.player));
        CommandSource admin = mock(CommandSource.class);
        SimpleCommand.Invocation invocation = mock(SimpleCommand.Invocation.class);
        when(invocation.source()).thenReturn(admin);
        when(invocation.arguments()).thenReturn(new String[]{"send", "player", "Alice", "ThePit"});
        ReconnectCommand command = new ReconnectCommand(this.proxy, this.service, () -> {});
        command.execute(invocation);
        verify(this.player, never()).sendMessage(any(Component.class));
        when(admin.hasPermission("mythicfallback.admin")).thenReturn(true);
        command.execute(invocation);
        String token = this.button("reject").clickEvent().value().split(" ")[2];
        assertTrue(this.service.reject(this.player, token));
        when(invocation.arguments()).thenReturn(new String[]{"send", "server", "Lobby", "ThePit"});
        command.execute(invocation);
        this.scheduled.getLast().run();
        verify(this.player).createConnectionRequest(this.game);
        this.current = this.connectionTo(this.login);
        assertTrue(this.service.send(this.player, this.game));
    }

    @Test
    void manualAcceptRespectsAuthenticationRedirect() {
        this.current = this.connectionTo(this.lobby);
        assertTrue(this.service.send(this.player, this.game));
        String token = this.button("accept").clickEvent().value().split(" ")[2];
        doAnswer(call -> {
            ServerPreConnectEvent event = new ServerPreConnectEvent(this.player, this.game);
            this.service.onPreConnect(event);
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(this.login));
            this.service.onPreConnectResult(event);
            assertSame(this.login, event.getResult().getServer().orElseThrow());
            return CompletableFuture.completedFuture(result(ConnectionRequestBuilder.Status.SUCCESS));
        }).when(this.connection).connect();
        assertTrue(this.service.accept(this.player, token));
        this.service.poll();
        verify(this.player, times(1)).createConnectionRequest(this.game);
    }

    @Test
    void manualRequestCancelsOnSwitchAndOldButtonCannotAct() {
        this.current = this.connectionTo(this.lobby);
        assertTrue(this.service.send(this.player, this.game));
        assertFalse(this.service.send(this.player, this.game));
        String token = this.button("accept").clickEvent().value().split(" ")[2];
        this.service.onPreConnect(new ServerPreConnectEvent(this.player, this.login));
        this.current = this.connectionTo(this.login);
        assertFalse(this.service.accept(this.player, token));
        this.scheduled.getFirst().run();
        verify(this.player, never()).createConnectionRequest(any());
    }

    @Test
    void reloadRequiresPermissionAndKeepsPendingRequest() throws IOException {
        this.arrive();
        this.prompt();
        String oldToken = this.button("reject").clickEvent().value().split(" ")[2];
        Path file = this.directory.resolve("config.yml");
        String original = Files.readString(file);
        Files.writeString(file, original.replace("[同意]", "[返回]")
                .replace("正在连接到服务器...", "正在连接测试服务器")
                .replace("已拒绝传送请求。", "管理员请求已取消")
                .replace("连接失败, 已自动拒绝传送请求。", "回连请求已取消"));
        CommandSource admin = mock(CommandSource.class);
        SimpleCommand.Invocation invocation = mock(SimpleCommand.Invocation.class);
        when(invocation.source()).thenReturn(admin);
        when(invocation.arguments()).thenReturn(new String[]{"reload"});
        ReconnectCommand command = new ReconnectCommand(this.proxy, this.service,
                () -> this.service.updateMessages(ReconnectMessages.load(this.directory, this.getClass().getClassLoader())));
        command.execute(invocation);
        when(admin.hasPermission("mythicfallback.admin")).thenReturn(true);
        command.execute(invocation);
        verify(admin).sendMessage(Component.text("配置已重载。", NamedTextColor.GREEN));
        assertTrue(this.service.reject(this.player, oldToken));
        verify(this.player).sendMessage(Component.text("管理员请求已取消", NamedTextColor.RED));
        this.arrive();
        this.prompt();
        assertEquals(Component.text("[返回]", NamedTextColor.GREEN, TextDecoration.BOLD).content(),
                ((net.kyori.adventure.text.TextComponent) this.button("accept")).content());
        this.scheduled.getLast().run();
        verify(this.player).sendMessage(Component.text("正在连接测试服务器", NamedTextColor.GREEN));
        assertTrue(this.service.send(this.player, this.game));
        assertTrue(this.service.reject(this.player, this.button("reject").clickEvent().value().split(" ")[2]));
        verify(this.player, times(2)).sendMessage(Component.text("管理员请求已取消", NamedTextColor.RED));
        Files.writeString(file, "request: ['{accept}']\n");
        command.execute(invocation);
        verify(admin).sendMessage(argThat(message -> message.toString().contains("重载失败")));
    }

    @Test
    void sendsClickableRequestAndReturnsAfterCountdownWithoutInput() {
        this.arrive();
        this.prompt();
        verify(this.current).sendPluginMessage(
                eq(com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from(TransferNotification.CHANNEL)),
                org.mockito.ArgumentMatchers.<byte[]>argThat(data -> java.util.Arrays.equals(data, new byte[]{1})));
        ClickEvent click = this.button();
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());
        assertTrue(click.value().startsWith("/mythicfallback reject "));
        verify(this.player, never()).createConnectionRequest(any());
        this.scheduled.getFirst().run();
        verify(this.player).createConnectionRequest(this.game);
        this.service.poll();
        verify(this.player, times(2)).sendMessage(any(Component.class));
        verify(this.player).sendMessage(Component.text("正在连接到服务器...", NamedTextColor.GREEN));
    }

    @Test
    void clickingAcceptConnectsImmediatelyAndCountdownCannotConnectTwice() {
        this.arrive();
        this.prompt();
        String token = this.button("accept").clickEvent().value().split(" ")[2];
        doReturn(new CompletableFuture<ConnectionRequestBuilder.Result>()).when(this.connection).connect();
        SimpleCommand.Invocation invocation = mock(SimpleCommand.Invocation.class);
        when(invocation.source()).thenReturn(this.player);
        when(invocation.arguments()).thenReturn(new String[]{"accept", token});
        ReconnectCommand command = new ReconnectCommand(this.proxy, this.service, () -> {});
        command.execute(invocation);
        command.execute(invocation);
        this.scheduled.getFirst().run();
        verify(this.player, times(1)).createConnectionRequest(this.game);
    }

    @Test
    void successfulRequestNotifiesOnlyAfterConnectingToTheTarget() {
        this.current = this.connectionTo(this.lobby);
        assertTrue(this.service.send(this.player, this.game));
        String token = this.button("accept").clickEvent().value().split(" ")[2];
        CompletableFuture<ConnectionRequestBuilder.Result> pending = new CompletableFuture<>();
        doAnswer(call -> {
            ServerPreConnectEvent event = new ServerPreConnectEvent(this.player, this.game);
            this.service.onPreConnect(event);
            this.service.onPreConnectResult(event);
            return pending;
        }).when(this.connection).connect();
        assertTrue(this.service.accept(this.player, token));
        verify(this.player, never()).sendMessage(Component.text("连接成功!", NamedTextColor.GREEN));
        this.service.onConnected(new ServerConnectedEvent(this.player, this.game, this.lobby));
        pending.complete(result(ConnectionRequestBuilder.Status.SUCCESS));
        verify(this.player).sendMessage(Component.text("连接成功!", NamedTextColor.GREEN));
    }

    @Test
    void acceptButtonsCannotActOnReplacedOrCancelledRequests() {
        this.arrive();
        this.prompt();
        String oldToken = this.button("accept").clickEvent().value().split(" ")[2];
        this.arrive();
        this.prompt();
        assertFalse(this.service.accept(this.player, oldToken));
        String token = this.button("accept").clickEvent().value().split(" ")[2];
        this.service.onPreConnect(new ServerPreConnectEvent(this.player, this.login));
        this.current = this.connectionTo(this.login);
        assertFalse(this.service.accept(this.player, token));
        this.scheduled.forEach(Runnable::run);
        verify(this.player, never()).createConnectionRequest(any());
    }

    @Test
    void requestButtonsHaveIndependentActionsAndHoverText() {
        this.arrive();
        this.prompt();
        Component accept = this.button("accept");
        Component reject = this.button("reject");
        assertEquals(NamedTextColor.GREEN, accept.color());
        assertEquals(NamedTextColor.RED, reject.color());
        assertEquals(TextDecoration.State.TRUE, accept.decoration(TextDecoration.BOLD));
        assertEquals(TextDecoration.State.TRUE, reject.decoration(TextDecoration.BOLD));
        assertEquals(HoverEvent.Action.SHOW_TEXT, reject.hoverEvent().action());
        assertEquals(Component.text("点击拒绝传送请求!", NamedTextColor.RED), reject.hoverEvent().value());
        assertEquals(HoverEvent.Action.SHOW_TEXT, accept.hoverEvent().action());
        assertEquals(ClickEvent.Action.RUN_COMMAND, accept.clickEvent().action());
        assertEquals(accept.clickEvent().value().split(" ")[2], reject.clickEvent().value().split(" ")[2]);
    }

    @Test
    void clickingRejectCancelsCountdownAndOldButtonsCannotCancelNewRequests() {
        this.arrive();
        this.prompt();
        String oldToken = this.button().value().split(" ")[2];
        SimpleCommand.Invocation invocation = mock(SimpleCommand.Invocation.class);
        when(invocation.source()).thenReturn(this.player);
        when(invocation.arguments()).thenReturn(new String[]{"reject", oldToken});
        new ReconnectCommand(this.proxy, this.service, () -> {}).execute(invocation);
        this.scheduled.getFirst().run();
        this.service.poll();
        verify(this.player, never()).createConnectionRequest(any());

        this.arrive();
        this.prompt();
        assertFalse(this.service.reject(this.player, oldToken));
        this.scheduled.getLast().run();
        verify(this.player).createConnectionRequest(this.game);
    }

    @Test
    void manualSwitchCancelsEvenWhenTheSwitchFails() {
        this.arrive();
        this.prompt();
        ServerPreConnectEvent manual = new ServerPreConnectEvent(this.player, this.login);
        this.service.onPreConnect(manual);
        manual.setResult(ServerPreConnectEvent.ServerResult.denied());
        this.service.onPreConnectResult(manual);
        this.scheduled.getFirst().run();
        this.service.poll();
        verify(this.player, never()).createConnectionRequest(any());
    }

    @Test
    void loginAndOrdinaryLobbyArrivalsNeverBecomeReturnCandidates() {
        this.current = this.connectionTo(this.lobby);
        this.service.onPostConnect(new ServerPostConnectEvent(this.player, this.login));
        this.service.poll();
        this.service.shutdown(this.player, this.login);
        this.service.fallback(this.player, this.lobby);
        this.service.onPostConnect(new ServerPostConnectEvent(this.player, this.login));
        this.service.poll();
        this.service.shutdown(this.player, this.game);
        this.service.fallback(this.player, this.login);
        this.current = this.connectionTo(this.login);
        this.service.onPostConnect(new ServerPostConnectEvent(this.player, this.game));
        this.service.poll();
        verify(this.game, never()).ping();
        verify(this.player, never()).createConnectionRequest(any());
    }

    @Test
    void leavingLobbyDeniesAlreadyQueuedAutomaticPreConnect() {
        this.arrive();
        this.prompt();
        CompletableFuture<ConnectionRequestBuilder.Result> pending = new CompletableFuture<>();
        doReturn(pending).when(this.connection).connect();
        this.scheduled.getFirst().run();
        this.service.onPreConnect(new ServerPreConnectEvent(this.player, this.login));
        this.current = this.connectionTo(this.login);
        ServerPreConnectEvent automatic = new ServerPreConnectEvent(this.player, this.game, this.lobby);
        this.service.onPreConnect(automatic);
        this.service.onPreConnectResult(automatic);
        assertFalse(automatic.getResult().isAllowed());
        pending.complete(result(ConnectionRequestBuilder.Status.CONNECTION_CANCELLED));
        this.service.poll();
        verify(this.player, times(1)).createConnectionRequest(any());
    }

    @Test
    void authenticationRedirectIsPreservedAndCancelsFurtherReturns() {
        this.arrive();
        this.prompt();
        doAnswer(call -> {
            ServerPreConnectEvent event = new ServerPreConnectEvent(this.player, this.game);
            this.service.onPreConnect(event);
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(this.login));
            this.service.onPreConnectResult(event);
            assertSame(this.login, event.getResult().getServer().orElseThrow());
            return CompletableFuture.completedFuture(result(ConnectionRequestBuilder.Status.SUCCESS));
        }).when(this.connection).connect();
        this.scheduled.getFirst().run();
        this.service.poll();
        verify(this.player, times(1)).createConnectionRequest(any());
    }

    @Test
    void logoutAndUnloadInvalidatePendingPingsAndCountdowns() {
        this.arrive();
        CompletableFuture<ServerPing> pending = new CompletableFuture<>();
        when(this.game.ping()).thenReturn(pending);
        this.service.poll();
        this.service.onDisconnect(new DisconnectEvent(this.player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        pending.complete(mock(ServerPing.class));
        verify(this.player, never()).sendMessage(any(Component.class));

        when(this.game.ping()).thenAnswer(call -> CompletableFuture.completedFuture(mock(ServerPing.class)));
        this.arrive();
        this.prompt();
        this.service.close();
        this.scheduled.getFirst().run();
        this.service.poll();
        verify(this.player, never()).createConnectionRequest(any());
    }

    @Test
    void temporaryConnectionFailureRetriesWithoutRepeatingThePromptAndRemainsRejectable() {
        this.arrive();
        this.prompt();
        String token = this.button().value().split(" ")[2];
        doReturn(CompletableFuture.failedFuture(new ConnectException())).when(this.connection).connect();
        this.scheduled.getFirst().run();
        assertFalse(this.service.accept(this.player, token));
        this.scheduled.getLast().run();
        verify(this.player, times(2)).createConnectionRequest(this.game);
        verify(this.player, times(2)).sendMessage(Component.text("正在连接到服务器...", NamedTextColor.GREEN));
        SimpleCommand.Invocation invocation = mock(SimpleCommand.Invocation.class);
        when(invocation.source()).thenReturn(this.player);
        when(invocation.arguments()).thenReturn(new String[]{"reject", token});
        new ReconnectCommand(this.proxy, this.service, () -> {}).execute(invocation);
        verify(this.player).sendMessage(Component.text("已拒绝传送请求。", NamedTextColor.RED));
        this.scheduled.getLast().run();
        verify(this.player, times(2)).createConnectionRequest(this.game);
    }

    @Test
    void rejectingDuringAnInFlightAttemptPreventsFurtherRetries() {
        this.arrive();
        this.prompt();
        String token = this.button().value().split(" ")[2];
        CompletableFuture<ConnectionRequestBuilder.Result> pending = new CompletableFuture<>();
        doReturn(pending).when(this.connection).connect();
        this.scheduled.getFirst().run();
        assertTrue(this.service.reject(this.player, token));
        pending.completeExceptionally(new ConnectException());
        this.service.poll();
        verify(this.player).sendMessage(Component.text("已拒绝传送请求。", NamedTextColor.RED));
        verify(this.player, times(1)).createConnectionRequest(this.game);
        assertEquals(1, this.scheduled.size());
    }

    @Test
    void threeFailedAttemptsRejectTheRequest() {
        this.arrive();
        this.prompt();
        String token = this.button().value().split(" ")[2];
        ConnectionRequestBuilder.Result disconnected = result(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED);
        Component reason = Component.text("You are not whitelisted on this server!");
        when(disconnected.getReasonComponent()).thenReturn(Optional.of(reason));
        doReturn(CompletableFuture.completedFuture(disconnected)).when(this.connection).connect();
        this.scheduled.getFirst().run();
        this.scheduled.getLast().run();
        this.scheduled.getLast().run();
        verify(this.player, times(3)).createConnectionRequest(this.game);
        verify(this.player, times(3)).sendMessage(Component.text("正在连接到服务器...", NamedTextColor.GREEN));
        verify(this.connection, times(3)).connect();
        ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
        verify(this.player, atLeastOnce()).sendMessage(messages.capture());
        List<Component> sent = messages.getAllValues();
        assertEquals("velocity.error.cant-connect", ((TranslatableComponent) sent.get(sent.size() - 2)).key());
        assertEquals(Component.text("连接失败, 已自动拒绝传送请求。", NamedTextColor.RED), sent.getLast());
        assertFalse(this.service.reject(this.player, token));
        this.service.poll();
        verify(this.player, times(3)).createConnectionRequest(this.game);
    }

    @Test
    void manualRequestsRetryEvenWhenAutomaticReconnectIsDisabled() {
        ReconnectService disabled = new ReconnectService(this.proxy, this,
                new FallbackConfig(List.of("Lobby", "Login"), List.of("Login"),
                        new FallbackConfig.Reconnect(false, 5, 10)));
        disabled.updateMessages(ReconnectMessages.load(this.directory, this.getClass().getClassLoader()));
        this.current = this.connectionTo(this.lobby);
        assertTrue(disabled.send(this.player, this.game));
        doReturn(CompletableFuture.failedFuture(new ConnectException())).when(this.connection).connect();
        this.scheduled.getFirst().run();
        this.scheduled.getLast().run();
        this.scheduled.getLast().run();
        verify(this.player, times(3)).createConnectionRequest(this.game);
        verify(this.player).sendMessage(Component.text("连接失败, 已自动拒绝传送请求。", NamedTextColor.RED));
    }

    @Test
    void explicitlyAcceptedManualRequestUsesReconnectMessageAfterThreeFailures() {
        this.current = this.connectionTo(this.lobby);
        assertTrue(this.service.send(this.player, this.game));
        String token = this.button("accept").clickEvent().value().split(" ")[2];
        doReturn(CompletableFuture.failedFuture(new ConnectException())).when(this.connection).connect();
        assertTrue(this.service.accept(this.player, token));
        this.scheduled.getLast().run();
        this.scheduled.getLast().run();
        verify(this.player, times(3)).createConnectionRequest(this.game);
        verify(this.player).sendMessage(Component.text("连接失败, 已自动拒绝传送请求。", NamedTextColor.RED));
        verify(this.player, never()).sendMessage(Component.text("已拒绝传送请求。", NamedTextColor.RED));
    }

    @Test
    void cancelledInFlightAttemptDoesNotBlockOrCancelALaterShutdownRequest() {
        this.arrive();
        this.prompt();
        CompletableFuture<ConnectionRequestBuilder.Result> oldAttempt = new CompletableFuture<>();
        doReturn(oldAttempt).when(this.connection).connect();
        this.scheduled.getFirst().run();
        this.service.onPreConnect(new ServerPreConnectEvent(this.player, this.login));
        this.arrive();
        this.prompt();
        assertEquals(2, this.scheduled.size());
        ServerPreConnectEvent stale = new ServerPreConnectEvent(this.player, this.game, this.lobby);
        this.service.onPreConnect(stale);
        this.service.onPreConnectResult(stale);
        assertFalse(stale.getResult().isAllowed());
        oldAttempt.complete(result(ConnectionRequestBuilder.Status.CONNECTION_CANCELLED));
        doReturn(CompletableFuture.completedFuture(result(ConnectionRequestBuilder.Status.SUCCESS)))
                .when(this.connection).connect();
        this.scheduled.getLast().run();
        verify(this.player, times(2)).createConnectionRequest(this.game);
    }

    @Test
    void sameOriginPlayersShareOneProbeAndDisabledReconnectSchedulesNothing() {
        this.arrive();
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        when(other.isActive()).thenReturn(true);
        ServerConnection lobbyConnection = this.connectionTo(this.lobby);
        when(other.getCurrentServer()).thenReturn(Optional.of(lobbyConnection));
        this.service.shutdown(other, this.game);
        this.service.fallback(other, this.lobby);
        this.service.onPostConnect(new ServerPostConnectEvent(other, this.game));
        this.prompt();
        verify(this.game, times(2)).ping();
        verify(other).sendMessage(any(Component.class));
        assertEquals(2, this.scheduled.size());

        ReconnectService disabled = new ReconnectService(this.proxy, this,
                new FallbackConfig(List.of("Lobby", "Login"), List.of("Login"),
                        new FallbackConfig.Reconnect(false, 5, 10)));
        disabled.start();
        disabled.shutdown(this.player, this.game);
        disabled.fallback(this.player, this.lobby);
        disabled.onPostConnect(new ServerPostConnectEvent(this.player, this.game));
        disabled.poll();
        verify(this.game, times(2)).ping();
        assertEquals(2, this.scheduled.size());
    }

    private void arrive() {
        this.current = this.connectionTo(this.game);
        this.service.shutdown(this.player, this.game);
        this.service.fallback(this.player, this.lobby);
        ServerPreConnectEvent fallback = new ServerPreConnectEvent(this.player, this.lobby);
        this.service.onPreConnect(fallback);
        this.service.onPreConnectResult(fallback);
        this.service.onConnected(new ServerConnectedEvent(this.player, this.lobby, this.game));
        this.current = this.connectionTo(this.lobby);
        this.service.onPostConnect(new ServerPostConnectEvent(this.player, this.game));
    }

    private void prompt() {
        this.service.poll();
        this.service.poll();
    }

    private ClickEvent button() {
        return this.button("reject").clickEvent();
    }

    private Component button(String action) {
        ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
        verify(this.player, atLeastOnce()).sendMessage(messages.capture());
        return messages.getValue().children().stream()
                .filter(component -> component.clickEvent() != null
                        && component.clickEvent().value().startsWith("/mythicfallback " + action + " "))
                .findFirst().orElseThrow();
    }

    private ServerConnection connectionTo(RegisteredServer server) {
        ServerConnection connection = mock(ServerConnection.class);
        when(connection.getServer()).thenReturn(server);
        return connection;
    }

    private static ConnectionRequestBuilder.Result result(ConnectionRequestBuilder.Status status) {
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.getStatus()).thenReturn(status);
        return result;
    }

    private static RegisteredServer server(String name, int port) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress("127.0.0.1", port)));
        return server;
    }
}
