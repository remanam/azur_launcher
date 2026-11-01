package com.azur.launcher.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** The last {@code capacity} lines of a stream (the game output shown in the error dialog). */
public final class TailBuffer {

    private final int capacity;
    private final ArrayDeque<String> lines = new ArrayDeque<>();

    public TailBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void add(String line) {
        if (lines.size() == capacity) {
            lines.removeFirst();
        }
        lines.addLast(line);
    }

    public synchronized List<String> lines() {
        return new ArrayList<>(lines);
    }
}
