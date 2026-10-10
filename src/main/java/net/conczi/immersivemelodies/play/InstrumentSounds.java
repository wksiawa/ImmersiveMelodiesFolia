package net.conczi.immersivemelodies.play;

import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import net.conczi.immersivemelodies.melody.Note;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.joml.Vector3f;

import java.util.Random;

/**
 * 乐器单音发声：音量/音高/八度采样映射/人性化扰动/感知响度/音符粒子，
 * 移植自原模组 InstrumentItem.playNote。旋律回放（PlaybackSession）与
 * 自由演奏（FreePlayGui）共用这一条链路，保证两种玩法音色一致。
 */
public final class InstrumentSounds {

    private static final Random RANDOM = new Random();

    private InstrumentSounds() {
    }

    /**
     * 在玩家所在位置播放一个乐器音符（世界音效，附近玩家自然可闻）。
     */
    public static void playNote(ImmersiveMelodiesPlugin plugin, LivingEntity entity,
                                InstrumentType instrument, Note note) {
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

    private static double boundedGaussian(double maxAbs) {
        if (maxAbs <= 0.0) {
            return 0.0;
        }
        double value = RANDOM.nextGaussian() * (maxAbs / 3.0);
        return Math.clamp(value, -maxAbs, maxAbs);
    }
}
