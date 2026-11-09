package com.bridge.filt3r;

import android.content.Context;

import com.bridge.filt3r.core.nodes.NodeHistory;

import java.io.File;

/** The one node history of the process, shared by the service (writes) and the screens (read, clear). */
final class NodeHistories {

    private static NodeHistory instance;

    private NodeHistories() {
    }

    static synchronized NodeHistory get(Context context) {
        if (instance == null) {
            instance = new NodeHistory(new File(context.getApplicationContext().getFilesDir(), "node_history.txt"),
                    System::currentTimeMillis);
        }
        return instance;
    }
}
