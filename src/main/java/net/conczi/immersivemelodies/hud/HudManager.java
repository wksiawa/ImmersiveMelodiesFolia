package net.conczi.immersivemelodies.hud;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import net.conczi.immersivemelodies.melody.Melody;
import net.conczi.immersivemelodies.play.PlaybackSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 演奏 HUD：通过资源包中的自定义字体（assets/immersive_melodies/font/hud.json）
 * 在 ActionBar 显示乐器图标、旋律名和进度条。
 */
public class HudManager {

    /** HUD 字体键，与资源包 font/hud.json 对应 */
    public static final Key HUD_FONT = Key.key("immersive_melodies", "hud");

    /** 每种乐器对应的 HUD 图标字符（PUA 区，与资源包字体定义一致） */
    private static final char ICON_BASE = '\uE101';

    /** 乐谱图标字符（模组原版 gui/paper.png） */
    private static final char ICON_SHEET = '\uE100';

    /** 进度条填充/空白字符（资源包字体中的位图字符） */
    private static final char BAR_FULL = '\uE10C';
    private static final char BAR_EMPTY = '\uE10D';

    private static final int BAR_SEGMENTS = 20;

    private final ImmersiveMelodiesPlugin plugin;
    private final Map<UUID, ScheduledTask> hudTasks = new ConcurrentHashMap<>();

    public HudManager(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 为演奏会话启动 HUD 刷新任务（每 5 tick）。
     */
    public void start(PlaybackSession session) {
        Player player = session.getPlayer();
        stop(player.getUniqueId());

        Consumer<ScheduledTask> consumer = task -> {
            // 暂停时 HUD 不关闭，进度条冻结在暂停位置；仅 stop/播完/离线才结束
            if (!session.isActive() || !player.isOnline()) {
                stop(player.getUniqueId());
                return;
            }
            player.sendActionBar(buildHud(session));
        };
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, consumer, null, 1L, 5L);
        if (task != null) {
            hudTasks.put(player.getUniqueId(), task);
        }
    }

    public void stop(UUID playerUuid) {
        ScheduledTask task = hudTasks.remove(playerUuid);
        if (task != null) {
            task.cancel();
        }
    }

    public void stopAll() {
        for (ScheduledTask task : hudTasks.values()) {
            task.cancel();
        }
        hudTasks.clear();
    }

    private Component buildHud(PlaybackSession session) {
        Melody melody = session.getMelody();
        InstrumentType instrument = session.getInstrument();

        long elapsed = session.getElapsedMs();
        int total = Math.max(1, melody.getLength());
        double progress = Math.min(1.0, elapsed / (double) total);

        // 乐谱图标 + 乐器图标（资源包字体位图，均为模组原版贴图）
        Component icon = Component.text(ICON_SHEET).font(HUD_FONT)
                .append(Component.text(String.valueOf((char) (ICON_BASE + instrument.ordinal()))).font(HUD_FONT));

        // 歌名用默认字体显示（原版不修饰），下划线显示为空格
        Component name = Component.text(" " + melody.getDisplayName() + " ", NamedTextColor.GOLD);

        // 进度条
        int filled = (int) Math.round(progress * BAR_SEGMENTS);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < BAR_SEGMENTS; i++) {
            bar.append(i < filled ? BAR_FULL : BAR_EMPTY);
        }
        Component progressBar = Component.text(bar.toString())
                .font(HUD_FONT)
                .color(TextColor.color(0x55FF55));

        Component time = Component.text(
                " " + formatTime(elapsed) + "/" + formatTime(total),
                NamedTextColor.GRAY
        );

        return Component.empty().append(icon).append(name).append(progressBar).append(time);
    }

    private static String formatTime(long ms) {
        long seconds = ms / 1000;
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }
}
