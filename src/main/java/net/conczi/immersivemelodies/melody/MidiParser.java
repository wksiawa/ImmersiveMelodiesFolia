package net.conczi.immersivemelodies.melody;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 从 MIDI 文件解析旋律，移植自原模组的 MidiParser。
 */
public class MidiParser {

    public static Melody parseMidi(InputStream inputStream, String midiName) throws Exception {
        Melody melody = new Melody(midiName, new LinkedList<>());
        Sequence sequence = MidiSystem.getSequence(inputStream);

        // 收集所有轨道的速度事件（tempo 是全局共享的）
        List<MidiEvent> sharedEvents = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            getEvents(track).stream()
                    .filter(event -> event.getMessage() instanceof MetaMessage message
                            && message.getType() == 0x51)
                    .forEach(sharedEvents::add);
        }

        List<ParsedTrack> parsedTracks = new ArrayList<>();

        javax.sound.midi.Track[] tracks = sequence.getTracks();
        for (int trackIndex = 0; trackIndex < tracks.length; trackIndex++) {
            List<MidiEvent> trackEvents = getEvents(tracks[trackIndex]);
            String trackName = getTrackName(trackEvents, trackIndex + 1);

            List<MidiEvent> events = new ArrayList<>(sharedEvents);
            trackEvents.stream()
                    .filter(event -> event.getMessage() instanceof ShortMessage)
                    .forEach(events::add);
            events.sort(Comparator.comparingLong(MidiEvent::getTick));

            double bpm = 120;
            long lastTick = 0;
            double time = 0;
            Map<Integer, ChannelData> channels = new TreeMap<>();

            for (MidiEvent event : events) {
                // 将 tick 换算为毫秒
                long tick = event.getTick();
                double deltaMs = ((tick - lastTick) * 60000.0) / (sequence.getResolution() * bpm);
                time += deltaMs;
                lastTick = tick;
                int ms = (int) time;

                MidiMessage message = event.getMessage();

                // 解析速度变化
                if (message instanceof MetaMessage metaMessage) {
                    byte[] data = metaMessage.getData();
                    int microsecondsPerBeat =
                            ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
                    bpm = Math.round(60000000.0f / microsecondsPerBeat);
                    continue;
                }

                if (!(message instanceof ShortMessage sm)) {
                    continue;
                }

                int channel = sm.getChannel();
                int command = sm.getCommand();
                ChannelData channelData = channels.computeIfAbsent(channel, ignored -> new ChannelData());

                // NOTE_ON 力度为 0 视为 NOTE_OFF
                if (command == ShortMessage.NOTE_ON && sm.getData2() == 0) {
                    command = ShortMessage.NOTE_OFF;
                }

                if (command == ShortMessage.NOTE_ON) {
                    int note = sm.getData1();
                    int velocity = sm.getData2();

                    // 同音符重复按下时，用间隔模拟最小延音
                    if (channelData.currentNotes.containsKey(note)) {
                        Note.Builder previousNote = channelData.currentNotes.get(note);
                        previousNote.sustain = ms - previousNote.time;
                    }

                    channelData.currentNotes.put(note, new Note.Builder(note, velocity, ms));
                } else if (command == ShortMessage.NOTE_OFF) {
                    int note = sm.getData1();
                    Note.Builder noteBuilder = channelData.currentNotes.remove(note);

                    if (noteBuilder != null) {
                        noteBuilder.length = ms - noteBuilder.time;
                        channelData.notes.add(noteBuilder.build());
                    }
                }
            }

            for (Map.Entry<Integer, ChannelData> entry : channels.entrySet()) {
                ChannelData channelData = entry.getValue();

                if (channelData.notes.isEmpty()) {
                    continue;
                }

                channelData.notes.sort(Comparator.comparingInt(Note::getTime));

                parsedTracks.add(new ParsedTrack(
                        trackIndex + 1,
                        entry.getKey() + 1,
                        trackName,
                        channelData.notes
                ));
            }
        }

        // 轨道命名：尽量短，冲突时扩展
        Map<String, Integer> preferredNames = new HashMap<>();
        Map<String, Integer> expandedNames = new HashMap<>();

        for (ParsedTrack track : parsedTracks) {
            preferredNames.merge(track.preferredName(), 1, Integer::sum);
            expandedNames.merge(track.expandedName(), 1, Integer::sum);
        }

        for (ParsedTrack track : parsedTracks) {
            String name = track.preferredName();

            if (preferredNames.get(name) > 1) {
                name = track.expandedName();

                if (expandedNames.get(name) > 1) {
                    name = track.qualifiedName();
                }
            }

            melody.addTrack(new Track(name, track.notes()));
        }

        melody.trim();
        return melody;
    }

    private static String getTrackName(List<MidiEvent> events, int trackNr) {
        String name = "Track " + trackNr;

        for (MidiEvent event : events) {
            if (event.getMessage() instanceof MetaMessage message && message.getType() == 0x03) {
                String newName = new String(message.getData()).strip();

                if (!newName.isEmpty()) {
                    name = newName;
                }
            }
        }

        return name;
    }

    private static List<MidiEvent> getEvents(javax.sound.midi.Track track) {
        List<MidiEvent> events = new ArrayList<>(track.size());

        for (int i = 0; i < track.size(); i++) {
            events.add(track.get(i));
        }

        return events;
    }

    private static class ChannelData {
        private final List<Note> notes = new ArrayList<>();
        private final Map<Integer, Note.Builder> currentNotes = new HashMap<>();
    }

    private record ParsedTrack(
            int trackId,
            int channelId,
            String trackName,
            List<Note> notes
    ) {
        private String preferredName() {
            return trackName;
        }

        private String expandedName() {
            return trackName + " - Channel " + channelId;
        }

        private String qualifiedName() {
            return trackName + " (" + trackId + ") - Channel " + channelId;
        }
    }
}
