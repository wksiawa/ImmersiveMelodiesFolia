package net.conczi.immersivemelodies.gui;

import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentItems;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import net.conczi.immersivemelodies.melody.Melody;
import net.conczi.immersivemelodies.text.TextAssetGenerator;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 乐谱选择 GUI：复刻原模组乐谱界面布局。
 * 54 格容器的背景与 5 行歌名全部通过标题字形（资源包 hud 字体）绘制：
 * - 标题 = 负位移(-8) + 背景 glyph(E400, 176x166) + 游标法排布的 5 行歌名字形
 * - 第 0..4 行 col0..7 为不可见点击区（gui_blank），点击选中该行歌曲开始演奏
 * - col8 自上而下：上翻 / 下翻 / 播放（演奏中变为停止）/ 上传 / 关闭
 */
public final class MelodyGui implements Listener {

    /** 资源包 hud 字体（背景位图、位移空格与歌名字形） */
    public static final Key HUD_FONT = Key.key("immersive_melodies", "hud");

    public static final int SIZE = 54;
    public static final int ROWS = TextAssetGenerator.ROWS;
    public static final int SLOT_UP = 8;
    public static final int SLOT_DOWN = 17;
    public static final int SLOT_PLAY = 26;
    public static final int SLOT_UPLOAD = 35;
    public static final int SLOT_CLOSE = 44;

    private final ImmersiveMelodiesPlugin plugin;

    public MelodyGui(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
    }

