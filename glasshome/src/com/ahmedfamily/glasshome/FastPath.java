package com.ahmedfamily.glasshome;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands the simple, unambiguous requests without the AI, so they happen in about a second:
 * volume, mute, pause/play, back, home, TV on/off, and seeking while a video is in front.
 * Also decides when you have finished talking ("thank you", "bye", "that's all").
 */
final class FastPath {
    /** One decided step, or null fields when the request needs the AI. */
    static final class Step {
        final String action, arg, say;
        final int count;
        Step(String action, String arg, int count, String say) { this.action = action; this.arg = arg; this.count = count; this.say = say; }
    }

    private FastPath() { }

    static String clean(String words) {
        return (" " + words.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("[^a-z0-9' ]", " ") + " ")
            .replaceAll(" +", " ");
    }

    // words that may surround a goodbye without making it a command ("ok thank you so much, that's all")
    private static final Set<String> FAREWELL = new HashSet<>(Arrays.asList(
        "thank", "thanks", "thankyou", "you", "so", "very", "much", "a", "lot", "bye", "goodbye", "good", "night", "goodnight",
        "that's", "thats", "that", "is", "all", "it", "for", "now", "i'm", "im", "i", "am", "done", "finished", "nothing",
        "else", "no", "ok", "okay", "great", "perfect", "cheers", "fine", "stop", "listening", "see", "later", "ya",
        "please", "siri", "tv", "mate", "buddy", "lovely", "awesome", "cool", "we're", "were", "we", "are", "and", "have", "nice", "evening", "day"));
    private static final Pattern FAREWELL_CUE = Pattern.compile(
        " (thank|thanks|thankyou|bye|goodbye|goodnight|good night|that'?s all|that'?s it|i'?m done|i am done|we'?re done|nothing else|no thanks|stop listening|see you|finished|cheers) ");

    /** True when the words only say goodbye; "thanks, turn it down" or "play Goodbye" are still commands. */
    static boolean isFarewell(String words) {
        String w = clean(words);
        if (!FAREWELL_CUE.matcher(w).find()) return false;
        for (String t : w.trim().split(" ")) if (!t.isEmpty() && !FAREWELL.contains(t)) return false;
        return true;
    }

    /** The words contain a goodbye cue somewhere ("all done, goodbye"); not enough on its own to end. */
    static boolean hasFarewellCue(String words) {
        return words != null && FAREWELL_CUE.matcher(clean(words)).find();
    }

