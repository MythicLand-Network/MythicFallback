package cn.mythicland.mythicfallback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FallbackConfigTest {
    @TempDir
    Path directory;

    @Test
    void loadsDefaultsAndHonorsEditedOrderAndExclusions() throws IOException {
        FallbackConfig defaults = FallbackConfig.load(this.directory, this.getClass().getClassLoader());
        assertEquals(List.of("Lobby", "Login"), defaults.fallbackServers());
        assertTrue(defaults.excludes("login"));
        assertTrue(defaults.reconnect().enabled());
        assertEquals(10, defaults.reconnect().waitSeconds());
        Files.writeString(this.directory.resolve("config.yml"), """
                fallback-servers: [Lobby2, Lobby, Login]
                excluded-servers: [Login, Maintenance]
                reconnect:
                  enabled: false
                  check-seconds: 8
                  wait-seconds: 15
                """);
        FallbackConfig edited = FallbackConfig.load(this.directory, this.getClass().getClassLoader());
        assertEquals(List.of("Lobby2", "Lobby", "Login"), edited.fallbackServers());
        assertTrue(edited.excludes("Maintenance"));
        assertFalse(edited.reconnect().enabled());
        assertEquals(8, edited.reconnect().checkSeconds());
        assertEquals(15, edited.reconnect().waitSeconds());
        assertTrue(edited.isWaitingServer("lobby2"));
        assertFalse(edited.isWaitingServer("Login"));
    }

    @Test
    void rejectsEmptyOrDuplicateDestinations() {
        assertThrows(IllegalArgumentException.class,
                () -> new FallbackConfig(List.of(), List.of(), new FallbackConfig.Reconnect(true, 5, 10)));
        assertThrows(IllegalArgumentException.class,
                () -> new FallbackConfig(List.of("Lobby", "lobby"), List.of(),
                        new FallbackConfig.Reconnect(true, 5, 10)));
        assertThrows(IllegalArgumentException.class, () -> new FallbackConfig.Reconnect(true, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new FallbackConfig.Reconnect(true, 5, 0));
    }
}
