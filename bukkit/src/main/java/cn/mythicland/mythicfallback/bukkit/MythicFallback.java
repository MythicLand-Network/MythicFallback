package cn.mythicland.mythicfallback.bukkit;

import cn.mythicland.framework.bukkit.proxy.BukkitShutdownNotifier;
import cn.mythicland.mythicfallback.TransferNotification;
import org.bukkit.Sound;
import org.bukkit.plugin.java.JavaPlugin;

public final class MythicFallback extends JavaPlugin {
    @Override
    public void onEnable() {
        this.getServer().getMessenger().registerIncomingPluginChannel(this, TransferNotification.CHANNEL, (channel, player, data) -> {
            if (data.length == 1 && data[0] == 1) player.playSound(player.getLocation(), Sound.NOTE_PLING, 1.0F, 1.0F);
        });
    }

    @Override
    public void onDisable() {
        BukkitShutdownNotifier.notifyProxy(this.getServer());
    }
}
