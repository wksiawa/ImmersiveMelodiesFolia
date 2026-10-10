package net.conczi.immersivemelodies.gui;

import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import net.conczi.immersivemelodies.melody.Note;
import net.conczi.immersivemelodies.play.InstrumentSounds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * 自由演奏界面（对应原模组 ImmersiveMelodiesFreePlayingScreen）。
 * 原模组靠客户端自定义 Screen + 键盘监听实现，服务端插件无法接收游戏内按键，
 * 因此用容器界面的可点击琴键复刻原版钢琴布局：黑键上行错位、白键下行连续，
 * 键位映射与原模组 Config.keycodeToMidi 完全一致（A S D F G H J K / W E T Y U）。
 * 点击琴键即以手持乐器发出该音（世界音效，附近玩家可闻），与原模组 playNote 同算法。
 */
public final class FreePlayGui implements Listener {

    public static final int SIZE = 27;
    /** 退出按钮放底行中间偏右，对应原版 width/2-8, height/2+58 的位置 */
    public static final int SLOT_EXIT = 22;

    // 白键下行（行1）：C4 D4 E4 F4 G4 A4 B4 C5，连续排列
    private static final int[] WHITE_SLOTS = {9, 10, 11, 12, 13, 14, 15, 16};
    private static final int[] WHITE_MIDI = {60, 62, 64, 65, 67, 69, 71, 72};
    private static final String[] WHITE_KEYS = {"A", "S", "D", "F", "G", "H", "J", "K"};

    // 黑键上行（行0）：按钢琴键盘错位排列（C#4 在 C4/D4 之间，依此类推）
    // 槽位 1=C#4, 2=D#4, 4=F#4, 5=G#4, 6=A#4（E-F、B-C 之间无黑键）
    private static final int[] BLACK_SLOTS = {1, 2, 4, 5, 6};
    private static final int[] BLACK_MIDI = {61, 63, 66, 68, 70};
    private static final String[] BLACK_KEYS = {"W", "E", "T", "Y", "U"};

    private static final String[] NOTE_NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    private final ImmersiveMelodiesPlugin plugin;

    public FreePlayGui(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
    }

    /** 标记用 Holder：携带乐器类型 */
    public record FreePlayHolder(InstrumentType instrument) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /** 打开自由演奏琴键界面（布局与原模组一致：黑键上行错位、白键下行连续、底部中央红X退出） */
    public static void open(ImmersiveMelodiesPlugin plugin, Player player, InstrumentType instrument) {
        Inventory gui = Bukkit.createInventory(new FreePlayHolder(instrument), SIZE,
                Component.text("用键盘奏出音符吧！"));

        // 填满空白隐藏箱子格子
        ItemStack blank = plugin.getMelodyGui().guiBlank();
        for (int i = 0; i < SIZE; i++) {
            gui.setItem(i, blank);
        }

        // 白键下行（连续）
        for (int i = 0; i < WHITE_SLOTS.length; i++) {
            gui.setItem(WHITE_SLOTS[i], keyItem(Material.WHITE_WOOL, WHITE_MIDI[i], WHITE_KEYS[i], NamedTextColor.WHITE));
        }
        // 黑键上行（错位）
        for (int i = 0; i < BLACK_SLOTS.length; i++) {
            gui.setItem(BLACK_SLOTS[i], keyItem(Material.BLACK_WOOL, BLACK_MIDI[i], BLACK_KEYS[i], NamedTextColor.GRAY));
        }
        ItemStack exit = plugin.getCraftEngineBridge() == null ? null
                : plugin.getCraftEngineBridge().buildInstrumentItem("gui_exit");
        gui.setItem(SLOT_EXIT, exit != null ? exit : new ItemStack(Material.BARRIER));

        player.openInventory(gui);
    }

    /** 构建琴键物品：名为音名（如 C4 / C#4），lore 标注原模组按键 */
    private static ItemStack keyItem(Material material, int midi, String key, NamedTextColor nameColor) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(midiName(midi), nameColor).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("原模组按键: " + key, NamedTextColor.DARK_GRAY),
                    Component.text("点击演奏该音符", NamedTextColor.DARK_GRAY)
            ));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** MIDI 音高转音名，与原模组 ImmersiveMelodiesFreePlayingScreen.midiName 一致 */
    private static String midiName(int midi) {
        return NOTE_NAMES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof FreePlayHolder holder)) {
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
        if (slot == SLOT_EXIT) {
            player.closeInventory();
            return;
        }
        int midi = midiAt(slot);
        if (midi < 0) {
            return;
        }
        // 与原模组一致：自由演奏发声前自动暂停正在播放的旋律
        if (plugin.getPlaybackManager().isPlaying(player)) {
            plugin.getPlaybackManager().pause(player);
        }
        // 力度 127，与原模组鼠标/按键点击一致
        InstrumentSounds.playNote(plugin, player, holder.instrument(),
                new Note(midi, 127, 0, 10_000, 200));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof FreePlayHolder) {
            event.setCancelled(true);
        }
    }

    private static int midiAt(int slot) {
        for (int i = 0; i < WHITE_SLOTS.length; i++) {
            if (WHITE_SLOTS[i] == slot) {
                return WHITE_MIDI[i];
            }
        }
        for (int i = 0; i < BLACK_SLOTS.length; i++) {
            if (BLACK_SLOTS[i] == slot) {
                return BLACK_MIDI[i];
            }
        }
        return -1;
    }
}
