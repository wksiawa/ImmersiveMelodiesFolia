package net.conczi.immersivemelodies.gui;

import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentItems;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import net.conczi.immersivemelodies.melody.Melody;
import net.conczi.immersivemelodies.melody.Track;
import net.conczi.immersivemelodies.text.TextAssetGenerator;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 乐谱选择 GUI：复刻原模组 ImmersiveMelodiesScreen。
 * 54 格容器的背景与 5 行歌名全部通过标题字形（资源包 hud 字体）绘制：
 * - 标题 = 负位移(-8) + 背景 glyph(E400, 176x166) + 游标法排布的 5 行歌名字形
 * - 第 0..4 行 col0..6 为不可见点击区（gui_blank），点击即选中并立即演奏该行歌曲
 * - col7/col8 两列共 10 个按钮，顺序对应原模组主界面底排
 *   关闭/曲目列表/自由演奏/暂停/继续/删除/上传/帮助 + 插件适配的上一页/下一页
 */
public final class MelodyGui implements Listener {

    /** 资源包 hud 字体（背景位图、位移空格与歌名字形） */
    public static final Key HUD_FONT = Key.key("immersive_melodies", "hud");

    /** 原模组自定义旋律 wiki（帮助按钮打开） */
    public static final String HELP_URL =
            "https://github.com/Luke100000/ImmersiveMelodies/wiki/Custom-Melodies";

    public static final int SIZE = 54;
    public static final int ROWS = TextAssetGenerator.ROWS;

    // ===== 主界面按钮槽位 =====
    // 原版底排水平一行（y = height/2 + 69），顺序：关闭/曲目/键盘/暂停/继续/删除/上传/帮助
    // 容器底行（第 6 行）槽位 45..53，按原版从左到右排列；翻页按钮放右上角 col8
    public static final int SLOT_CLOSE = 45;
    public static final int SLOT_TRACKS = 46;
    public static final int SLOT_KEYBOARD = 47;
    public static final int SLOT_PAUSE = 48;
    public static final int SLOT_PLAY = 49;
    public static final int SLOT_DELETE = 50;
    public static final int SLOT_UPLOAD = 51;
    public static final int SLOT_HELP = 52;
    /** 右上角翻页（原版用滚轮，容器界面无滚轮，故插件适配此二控件） */
    public static final int SLOT_PREV = 16;
    public static final int SLOT_NEXT = 25;
    /** 搜索按钮（原模组顶部搜索框的容器适配），放 col7 行 0 */
    public static final int SLOT_SEARCH = 7;

    // ===== 曲目面板 =====
    public static final int TRACKS_SIZE = 54;
    /** 曲目条目最多占用 0..44（前 5 行） */
    public static final int TRACK_ITEM_MAX = 44;
    public static final int SLOT_TRACK_BACK = 49;

    private final ImmersiveMelodiesPlugin plugin;

    public MelodyGui(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
    }

