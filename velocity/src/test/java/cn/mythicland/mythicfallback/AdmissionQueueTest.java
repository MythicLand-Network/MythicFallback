package cn.mythicland.mythicfallback;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdmissionQueueTest {
    @Test
    void releasesTransfersInBatchesAndSkipsDisconnectedOrDuplicateRequests() {
        ProxyServer proxy = mock(ProxyServer.class);
        Scheduler scheduler = mock(Scheduler.class);
        RegisteredServer target = mock(RegisteredServer.class);
        when(target.getServerInfo()).thenReturn(new ServerInfo("ThePit", new InetSocketAddress("localhost", 25568)));
        when(proxy.getServer("ThePit")).thenReturn(Optional.of(target));
        when(proxy.getScheduler()).thenReturn(scheduler);
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        when(scheduler.buildTask(eq(this), any(Runnable.class))).thenAnswer(call -> {
            Runnable task = call.getArgument(1);
            Scheduler.TaskBuilder builder = mock(Scheduler.TaskBuilder.class, RETURNS_SELF);
            when(builder.schedule()).thenAnswer(ignored -> {
                tasks.add(task);
                return mock(ScheduledTask.class);
            });
            return builder;
        });
        AdmissionQueue queue = new AdmissionQueue(
                proxy,
                this,
                new FallbackConfig.Admission(List.of("ThePit"), 2, 1500)
        );
        Player first = waitingPlayer();
        Player disconnected = waitingPlayer();
        Player second = waitingPlayer();
        Player third = waitingPlayer();
        ServerPreConnectEvent denied = new ServerPreConnectEvent(first, target);
        denied.setResult(ServerPreConnectEvent.ServerResult.denied());
        assertNull(queue.onPreConnect(denied));
        Continuation firstRelease = enqueue(queue, new ServerPreConnectEvent(first, target));
        ServerPreConnectEvent duplicate = new ServerPreConnectEvent(first, target);
        verify(enqueue(queue, duplicate)).resume();
        assertFalse(duplicate.getResult().isAllowed());
        ServerPreConnectEvent expired = new ServerPreConnectEvent(disconnected, target);
        Continuation expiredRelease = enqueue(queue, expired);
        Continuation secondRelease = enqueue(queue, new ServerPreConnectEvent(second, target));
        Continuation thirdRelease = enqueue(queue, new ServerPreConnectEvent(third, target));
        when(disconnected.isActive()).thenReturn(false);

        tasks.removeFirst().run();

        verify(firstRelease).resume();
        verify(secondRelease).resume();
        verify(expiredRelease).resume();
        assertFalse(expired.getResult().isAllowed());
        verifyNoInteractions(thirdRelease);
        tasks.removeFirst().run();
        verify(thirdRelease).resume();
        assertTrue(tasks.isEmpty());
        queue.close();
    }

    private static Player waitingPlayer() {
        Player player = mock(Player.class);
        when(player.isActive()).thenReturn(true);
        when(player.getCurrentServer()).thenReturn(Optional.of(mock(ServerConnection.class)));
        return player;
    }

    private static Continuation enqueue(AdmissionQueue queue, ServerPreConnectEvent event) {
        Continuation continuation = mock(Continuation.class);
        queue.onPreConnect(event).execute(continuation);
        return continuation;
    }
}
