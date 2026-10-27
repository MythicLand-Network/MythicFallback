package cn.mythicland.mythicfallback;

import cn.mythicland.framework.runtime.FrameworkRuntimeClassLoading;
import cn.mythicland.framework.runtime.RuntimeBootstrap;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@Plugin(id = "mythicfallback", name = "MythicFallback", version = "1.0.0", authors = {"ShanguanLinG"})
public final class MythicFallback {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private RuntimeBootstrap runtime;

    @Inject
    public MythicFallback(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        this.runtime = RuntimeBootstrap.builder(this, "cn.mythicland.mythicfallback.FallbackRuntime")
                .runtimeName("MythicFallback")
                .libraries(this.dataDirectory, "META-INF/mythicfallback-libraries.properties", Set.of("velocity"))
                .loggers(this.logger::info, this.logger::warn)
                .classLoading(FrameworkRuntimeClassLoading.velocity(
                        List.of("cn.mythicland.mythicfallback.", "org.yaml.snakeyaml."),
                        List.of(MythicFallback.class.getName())))
                .build();
        this.runtime.load();
        this.runtime.enable();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (this.runtime == null) return;
        this.runtime.disable();
        this.runtime = null;
    }

    public ProxyServer proxy() {
        return this.proxy;
    }

    public Path dataDirectory() {
        return this.dataDirectory;
    }
}
