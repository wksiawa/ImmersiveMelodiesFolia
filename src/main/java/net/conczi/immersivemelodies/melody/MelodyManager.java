package net.conczi.immersivemelodies.melody;

import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 服务端旋律管理：从 plugins/ImmersiveMelodiesFolia/melodies 与 uploads 目录加载 .mid/.midi 文件。
 * uploads 目录存放玩家通过 GUI 上传的自定义旋律（可在运行时动态注册）。
 */
public class MelodyManager {

    private final ImmersiveMelodiesPlugin plugin;
    private final File melodyDir;
    private final File uploadDir;
    private final Map<String, Melody> melodies = new ConcurrentHashMap<>();

    public MelodyManager(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
        this.melodyDir = new File(plugin.getDataFolder(), "melodies");
        this.uploadDir = new File(plugin.getDataFolder(), "uploads");
        //noinspection ResultOfMethodCallIgnored
        melodyDir.mkdirs();
    }

    public void loadAll() {
        melodies.clear();
        unpackBundledMelodies();
        loadDir(melodyDir);
        loadDir(uploadDir);
    }

    private void loadDir(File dir) {
        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".mid") || lower.endsWith(".midi");
        });
        if (files == null) {
            return;
        }

        for (File file : files) {
            String id = file.getName().replaceAll("(?i)\\.(mid|midi)$", "");
            try (InputStream in = new FileInputStream(file)) {
                melodies.put(id.toLowerCase(Locale.ROOT), MidiParser.parseMidi(in, id));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "无法解析旋律文件 " + file.getName() + "，已跳过", e);
            }
        }
    }

    /**
     * 首次启动时释放 jar 内置的全部旋律（枚举 jar 内 melodies/ 目录）。
     */
    private void unpackBundledMelodies() {
        File[] existing = melodyDir.listFiles();
        if (existing != null && existing.length > 0) {
            return;
        }
        try {
            File jarFile = new File(plugin.getClass().getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.startsWith("melodies/")
                            || !(name.endsWith(".midi") || name.endsWith(".mid"))) {
                        continue;
                    }
                    String fileName = name.substring("melodies/".length());
                    try (InputStream in = jar.getInputStream(entry)) {
                        java.nio.file.Files.copy(in, new File(melodyDir, fileName).toPath());
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "释放内置旋律失败", e);
        }
    }

    public Map<String, Melody> getMelodies() {
        return Collections.unmodifiableMap(melodies);
    }

    public List<String> getMelodyNames() {
        return new ArrayList<>(melodies.keySet());
    }

    public Melody getMelody(String name) {
        return melodies.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean hasMelody(String name) {
        return melodies.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /**
     * 运行时动态注册旋律（上传自定义音乐时调用，线程安全）。
     * 若同名旋律已存在则覆盖。
     */
    public void registerMelody(String name, Melody melody) {
        melodies.put(name.toLowerCase(Locale.ROOT), melody);
    }

    /**
     * 该旋律是否为玩家上传（对应原模组 player 命名空间，可被删除）。
     * 内置旋律在 melodies/ 目录，永远不可删。
     */
    public boolean isUploaded(String name) {
        if (name == null) {
            return false;
        }
        String id = name.toLowerCase(Locale.ROOT);
        File[] files = uploadDir.listFiles((d, n) ->
                n.toLowerCase(Locale.ROOT).equals(id + ".mid")
                        || n.toLowerCase(Locale.ROOT).equals(id + ".midi"));
        return files != null && files.length > 0;
    }

    /**
     * 删除玩家上传的旋律：注销内存记录并删除 uploads/ 中的文件。
     * 内置旋律或不存在时返回 false。调用方需先做权限校验。
     */
    public boolean deleteMelody(String name) {
        if (!isUploaded(name)) {
            return false;
        }
        String id = name.toLowerCase(Locale.ROOT);
        melodies.remove(id);
        File[] files = uploadDir.listFiles((d, n) ->
                n.toLowerCase(Locale.ROOT).equals(id + ".mid")
                        || n.toLowerCase(Locale.ROOT).equals(id + ".midi"));
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        return true;
    }
}
