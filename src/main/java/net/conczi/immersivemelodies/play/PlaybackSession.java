package net.conczi.immersivemelodies.play;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import net.conczi.immersivemelodies.melody.Melody;
import net.conczi.immersivemelodies.melody.Note;
import net.conczi.immersivemelodies.melody.Track;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单个玩家的演奏会话。
 * 使用 Folia 的 EntityScheduler 每 tick 驱动，时间轴为真实毫秒。
 * 支持暂停/继续（暂停期间时间轴冻结，继续时顺延起点，与原模组
 * START_TIME/PAUSED_TIME 的游戏刻顺延逻辑等价）与按曲目静音。
 */
public class PlaybackSession {

    /** 迟到超过该时间的音符直接跳过（毫秒），与原模组一致 */
    private static final long MAX_LATE_NOTE_TIME = 150L;

    private final ImmersiveMelodiesPlugin plugin;
    private final Player player;
    private final InstrumentType instrument;
    private final Melody melody;

    private final int[] lastIndices;
    private long startEpochMs;
    /** 暂停时刻（epoch 毫秒）；0 表示未暂停 */
    private long pausedAtMs;

    /** 启用的曲目序号；空集合 = 全部启用（与原模组 TRACKS 组件语义一致） */
    private final Set<Integer> enabledTracks;

    /**
     * 任务存活标志：stop 后置 false；暂停不改变它
     */
    private volatile boolean active = true;
    private ScheduledTask task;
    /** 自然结束（曲终/离线）回调，由 PlaybackManager 注入做会话移除与状态复位 */
    private Runnable onEnd;

    /** 会话是否仍然存活（暂停中也算；stop/播完后为 false），供 HUD 刷新任务判定 */
    public boolean isActive() {
        return active;
    }

    public PlaybackSession(ImmersiveMelodiesPlugin plugin, Player player, InstrumentType instrument,
                           Melody melody, Collection<Integer> enabledTracks) {
        this.plugin = plugin;
        this.player = player;
        this.instrument = instrument;
        this.melody = melody;
        this.lastIndices = new int[melody.getTracks().size()];
        this.startEpochMs = System.currentTimeMillis();
        this.enabledTracks = enabledTracks == null ? new HashSet<>() : new HashSet<>(enabledTracks);
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

    /** 是否处于可发声状态（未停止且未暂停） */
    public boolean isPlaying() {
        return active && pausedAtMs == 0L;
    }

    public boolean isPaused() {
        return active && pausedAtMs != 0L;
    }

    /** 已演奏毫秒数（暂停时冻结在暂停位置） */
    public long getElapsedMs() {
        if (!active) {
            return 0;
        }
        long end = pausedAtMs != 0L ? pausedAtMs : System.currentTimeMillis();
        return end - startEpochMs;
    }

    /**
     * 暂停：记录暂停时刻，时间轴冻结。已暂停或已停止时无效。
     */
    public void pause() {
        if (active && pausedAtMs == 0L) {
            pausedAtMs = System.currentTimeMillis();
        }
    }

    /**
     * 继续：起点顺延暂停时长，时间轴从暂停位置继续。
     */
    public void resume() {
        if (active && pausedAtMs != 0L) {
            startEpochMs += System.currentTimeMillis() - pausedAtMs;
            pausedAtMs = 0L;
        }
    }

    /** 运行时更新启用曲目（曲目面板点击切换时调用） */
    public void setEnabledTracks(Collection<Integer> tracks) {
        enabledTracks.clear();
        if (tracks != null) {
            enabledTracks.addAll(tracks);
        }
    }

    void setTask(ScheduledTask task) {
        this.task = task;
    }

    void setOnEnd(Runnable onEnd) {
        this.onEnd = onEnd;
    }

    /**
     * 每 tick 调用（在玩家所在区域线程上）。
     */
    void tick() {
        if (!active || pausedAtMs != 0L) {
            return;
        }
        if (!player.isOnline()) {
            finish();
            return;
        }

        long time = getElapsedMs();
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
                    // 已错过，跳过
                    i++;
                    continue;
                }
                if (time + lookAhead >= note.getTime()) {
                    if (trackEnabled) {
                        InstrumentSounds.playNote(plugin, player, instrument, note);
                    }
                    i++;
                } else {
                    break;
                }
            }
            lastIndices[trackIndex] = i;
            // 只要还有音符（无论是否静音）会话就持续到旋律结束，便于中途重新启用曲目
            if (i < notes.size()) {
                anyNoteRemaining = true;
            }
        }

        if (!anyNoteRemaining) {
            // 旋律播放完毕：走结束回调，由管理器移除会话并复位物品状态
            finish();
        }
    }

    /** 自然结束（曲终/离线）：停止任务并通知管理器；手动 stop 不触发回调 */
    private void finish() {
        if (!active) {
            return;
        }
        active = false;
        pausedAtMs = 0L;
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (onEnd != null) {
            onEnd.run();
        }
    }

    public void stop() {
        active = false;
        pausedAtMs = 0L;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
