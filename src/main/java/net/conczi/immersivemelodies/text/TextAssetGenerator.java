package net.conczi.immersivemelodies.text;

import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentType;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 运行时字体图集生成器（写入 CraftEngine 资源目录，随 CE 资源包分发）：
 * - 从 jar 内 paper.png 裁剪 GUI 背景 chest_bg.png（192x192 拉伸到 176x166）与 9 个 16x16 按钮图标
 * - 用原版 ascii.png 字符表把每个旋律名逐字符拼成纯白无描边的透明 PNG（font/text/t<i>.png），
 *   视觉等同 Minecraft 默认字体；下划线按空格宽度留白
 * - 完整生成 font/hud.json：背景 glyph、E410-E41F 精确位移空格、每首歌 5 行文字 glyph、HUD 保留 glyph
 * 插件启动时与每次上传新歌后调用。
 */
public class TextAssetGenerator {

    /** GUI 背景 glyph（hud.json 中 height=166 ascent=7） */
    public static final char CHAR_BG = '\uE400';
    /** HUD 乐谱小图标（音符按钮图，8px） */
    public static final char CHAR_SHEET = '\uE100';
    /** 11 个乐器 HUD 图标基址（按 InstrumentType.ordinal，E101..E10B） */
    public static final char CHAR_INSTRUMENT_BASE = '\uE101';
    /** HUD 进度条填充/空白 */
    public static final char CHAR_BAR_FULL = '\uE10C';
    public static final char CHAR_BAR_EMPTY = '\uE10D';
    /** 歌名文字 glyph 基址：索引 = 歌曲序号*5+行号，0xE500+索引（超过 0xFF 自动进入 E600/E700 区间） */
    public static final char CHAR_TEXT_BASE = '\uE500';

    /** GUI 显示的行数 */
    public static final int ROWS = 5;

    /** 原版空格前进宽度（像素，与默认字体一致） */
    private static final int SPACE_ADVANCE = 3;
    /** 非 Latin-1 / 控制字符的替代字形 */
    private static final char FALLBACK_CHAR = '?';

    /**
     * paper.png 图标带中本插件用到的图标（id, u, v），坐标与原模组
     * ImmersiveMelodiesScreen / ImmersiveMelodiesFreePlayingScreen 完全一致。
     * 这是 GUI 图标的唯一配置源：贴图裁剪、模型 json、CraftEngineBridge 物品注册、
     * MelodyGui 槽位摆放全部引用此表，避免多处硬编码导致图标与功能错位。
     * u/v 即原模组 TexturedButtonWidget 直接 blit 的纹理坐标（paper.png 不翻转），
     * 已逐格核对与 1.21.1 原模组源码一致。
     */
    public record GuiIcon(String id, int u, int v) {
    }

    public static final List<GuiIcon> GUI_ICONS = List.of(
            new GuiIcon("close", 240, 0),      // 灰色回旋箭头：关闭
            new GuiIcon("tracks", 208, 16),    // 音符纸：曲目列表
            new GuiIcon("keyboard", 208, 0),   // 钢琴键盘：自由演奏
            new GuiIcon("pause", 224, 32),     // 双竖线：暂停
            new GuiIcon("play", 240, 32),      // 绿色三角：继续/演奏
            new GuiIcon("delete", 240, 16),    // 红色 X：删除（自由演奏界面也用作退出）
            new GuiIcon("upload", 208, 48),    // 绿色上传云：上传
            new GuiIcon("help", 208, 32),      // 黄色感叹号：打开帮助
            new GuiIcon("page_up", 224, 0),    // 灰菱形：上一页（原模组用滚轮翻页，服务端容器界面无滚轮事件，属插件适配控件）
            new GuiIcon("page_down", 224, 16)  // 黄星：下一页（同上，插件适配控件）
    );

    private final ImmersiveMelodiesPlugin plugin;
    /** 与 hud.json 字形一一对应的歌名与贴图宽度（MelodyGui 构建标题位移用） */
    private final List<String> glyphNames = new ArrayList<>();
    private final List<Integer> glyphWidths = new ArrayList<>();
    /** 原版 ascii.png 字符表（128x128，16x16 格、每格 8x8），首次生成时加载 */
    private BufferedImage asciiSheet;

    public TextAssetGenerator(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
    }

    // ===== 查询接口（MelodyGui 用） =====

    public synchronized int getMelodyCount() {
        return glyphNames.size();
    }

