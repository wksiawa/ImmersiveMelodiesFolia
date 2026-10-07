package net.conczi.immersivemelodies.melody;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class Melody {
    private final String name;
    private final List<Track> tracks = new LinkedList<>();

    public Melody(String name, List<Track> tracks) {
        this.name = name;
        this.tracks.addAll(tracks);
    }

    public String getName() {
        return name;
    }

    public List<Track> getTracks() {
        return Collections.unmodifiableList(tracks);
    }

    public int getLength() {
        int length = 0;
        for (Track track : tracks) {
            length = Math.max(length, track.getLength());
        }
        return length;
    }

    public void addTrack(Track track) {
        tracks.add(track);
    }

    /**
     * 去除旋律开头的空白，使第一个音符从 0ms 开始。
     */
    public void trim() {
        int offset = getFirstNoteTime();
        if (offset <= 0 || offset == Integer.MAX_VALUE) {
            return;
        }
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            List<Note> newNotes = new LinkedList<>();
            for (Note note : track.getNotes()) {
                newNotes.add(new Note(
                        note.getNote(),
                        note.getVelocity(),
                        note.getTime() - offset,
                        note.getLength(),
                        note.getSustain()
                ));
            }
            tracks.set(i, new Track(track.getName(), newNotes));
        }
    }

    private int getFirstNoteTime() {
        int earliestNote = Integer.MAX_VALUE;
        for (Track track : tracks) {
            List<Note> notes = track.getNotes();
            if (!notes.isEmpty()) {
                earliestNote = Math.min(earliestNote, notes.get(0).getTime());
            }
        }
        return earliestNote;
    }
}
