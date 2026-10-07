package net.conczi.immersivemelodies.play;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import net.conczi.immersivemelodies.melody.Melody;
import net.conczi.immersivemelodies.melody.Note;
import net.conczi.immersivemelodies.melody.Track;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.joml.Vector3f;

import java.util.List;
import java.util.Random;

/**
 * 单个玩家的演奏会话。
 * 使用 Folia 的 EntityScheduler 每 tick 驱动，时间轴为真实毫秒。
 */
public class PlaybackSession {

    /** 迟到超过该时间的音符直接跳过（毫秒），与原模组一致 */
    private static final long MAX_LATE_NOTE_TIME = 150L;

    private final ImmersiveMelodiesPlugin plugin;
    private final Player player;
    private final InstrumentType instrument;
    private final Melody melody;
    private final Random random = new Random();

    private final int[] lastIndices;
    private final long startEpochMs;

    private volatile boolean playing = true;
    private ScheduledTask task;

    public PlaybackSession(ImmersiveMelodiesPlugin plugin, Player player, InstrumentType instrument, Melody melody) {
        this.plugin = plugin;
        this.player = player;
        this.instrument = instrument;
        this.melody = melody;
        this.lastIndices = new int[melody.getTracks().size()];
        this.startEpochMs = System.currentTimeMillis();
    }

    public Player getPlayer() {
        return player;
    }

    public Melody getMelody() {
        return melody;
    }

    public InstrumentType getInstrument() {
        return instrument;
    }

    public long getStartEpochMs() {
        return startEpochMs;
    }

    public boolean isPlaying() {
        return playing;
    }

    public long getElapsedMs() {
        return playing ? System.currentTimeMillis() - startEpochMs : 0;
    }

    void setTask(ScheduledTask task) {
        this.task = task;
    }

    /**
     * 每 tick 调用（在玩家所在区域线程上）。
     */
    void tick() {
        if (!playing) {
            return;
        }
        if (!player.isOnline()) {
            stop();
            return;
        }

        long time = getElapsedMs();
        long lookAhead = Math.max(0, plugin.getConfig().getInt("humanization-time", 20));

        boolean anyNoteRemaining = false;
        List<Track> tracks = melody.getTracks();
        for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
            List<Note> notes = tracks.get(trackIndex).getNotes();
            int i = lastIndices[trackIndex];
            while (i < notes.size()) {
                Note note = notes.get(i);
                if (time - note.getTime() > MAX_LATE_NOTE_TIME) {
                    // 已错过，跳过
                    i++;
                    continue;
                }
                if (time + lookAhead >= note.getTime()) {
                    playNote(player, note, time);
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

        if (!anyNoteRemaining) {
            // 旋律播放完毕
            stop();
        }
    }

    /**
     * 播放单个音符，移植自原模组 InstrumentItem.playNote 的音量/音高算法。
     */
    private void playNote(Player entity, Note note, long time) {
        double instrumentVolumeFactor = plugin.getConfig().getDouble("instrument-volume-factor", 1.0);
        double perceivedLoudnessFactor = plugin.getConfig().getDouble("perceived-loudness-adjustment-factor", 0.5);
        double humanizationVolume = plugin.getConfig().getDouble("humanization-volume", 0.06);
        double humanizationPitch = plugin.getConfig().getDouble("humanization-pitch", 0.02);
        double rangeFactor = plugin.getConfig().getDouble("range-factor", 2.0);

        double volume = note.getVelocity() / 255.0 * 2.0 * instrumentVolumeFactor;
        double pitch = Math.pow(2, (note.getNote() - 24) / 12.0);

        // 人性化扰动
        volume = Math.max(0.0, volume * (1.0 + boundedGaussian(humanizationVolume)));
        pitch *= Math.pow(2.0, boundedGaussian(humanizationPitch) / 12.0);

        // 映射到 8 个八度采样
        int octave = 1;
        while (octave < 8 && pitch > 4.0 / 3.0) {
            pitch /= 2;
            octave++;
        }

        // 按感知响度调整音量
        double adjustedVolume = volume / Math.sqrt(pitch * Math.pow(2, octave - 4));
        volume = volume * (1.0 - perceivedLoudnessFactor) + adjustedVolume * perceivedLoudnessFactor;
        volume = Math.max(0.0, volume);

        Location loc = entity.getLocation();
        entity.getWorld().playSound(
                loc,
                instrument.getSoundName(octave),
                SoundCategory.RECORDS,
                (float) (volume * rangeFactor),
                (float) pitch
        );

        // 音符粒子（与原模组相同的偏移计算）
        Vector3f offset = instrument.getParticleOffset();
        double yawRad = Math.toRadians(-entity.getBodyYaw());
        double x = Math.sin(yawRad);
        double z = Math.cos(yawRad);
        double px = entity.getX() + x * offset.z + z * offset.x;
        double py = entity.getY() + entity.getHeight() / 2.0 + offset.y;
        double pz = entity.getZ() + z * offset.z - x * offset.x;
        entity.getWorld().spawnParticle(Particle.NOTE, px, py, pz, 1);
    }

    public void stop() {
        playing = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private double boundedGaussian(double maxAbs) {
        if (maxAbs <= 0.0) {
            return 0.0;
        }
        double value = random.nextGaussian() * (maxAbs / 3.0);
        return Math.clamp(value, -maxAbs, maxAbs);
    }
}
