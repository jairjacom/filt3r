package com.bridge.filt3r;

import com.bridge.filt3r.core.nodes.NodeManager;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Nodes the user pasted, in their own file (files/nodes_manual.txt, one ss:// URL per line) so that a
 * subscription update never touches them. Like the subscription caches the file holds passwords, so it
 * stays in private storage and is never logged.
 */
final class ManualNodes {

    /** The "subscription" number a pasted node reports; real slots are 0-2. */
    static final int SOURCE = 99;

    private static final String FILE = "nodes_manual.txt";
    private static final Object LOCK = new Object();

    private ManualNodes() {
    }

    /** The saved nodes; a line that does not parse is skipped. */
    static List<ShadowsocksNode> read(File dir) {
        List<ShadowsocksNode> out = new ArrayList<>();
        synchronized (LOCK) {
            for (String line : lines(dir)) {
                try {
                    out.add(ShadowsocksNode.parse(line));
                } catch (IllegalArgumentException e) {
                    // skipped
                }
            }
        }
        return out;
    }

    /** @return false if a node with the same server and port is already saved */
    static boolean add(File dir, ShadowsocksNode node) throws IOException {
        synchronized (LOCK) {
            List<String> lines = lines(dir);
            for (ShadowsocksNode n : read(dir)) {
                if (NodeManager.keyOf(n).equals(NodeManager.keyOf(node))) {
                    return false;
                }
            }
            lines.add(node.toUri());
            write(dir, lines);
            return true;
        }
    }

    static void remove(File dir, ShadowsocksNode node) throws IOException {
        synchronized (LOCK) {
            List<String> keep = new ArrayList<>();
            for (String line : lines(dir)) {
                try {
                    if (NodeManager.keyOf(ShadowsocksNode.parse(line)).equals(NodeManager.keyOf(node))) {
                        continue;
                    }
                } catch (IllegalArgumentException e) {
                    continue; // a broken line is dropped with the rewrite
                }
                keep.add(line);
            }
            write(dir, keep);
        }
    }

    private static List<String> lines(File dir) {
        List<String> out = new ArrayList<>();
        File f = new File(dir, FILE);
        if (!f.isFile()) {
            return out;
        }
        try {
            for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (!l.trim().isEmpty()) {
                    out.add(l.trim());
                }
            }
        } catch (IOException e) {
            // unreadable: treated as empty
        }
        return out;
    }

    private static void write(File dir, List<String> lines) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String l : lines) {
            text.append(l).append('\n');
        }
        File tmp = new File(dir, "nodes_manual.tmp");
        Files.write(tmp.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), new File(dir, FILE).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
