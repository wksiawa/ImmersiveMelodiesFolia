package net.conczi.immersivemelodies.mob;

import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentItems;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import net.conczi.immersivemelodies.melody.Melody;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理所有生物的演奏会话，支持合奏（附近同曲目演奏者对齐起始时间）。
 * 对应原模组 InstrumentItem.inventoryServerTick 的自动演奏 + 合奏逻辑。
 */
public class MobPlaybackManager {

    private final ImmersiveMelodiesPlugin plugin;
    private final Map<UUID, MobPlaybackSession> sessions = new ConcurrentHashMap<>();

    public MobPlaybackManager(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 让生物开始演奏指定旋律。
     * 合奏：搜索 mob-ensemble-radius 格内正在演奏同一旋律的生物/玩家，
     * 若存在则复用其 startEpochMs，实现同步合奏（与原模组一致）。
     */
    public MobPlaybackSession start(LivingEntity entity, InstrumentType instrument, Melody melody) {
        return start(entity, instrument, melody, null);
    }

    public MobPlaybackSession start(LivingEntity entity, InstrumentType instrument, Melody melody,
                                    Collection<Integer> enabledTracks) {
        stop(entity);

        long startEpochMs = findEnsembleStartTime(entity, melody);
        MobPlaybackSession session = new MobPlaybackSession(
                plugin, entity, instrument, melody, startEpochMs, enabledTracks);
        sessions.put(entity.getUniqueId(), session);

        var task = entity.getScheduler().runAtFixedRate(plugin, t -> session.tick(), null, 1L, 1L);
        if (task == null) {
            sessions.remove(entity.getUniqueId());
            return null;
        }
        session.setTask(task);
        return session;
    }

    /** 让生物演奏随机旋律（原模组怪物自动演奏行为） */
    public MobPlaybackSession startRandom(LivingEntity entity, InstrumentType instrument) {
        List<String> names = plugin.getMelodyManager().getMelodyNames();
        if (names.isEmpty()) {
            return null;
        }
        String name = names.get((int) (Math.random() * names.size()));
        Melody melody = plugin.getMelodyManager().getMelody(name);
        if (melody == null) {
            return null;
        }
        return start(entity, instrument, melody);
    }

    public void stop(LivingEntity entity) {
        MobPlaybackSession session = sessions.remove(entity.getUniqueId());
        if (session != null) {
            session.stop();
        }
    }

    public void stopAll() {
        for (MobPlaybackSession session : sessions.values()) {
            session.stop();
        }
        sessions.clear();
    }

    public MobPlaybackSession getSession(LivingEntity entity) {
        return sessions.get(entity.getUniqueId());
    }

    public boolean isPlaying(LivingEntity entity) {
        MobPlaybackSession session = sessions.get(entity.getUniqueId());
        return session != null && session.isActive();
    }

    /**
     * 合奏时间对齐：搜索附近正在演奏同一旋律的生物或玩家，返回其起始时间；
     * 找不到则返回当前时间。
     */
    private long findEnsembleStartTime(LivingEntity entity, Melody melody) {
        double radius = plugin.getConfig().getDouble("mob-ensemble-radius", 32);
        long now = System.currentTimeMillis();

        // 搜索附近生物会话
        for (MobPlaybackSession other : sessions.values()) {
            if (!other.isActive() || other.getEntity() == entity) {
                continue;
            }
            if (!other.getMelody().getName().equals(melody.getName())) {
                continue;
            }
            if (other.getEntity().getWorld().equals(entity.getWorld())
                    && other.getEntity().getLocation().distance(entity.getLocation()) <= radius) {
                return other.getStartEpochMs();
            }
        }

        // 搜索附近玩家会话
        for (Player player : entity.getWorld().getPlayers()) {
            if (player.getLocation().distance(entity.getLocation()) > radius) {
                continue;
            }
            var playerSession = plugin.getPlaybackManager().getSession(player);
            if (playerSession != null && playerSession.isActive()
                    && playerSession.getMelody().getName().equals(melody.getName())) {
                return playerSession.getStartEpochMs();
            }
        }

        return now;
    }

    /**
     * 尝试让附近持有乐器的生物加入玩家的合奏（玩家开始/继续演奏时调用）。
     * 搜索 mob-ensemble-radius 格内持有同旋律乐器的生物，启动其演奏并对齐时间。
     */
    public void joinPlayerEnsemble(Player player, Melody melody, long playerStartEpochMs) {
        double radius = plugin.getConfig().getDouble("mob-ensemble-radius", 32);
        for (LivingEntity nearby : player.getWorld().getNearbyLivingEntities(player.getLocation(), radius)) {
            if (nearby instanceof Player) {
                continue;
            }
            ItemStack hand = nearby.getEquipment() != null
                    ? nearby.getEquipment().getItemInMainHand() : null;
            if (!InstrumentItems.isInstrument(hand)) {
                continue;
            }
            if (isPlaying(nearby)) {
                continue;
            }
            InstrumentType type = InstrumentItems.getInstrument(hand);
            if (type == null) {
                continue;
            }
            // 生物持有的乐器绑定了某旋律时，仅在同曲时合奏；未绑定则直接合奏玩家旋律
            String bound = InstrumentItems.getMelody(hand);
            if (bound != null && !bound.equals(melody.getName())) {
                continue;
            }
            MobPlaybackSession session = new MobPlaybackSession(
                    plugin, nearby, type, melody, playerStartEpochMs,
                    InstrumentItems.getEnabledTracks(hand));
            sessions.put(nearby.getUniqueId(), session);
            var task = nearby.getScheduler().runAtFixedRate(plugin, t -> session.tick(), null, 1L, 1L);
            if (task != null) {
                session.setTask(task);
            }
        }
    }
}