    /** 标记用 Holder：携带滚动偏移与乐器类型，供事件处理与 UploadListener 识别 */
    public record GuiHolder(int offset, InstrumentType instrument) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null; // 仅作标记，不反查
        }
    }

    /**
     * 打开乐谱选择界面。offset 为列表滚动偏移，越界时自动收敛。
     */
    public void open(Player player, InstrumentType instrument, int offset) {
        List<String> melodies = sortedMelodies();
        int off = clampOffset(offset, melodies.size());

        Inventory inv = Bukkit.createInventory(new GuiHolder(off, instrument), SIZE, buildTitle(melodies, off));

        for (int row = 0; row < ROWS; row++) {
            if (off + row >= melodies.size()) {
                break;
            }
            for (int col = 0; col < 8; col++) {
                inv.setItem(row * 9 + col, guiItem("blank"));
            }
        }
        inv.setItem(SLOT_UP, guiItem("up"));
        inv.setItem(SLOT_DOWN, guiItem("down"));
        inv.setItem(SLOT_PLAY, plugin.getPlaybackManager().isPlaying(player) ? guiItem("refresh") : guiItem("play"));
        inv.setItem(SLOT_UPLOAD, guiItem("upload"));
        inv.setItem(SLOT_CLOSE, guiItem("close"));

        player.openInventory(inv);
    }

    /**
     * 构建标题字形文本：
     * E41B(-8) 使背景对齐 GUI 左缘；背景 glyph(advance=176) 输出后游标回 0；
     * 每行 +8 到羊皮纸内容左缘、输出该行歌名字形（advance=贴图宽）、行尾负位移回到 0。
     */
    private Component buildTitle(List<String> melodies, int offset) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uE41B');
        sb.append(TextAssetGenerator.CHAR_BG);
        appendShift(sb, -176);
        for (int row = 0; row < ROWS; row++) {
            int songIndex = offset + row;
            if (songIndex >= melodies.size()) {
                break;
            }
            appendShift(sb, 8);
            sb.append(TextAssetGenerator.textChar(songIndex, row));
            appendShift(sb, -(8 + glyphWidth(songIndex)));
        }
        return Component.text(sb.toString())
                .font(HUD_FONT)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** 歌名字形 advance；字贴未生成时用保守估算回退 */
    private int glyphWidth(int songIndex) {
        int w = plugin.getTextAssetGenerator().getGlyphWidth(songIndex);
        if (w <= 0) {
            w = 6;
        }
        return w;
    }

    /** 把位移量分解为 ±1/2/4/8/16/32/64/128 空格字形序列（E410=+1 基址，E418=-1 基址） */
    static void appendShift(StringBuilder sb, int delta) {
        int d = Math.abs(delta);
        if (d == 0) {
            return;
        }
        char base = delta > 0 ? '\uE410' : '\uE418';
        for (int p = 7; p >= 0; p--) {
            int v = 1 << p;
            while (d >= v) {
                sb.append((char) (base + p));
                d -= v;
            }
        }
    }

    private int clampOffset(int offset, int size) {
        return Math.max(0, Math.min(offset, Math.max(0, size - ROWS)));
    }

    private List<String> sortedMelodies() {
        List<String> melodies = new ArrayList<>(plugin.getMelodyManager().getMelodyNames());
        melodies.sort(Comparator.naturalOrder());
        return melodies;
    }

    /** 构建 CE GUI 按钮物品（CE 未加载时回退纸，不改名不带 lore） */
    private ItemStack guiItem(String id) {
        ItemStack stack = plugin.getCraftEngineBridge().buildInstrumentItem("gui_" + id);
        if (stack == null) {
            stack = new ItemStack(Material.PAPER);
        }
        return stack;
    }

    // ===== 事件 =====

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }

        switch (slot) {
            case SLOT_UP -> {
                if (holder.offset() > 0) {
                    open(player, holder.instrument(), holder.offset() - 1);
                }
            }
            case SLOT_DOWN -> open(player, holder.instrument(), holder.offset() + 1);
            case SLOT_PLAY -> handlePlay(player, holder);
            case SLOT_CLOSE -> player.closeInventory();
            default -> {
                int row = slot / 9;
                int col = slot % 9;
                if (row < ROWS && col < 8) {
                    handleSelect(player, holder, holder.offset() + row);
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }

    /** 点击行点击区：绑定旋律到手中乐器并立即开始演奏 */
    private void handleSelect(Player player, GuiHolder holder, int songIndex) {
        List<String> melodies = sortedMelodies();
        if (songIndex < 0 || songIndex >= melodies.size()) {
            return;
        }
        String melodyName = melodies.get(songIndex);
        Melody melody = plugin.getMelodyManager().getMelody(melodyName);
        if (melody == null) {
            return;
        }
        ItemStack instrument = InstrumentItems.findInstrumentInHands(player);
        if (instrument == null) {
            player.sendMessage(Component.text("请手持乐器", net.kyori.adventure.text.format.NamedTextColor.RED));
            player.closeInventory();
            return;
        }
        InstrumentItems.setMelody(instrument, melodyName);
        InstrumentItems.setPlaying(instrument, true);
        player.closeInventory();
        plugin.getPlaybackManager().start(player, holder.instrument(), melody);
    }

    /** 播放/停止按钮：未演奏时播放手中乐器绑定的旋律；演奏中显示刷新图标并停止 */
    private void handlePlay(Player player, GuiHolder holder) {
        if (plugin.getPlaybackManager().isPlaying(player)) {
            plugin.getPlaybackManager().stop(player);
            clearPlayingFlags(player);
            player.sendActionBar(Component.text("已停止演奏", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
            open(player, holder.instrument(), holder.offset());
            return;
        }
        ItemStack instrument = InstrumentItems.findInstrumentInHands(player);
        String bound = instrument != null ? InstrumentItems.getMelody(instrument) : null;
        Melody melody = bound != null ? plugin.getMelodyManager().getMelody(bound) : null;
        if (melody == null) {
            player.sendMessage(Component.text("请先点击列表选择歌曲", net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        InstrumentItems.setPlaying(instrument, true);
        plugin.getPlaybackManager().start(player, holder.instrument(), melody);
        open(player, holder.instrument(), holder.offset());
    }

    private void clearPlayingFlags(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (InstrumentItems.isInstrument(main)) InstrumentItems.setPlaying(main, false);
        ItemStack off = player.getInventory().getItemInOffHand();
        if (InstrumentItems.isInstrument(off)) InstrumentItems.setPlaying(off, false);
    }
}
