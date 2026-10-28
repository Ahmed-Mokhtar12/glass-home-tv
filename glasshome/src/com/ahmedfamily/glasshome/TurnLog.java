package com.ahmedfamily.glasshome;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small diary of the last 200 voice turns, kept in memory only (never in a file): when each stage happened,
 * which AI model answered and how it ended. Your words are kept only while "Debug log" is on.
 * Read it from the PC with GET /log. No Android code, so it is tested on the PC.
 */
final class TurnLog {
    static final int MAX = 200;

    private static final class Entry {
        final String id, route, words;
        final int wordCount;
        final long startMs;
        final StringBuilder stages = new StringBuilder();
        final Map<String, String> notes = new LinkedHashMap<>();
        String status = "open";
        Entry(String id, String route, int wordCount, String words, long startMs) {
            this.id = id; this.route = route; this.wordCount = wordCount; this.words = words; this.startMs = startMs;
        }
    }

    private final ArrayDeque<Entry> turns = new ArrayDeque<>();
    private int seq;

    /** Starts a turn and returns its id ("t0001"). wordsOrNull: null unless Debug log is on. */
    synchronized String begin(String route, int wordCount, String wordsOrNull, long nowMs) {
        String id = String.format(java.util.Locale.ROOT, "t%04d", ++seq % 10000);
        turns.addLast(new Entry(id, route, wordCount, wordsOrNull == null ? null : clean(wordsOrNull), nowMs));
        while (turns.size() > MAX) turns.pollFirst();
        return id;
    }

    /** received | picture | model | guard | first_press | verified | replied ... with ms since the turn began */
    synchronized void stage(String id, String stage, long nowMs) {
        Entry e = find(id);
        if (e != null) e.stages.append(' ').append(stage).append('+').append(nowMs - e.startMs).append("ms");
    }

    synchronized void note(String id, String key, String value) {
        Entry e = find(id);
        if (e != null) e.notes.put(key, clean(value));
    }

    synchronized void finish(String id, String status, long nowMs) {
        Entry e = find(id);
        if (e == null) return;
        e.status = status;
        e.stages.append(" end+").append(nowMs - e.startMs).append("ms");
    }

    synchronized int size() { return turns.size(); }

    /** Plain text, oldest first. */
    synchronized String dump() {
        StringBuilder s = new StringBuilder();
        for (Entry e : turns) {
            s.append(e.id).append(' ').append(e.route).append(" status=").append(e.status)
             .append(" words=").append(e.wordCount);
            for (Map.Entry<String, String> n : e.notes.entrySet()) s.append(' ').append(n.getKey()).append('=').append(n.getValue());
            s.append(" |").append(e.stages);
            if (e.words != null) s.append(" | \"").append(e.words).append('"');
            s.append('\n');
        }
        return s.toString();
    }

    private Entry find(String id) {
        if (id == null) return null;
        for (java.util.Iterator<Entry> i = turns.descendingIterator(); i.hasNext(); ) {
            Entry e = i.next();
            if (e.id.equals(id)) return e;
        }
        return null;
    }

    /** Never keep anything that looks like a key, and keep each value on one short line. */
    static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("sk-[A-Za-z0-9_-]{6,}", "[key]").replaceAll("[\\r\\n]+", " ");
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
