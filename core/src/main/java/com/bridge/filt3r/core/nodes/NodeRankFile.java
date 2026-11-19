package com.bridge.filt3r.core.nodes;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves the node ranking between runs: a header {@code filt3r-ranks 1 <savedWallMs>} and one line
 * {@code host:port<TAB>ageMs<TAB>s1,s2,...} per node. No passwords are stored.
 */
public final class NodeRankFile {

    static final long MAX_AGE_MS = 7L * 24 * 3_600_000;
    static final int MAX_LINES = 20;
    private static final int MAX_SAMPLES = 5;
    private static final String HEADER = "filt3r-ranks 1 ";

    private NodeRankFile() {
    }

    /** @return the saved ranks, aged by the time since saving; empty if the file is missing or unusable */
    public static List<NodeManager.Rank> read(File file, long wallNowMs) {
        List<NodeManager.Rank> out = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return out;
        }
        if (lines.isEmpty() || !lines.get(0).startsWith(HEADER)) {
            return out;
        }
        long saved;
        try {
            saved = Long.parseLong(lines.get(0).substring(HEADER.length()).trim());
        } catch (NumberFormatException e) {
            return out;
        }
        long gap = Math.max(0, wallNowMs - saved);
        for (int i = 1; i < lines.size() && i <= MAX_LINES; i++) {
            NodeManager.Rank r = parse(lines.get(i), gap);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    private static NodeManager.Rank parse(String line, long gap) {
        String[] f = line.split("\t", -1);
        if (f.length != 3 || f[0].isEmpty()) {
            return null;
        }
        try {
            long age = Long.parseLong(f[1]);
            if (age < 0) {
                return null;
            }
            String[] parts = f[2].split(",", -1);
            if (parts.length == 0 || parts.length > MAX_SAMPLES) {
                return null;
            }
            long[] samples = new long[parts.length];
            for (int j = 0; j < parts.length; j++) {
                samples[j] = Long.parseLong(parts[j]);
                if (samples[j] < 0) {
                    return null;
                }
            }
            long effective = age + gap;
            if (effective < 0 || effective > MAX_AGE_MS) {
                return null;
            }
            return new NodeManager.Rank(f[0], samples, effective);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Writes the ranks through a temporary file so a crash never leaves a half-written file. */
    public static void write(File file, long wallNowMs, List<NodeManager.Rank> ranks) throws IOException {
        StringBuilder sb = new StringBuilder(HEADER).append(wallNowMs).append('\n');
        for (NodeManager.Rank r : ranks) {
            sb.append(r.key).append('\t').append(r.ageMs).append('\t');
            for (int i = 0; i < r.samples.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(r.samples[i]);
            }
            sb.append('\n');
        }
        File tmp = new File(file.getPath() + ".tmp");
        Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Deletes the file and its temporary file; failures are ignored. */
    public static void delete(File file) {
        file.delete();
        new File(file.getPath() + ".tmp").delete();
    }
}