    /** 歌曲贴图宽度（即字形 advance）；未生成时返回 -1 */
    public synchronized int getGlyphWidth(int songIndex) {
        if (songIndex >= 0 && songIndex < glyphWidths.size()) {
            return glyphWidths.get(songIndex);
        }
        return -1;
    }

    /** 歌曲文字 glyph 字符（songIndex 为排序后索引，row=0..4） */
    public static char textChar(int songIndex, int row) {
        return (char) (CHAR_TEXT_BASE + songIndex * ROWS + row);
    }

    // ===== 生成 =====

    /**
     * 全量生成：静态图集 + 歌名字贴 + hud.json。可从任意线程调用（内部同步）。
     */
    public synchronized void generateAll() {
        File assetsRoot = assetsRoot();
        if (assetsRoot == null) {
            plugin.getLogger().warning("无法创建 CraftEngine 资源目录，跳过字体图集生成");
            return;
        }

        try {
            BufferedImage paper = loadPaper(assetsRoot);
            if (paper != null) {
                generateBackground(paper, assetsRoot);
                generateButtons(paper, assetsRoot);
                generateModelJsons(assetsRoot);
            } else {
                plugin.getLogger().warning("jar 内未找到 paper.png，跳过 GUI 背景与按钮图集生成");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("GUI 背景与按钮图集生成失败: " + e.getMessage());
        }

        try {
            generateTexts(assetsRoot);
        } catch (Exception e) {
            plugin.getLogger().warning("歌名字贴与 hud.json 生成失败: " + e.getMessage());
        }
    }

    private File assetsRoot() {
        File cePack = new File(new File(plugin.getDataFolder().getParentFile(), "CraftEngine"),
                "resources/immersive_melodies/resourcepack/assets/immersive_melodies");
        if (!cePack.isDirectory() && !cePack.mkdirs()) {
            return null;
        }
        return cePack;
    }

    private BufferedImage loadPaper(File assetsRoot) throws Exception {
        InputStream in = plugin.getResource("pack/assets/immersive_melodies/textures/gui/paper.png");
        if (in == null) {
            File fallback = new File(assetsRoot, "textures/gui/paper.png");
            if (fallback.isFile()) {
                in = new FileInputStream(fallback);
            }
        }
        if (in == null) {
            return null;
        }
        InputStream stream = in;
        try (stream) {
            return ImageIO.read(stream);
        }
    }

    // ===== 静态图集 =====

    /** 左侧 (0,0,192,192) 拉伸到 176x170 → textures/gui/chest_bg.png
     *  176=容器9列宽度，170 略大于 6 行容器高度(166)，配合 ascent=10 使羊皮纸背景
     *  垂直居中并完全覆盖箱子格子区域。 */
    private void generateBackground(BufferedImage paper, File assetsRoot) throws Exception {
        BufferedImage bg = new BufferedImage(176, 170, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(paper, 0, 0, 176, 170, 0, 0, 192, 192, null);
        g.dispose();
        savePng(bg, new File(assetsRoot, "textures/gui/chest_bg.png"));
    }

    /** 按 {@link #GUI_ICONS} 各裁 16x16 → textures/gui/btn_<id>.png */
    private void generateButtons(BufferedImage paper, File assetsRoot) throws Exception {
        for (GuiIcon icon : GUI_ICONS) {
            BufferedImage btn = paper.getSubimage(icon.u(), icon.v(), 16, 16);
            savePng(btn, new File(assetsRoot, "textures/gui/btn_" + icon.id() + ".png"));
        }
        // 清理改版前遗留的旧贴图（note/star/up/down/refresh/folder），避免资源包残留
        File guiDir = new File(assetsRoot, "textures/gui");
        File[] oldIcons = guiDir.listFiles((d, n) -> n.startsWith("btn_") && n.endsWith(".png")
                && GUI_ICONS.stream().noneMatch(i -> n.equals("btn_" + i.id() + ".png")));
        if (oldIcons != null) {
            for (File f : oldIcons) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** 按钮模型 json（models/gui/btn_<id>.json）与空模型（models/gui/empty.json，供 gui_blank 使用） */
    private void generateModelJsons(File assetsRoot) throws Exception {
        File modelDir = new File(assetsRoot, "models/gui");
        File[] oldModels = modelDir.listFiles((d, n) -> n.startsWith("btn_") && n.endsWith(".json")
                && GUI_ICONS.stream().noneMatch(i -> n.equals("btn_" + i.id() + ".json")));
        if (oldModels != null) {
            for (File f : oldModels) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        for (GuiIcon icon : GUI_ICONS) {
            String json = "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"immersive_melodies:gui/btn_" + icon.id() + "\"}}";
            writeUtf8(new File(assetsRoot, "models/gui/btn_" + icon.id() + ".json"), json);
        }
        writeUtf8(new File(assetsRoot, "models/gui/empty.json"), "{\"textures\":{},\"elements\":[]}");
    }

    // ===== 歌名字贴 + hud.json =====

    private void generateTexts(File assetsRoot) throws Exception {
        List<String> names = new ArrayList<>(plugin.getMelodyManager().getMelodyNames());
        names.sort(Comparator.naturalOrder());

        BufferedImage sheet = asciiSheet();

        // 字体 provider 的 file 会自动带 textures/ 前缀，故 PNG 输出到 textures/font/text/
        File textDir = new File(assetsRoot, "textures/font/text");
        //noinspection ResultOfMethodCallIgnored
        textDir.mkdirs();

        List<String> providers = new ArrayList<>();

        // a) 背景 glyph：铺满整个 54 格容器界面（176x170，ascent=10 垂直居中）
        providers.add("    {\"type\": \"bitmap\", \"file\": \"immersive_melodies:gui/chest_bg.png\", \"height\": 170, \"ascent\": 10, \"chars\": [\"\\uE400\"]}");

        // b) 精确位移空格：E410..E417 = +1..+128，E418..E41F = -1..-128
        StringBuilder space = new StringBuilder("    {\"type\": \"space\", \"advances\": {");
        for (int i = 0; i < 8; i++) {
            space.append("\"\\u").append(String.format("%04X", 0xE410 + i)).append("\": ").append(1 << i).append(", ");
        }
        for (int i = 0; i < 8; i++) {
            space.append("\"\\u").append(String.format("%04X", 0xE418 + i)).append("\": ").append(-(1 << i));
            if (i < 7) {
                space.append(", ");
            }
        }
        space.append("}}");
        providers.add(space.toString());

        // c) 每首歌 x 5 行的文字 glyph（同一贴图、不同 ascent）
        glyphNames.clear();
        glyphWidths.clear();
        for (int i = 0; i < names.size(); i++) {
            // 码位上限：0xE500+索引 不得超过 0xE7FF（约 153 首歌），超出则不生成
            if (0xE500 + (long) (i + 1) * ROWS - 1 > 0xE7FF) {
                plugin.getLogger().warning("旋律数量超出字形码位上限，仅生成前 " + i + " 首的文字贴图");
                break;
            }
            String name = names.get(i);
            BufferedImage img = renderText(sheet, name);
            ImageIO.write(img, "png", new File(textDir, "t" + i + ".png"));
            glyphNames.add(name);
            glyphWidths.add(img.getWidth());

            for (int row = 0; row < ROWS; row++) {
                int idx = i * ROWS + row;
                int ascent = -15 - 18 * row;
                providers.add("    {\"type\": \"bitmap\", \"file\": \"immersive_melodies:font/text/t" + i
                        + ".png\", \"height\": " + img.getHeight()
                        + ", \"ascent\": " + ascent
                        + ", \"chars\": [\"\\u" + String.format("%04X", CHAR_TEXT_BASE + idx) + "\"]}");
            }
        }

        // 清理多余的旧字贴
        File[] oldPngs = textDir.listFiles((dir, n) -> n.matches("t\\d+\\.png"));
        if (oldPngs != null) {
            for (File f : oldPngs) {
                try {
                    int idx = Integer.parseInt(f.getName().replaceAll("\\D", ""));
                    if (idx >= glyphNames.size()) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // d) HUD 保留 glyph：乐谱小图标、11 个乐器图标、进度条（演奏 HUD 仍在使用）
        providers.add("    {\"type\": \"bitmap\", \"file\": \"immersive_melodies:gui/btn_keyboard.png\", \"height\": 8, \"ascent\": 8, \"chars\": [\"\\uE100\"]}");
        for (InstrumentType type : InstrumentType.values()) {
            providers.add("    {\"type\": \"bitmap\", \"file\": \"immersive_melodies:item/icon/" + type.getId()
                    + ".png\", \"height\": 8, \"ascent\": 8, \"chars\": [\"\\u"
                    + String.format("%04X", CHAR_INSTRUMENT_BASE + type.ordinal()) + "\"]}");
        }
        providers.add("    {\"type\": \"bitmap\", \"file\": \"immersive_melodies:hud/bar_full.png\", \"height\": 8, \"ascent\": 8, \"chars\": [\"\\uE10C\"]}");
        providers.add("    {\"type\": \"bitmap\", \"file\": \"immersive_melodies:hud/bar_empty.png\", \"height\": 8, \"ascent\": 8, \"chars\": [\"\\uE10D\"]}");

        StringBuilder json = new StringBuilder("{\n  \"providers\": [\n");
        json.append(String.join(",\n", providers));
        json.append("\n  ]\n}\n");
        writeUtf8(new File(assetsRoot, "font/hud.json"), json.toString());

        plugin.getLogger().info("字体图集已生成: " + glyphNames.size() + " 首歌名字贴 -> " + textDir.getParent());
    }

    /** 从 jar 内加载原版 ascii.png 字符表（128x128，16x16 格、每格 8x8） */
    private BufferedImage asciiSheet() throws Exception {
        if (asciiSheet == null) {
            try (InputStream in = plugin.getResource("ascii.png")) {
                if (in == null) {
                    throw new IllegalStateException("jar 内缺少 ascii.png");
                }
                BufferedImage img = ImageIO.read(in);
                if (img == null || img.getWidth() != 128 || img.getHeight() != 128) {
                    throw new IllegalStateException("ascii.png 无效，应为 128x128 原版字符表");
                }
                asciiSheet = img;
            }
        }
        return asciiSheet;
    }

    /**
     * 用原版 ascii.png 字符表逐字符拼出旋律名：黑色像素、无描边，
     * 与原模组 MelodyListWidget 歌名颜色 0x404040（深灰）接近，在浅色羊皮纸背景上清晰可读。
     * 下划线按用户要求渲染为空格（仅留白 3px）；非 Latin-1 / 控制字符以 '?' 代替。
     * 贴图恒高 8（hud.json 中 height=8 时像素 1:1 显示），宽 = 各字符原版前进宽度之和，
     * 即该 bitmap 字形的实际 advance，MelodyGui 据此精确回位游标。
     */
    private BufferedImage renderText(BufferedImage sheet, String name) {
        int len = name.length();
        int[] advances = new int[len];
        int total = 0;
        for (int i = 0; i < len; i++) {
            advances[i] = charAdvance(sheet, name.charAt(i));
            total += advances[i];
        }

        BufferedImage img = new BufferedImage(Math.max(1, total), 8, BufferedImage.TYPE_INT_ARGB);
        int x = 0;
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            if (c != '_' && c != ' ') {
                if (c < 0x20 || c > 0xFF) {
                    c = FALLBACK_CHAR;
                }
                int sx = (c & 15) * 8;
                int sy = (c >> 4) * 8;
                // 复制该字符整格 8x8 的不透明像素，统一染成黑色（原版歌名深灰，这里用纯黑更清晰）
                for (int dx = 0; dx < 8; dx++) {
                    for (int y = 0; y < 8; y++) {
                        int argb = sheet.getRGB(sx + dx, sy + y);
                        if ((argb >>> 24) != 0 && x + dx < img.getWidth()) {
                            img.setRGB(x + dx, y, 0xFF000000);
                        }
                    }
                }
            }
            x += advances[i];
        }
        return img;
    }

    /**
     * 原版字符前进宽度（与 BitmapProvider 自动包围盒算法一致）：
     * 格内非空像素包围盒宽 + 1px 尾随间距；空格、下划线、控制字符与全空格为 3px。
     */
    private int charAdvance(BufferedImage sheet, char c) {
        if (c == ' ' || c == '_' || c < 0x20 || c > 0xFF) {
            return SPACE_ADVANCE;
        }
        int sx = (c & 15) * 8;
        int sy = (c >> 4) * 8;
        int left = 8;
        int right = -1;
        for (int dx = 0; dx < 8; dx++) {
            for (int y = 0; y < 8; y++) {
                if ((sheet.getRGB(sx + dx, sy + y) >>> 24) != 0) {
                    if (dx < left) {
                        left = dx;
                    }
                    right = dx;
                    break;
                }
            }
        }
        return right < 0 ? SPACE_ADVANCE : right - left + 2;
    }

    private void savePng(BufferedImage img, File out) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
    }

    private void writeUtf8(File out, String content) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        out.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }
}
