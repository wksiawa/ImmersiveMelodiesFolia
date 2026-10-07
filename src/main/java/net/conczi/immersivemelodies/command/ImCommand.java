package net.conczi.immersivemelodies.command;

import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentItems;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class ImCommand implements CommandExecutor, TabCompleter {

    private final ImmersiveMelodiesPlugin plugin;

    public ImCommand(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> handleGive(sender, args);
            case "play" -> handlePlay(sender, args);
            case "stop" -> handleStop(sender);
            case "list" -> handleList(sender);
            case "reload" -> handleReload(sender);
            case "pack" -> handlePack(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    /** /im give <乐器> [旋律] — 获取乐器物品 */
    private void handleGive(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("只有玩家可以使用此命令", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("用法: /im give <乐器> [旋律]", NamedTextColor.RED));
            player.sendMessage(Component.text("乐器: " + Arrays.stream(InstrumentType.values())
                    .map(InstrumentType::getId).collect(Collectors.joining(", ")), NamedTextColor.GRAY));
            return;
        }

        InstrumentType type = InstrumentType.fromKey(args[1]);
        if (type == null) {
            player.sendMessage(Component.text("未知乐器: " + args[1], NamedTextColor.RED));
            return;
        }

        String melodyName = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : null;
        if (melodyName != null && !plugin.getMelodyManager().hasMelody(melodyName)) {
            player.sendMessage(Component.text("旋律不存在: " + melodyName, NamedTextColor.RED));
            return;
        }

        ItemStack item = InstrumentItems.createItem(type, melodyName);
        player.getInventory().addItem(item);
        player.sendMessage(Component.text("已获得乐器: " + type.getId()
                + (melodyName != null ? "（旋律: " + melodyName + "）" : ""), NamedTextColor.GREEN));
    }

    /** /im play <旋律> — 为手中乐器绑定旋律并开始演奏 */
    private void handlePlay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("只有玩家可以使用此命令", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("用法: /im play <旋律>", NamedTextColor.RED));
            return;
        }

        String melodyName = args[1].toLowerCase(Locale.ROOT);
        if (!plugin.getMelodyManager().hasMelody(melodyName)) {
            player.sendMessage(Component.text("旋律不存在: " + melodyName, NamedTextColor.RED));
            return;
        }

        ItemStack stack = InstrumentItems.findInstrumentInHands(player);
        if (stack == null) {
            player.sendMessage(Component.text("请先将乐器拿在主手或副手", NamedTextColor.RED));
            return;
        }

        InstrumentItems.setMelody(stack, melodyName);
        InstrumentItems.setPlaying(stack, true);

        InstrumentType instrument = InstrumentItems.getInstrument(stack);
        plugin.getPlaybackManager().start(player, instrument, plugin.getMelodyManager().getMelody(melodyName));
        player.sendMessage(Component.text("开始演奏: " + melodyName, NamedTextColor.GREEN));
    }

    /** /im stop — 停止演奏 */
    private void handleStop(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("只有玩家可以使用此命令", NamedTextColor.RED));
            return;
        }
        plugin.getPlaybackManager().stop(player);
        ItemStack main = player.getInventory().getItemInMainHand();
        if (InstrumentItems.isInstrument(main)) InstrumentItems.setPlaying(main, false);
        ItemStack off = player.getInventory().getItemInOffHand();
        if (InstrumentItems.isInstrument(off)) InstrumentItems.setPlaying(off, false);
        player.sendMessage(Component.text("已停止演奏", NamedTextColor.YELLOW));
    }

    /** /im list — 列出可用旋律 */
    private void handleList(CommandSender sender) {
        List<String> names = plugin.getMelodyManager().getMelodyNames();
        sender.sendMessage(Component.text("可用旋律 (" + names.size() + "):", NamedTextColor.GOLD));
        for (String name : names) {
            sender.sendMessage(Component.text("  - " + name, NamedTextColor.GRAY));
        }
    }

    /** /im reload — 重载配置和旋律 */
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("immersivemelodies.admin")) {
            sender.sendMessage(Component.text("没有权限", NamedTextColor.RED));
            return;
        }
        plugin.reloadConfig();
        plugin.getMelodyManager().loadAll();
        sender.sendMessage(Component.text("已重载，共 " + plugin.getMelodyManager().getMelodyNames().size() + " 首旋律", NamedTextColor.GREEN));
    }

    /** /im pack — 显示资源包状态 */
    private void handlePack(CommandSender sender) {
        sender.sendMessage(Component.text("资源包输出目录: " + plugin.getDataFolder() + "/pack", NamedTextColor.GRAY));
        sender.sendMessage(Component.text(plugin.getCraftEngineBridge().isCraftEnginePresent()
                ? "已检测到 CraftEngine，物品材质配置已注入" : "未检测到 CraftEngine，使用独立资源包", NamedTextColor.YELLOW));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("ImmersiveMelodies Folia 命令:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/im give <乐器> [旋律] - 获取乐器", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/im play <旋律> - 绑定旋律并演奏", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/im stop - 停止演奏", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/im list - 列出旋律", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/im reload - 重载配置", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/im pack - 资源包状态", NamedTextColor.GRAY));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 1) {
            return filter(args[0], Arrays.asList("give", "play", "stop", "list", "reload", "pack"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return filter(args[1], Arrays.stream(InstrumentType.values()).map(InstrumentType::getId).collect(Collectors.toList()));
        }
        if ((args.length == 3 && args[0].equalsIgnoreCase("give")) || (args.length == 2 && args[0].equalsIgnoreCase("play"))) {
            return filter(args[args.length - 1], plugin.getMelodyManager().getMelodyNames());
        }
        return Collections.emptyList();
    }

    private List<String> filter(String prefix, List<String> options) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
