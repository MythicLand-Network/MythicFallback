package cn.mythicland.mythicfallback;

import cn.mythicland.framework.config.YamlConfigFile;

import java.nio.file.Path;
import java.util.List;

public record ReconnectMessages(List<String> request, String acceptText, String acceptHover,
                                String rejectText, String rejectHover, String connecting, String connected,
                                String requestRejected, String reconnectRejected) {
    public static ReconnectMessages load(Path directory, ClassLoader classLoader) {
        YamlConfigFile config = YamlConfigFile.load(directory.resolve("config.yml"), classLoader, "velocity/config.yml");
        List<String> lines = config.stringList("messages.request");
        validate(lines);
        return new ReconnectMessages(lines, config.stringValue("messages.accept-text"), config.stringValue("messages.accept-hover"),
                config.stringValue("messages.reject-text"), config.stringValue("messages.reject-hover"),
                config.stringValue("messages.connecting"), config.stringValue("messages.connected"), config.stringValue("messages.request-rejected"),
                config.stringValue("messages.reconnect-rejected"));
    }

    static void validate(List<String> lines) {
        if (lines.stream().noneMatch(line -> line.contains("{accept}"))
                || lines.stream().noneMatch(line -> line.contains("{reject}"))) {
            throw new IllegalArgumentException("request must contain {accept} and {reject}");
        }
    }
}
