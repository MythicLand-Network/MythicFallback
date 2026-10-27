package cn.mythicland.mythicfallback;

import com.velocitypowered.api.command.CommandMeta;

public final class FallbackRuntime {
    private final MythicFallback plugin;
    private FallbackListener listener;
    private ReconnectService reconnect;
    private CommandMeta commandMeta;

    public FallbackRuntime(MythicFallback plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FallbackConfig config = FallbackConfig.load(this.plugin.dataDirectory(), this.getClass().getClassLoader());
        this.reconnect = new ReconnectService(this.plugin.proxy(), this.plugin, config);
        this.reconnect.updateMessages(ReconnectMessages.load(this.plugin.dataDirectory(), this.getClass().getClassLoader()));
        this.listener = new FallbackListener(this.plugin.proxy(), config, this.reconnect);
    }

    public void enable() {
        this.plugin.proxy().getChannelRegistrar().register(FallbackListener.SHUTDOWN_CHANNEL);
        this.plugin.proxy().getEventManager().register(this.plugin, this.listener);
        this.plugin.proxy().getEventManager().register(this.plugin, this.reconnect);
        this.commandMeta = this.plugin.proxy().getCommandManager().metaBuilder("mythicfallback")
                .aliases("mfb")
                .plugin(this.plugin)
                .build();
        this.plugin.proxy().getCommandManager().register(this.commandMeta,
                new ReconnectCommand(this.plugin.proxy(), this.reconnect, this::reload));
        this.reconnect.start();
    }

    private synchronized void reload() {
        FallbackConfig config = FallbackConfig.load(this.plugin.dataDirectory(), this.getClass().getClassLoader());
        ReconnectMessages messages = ReconnectMessages.load(this.plugin.dataDirectory(), this.getClass().getClassLoader());
        FallbackListener.Settings settings = FallbackListener.settings(this.plugin.proxy(), config);
        this.reconnect.updateConfig(config);
        this.reconnect.updateMessages(messages);
        this.listener.updateSettings(settings);
    }

    public void unload() {
        this.reconnect.close();
        this.plugin.proxy().getCommandManager().unregister(this.commandMeta);
        this.plugin.proxy().getEventManager().unregisterListener(this.plugin, this.reconnect);
        this.plugin.proxy().getEventManager().unregisterListener(this.plugin, this.listener);
        this.plugin.proxy().getChannelRegistrar().unregister(FallbackListener.SHUTDOWN_CHANNEL);
        this.listener.close();
    }
}
