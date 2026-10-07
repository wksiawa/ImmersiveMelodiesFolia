package net.conczi.immersivemelodies.text;

import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentType;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
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
 * - 用 AWT（headless 可用）把每个旋律名渲染为白字黑描边的透明 PNG（font/text/t<i>.png），按名字宽度裁剪
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

    private static final Font TEXT_FONT = new Font("Microsoft YaHei", Font.PLAIN, 9);

    private static final String[] BUTTON_NAMES = {
            "note", "star", "close", "up", "down", "play", "upload", "refresh", "folder"
    };
    private static final int[][] BUTTON_XY = {
            {208, 0}, {224, 0}, {240, 0},
            {208, 16}, {224, 16}, {240, 16},
            {208, 32}, {224, 32}, {240, 32}
    };

    private final ImmersiveMelodiesPlugin plugin;
    /** 与 hud.json 字形一一对应的歌名与贴图宽度（MelodyGui 构建标题位移用） */
    private final List<String> glyphNames = new ArrayList<>();
    private final List<Integer> glyphWidths = new ArrayList<>();

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

    /** 左侧 (0,0,192,192) 拉伸到 176x166 → textures/gui/chest_bg.png */
    private void generateBackground(BufferedImage paper, File assetsRoot) throws Exception {
        BufferedImage bg = new BufferedImage(176, 166, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(paper, 0, 0, 176, 166, 0, 0, 192, 192, null);
        g.dispose();
        savePng(bg, new File(assetsRoot, "textures/gui/chest_bg.png"));
    }

    /** 9 个按钮图标各裁 16x16 → textures/gui/btn_<name>.png */
    private void generateButtons(BufferedImage paper, File assetsRoot) throws Exception {
        for (int i = 0; i < BUTTON_NAMES.length; i++) {
            int x = BUTTON_XY[i][0];
            int y = BUTTON_XY[i][1];
            BufferedImage btn = paper.getSubimage(x, y, 16, 16);
            savePng(btn, new File(assetsRoot, "textures/gui/btn_" + BUTTON_NAMES[i] + ".png"));
        }
    }

    /** 按钮模型 json（models/gui/btn_<name>.json）与空模型（models/gui/empty.json，供 gui_blank 使用） */
    private void generateModelJsons(File assetsRoot) throws Exception {
        for (String name : BUTTON_NAMES) {
            String json = "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"immersive_melodies:gui/btn_" + name + "\"}}";
            writeUtf8(new File(assetsRoot, "models/gui/btn_" + name + ".json"), json);
        }
        writeUtf8(new File(assetsRoot, "models/gui/empty.json"), "{\"textures\":{},\"elements\":[]}");
    }

    // ===== 歌名字贴 + hud.json =====

    private void generateTexts(File assetsRoot) throws Exception {
        List<String> names = new ArrayList<>(plugin.getMelodyManager().getMelodyNames());
        names.sort(Comparator.naturalOrder());

        // 字体 provider 的 file 会自动带 textures/ 前缀，故 PNG 输出到 textures/font/text/
        File textDir = new File(assetsRoot, "textures/font/text");
        //noinspection ResultOfMethodCallIgnored
        textDir.mkdirs();

        List<String> providers = new ArrayList<>();

        // a) 背景 glyph：铺满整个 54 格容器界面（176x166）
        providers.add("    {\"type\": \"bitmap\", \"file\": \"immersive_melodies:gui/chest_bg.png\", \"height\": 166, \"ascent\": 7, \"chars\": [\"\\uE400\"]}");

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
            BufferedImage img = renderText(name);
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
        providers.add("    {\"type\": \"bitmap\", \"file\": \"immersive_melodies:gui/btn_note.png\", \"height\": 8, \"ascent\": 8, \"chars\": [\"\\uE100\"]}");
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

    /**
     * 把旋律名渲染为透明底 PNG：白字 + 上下左右 1px 黑描边，按实际内容宽度裁剪。
     */
    private BufferedImage renderText(String name) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(TEXT_FONT);
        FontMetrics fm = pg.getFontMetrics();
        int w = Math.max(4, fm.stringWidth(name) + 4);
        int h = fm.getHeight() + 4;
        pg.dispose();

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setFont(TEXT_FONT);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        int x = 2;
        int y = fm.getAscent() + 2;
        g.setColor(Color.BLACK);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 || dy != 0) {
                    g.drawString(name, x + dx, y + dy);
                }
            }
        }
        g.setColor(Color.WHITE);
        g.drawString(name, x, y);
        g.dispose();
        return trim(img);
    }

    /** 裁剪到非透明像素的包围盒（贴图宽即字形 advance） */
    private BufferedImage trim(BufferedImage img) {
        int minX = img.getWidth(), minY = img.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) != 0) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        }
        return img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
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
