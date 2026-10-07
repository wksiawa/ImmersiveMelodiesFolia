package net.conczi.immersivemelodies.pack;

import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 资源包构建器：
 * 1. 将 jar 内 pack/ 下的资源（sounds、textures、font HUD 定义）释放到数据目录
 * 2. 打包为 plugins/ImmersiveMelodiesFolia/pack/ImmersiveMelodies-Pack.zip
 * 3. 若存在 CraftEngine，则同步复制到其资源包目录，由 CE 合并分发
 */
public class ResourcePackBuilder {

    private final ImmersiveMelodiesPlugin plugin;

    public ResourcePackBuilder(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
    }

    public void build() {
        File packRoot = new File(plugin.getDataFolder(), "pack");
        File extracted = new File(packRoot, "assets_src");

        // 枚举 jar 内 pack/ 目录并释放全部资源
        try {
            File jarFile = new File(plugin.getClass().getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.startsWith("pack/")) {
                        continue;
                    }
                    String relative = name.substring("pack/".length());
                    File out = new File(extracted, relative);
                    //noinspection ResultOfMethodCallIgnored
                    out.getParentFile().mkdirs();
                    try (InputStream in = jar.getInputStream(entry);
                         OutputStream os = new FileOutputStream(out)) {
                        in.transferTo(os);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("释放资源包文件失败: " + e.getMessage());
        }

        // 打成 zip
        File zip = new File(packRoot, "ImmersiveMelodies-Pack.zip");
        try {
            zipDirectory(extracted, zip);
            plugin.getLogger().info("资源包已生成: " + zip.getAbsolutePath());
        } catch (Exception e) {
            plugin.getLogger().warning("打包资源包失败: " + e.getMessage());
        }

        // 注入 CraftEngine 资源包目录
        injectIntoCraftEngine(extracted);
    }

    private void injectIntoCraftEngine(File extracted) {
        File ceDataFolder = new File(plugin.getDataFolder().getParentFile(), "CraftEngine");
        if (!ceDataFolder.isDirectory()) {
            return;
        }
        // 只注入 assets/，避免覆盖 CE 自身的 pack.mcmeta
        File assetsSrc = new File(extracted, "assets");
        if (!assetsSrc.isDirectory()) {
            return;
        }
        File cePack = new File(ceDataFolder, "resources/immersive_melodies/resourcepack/assets");
        try {
            copyDirectory(assetsSrc.toPath(), cePack.toPath());
            plugin.getLogger().info("已将资源包内容注入 CraftEngine，请执行 /ce reload all 以应用");
        } catch (Exception e) {
            plugin.getLogger().warning("注入 CraftEngine 资源包失败: " + e.getMessage());
        }
    }

    private void zipDirectory(File source, File target) throws Exception {
        List<File> files = new ArrayList<>();
        collectFiles(source, files);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(target))) {
            for (File file : files) {
                String entryName = source.toPath().relativize(file.toPath()).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(file.toPath(), zos);
                zos.closeEntry();
            }
        }
    }

    private void collectFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectFiles(child, out);
            } else {
                out.add(child);
            }
        }
    }

    private void copyDirectory(java.nio.file.Path source, java.nio.file.Path target) throws Exception {
        Files.walk(source).forEach(path -> {
            try {
                java.nio.file.Path dest = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(path, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