    /** 主界面标记：携带滚动偏移、乐器类型、当前选中旋律与搜索关键词，供事件处理与 UploadListener 识别 */
    public record GuiHolder(int offset, InstrumentType instrument, String selected, String search) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null; // 仅作标记，不反查
        }
    }

    /** 曲目面板标记 */
    public record TrackHolder(InstrumentType instrument, String melody) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /**
     * 打开乐谱选择界面。offset 为列表滚动偏移，越界时自动收敛。
     */
    public void open(Player player, InstrumentType instrument, int offset) {
        open(player, instrument, offset, null, null);
    }

    /**
     * 打开乐谱选择界面，可指定当前选中旋律（曲目/删除按钮仅在选中后出现，与原模组一致）。
     */
    public void open(Player player, InstrumentType instrument, int offset, String selected) {
        open(player, instrument, offset, selected, null);
    }

    /**
     * 打开乐谱选择界面，支持搜索关键词过滤歌曲列表。
     */
    public void open(Player player, InstrumentType instrument, int offset, String selected, String search) {
        List<String> melodies = sortedMelodies(search);
        int off = clampOffset(offset, melodies.size());

        Inventory inv = Bukkit.createInventory(new GuiHolder(off, instrument, selected, search), SIZE,
                buildTitle(melodies, off, search));

        // 先用透明 blank 填满所有 54 格，彻底隐藏箱子格子背景
        ItemStack blank = guiItem("blank");
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, blank);
        }

        // 歌曲行点击区：col0..6（覆盖回 blank，点击即选中并演奏）
        for (int row = 0; row < ROWS; row++) {
            if (off + row >= melodies.size()) {
                break;
            }
            for (int col = 0; col < 7; col++) {
                inv.setItem(row * 9 + col, blank);
            }
        }

        // 搜索按钮（原模组顶部搜索框的容器适配，用指南针图标 + "搜索"名称）
        inv.setItem(SLOT_SEARCH, searchButton(search));

        // 底排按钮（水平一行，顺序与原模组完全一致）
        inv.setItem(SLOT_CLOSE, guiItem("close"));
        if (selected != null) {
            inv.setItem(SLOT_TRACKS, guiItem("tracks"));
            // 仅玩家上传的旋律且拥有上传权限时显示删除（对应原模组 Utils.canDelete）
            if (plugin.getMelodyManager().isUploaded(selected)
                    && player.hasPermission("immersivemelodies.upload")) {
                inv.setItem(SLOT_DELETE, guiItem("delete"));
            }
        }
        inv.setItem(SLOT_KEYBOARD, guiItem("keyboard"));
        inv.setItem(SLOT_PAUSE, guiItem("pause"));
        inv.setItem(SLOT_PLAY, guiItem("play"));
        inv.setItem(SLOT_UPLOAD, guiItem("upload"));
        inv.setItem(SLOT_HELP, guiItem("help"));
        inv.setItem(SLOT_PREV, guiItem("page_up"));
        inv.setItem(SLOT_NEXT, guiItem("page_down"));

        player.openInventory(inv);
    }

    /**
     * 打开曲目列表面板（对应原模组主纸右侧展开的 track list）。
     */
    public void openTracks(Player player, InstrumentType instrument, String melodyName) {
        Melody melody = plugin.getMelodyManager().getMelody(melodyName);
        if (melody == null) {
            return;
        }
        Inventory inv = Bukkit.createInventory(new TrackHolder(instrument, melodyName), TRACKS_SIZE,
                Component.text("曲目列表: " + melody.getDisplayName()));

        // 填满空槽位隐藏箱子格子
        ItemStack blank = guiItem("blank");
        for (int i = 0; i < TRACKS_SIZE; i++) {
            inv.setItem(i, blank);
        }

        ItemStack hand = InstrumentItems.findInstrumentInHands(player);
        Set<Integer> enabledList = hand == null ? Set.of()
                : new HashSet<>(InstrumentItems.getEnabledTracks(hand));
        List<Track> tracks = melody.getTracks();
        for (int i = 0; i < Math.min(tracks.size(), TRACK_ITEM_MAX + 1); i++) {
            inv.setItem(i, trackItem(tracks.get(i), i, tracks.size(), enabledList));
        }
        inv.setItem(SLOT_TRACK_BACK, guiItem("close"));

        player.openInventory(inv);
    }

    /** 构建曲目条目：启用=常规白纸，静音=删除线灰纸（对应原模组 DARK_GRAY / STRIKETHROUGH） */
    private ItemStack trackItem(Track track, int index, int trackCount, Set<Integer> enabledList) {
        boolean enabled = enabledList.isEmpty() || enabledList.contains(index);
        ItemStack stack = new ItemStack(enabled ? Material.PAPER : Material.GUNPOWDER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            Component name = Component.text(track.getName(), enabled ? NamedTextColor.DARK_GRAY : NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.STRIKETHROUGH, !enabled);
            meta.displayName(name);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(enabled ? "状态: 已启用" : "状态: 已静音",
                    enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            lore.add(Component.text("点击启用/静音该曲目", NamedTextColor.DARK_GRAY));
            meta.lore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * 构建标题字形文本：
     * 背景 glyph(advance=176) 正好覆盖容器宽度；每行 +8 到内容左缘、输出歌名字形、行尾回 0。
     * 搜索关键词非空时，在背景下方额外渲染"搜索: xxx"提示行。
     */
    private Component buildTitle(List<String> melodies, int offset, String search) {
        StringBuilder sb = new StringBuilder();
        // 背景从 x=0 开始正好覆盖 0..176 容器宽度
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

    /** 搜索按钮：指南针图标，名称显示当前关键词（无关键词时显示"搜索"） */
    private ItemStack searchButton(String currentSearch) {
        ItemStack stack = new ItemStack(Material.COMPASS);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String label = (currentSearch == null || currentSearch.isBlank())
                    ? "搜索歌曲" : "搜索: " + currentSearch;
            meta.displayName(Component.text(label, NamedTextColor.DARK_AQUA)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("点击后在聊天栏输入关键词", NamedTextColor.DARK_GRAY),
                    Component.text("输入空行清除搜索", NamedTextColor.DARK_GRAY)
            ));
            stack.setItemMeta(meta);
        }
        return stack;
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
        return sortedMelodies(null);
    }

    private List<String> sortedMelodies(String search) {
        List<String> melodies = new ArrayList<>(plugin.getMelodyManager().getMelodyNames());
        if (search != null && !search.isBlank()) {
            String q = search.toLowerCase();
            melodies.removeIf(n -> !n.toLowerCase().contains(q));
        }
        melodies.sort(Comparator.naturalOrder());
        return melodies;
    }

    /** 构建 CE GUI 按钮物品（CE 未加载时回退纸） */
    private ItemStack guiItem(String id) {
        ItemStack stack = plugin.getCraftEngineBridge() == null ? null
                : plugin.getCraftEngineBridge().buildInstrumentItem("gui_" + id);
        if (stack == null) {
            stack = new ItemStack(Material.PAPER);
        }
        return stack;
    }

    /** 透明空白物品（用于填充空槽位、隐藏箱子格子） */
    public ItemStack guiBlank() {
        return guiItem("blank");
    }

    // ===== 事件 =====

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof GuiHolder guiHolder) {
            onMainClick(event, guiHolder);
        } else if (holder instanceof TrackHolder trackHolder) {
            onTrackClick(event, trackHolder);
        }
    }

    private void onMainClick(InventoryClickEvent event, GuiHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }

        switch (slot) {
            case SLOT_CLOSE -> player.closeInventory();
            case SLOT_TRACKS -> {
                if (holder.selected() != null) {
                    openTracks(player, holder.instrument(), holder.selected());
                }
            }
            case SLOT_KEYBOARD -> FreePlayGui.open(plugin, player, holder.instrument());
            case SLOT_PAUSE -> handlePause(player);
            case SLOT_PLAY -> handleContinue(player, holder);
            case SLOT_DELETE -> handleDelete(player, holder);
            // 上传按钮由 UploadListener 独立处理（本地文件导入方案）
            case SLOT_UPLOAD -> { }
            case SLOT_HELP -> handleHelp(player);
            case SLOT_SEARCH -> startSearch(player, holder);
            case SLOT_PREV -> {
                if (holder.offset() > 0) {
                    open(player, holder.instrument(), holder.offset() - 1, holder.selected(), holder.search());
                }
            }
            case SLOT_NEXT -> {
                int size = sortedMelodies(holder.search()).size();
                if (holder.offset() < size - ROWS) {
                    open(player, holder.instrument(), holder.offset() + 1, holder.selected(), holder.search());
                }
            }
            default -> {
                int row = slot / 9;
                int col = slot % 9;
                if (row < ROWS && col < 7) {
                    handleSelect(player, holder, holder.offset() + row);
                }
            }
        }
    }

    /** 进入搜索模式：关闭 GUI，提示聊天输入关键词（空行=清除搜索） */
    private void startSearch(Player player, GuiHolder holder) {
        player.closeInventory();
        player.sendMessage(Component.text("请在聊天栏输入搜索关键词（直接发送空行可清除搜索）", NamedTextColor.YELLOW));
        plugin.getUploadListener().startSearchMode(player, holder.instrument(), holder.selected(), holder.search());
    }

    private void onTrackClick(InventoryClickEvent event, TrackHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= TRACKS_SIZE) {
            return;
        }
        if (slot == SLOT_TRACK_BACK) {
            open(player, holder.instrument(), 0, holder.melody(), null);
            return;
        }
        if (slot > TRACK_ITEM_MAX) {
            return;
        }
        Melody melody = plugin.getMelodyManager().getMelody(holder.melody());
        if (melody == null || slot >= melody.getTracks().size()) {
            return;
        }
        toggleTrack(player, holder, melody, slot);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GuiHolder
                || event.getInventory().getHolder() instanceof TrackHolder) {
            event.setCancelled(true);
        }
    }

    /** 点击行点击区：绑定旋律到手中乐器并立即开始演奏（与原模组一致），界面保持打开并选中该曲 */
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
            player.sendMessage(Component.text("请手持乐器", NamedTextColor.RED));
            player.closeInventory();
            return;
        }
        InstrumentItems.setMelody(instrument, melodyName);
        InstrumentItems.setPlaying(instrument, true);
        plugin.getPlaybackManager().start(player, holder.instrument(), melody,
                InstrumentItems.getEnabledTracks(instrument));
        open(player, holder.instrument(), holder.offset(), melodyName, holder.search());
    }

    /** 暂停按钮（原模组 ItemActionMessage.State.PAUSE） */
    private void handlePause(Player player) {
        if (plugin.getPlaybackManager().isPlaying(player)) {
            plugin.getPlaybackManager().pause(player);
            player.sendActionBar(Component.text("已暂停演奏", NamedTextColor.YELLOW));
        }
    }

    /** 绿色三角按钮：暂停中则继续（CONTINUE）；无会话时开始演奏已绑定旋律 */
    private void handleContinue(Player player, GuiHolder holder) {
        if (plugin.getPlaybackManager().isPaused(player)) {
            plugin.getPlaybackManager().resume(player);
            player.sendActionBar(Component.text("继续演奏", NamedTextColor.GREEN));
            return;
        }
        if (plugin.getPlaybackManager().isPlaying(player)) {
            return;
        }
        ItemStack instrument = InstrumentItems.findInstrumentInHands(player);
        String bound = instrument != null ? InstrumentItems.getMelody(instrument) : null;
        Melody melody = bound != null ? plugin.getMelodyManager().getMelody(bound) : null;
        if (melody == null) {
            player.sendMessage(Component.text("请先点击列表选择歌曲", NamedTextColor.RED));
            return;
        }
        InstrumentItems.setPlaying(instrument, true);
        plugin.getPlaybackManager().start(player, holder.instrument(), melody,
                InstrumentItems.getEnabledTracks(instrument));
    }

    /** 删除按钮：仅可删除玩家上传旋律（原模组 MelodyDeleteRequest + Utils.canDelete） */
    private void handleDelete(Player player, GuiHolder holder) {
        String selected = holder.selected();
        if (selected == null) {
            return;
        }
        if (!plugin.getMelodyManager().isUploaded(selected)) {
            player.sendMessage(Component.text("内置旋律不可删除", NamedTextColor.RED));
            return;
        }
        if (!player.hasPermission("immersivemelodies.upload")) {
            player.sendMessage(Component.text("你没有删除旋律的权限", NamedTextColor.RED));
            return;
        }
        // 若正在播放该曲则先停止
        var session = plugin.getPlaybackManager().getSession(player);
        if (session != null && selected.equals(session.getMelody().getName())) {
            plugin.getPlaybackManager().stop(player);
            clearPlayingFlags(player);
        }
        if (!plugin.getMelodyManager().deleteMelody(selected)) {
            player.sendMessage(Component.text("删除失败", NamedTextColor.RED));
            return;
        }
        player.sendMessage(Component.text("已删除旋律: " + selected, NamedTextColor.YELLOW));

        // 字贴/hud.json 重建涉及图片 IO，放异步线程；ce reload 与界面重开回到主线程
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            plugin.getTextAssetGenerator().generateAll();
            if (Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
                Bukkit.getGlobalRegionScheduler().run(plugin, t ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ce reload all"));
            }
            player.getScheduler().run(plugin, t -> {
                if (player.isOnline()) {
                    open(player, holder.instrument(), holder.offset(), null, holder.search());
                }
            }, null);
        });
    }

    /** 帮助按钮：发送可点击的自定义旋律 wiki 链接（对应原模组 Util.openUri） */
    private void handleHelp(Player player) {
        player.sendMessage(Component.text("自定义旋律教程: ", NamedTextColor.GOLD)
                .append(Component.text(HELP_URL, NamedTextColor.GREEN, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(HELP_URL))));
    }

    /**
     * 切换某首旋律的曲目启用状态，写入对应乐器物品 PDC（空列表=全部启用），
     * 并同步正在演奏该曲的会话。
     */
    private void toggleTrack(Player player, TrackHolder holder, Melody melody, int trackIndex) {
        ItemStack hand = InstrumentItems.findInstrumentInHands(player);
        if (hand == null || InstrumentItems.getInstrument(hand) != holder.instrument()) {
            player.sendMessage(Component.text("请手持该乐器", NamedTextColor.RED));
            return;
        }
        int n = melody.getTracks().size();
        Set<Integer> enabled = InstrumentItems.getEnabledTracks(hand).isEmpty()
                ? allIndices(n) : new HashSet<>(InstrumentItems.getEnabledTracks(hand));
        if (!enabled.remove(trackIndex)) {
            enabled.add(trackIndex);
        }
        // 全部启用时按原模组约定存空列表
        Set<Integer> stored = enabled.size() == n ? Set.of() : enabled;
        InstrumentItems.setEnabledTracks(hand, stored);

        var session = plugin.getPlaybackManager().getSession(player);
        if (session != null && session.getInstrument() == holder.instrument()
                && holder.melody().equals(session.getMelody().getName())) {
            session.setEnabledTracks(stored);
        }
        openTracks(player, holder.instrument(), holder.melody());
    }

    private static Set<Integer> allIndices(int n) {
        Set<Integer> all = new HashSet<>();
        for (int i = 0; i < n; i++) {
            all.add(i);
        }
        return all;
    }

    private void clearPlayingFlags(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (InstrumentItems.isInstrument(main)) InstrumentItems.setPlaying(main, false);
        ItemStack off = player.getInventory().getItemInOffHand();
        if (InstrumentItems.isInstrument(off)) InstrumentItems.setPlaying(off, false);
    }
}
