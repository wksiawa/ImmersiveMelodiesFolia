package net.conczi.immersivemelodies.instrument;

import org.bukkit.NamespacedKey;
import org.joml.Vector3f;

import java.util.Locale;

/**
 * 11 种乐器定义，与原模组 Items.java 对应。
 * sustain 单位为 tick，particleOffset 为音符粒子相对玩家的偏移。
 */
public enum InstrumentType {
    BAGPIPE(300, new Vector3f(0.5f, 0.6f, 0.05f)),
    DIDGERIDOO(400, new Vector3f(0.0f, -0.45f, 1.0f)),
    FLUTE(100, new Vector3f(0.0f, 0.15f, 0.9f)),
    LUTE(300, new Vector3f(0.0f, 0.0f, 0.5f)),
    PIANO(500, new Vector3f(0.0f, 0.25f, 0.5f)),
    TRIANGLE(300, new Vector3f(0.0f, 0.0f, 0.6f)),
    TRUMPET(100, new Vector3f(0.0f, 0.25f, 1.4f)),
    TINY_DRUM(500, new Vector3f(0.0f, 0.25f, 0.5f)),
    VIELLE(200, new Vector3f(-0.25f, 0.4f, 0.35f)),
    ENDER_BASS(100, new Vector3f(0.0f, 0.0f, 0.65f)),
    HANDPAN(300, new Vector3f(0.0f, 0.25f, 0.5f));

    private final long sustain;
    private final Vector3f particleOffset;
    private final String id;

    InstrumentType(long sustain, Vector3f particleOffset) {
        this.sustain = sustain;
        this.particleOffset = particleOffset;
        this.id = name().toLowerCase(Locale.ROOT);
    }

    public long getSustain() {
        return sustain;
    }

    public Vector3f getParticleOffset() {
        return particleOffset;
    }

    public String getId() {
        return id;
    }

    /**
     * 指定八度的音效键，octave 范围 1-8。
     */
    public String getSoundName(int octave) {
        int clamped = Math.max(1, Math.min(8, octave));
        return "immersive_melodies:" + id + ".c" + clamped;
    }

    public NamespacedKey getItemKey() {
        return NamespacedKey.fromString("immersive_melodies:" + id);
    }

    public static InstrumentType fromKey(String key) {
        if (key == null) {
            return null;
        }
        try {
            return valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
