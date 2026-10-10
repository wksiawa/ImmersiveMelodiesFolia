package net.conczi.immersivemelodies.mob;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import net.conczi.immersivemelodies.melody.Melody;
import net.conczi.immersivemelodies.melody.Note;
import net.conczi.immersivemelodies.melody.Track;
import net.conczi.immersivemelodies.play.InstrumentSounds;
import org.bukkit.entity.LivingEntity;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单个生物的演奏会话。
 * 与玩家 PlaybackSession 算法一致，但驱动实体为任意 LivingEntity，
 * 并定期 swingMainHand() 模拟演奏时的手部动作（服务端无法做骨骼动画，此为可行替代）。
 * 合奏：构造时可指定共享的 startEpochMs，使多个生物/玩家同步演奏同一旋律。
 */
public class MobPlaybackSession {

    private static final long MAX_LATE_NOTE_TIME = 150L;

    private final ImmersiveMelodiesPlugin plugin;
    private final LivingEntity entity;
    private final InstrumentType instrument;
    private final Melody melody;
    private final int[] lastIndices;
    private final Set<Integer> enabledTracks;

    private long startEpochMs;
    private volatile boolean active = true;
    private ScheduledTask task;

    public MobPlaybackSession(ImmersiveMelodiesPlugin plugin, LivingEntity entity,
                              InstrumentType instrument, Melody melody,
                              long startEpochMs, Collection<Integer> enabledTracks) {
        this.plugin = plugin;
        this.entity = entity;
        this.instrument = instrument;
        this.melody = melody;
        this.lastIndices = new int[melody.getTracks().size()];
        this.startEpochMs = startEpochMs;
        this.enabledTracks = enabledTracks == null ? new HashSet<>() : new HashSet<>(enabledTracks);
    }

    public LivingEntity getEntity() {
        return entity;
    }

    public Melody getMelody() {
        return melody;
    }

    public long getStartEpochMs() {
        return startEpochMs;
    }

    public boolean isActive() {
        return active;
    }

    void setTask(ScheduledTask task) {
        this.task = task;
    }

    void tick() {
        if (!active) {
            return;
        }
        if (!entity.isValid() || entity.isDead()) {
            stop();
            return;
        }

        long time = System.currentTimeMillis() - startEpochMs;
        long lookAhead = Math.max(0, plugin.getConfig().getInt("humanization-time", 20));

        boolean anyNoteRemaining = false;
        List<Track> tracks = melody.getTracks();
        for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
            boolean trackEnabled = enabledTracks.isEmpty() || enabledTracks.contains(trackIndex);
            List<Note> notes = tracks.get(trackIndex).getNotes();
            int i = lastIndices[trackIndex];
            while (i < notes.size()) {
                Note note = notes.get(i);
                if (time - note.getTime() > MAX_LATE_NOTE_TIME) {
                    i++;
                    continue;
                }
                if (time + lookAhead >= note.getTime()) {
                    if (trackEnabled) {
                        InstrumentSounds.playNote(plugin, entity, instrument, note);
                    }
                    i++;
                } else {
                    break;
                }
            }
            lastIndices[trackIndex] = i;
            if (i < notes.size()) {
                anyNoteRemaining = true;
            }
        }

        // 每 6 tick 摆动主手，模拟演奏动作（服务端动画替代）
        if (time % 300 < 50) {
            entity.swingMainHand();
        }

        if (!anyNoteRemaining) {
            stop();
        }
    }

    public void stop() {
        active = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
