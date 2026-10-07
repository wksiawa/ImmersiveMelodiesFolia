package net.conczi.immersivemelodies.play;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import net.conczi.immersivemelodies.melody.Melody;
import org.bukkit.entity.Player;

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
     * 让玩家开始演奏。若已在演奏则先停止旧会话。
     */
    public PlaybackSession start(Player player, InstrumentType instrument, Melody melody) {
        stop(player);

        PlaybackSession session = new PlaybackSession(plugin, player, instrument, melody);
        sessions.put(player.getUniqueId(), session);

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
        return session;
    }

    public void stop(Player player) {
        PlaybackSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.stop();
            plugin.getHudManager().stop(player.getUniqueId());
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

    public boolean isPlaying(Player player) {
        PlaybackSession session = sessions.get(player.getUniqueId());
        return session != null && session.isPlaying();
    }
}
