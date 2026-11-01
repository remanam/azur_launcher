package com.azur.launcher.game;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Cheap reads of the end of a big log file: never loads the whole file. */
public final class LogFiles {

    private LogFiles() {
    }

    /** Bytes {@code [from, end)} of the file as text (at most {@code maxBytes} from the end); empty if unreadable. */
    public static String readFrom(Path file, long from, int maxBytes) {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            long len = f.length();
            long start = Math.max(Math.max(from, 0), len - maxBytes);
            if (start >= len) {
                return "";
            }
            byte[] buf = new byte[(int) (len - start)];
            f.seek(start);
            f.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** The last {@code n} lines, looking at most {@code maxBytes} back. */
    public static List<String> tail(Path file, int n, int maxBytes) {
        List<String> all = new ArrayList<>(List.of(readFrom(file, 0, maxBytes).split("\\R")));
        all.removeIf(String::isEmpty);
        return all.size() <= n ? all : new ArrayList<>(all.subList(all.size() - n, all.size()));
    }

    public static long size(Path file) {
        return file.toFile().length();
    }
}
