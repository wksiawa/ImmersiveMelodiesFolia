package net.conczi.immersivemelodies.melody;

import java.util.Collections;
import java.util.List;

public class Track {
    private final String name;
    private final List<Note> notes;
    private int cachedLength = -1;

    public Track(String name, List<Note> notes) {
        this.name = name;
        this.notes = notes;
    }

    public List<Note> getNotes() {
        return Collections.unmodifiableList(notes);
    }

    public String getName() {
        return name;
    }

    public int getLength() {
        if (cachedLength >= 0) {
            return cachedLength;
        }
        cachedLength = notes.stream()
                .mapToInt(note -> note.getTime() + note.getLength())
                .max()
                .orElse(0);
        return cachedLength;
    }
}
