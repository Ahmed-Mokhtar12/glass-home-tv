package com.ahmedfamily.glasshome;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The last few turns of the conversation, as what really happened (not what the AI planned), so "again"
 * or "louder" make sense. Forgotten after 15 quiet minutes. No Android code, so it is tested on the PC.
 */
final class Turns {
    static final int MAX = 6;
    static final long FORGET_MS = 15 * 60_000L;

    /** result: done | unverified | partial | failed | stopped | changed | answered */
    static final class Turn {
        final String words, did, result, appBefore, appAfter;
        final long atMs;
        Turn(String words, String did, String result, String appBefore, String appAfter, long atMs) {
            this.words = words; this.did = did; this.result = result;
            this.appBefore = appBefore; this.appAfter = appAfter; this.atMs = atMs;
        }
    }

    private final ArrayDeque<Turn> turns = new ArrayDeque<>();
    private long last;

    synchronized void add(Turn t, long nowMs) {
        forgetIfQuiet(nowMs);
        turns.addLast(t);
        while (turns.size() > MAX) turns.pollFirst();
        last = nowMs;
    }

    synchronized List<Turn> recent(long nowMs) {
        forgetIfQuiet(nowMs);
        return new ArrayList<>(turns);
    }

    /** The app that was in front after the last turn, or null when there is no recent turn. */
    synchronized String lastAppAfter(long nowMs) {
        forgetIfQuiet(nowMs);
        return turns.isEmpty() ? null : turns.peekLast().appAfter;
    }

    synchronized int size(long nowMs) {
        forgetIfQuiet(nowMs);
        return turns.size();
    }

    synchronized void clear() { turns.clear(); }

    private void forgetIfQuiet(long nowMs) {
        if (!turns.isEmpty() && nowMs - last > FORGET_MS) turns.clear();
    }

    /** Plain text for the AI (never JSON, so the AI doesn't copy old plans). Empty when there is nothing. */
    static String asPromptBlock(List<Turn> list) {
        if (list.isEmpty()) return "";
        StringBuilder s = new StringBuilder("Earlier in this conversation (oldest first; only what really happened):\n");
        int n = 1;
        for (Turn t : list) {
            s.append(n++).append(". The user said \"").append(t.words).append("\" -> ");
            s.append(t.did.isEmpty() ? "nothing was done" : t.did).append(" -> ").append(describe(t.result));
            s.append(" -> then in front: ").append(t.appAfter).append('\n');
        }
        return s.toString();
    }

    private static String describe(String result) {
        switch (result) {
            case "done": return "it worked";
            case "unverified": return "not confirmed";
            case "partial": return "only part of it worked";
            case "failed": return "it FAILED (don't assume it happened)";
            case "stopped": return "it was stopped";
            case "changed": return "not done, the screen had changed";
            default: return "answered only";
        }
    }
}
