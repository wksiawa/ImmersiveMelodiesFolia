package net.conczi.immersivemelodies.play;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import net.conczi.immersivemelodies.melody.Melody;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 管理所有玩家的演奏会话。
 * 每个会话挂在玩家的 EntityScheduler 上，天然满足 Folia 区域线程要求。
 */
public class PlaybackManager {

    private final ImmersiveMelodiesPlugin plugin;
    private final Map<UUID, PlaybackSession> sessions = new ConcurrentHashMap<>();

    public PlaybackManager(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 让玩家开始演奏（全部曲目启用）。若已在演奏则先停止旧会话。
     */
    public PlaybackSession start(Player player, InstrumentType instrument, Melody melody) {
        return start(player, instrument, melody, null);
    }

    /**
     * 让玩家开始演奏，可指定启用的曲目序号集合（空 = 全部启用，与原模组一致）。
     */
    public PlaybackSession start(Player player, InstrumentType instrument, Melody melody,
                                 Collection<Integer> enabledTracks) {
        stop(player);

        PlaybackSession session = new PlaybackSession(plugin, player, instrument, melody, enabledTracks);
        sessions.put(player.getUniqueId(), session);
        // 曲终自然结束：移除会话、关闭 HUD、复位双手乐器的演奏中标记（与原模组曲终状态复位一致）
        session.setOnEnd(() -> {
            sessions.remove(player.getUniqueId(), session);
            plugin.getHudManager().stop(player.getUniqueId());
            if (player.isOnline()) {
                resetPlayingFlags(player);
            }
        });

        Consumer<ScheduledTask> tickTask = task -> session.tick();
        ScheduledTask task = player.getScheduler().runAtFixedRate(
                plugin,
                tickTask,
                null,
                1L,
                1L
        );
        if (task == null) {
            // 玩家已离线等情况
            sessions.remove(player.getUniqueId());
            session.stop();
            return null;
        }
        session.setTask(task);

        plugin.getHudManager().start(session);
        // 合奏：让附近持有乐器的生物加入玩家演奏（对齐起始时间）
        plugin.getMobPlaybackManager().joinPlayerEnsemble(player, melody, session.getStartEpochMs());
        return session;
    }

    public void stop(Player player) {
        PlaybackSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.stop();
            plugin.getHudManager().stop(player.getUniqueId());
        }
    }

    /** 复位主/副手乐器物品的“演奏中”PDC 标记 */
    private static void resetPlayingFlags(Player player) {
        org.bukkit.inventory.ItemStack main = player.getInventory().getItemInMainHand();
        if (net.conczi.immersivemelodies.instrument.InstrumentItems.isInstrument(main)) {
            net.conczi.immersivemelodies.instrument.InstrumentItems.setPlaying(main, false);
        }
        org.bukkit.inventory.ItemStack off = player.getInventory().getItemInOffHand();
        if (net.conczi.immersivemelodies.instrument.InstrumentItems.isInstrument(off)) {
            net.conczi.immersivemelodies.instrument.InstrumentItems.setPlaying(off, false);
        }
    }

    public void stopAll() {
        for (UUID uuid : sessions.keySet()) {
            PlaybackSession session = sessions.remove(uuid);
            if (session != null) {
                session.stop();
            }
        }
        plugin.getHudManager().stopAll();
    }

    public PlaybackSession getSession(Player player) {
        return sessions.get(player.getUniqueId());
    }

    /** 是否存在会话（暂停中的会话也算，用于右键乐器=停止的判定） */
    public boolean hasSession(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public boolean isPlaying(Player player) {
        PlaybackSession session = sessions.get(player.getUniqueId());
        return session != null && session.isPlaying();
    }

    public boolean isPaused(Player player) {
        PlaybackSession session = sessions.get(player.getUniqueId());
        return session != null && session.isPaused();
    }

    /** 暂停当前会话（时间轴冻结）；无会话时无效 */
    public void pause(Player player) {
        PlaybackSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.pause();
        }
    }

    /** 从暂停位置继续；无会话时无效 */
    public void resume(Player player) {
        PlaybackSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.resume();
        }
    }
}
