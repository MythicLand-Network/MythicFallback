package cn.mythicland.mythicfallback;

import cn.mythicland.framework.config.YamlConfigFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public record FallbackConfig(
        List<String> fallbackServers,
        List<String> excludedServers,
        Reconnect reconnect
) {
    public FallbackConfig {
        fallbackServers = List.copyOf(fallbackServers);
        excludedServers = List.copyOf(excludedServers);
        if (fallbackServers.isEmpty()) throw new IllegalArgumentException("fallback-servers must not be empty");
        long unique = fallbackServers.stream().map(name -> name.toLowerCase(Locale.ROOT)).distinct().count();
        if (unique != fallbackServers.size())
            throw new IllegalArgumentException("fallback-servers contains duplicates");
    }

    public static FallbackConfig load(Path directory, ClassLoader classLoader) {
        YamlConfigFile config =
                YamlConfigFile.load(directory.resolve("config.yml"), classLoader, "velocity/config.yml");
        return new FallbackConfig(config.stringList("fallback-servers"), config.stringList("excluded-servers"),
                new Reconnect(config.booleanValue("reconnect.enabled", true),
                        config.intValue("reconnect.check-seconds", 5), config.intValue("reconnect.wait-seconds", 10)));
    }

    public boolean excludes(String server) {
        return this.excludedServers.stream().anyMatch(server::equalsIgnoreCase);
    }

    public boolean isWaitingServer(String server) {
        return !this.excludes(server) && this.fallbackServers.stream().anyMatch(server::equalsIgnoreCase);
    }

    public record Reconnect(boolean enabled, int checkSeconds, int waitSeconds) {
        public Reconnect {
            if (checkSeconds < 1) throw new IllegalArgumentException("reconnect.check-seconds must be positive");
            if (waitSeconds < 1) throw new IllegalArgumentException("reconnect.wait-seconds must be positive");
        }
    }
}
