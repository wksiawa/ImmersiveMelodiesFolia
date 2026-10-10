package net.conczi.immersivemelodies.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.gui.MelodyGui;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import net.conczi.immersivemelodies.melody.Melody;
import net.conczi.immersivemelodies.melody.MidiParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上传自定义音乐 + 搜索模式：
 * - 上传：玩家将 .mid/.midi 文件放入服务器 plugins/ImmersiveMelodiesFolia/uploads_pending/ 目录，
 *   在 GUI 点击上传按钮后打开文件列表，点击文件名即可导入（本地添加，替代原模组客户端拖放）。
 * - 搜索：点击搜索按钮后在聊天栏输入关键词，重新打开 GUI 时仅显示名称包含该关键词的歌曲。
 */
public class UploadListener implements Listener {

    private static final long TIMEOUT_MS = 60_000L;
    private static final int FILE_LIST_SIZE = 54;

    /** 文件列表 GUI 标记 */
    public record FileListHolder(InstrumentType instrument) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /** 搜索模式等待状态 */
    private record SearchPending(long time, InstrumentType instrument, String selected, String previousSearch) {}

    private final ImmersiveMelodiesPlugin plugin;
    private final Map<UUID, SearchPending> searchPending = new ConcurrentHashMap<>();

    public UploadListener(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
    }

    // ===== 上传按钮：打开本地文件列表 =====

    @EventHandler
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MelodyGui.GuiHolder holder)) {
            return;
        }
        if (event.getRawSlot() != MelodyGui.SLOT_UPLOAD) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!player.hasPermission("immersivemelodies.upload")) {
            player.sendMessage(Component.text("你没有上传音乐的权限", NamedTextColor.RED));
            return;
        }
        openFileList(player, holder.instrument());
    }

    /** 打开待上传文件列表 GUI（uploads_pending/ 目录下的 .mid/.midi 文件） */
    public void openFileList(Player player, InstrumentType instrument) {
        File pendingDir = new File(plugin.getDataFolder(), "uploads_pending");
        //noinspection ResultOfMethodCallIgnored
        pendingDir.mkdirs();

        File[] files = pendingDir.listFiles((d, n) ->
                n.toLowerCase(Locale.ROOT).endsWith(".mid") || n.toLowerCase(Locale.ROOT).endsWith(".midi"));
        List<File> fileList = files == null ? List.of()
                : new ArrayList<>(Arrays.asList(files));
        fileList.sort(Comparator.comparing(File::getName));

        Inventory inv = Bukkit.createInventory(new FileListHolder(instrument), FILE_LIST_SIZE,
                Component.text("选择本地旋律文件"));

        // 填满 blank 隐藏箱子格子
        ItemStack blank = plugin.getMelodyGui().guiBlank();
        for (int i = 0; i < FILE_LIST_SIZE; i++) {
            inv.setItem(i, blank);
        }

        // 每行放一个文件（用 PAPER 物品，名称=文件名）
        for (int i = 0; i < Math.min(fileList.size(), FILE_LIST_SIZE); i++) {
            inv.setItem(i, fileItem(fileList.get(i)));
        }

        // 底部提示
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("上传说明", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("将 .mid/.midi 文件放入服务器目录：", NamedTextColor.GRAY),
                    Component.text("plugins/ImmersiveMelodiesFolia/uploads_pending/", NamedTextColor.AQUA),
                    Component.text("然后点击上方文件名即可导入", NamedTextColor.GRAY)
            ));
            info.setItemMeta(meta);
        }
        inv.setItem(FILE_LIST_SIZE - 1, info);

        player.openInventory(inv);
    }

    private ItemStack fileItem(File file) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(file.getName(), NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("点击导入此旋律", NamedTextColor.DARK_GRAY),
                    Component.text("大小: " + (file.length() / 1024) + " KB", NamedTextColor.DARK_GRAY)
            ));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 文件列表点击：导入选中的本地文件 */
    @EventHandler
    public void onFileListClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof FileListHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= FILE_LIST_SIZE) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.PAPER) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || meta.displayName() == null) {
            return;
        }
        String fileName = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        File pendingDir = new File(plugin.getDataFolder(), "uploads_pending");
        File file = new File(pendingDir, fileName);
        if (!file.isFile()) {
            player.sendMessage(Component.text("文件不存在: " + fileName, NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        // 异步解析导入
        Bukkit.getAsyncScheduler().runNow(plugin, task -> importLocalFile(player, file, holder.instrument()));
    }

    @EventHandler
    public void onFileListDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof FileListHolder) {
            event.setCancelled(true);
        }
    }

    private void importLocalFile(Player player, File file, InstrumentType instrument) {
        try {
            String safeName = file.getName().replaceAll("[^a-zA-Z0-9._\\-]", "_");
            String melodyName = safeName.replaceAll("(?i)\\.(mid|midi)$", "");

            Melody melody;
            try (FileInputStream fis = new FileInputStream(file)) {
                melody = MidiParser.parseMidi(fis, melodyName);
            }
            if (melody.getTracks().isEmpty()) {
                throw new IllegalStateException("文件中没有可解析的音符");
            }

            // 移动到 uploads/ 目录（已导入的存档）
            File uploads = new File(plugin.getDataFolder(), "uploads");
            //noinspection ResultOfMethodCallIgnored
            uploads.mkdirs();
            File dest = new File(uploads, safeName);
            if (!file.renameTo(dest)) {
                // 重命名失败则复制
                java.nio.file.Files.copy(file.toPath(), dest.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }

            plugin.getMelodyManager().registerMelody(melodyName, melody);
            plugin.getTextAssetGenerator().generateAll();

            if (Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
                Bukkit.getGlobalRegionScheduler().run(plugin, task ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ce reload all"));
            }

            player.sendMessage(Component.text("导入成功: " + melodyName, NamedTextColor.GREEN));
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) {
                    plugin.getMelodyGui().open(player, instrument, 0);
                }
            }, null);
        } catch (Exception e) {
            plugin.getLogger().warning("导入旋律失败(" + player.getName() + "): " + e.getMessage());
            player.sendMessage(Component.text("导入失败: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    // ===== 搜索模式 =====

    /** 进入搜索模式：聊天输入关键词后过滤歌曲列表 */
    public void startSearchMode(Player player, InstrumentType instrument, String selected, String previousSearch) {
        UUID id = player.getUniqueId();
        searchPending.put(id, new SearchPending(System.currentTimeMillis(), instrument, selected, previousSearch));
        player.getScheduler().runDelayed(plugin, task -> {
            SearchPending p = searchPending.get(id);
            if (p != null && System.currentTimeMillis() - p.time() >= TIMEOUT_MS) {
                searchPending.remove(id);
                player.sendMessage(Component.text("搜索会话已超时", NamedTextColor.RED));
            }
        }, null, TIMEOUT_MS / 50L);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        SearchPending p = searchPending.remove(id);
        if (p == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (System.currentTimeMillis() - p.time() > TIMEOUT_MS) {
            player.sendMessage(Component.text("搜索会话已超时", NamedTextColor.RED));
            return;
        }
        String keyword = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        // 空行 = 清除搜索
        String search = keyword.isEmpty() ? null : keyword;
        player.getScheduler().run(plugin, task -> {
            if (player.isOnline()) {
                plugin.getMelodyGui().open(player, p.instrument(), 0, p.selected(), search);
            }
        }, null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        searchPending.remove(event.getPlayer().getUniqueId());
    }
}
