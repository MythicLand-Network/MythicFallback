package cn.mythicland.mythicfallback;

import cn.mythicland.framework.command.CommandRouter;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class ReconnectCommand implements SimpleCommand {
    private final ProxyServer proxy;
    private final ReconnectService reconnect;
    private final Runnable reload;
    private final CommandRouter<CommandSource> routes;

    public ReconnectCommand(ProxyServer proxy, ReconnectService reconnect, Runnable reload) {
        this.proxy = proxy;
        this.reconnect = reconnect;
        this.reload = reload;
        this.routes = new CommandRouter<CommandSource>()
                .route("send", 3, source -> source.hasPermission("mythicfallback.admin"), this::send)
                .route("reload", 0, source -> source.hasPermission("mythicfallback.admin"), (source, args) -> this.reload(source))
                .route("accept", 1, source -> source instanceof Player, (source, args) -> this.reconnect.accept((Player) source, args[1]))
                .route("reject", 1, source -> source instanceof Player, (source, args) -> this.reconnect.reject((Player) source, args[1]));
    }

    @Override
    public void execute(Invocation invocation) {
        if (this.routes.execute(invocation.source(), invocation.arguments())) return;
        String[] arguments = invocation.arguments();
        if (arguments.length > 0 && (arguments[0].equalsIgnoreCase("accept") || arguments[0].equalsIgnoreCase("reject"))) return;
        CommandSource source = invocation.source();
        if (!source.hasPermission("mythicfallback.admin")) {
            source.sendMessage(Component.text("你没有权限使用这个命令。", NamedTextColor.RED));
            return;
        }
        String label = invocation.alias();
        if (label == null || label.isBlank()) label = "mythicfallback";
        source.sendMessage(Component.text("用法: /" + label + " send player <玩家名> <目标服务器>", NamedTextColor.RED));
        source.sendMessage(Component.text("用法: /" + label + " send server <来源服务器> <目标服务器>", NamedTextColor.RED));
        source.sendMessage(Component.text("用法: /" + label + " reload", NamedTextColor.RED));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] arguments = invocation.arguments();
        if (!invocation.source().hasPermission("mythicfallback.admin")) return List.of();
        if (arguments.length <= 1) return filter(List.of("send", "reload"), arguments.length == 0 ? "" : arguments[0]);
        if (!arguments[0].equalsIgnoreCase("send")) return List.of();
        if (arguments.length == 2) return filter(List.of("player", "server"), arguments[1]);
        if (arguments.length == 3 && arguments[1].equalsIgnoreCase("player")) {
            return filter(this.proxy.getAllPlayers().stream().map(Player::getUsername).toList(), arguments[2]);
        }
        if ((arguments.length == 3 && arguments[1].equalsIgnoreCase("server"))
                || (arguments.length == 4 && (arguments[1].equalsIgnoreCase("player") || arguments[1].equalsIgnoreCase("server")))) {
            return filter(this.proxy.getAllServers().stream().map(server -> server.getServerInfo().getName()).toList(),
                    arguments[arguments.length - 1]);
        }
        return List.of();
    }

    private static List<String> filter(Collection<String> candidates, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(normalized))
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private void send(CommandSource source, String[] arguments) {
        if (!arguments[1].equalsIgnoreCase("player") && !arguments[1].equalsIgnoreCase("server")) {
            source.sendMessage(Component.text("用法: /mfb send <player|server> <名称> <目标服务器>", NamedTextColor.RED));
            return;
        }
        RegisteredServer target = this.proxy.getServer(arguments[3]).orElse(null);
        if (target == null) {
            source.sendMessage(Component.text("目标服务器不存在。", NamedTextColor.RED));
            return;
        }
        int sent = 0;
        if (arguments[1].equalsIgnoreCase("player")) {
            Player player = this.proxy.getPlayer(arguments[2]).orElse(null);
            if (player != null && this.reconnect.send(player, target)) sent++;
        } else if (arguments[1].equalsIgnoreCase("server")) {
            RegisteredServer from = this.proxy.getServer(arguments[2]).orElse(null);
            if (from == null) {
                source.sendMessage(Component.text("来源服务器不存在。", NamedTextColor.RED));
                return;
            }
            for (Player player : from.getPlayersConnected()) {
                if (this.reconnect.send(player, target)) sent++;
            }
        }
        source.sendMessage(Component.text("已发送 " + sent + " 个传送请求。", NamedTextColor.GREEN));
    }

    private void reload(CommandSource source) {
        try {
            this.reload.run();
            source.sendMessage(Component.text("配置已重载。", NamedTextColor.GREEN));
        } catch (RuntimeException exception) {
            source.sendMessage(Component.text("配置重载失败: " + exception.getMessage(), NamedTextColor.RED));
        }
    }
}
