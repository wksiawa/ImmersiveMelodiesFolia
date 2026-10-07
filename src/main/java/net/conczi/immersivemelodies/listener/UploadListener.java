package net.conczi.immersivemelodies.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.gui.MelodyGui;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import net.conczi.immersivemelodies.melody.Melody;
import net.conczi.immersivemelodies.melody.MidiParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上传自定义音乐：
 * 1. GUI 中点击上传按钮（col8 第 4 行）→ 关闭 GUI、提示粘贴 .mid/.midi 直链并记入 pending
 * 2. 监听 AsyncChatEvent（纯文本消息）：异步下载 → MidiParser 解析 → 保存 uploads/ 并注册 MelodyManager
 * 3. 重新生成字贴与 hud.json → ce reload all → 提示成功并重开 GUI；60 秒未发送自动失效
 */
public class UploadListener implements Listener {

    private static final long TIMEOUT_MS = 60_000L;

    /** 等待中的上传会话（时间戳 + 进入前的乐器类型） */
    private record Pending(long time, InstrumentType instrument) {}

    private final ImmersiveMelodiesPlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public UploadListener(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
    }

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
        startUploadMode(player, holder.instrument());
    }

    private void startUploadMode(Player player, InstrumentType instrument) {
        player.closeInventory();
        player.sendMessage(Component.text("请在聊天栏粘贴 .mid/.midi 文件直链 (60秒内)", NamedTextColor.YELLOW));
        UUID id = player.getUniqueId();
        pending.put(id, new Pending(System.currentTimeMillis(), instrument));
        // 60 秒超时自动移除
        player.getScheduler().runDelayed(plugin, task -> {
            Pending p = pending.get(id);
            if (p != null && System.currentTimeMillis() - p.time() >= TIMEOUT_MS) {
                pending.remove(id);
                player.sendMessage(Component.text("上传会话已超时", NamedTextColor.RED));
            }
        }, null, TIMEOUT_MS / 50L);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Pending p = pending.remove(id);
        if (p == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (System.currentTimeMillis() - p.time() > TIMEOUT_MS) {
            player.sendMessage(Component.text("上传会话已超时", NamedTextColor.RED));
            return;
        }
        String url = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        // 异步下载与解析（Folia AsyncScheduler，不阻塞任何区域线程）
        Bukkit.getAsyncScheduler().runNow(plugin, task -> handleUpload(player, url, p.instrument()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    private void handleUpload(Player player, String urlText, InstrumentType instrument) {
        try {
            URI uri = URI.create(urlText);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("仅支持 http/https 文件直链");
            }

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "ImmersiveMelodiesFolia")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("下载失败 HTTP " + response.statusCode());
            }

            // 从 URL 提取安全文件名
            String path = uri.getPath();
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            String safeName = fileName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
            if (safeName.isBlank() || safeName.equals("_")) {
                safeName = "upload_" + System.currentTimeMillis();
            }
            if (!safeName.toLowerCase(Locale.ROOT).endsWith(".mid") && !safeName.toLowerCase(Locale.ROOT).endsWith(".midi")) {
                safeName = safeName + ".mid";
            }
            String melodyName = safeName.replaceAll("(?i)\\.(mid|midi)$", "");

            Melody melody = MidiParser.parseMidi(new ByteArrayInputStream(response.body()), melodyName);

            File uploads = new File(plugin.getDataFolder(), "uploads");
            //noinspection ResultOfMethodCallIgnored
            uploads.mkdirs();
            File out = new File(uploads, safeName);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(response.body());
            }

            plugin.getMelodyManager().registerMelody(melodyName, melody);
            // 重新生成该歌字贴 + 完整 hud.json
            plugin.getTextAssetGenerator().generateAll();

            // CE 已加载时重载，使新字贴与 hud.json 进入资源包
            if (Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
                Bukkit.getGlobalRegionScheduler().run(plugin, task ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ce reload all"));
            }

            player.sendMessage(Component.text("上传成功，已注册旋律: " + melodyName, NamedTextColor.GREEN));
            // 回玩家所在区域线程重开 GUI
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) {
                    plugin.getMelodyGui().open(player, instrument, 0);
                }
            }, null);
        } catch (Exception e) {
            plugin.getLogger().warning("上传旋律失败(" + player.getName() + "): " + e.getMessage());
            player.sendMessage(Component.text("上传失败: " + e.getMessage(), NamedTextColor.RED));
        }
    }
}
