package com.borislav007.bzview.core;

import java.util.ArrayList;
import java.util.List;

/** One camera exposed by the camera phone, e.g. "Back, 4.4 mm (id 0)". */
public final class CameraOption {

    public final String id;
    public final String label;

    public CameraOption(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /** Parses the "camera=ID|LABEL" lines of a status reply. */
    public static List<CameraOption> parseAll(String status) {
        List<CameraOption> list = new ArrayList<>();
        for (String line : status.split("\n")) {
            String t = line.trim();
            if (t.startsWith("camera=")) {
                String rest = t.substring("camera=".length());
                int bar = rest.indexOf('|');
                if (bar > 0) {
                    list.add(new CameraOption(rest.substring(0, bar), rest.substring(bar + 1)));
                }
            }
        }
        return list;
    }

    /** Returns the id of the camera currently in use, or null if unknown. */
    public static String parseCurrent(String status) {
        for (String line : status.split("\n")) {
            String t = line.trim();
            if (t.startsWith("current=")) {
                String id = t.substring("current=".length());
                return id.isEmpty() ? null : id;
            }
        }
        return null;
    }
}
