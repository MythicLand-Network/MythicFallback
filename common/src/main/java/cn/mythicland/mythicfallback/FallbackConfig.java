package cn.mythicland.mythicfallback;

import cn.mythicland.framework.config.YamlConfigFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public record FallbackConfig(
        List<String> fallbackServers,
        List<String> excludedServers,
        Reconnect reconnect,
        Admission admission
) {
    private static final int DEFAULT_ADMISSION_BATCH_SIZE = 1;
    private static final int DEFAULT_ADMISSION_INTERVAL_MILLIS = 350;

    public FallbackConfig(List<String> fallbackServers, List<String> excludedServers, Reconnect reconnect) {
        this(
                fallbackServers,
                excludedServers,
                reconnect,
                new Admission(List.of(), DEFAULT_ADMISSION_BATCH_SIZE, DEFAULT_ADMISSION_INTERVAL_MILLIS)
        );
    }

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
        return new FallbackConfig(
                config.stringList("fallback-servers"),
                config.stringList("excluded-servers"),
                new Reconnect(
                        config.booleanValue("reconnect.enabled", true),
                        config.intValue("reconnect.check-seconds", 5), config.intValue("reconnect.wait-seconds", 10)
                ),
                new Admission(
                        config.booleanValue("admission.enabled", false) ? config.stringList("admission.servers") : List.of(),
                        config.intValue("admission.batch-size", DEFAULT_ADMISSION_BATCH_SIZE),
                        config.intValue("admission.interval-millis", DEFAULT_ADMISSION_INTERVAL_MILLIS)
                )
        );
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

    public record Admission(List<String> servers, int batchSize, int intervalMillis) {
        public Admission {
            servers = List.copyOf(servers);
            if (batchSize < 1) throw new IllegalArgumentException("admission.batch-size must be positive");
            if (intervalMillis < 1) throw new IllegalArgumentException("admission.interval-millis must be positive");
        }

        public boolean limits(String server) {
            return this.servers.stream().anyMatch(server::equalsIgnoreCase);
        }
    }
}
