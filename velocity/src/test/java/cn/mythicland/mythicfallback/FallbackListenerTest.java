package cn.mythicland.mythicfallback;

import cn.mythicland.framework.proxy.ServerShutdownNotice;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FallbackListenerTest {
    private final ProxyServer proxy = mock(ProxyServer.class);
    private final Player player = mock(Player.class);
    private final RegisteredServer game = server("ThePit", 25568);
    private final RegisteredServer lobby = server("Lobby", 25567);
    private final RegisteredServer login = server("Login", 25566);
    private final ReconnectService reconnect = mock(ReconnectService.class);
    private FallbackListener listener;

    @BeforeEach
    void setUp() {
        when(this.player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(this.proxy.getServer("Lobby")).thenReturn(Optional.of(this.lobby));
        when(this.proxy.getServer("Login")).thenReturn(Optional.of(this.login));
        this.listener = new FallbackListener(this.proxy,
                new FallbackConfig(List.of("Lobby", "Login"), List.of("Login"),
                        new FallbackConfig.Reconnect(true, 5, 10)), this.reconnect);
        this.current(this.game);
    }

    @Test
    void reloadedFallbackOrderDoesNotChangeAnInProgressFallbackPlan() {
        this.handleKick(this.kick(this.game, null));
        FallbackConfig changed = new FallbackConfig(List.of("Login", "Lobby"), List.of("Login"),
                new FallbackConfig.Reconnect(true, 5, 10));
        this.listener.updateSettings(FallbackListener.settings(this.proxy, changed));
        when(this.player.getCurrentServer()).thenReturn(Optional.empty());
        KickedFromServerEvent failed = this.kick(this.lobby, null);
        this.handleKick(failed);
        assertSame(this.login, assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, failed.getResult()).getServer());
    }

    @Test
    void shutdownTriesLobbyThenLoginAndStopsAfterBothFail() {
        this.notice();
        KickedFromServerEvent stopped = this.kick(this.game, Component.text("Custom maintenance message"));
        this.handleKick(stopped);
        verify(this.reconnect).shutdown(this.player, this.game);
        verify(this.reconnect).fallback(this.player, this.lobby);
        assertSame(this.lobby,
                assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, stopped.getResult()).getServer());

        when(this.player.getCurrentServer()).thenReturn(Optional.empty());
        KickedFromServerEvent lobbyFailed = this.kick(this.lobby, null);
        this.handleKick(lobbyFailed);
        assertSame(this.login,
                assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, lobbyFailed.getResult()).getServer());

        KickedFromServerEvent loginFailed = this.kick(this.login, null);
        this.handleKick(loginFailed);
        assertInstanceOf(KickedFromServerEvent.DisconnectPlayer.class, loginFailed.getResult());
    }

    @Test
    void lobbyShutdownGoesDirectlyToLogin() {
        this.current(this.lobby);
        this.notice();
        KickedFromServerEvent event = this.kick(this.lobby, Component.text("Server closed"));
        this.handleKick(event);
        assertSame(this.login,
                assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, event.getResult()).getServer());
    }

    @Test
    void loginPlayersNeverFallBackEvenIfVelocitySelectedAnotherServer() {
        this.current(this.login);
        KickedFromServerEvent event = this.kick(this.login, null);
        event.setResult(KickedFromServerEvent.RedirectPlayer.create(this.lobby));
        this.handleKick(event);
        assertInstanceOf(KickedFromServerEvent.DisconnectPlayer.class, event.getResult());
    }

    @Test
    void ordinaryKicksAndPunishmentsKeepTheirReason() {
        for (String reason : List.of("Banned", "Kicked by an operator", "Server closed", "Server is restarting")) {
            KickedFromServerEvent event = this.kick(this.game, Component.text(reason));
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(this.login));
            this.handleKick(event);
            assertEquals(Component.text(reason),
                    assertInstanceOf(KickedFromServerEvent.DisconnectPlayer.class,
                            event.getResult()).getReasonComponent());
        }
    }

    @Test
    void fallbackTargetRejectingPlayerDoesNotSkipItsPunishment() {
        this.handleKick(this.kick(this.game, null));
        when(this.player.getCurrentServer()).thenReturn(Optional.empty());
        KickedFromServerEvent rejected = this.kick(this.lobby, Component.text("Banned"));
        this.handleKick(rejected);
        assertInstanceOf(KickedFromServerEvent.DisconnectPlayer.class, rejected.getResult());
    }

    @Test
    void connectionFailureAndUnexpectedDisconnectBothFallBack() {
        for (Component reason : new Component[]{null,
                Component.translatable("velocity.error.internal-server-connection-error")}) {
            this.listener.onConnected(new ServerConnectedEvent(this.player, this.game, null));
            KickedFromServerEvent event = this.kick(this.game, reason);
            this.handleKick(event);
            assertSame(this.lobby,
                    assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, event.getResult()).getServer());
        }
        verify(this.reconnect, never()).shutdown(any(), any());
    }

    @Test
    void failedManualTransferKeepsWorkingOriginalServer() {
        KickedFromServerEvent.Notify result = KickedFromServerEvent.Notify.create(Component.text("Unavailable"));
        KickedFromServerEvent event = new KickedFromServerEvent(this.player, this.lobby, null, true, result);
        this.handleKick(event);
        assertSame(result, event.getResult());
    }

    @Test
    void initialConnectionFailureDoesNotChangeInitialRouting() {
        when(this.player.getCurrentServer()).thenReturn(Optional.empty());
        KickedFromServerEvent event = this.kick(this.game, null);
        KickedFromServerEvent.ServerKickResult result = event.getResult();
        this.handleKick(event);
        assertSame(result, event.getResult());
    }

    @Test
    void successfulConnectionAndLogoutClearFallbackProgress() {
        this.handleKick(this.kick(this.game, null));
        this.listener.onConnected(new ServerConnectedEvent(this.player, this.game, null));
        KickedFromServerEvent again = this.kick(this.game, null);
        this.handleKick(again);
        assertSame(this.lobby,
                assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, again.getResult()).getServer());

        this.listener.onDisconnect(new DisconnectEvent(this.player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        when(this.player.getCurrentServer()).thenReturn(Optional.empty());
        KickedFromServerEvent initial = this.kick(this.game, null);
        KickedFromServerEvent.ServerKickResult original = initial.getResult();
        this.handleKick(initial);
        assertSame(original, initial.getResult());
    }

    @Test
    void invalidServerNameFailsAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new FallbackListener(this.proxy,
                new FallbackConfig(List.of("missing"), List.of("Login"), new FallbackConfig.Reconnect(true, 5, 10)),
                this.reconnect));
    }

    @Test
    void notificationCanArriveAfterKickHandlerStarts() {
        KickedFromServerEvent event = this.kick(this.game, Component.text("Maintenance"));
        EventTask task = this.listener.onKick(event);
        assertNotNull(task);
        this.notice();
        await(task);
        assertSame(this.lobby,
                assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, event.getResult()).getServer());
    }

    @Test
    void clientMessagesAndMalformedPayloadsCannotAuthorizeFallback() {
        ServerConnection connection = this.player.getCurrentServer().orElseThrow();
        PluginMessageEvent forged = new PluginMessageEvent(this.player, connection,
                FallbackListener.SHUTDOWN_CHANNEL, ServerShutdownNotice.encode());
        this.listener.onPluginMessage(forged);
        assertFalse(forged.getResult().isAllowed());
        for (byte[] payload : new byte[][]{new byte[0], {2}, {1, 0}}) {
            PluginMessageEvent invalid = new PluginMessageEvent(connection, this.player,
                    FallbackListener.SHUTDOWN_CHANNEL, payload);
            this.listener.onPluginMessage(invalid);
            assertFalse(invalid.getResult().isAllowed());
        }
        KickedFromServerEvent event =
                this.kick(this.game, Component.translatable("multiplayer.disconnect.server_shutdown"));
        this.handleKick(event);
        assertInstanceOf(KickedFromServerEvent.DisconnectPlayer.class, event.getResult());
    }

    @Test
    void shutdownNoticeCannotCrossPlayerOrBackendConnections() {
        this.notice();
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        ServerConnection otherConnection = mock(ServerConnection.class);
        when(otherConnection.getPlayer()).thenReturn(other);
        when(otherConnection.getServer()).thenReturn(this.game);
        when(other.getCurrentServer()).thenReturn(Optional.of(otherConnection));
        KickedFromServerEvent otherKick = new KickedFromServerEvent(other, this.game,
                Component.text("Server closed"), false,
                KickedFromServerEvent.DisconnectPlayer.create(Component.empty()));
        this.handleKick(otherKick);
        assertInstanceOf(KickedFromServerEvent.DisconnectPlayer.class, otherKick.getResult());

        ServerConnection previous = this.player.getCurrentServer().orElseThrow();
        this.current(this.game);
        this.listener.onConnected(new ServerConnectedEvent(this.player, this.game, this.game));
        this.listener.onPluginMessage(new PluginMessageEvent(previous, this.player,
                FallbackListener.SHUTDOWN_CHANNEL, ServerShutdownNotice.encode()));
        KickedFromServerEvent rejoined = this.kick(this.game, Component.text("Server closed"));
        this.handleKick(rejoined);
        assertInstanceOf(KickedFromServerEvent.DisconnectPlayer.class, rejoined.getResult());
    }

    private void notice() {
        PluginMessageEvent event = new PluginMessageEvent(this.player.getCurrentServer().orElseThrow(), this.player,
                FallbackListener.SHUTDOWN_CHANNEL, ServerShutdownNotice.encode());
        this.listener.onPluginMessage(event);
        assertFalse(event.getResult().isAllowed());
    }

    private void handleKick(KickedFromServerEvent event) {
        await(this.listener.onKick(event));
    }

    private static void await(EventTask task) {
        if (task == null) return;
        CompletableFuture<Void> completed = new CompletableFuture<>();
        task.execute(new Continuation() {
            @Override
            public void resume() {
                completed.complete(null);
            }

            @Override
            public void resumeWithException(Throwable exception) {
                completed.completeExceptionally(exception);
            }
        });
        completed.orTimeout(3, TimeUnit.SECONDS).join();
    }

    private KickedFromServerEvent kick(RegisteredServer server, Component reason) {
        return new KickedFromServerEvent(this.player, server, reason, false,
                KickedFromServerEvent.DisconnectPlayer.create(Component.text("Disconnected")));
    }

    private void current(RegisteredServer server) {
        ServerConnection connection = mock(ServerConnection.class);
        when(connection.getServer()).thenReturn(server);
        when(connection.getPlayer()).thenReturn(this.player);
        when(this.player.getCurrentServer()).thenReturn(Optional.of(connection));
    }

    private static RegisteredServer server(String name, int port) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress("127.0.0.1", port)));
        return server;
    }
}
