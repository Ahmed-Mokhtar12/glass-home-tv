package com.ahmedfamily.glasshome;

import java.util.ArrayList;
import java.util.List;

/**
 * The models Jev may route to (its "allowed_models"), saved on the Voice control screen as a comma list.
 * Empty = plain Jev. If Jev still answers from a model outside the list, nothing is pressed.
 * No Android code, so it is tested on the PC.
 */
final class ModelPool {
    static final String DEFAULT = "anthropic/claude-haiku-5.5, google/gemini-3.8-flash";

    private ModelPool() { }

    static List<String> parse(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String p : csv.split(",")) {
            String m = p.trim();
            if (!m.isEmpty() && !out.contains(m)) out.add(m);
        }
        return out;
    }

    /**
     * True when the answer may be used. An unknown served model (missing, or Jev's own name) is allowed
     * and only logged; a known model outside the list is not. Dated names ("…-20261001") match their base.
     */
    static boolean allowed(String served, List<String> pool) {
        if (pool.isEmpty() || served == null || served.trim().isEmpty() || served.startsWith("typesafe/jev")) return true;
        for (String m : pool) if (served.equals(m) || served.startsWith(m + "-") || served.startsWith(m + ":")) return true;
        return false;
    }
}