    /** The AI's presses to a video on YouTube's results: only arrows, at most 8, and exactly one OK, at the end. */
    static boolean validPick(java.util.List<String> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > 8 || !"ok".equals(keys.get(keys.size() - 1))) return false;
        for (int i = 0; i < keys.size() - 1; i++) {
            String k = keys.get(i);
            if (!k.equals("up") && !k.equals("down") && !k.equals("left") && !k.equals("right")) return false;
        }
        return true;
    }

    private static final Pattern GOODBYE_WORD = Pattern.compile("(?i)good[\\s-]?bye");

    /**
     * The Shortcut ends when the reply contains "Goodbye", so only an ending reply may say it:
     * "Playing Goodbye by Mariah Carey" becomes "Playing Good-bye by Mariah Carey" (Siri says it the same).
     */
    static String guardGoodbye(String say, boolean end) {
        if (end || say == null) return say;
        return GOODBYE_WORD.matcher(say).replaceAll("good-bye");
    }

    private static final Pattern NUMBER = Pattern.compile(" (\\d{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty|forty five|fifty|sixty|ninety) (second|seconds|sec|secs|minute|minutes|min|mins) ");

    /** A step for clear requests, or null. playerInFront: a video app is in front, so seeking makes sense. */
    static Step decide(String words, boolean playerInFront, boolean tvOn) {
        // Siri often writes these instead of what was said
        String w = clean(words).replace(" paws ", " pause ").replace(" pose ", " pause ").replace(" paused ", " pause ")
            .replace(" lauder ", " louder ").replace(" mutes ", " mute ");
        int n = w.trim().split(" ").length;
        if (n > 9) return null;                     // long requests are for the AI
        // negations and other settings are for the AI: "don't turn it off", "turn down the brightness"
        if (w.matches(".* (don't|dont|do not|never|not|no) .*") || w.matches(".* (brightness|subtitle|subtitles|captions|light|picture|screen) .*")) return null;
        if (w.matches(".* (and|then|after|also) .*")) return null;   // several things at once

        // the whole request must be about power ("put the TV on mute" is not)
        String bare = w.replaceAll(" (please|now|the|my|for me|can you|could you|hey|ok|okay) ", " ").replaceAll(" +", " ");
        if (bare.matches(" (turn|switch|put) (on tv|tv on) | (wake up|wake) tv | tv on "))
            return new Step("power_on", "", 0, "Turning the TV on.");
        if (bare.matches(" (turn|switch) (off tv|tv off) | tv off | (shut down|shutdown) tv "))
            return new Step("power_off", "", 0, "Turning the TV off. Goodbye.");
        if (!tvOn) return null;

        if (has(w, "unmute", "un mute", "sound back", "sound on")) return new Step("key", "mute_off", 1, "Sound's back on.");
        if (has(w, " mute ", "silence", "sound off")) return new Step("key", "mute_on", 1, "Muted.");

        Matcher num = NUMBER.matcher(w);
        if (playerInFront && num.find() && has(w, "forward", "skip", "ahead", "back", "rewind", "go back", "jump")) {
            int secs = number(num.group(1)) * (num.group(2).startsWith("m") ? 60 : 1);
            boolean backwards = has(w, "back", "rewind", "backwards") && !has(w, "forward", "ahead");
            return new Step("seek", "", backwards ? -secs : secs,
                (backwards ? "Going back " : "Skipping ahead ") + spoken(secs) + ".");
        }

        boolean up = has(w, "volume up", "louder", "turn it up", "turn up", "increase the volume", "raise the volume", "more volume", "can't hear", "cant hear");
        boolean down = has(w, "volume down", "quieter", "softer", "turn it down", "turn down", "lower the volume", "decrease the volume", "less volume", "too loud");
        if (up ^ down) {
            int presses = has(w, "a bit", "a little", "slightly", "bit") ? 2 : has(w, "a lot", "much", "way") ? 6 : 3;
            Matcher by = Pattern.compile(" by (\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten) ").matcher(w);
            if (by.find()) presses = Math.min(10, number(by.group(1)));
            return new Step("key", up ? "volume_up" : "volume_down", presses, up ? "Volume up." : "Volume down.");
        }

        if (n <= 4 && has(w, " pause ", "pause it", "hold on", "freeze")) return new Step("key", "pause", 1, "Paused.");
        if (n <= 4 && has(w, " resume ", " continue ", "play again", "unpause", "keep playing", " play it ")) return new Step("key", "play", 1, "Playing.");
        if (n <= 4 && w.matches(" (go )?back( please)? ")) return new Step("key", "back", 1, "Going back.");
        if (n <= 4 && w.matches(" (go )?(to )?(the )?home( screen)?( please)? ")) return new Step("key", "home", 1, "Home.");
        return null;
    }

    private static boolean has(String w, String... any) {
        for (String a : any) if (w.contains(" " + a.trim() + " ")) return true;
        return false;
    }

    private static int number(String s) {
        switch (s) {
            case "one": return 1; case "two": return 2; case "three": return 3; case "four": return 4; case "five": return 5;
            case "six": return 6; case "seven": return 7; case "eight": return 8; case "nine": return 9; case "ten": return 10;
            case "fifteen": return 15; case "twenty": return 20; case "thirty": return 30; case "forty": return 40;
            case "forty five": return 45; case "fifty": return 50; case "sixty": return 60; case "ninety": return 90;
            default: return Integer.parseInt(s);
        }
    }

    private static String spoken(int secs) {
        if (secs % 60 == 0) return secs == 60 ? "a minute" : (secs / 60) + " minutes";
        return secs + " seconds";
    }

    /** Requests that refer to what is on screen; only these send a screen picture to the AI. */
    static boolean needsScreen(String words) {
        String w = clean(words);
        return w.matches(".* (first|second|third|fourth|fifth|last|next one|other one|this|that one|these|those|"
            + "screen|what's on|whats on|what is on|showing|highlighted|selected|select|choose|pick|click|top|bottom|"
            + "left one|right one|one on the|row|episode|season|which) .*");
    }
}
