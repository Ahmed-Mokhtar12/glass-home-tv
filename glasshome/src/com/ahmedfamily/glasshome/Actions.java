package com.ahmedfamily.glasshome;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fixed list of things Voice control may do. Anything else is refused, so the AI can only pick
 * from this list: no purchases, no passwords, no sign-ins, no settings.
 */
final class Actions {
    static final String YOUTUBE = "com.google.android.youtube.tv", LIONZ = "com.ipltv.ipltvbo19";

    /** action name -> what it does; also shown to the AI. "arg" and "count" are explained in the prompt. */
    static final Map<String, String> LIST = new LinkedHashMap<>();
    static {
        LIST.put("key", "press a remote button; arg = up, down, left, right, ok, back, home, play, pause, play_pause, stop, "
            + "forward, rewind, next, previous, volume_up, volume_down, mute, channel_up, channel_down; count = 1-10 presses");
        LIST.put("seek", "jump forward or back in the video that is playing; count = seconds, negative goes back (e.g. -10)");
        LIST.put("open_app", "open an app; arg = youtube, lionz, shahid, browser, live_tv");
        LIST.put("power_on", "turn the TV on (works while it is off)");
        LIST.put("power_off", "turn the TV off");
        LIST.put("youtube_search", "open YouTube search results; arg = what to search for");
        LIST.put("youtube_play", "search YouTube and play the first result; arg = the video or topic");
        LIST.put("youtube_home", "YouTube home page");
        LIST.put("youtube_library", "YouTube library (history, watch later, playlists)");
        LIST.put("youtube_page_down", "scroll down one page in YouTube");
        LIST.put("lionz_live", "Lionz TV: live channels");
        LIST.put("lionz_live_favourites", "Lionz TV: favourite live channels");
        LIST.put("lionz_movies", "Lionz TV: movies");
        LIST.put("lionz_movies_favourites", "Lionz TV: favourite movies");
        LIST.put("lionz_series", "Lionz TV: series");
        LIST.put("lionz_series_favourites", "Lionz TV: favourite series");
        LIST.put("lionz_continue", "Lionz TV: continue watching series");
        LIST.put("lionz_search", "Lionz TV: search live channels, movies and series; arg = the name as Lionz writes it (Arabic for Arabic titles)");
        LIST.put("none", "do nothing, only answer (when the request isn't possible or is unclear)");
    }

    private static final Map<String, String> KEYS = new LinkedHashMap<>();
    static {
        KEYS.put("up", "Up"); KEYS.put("down", "Down"); KEYS.put("left", "Left"); KEYS.put("right", "Right");
        KEYS.put("ok", "Confirm"); KEYS.put("back", "Return"); KEYS.put("home", "Home");
        KEYS.put("play", "Play"); KEYS.put("pause", "Pause"); KEYS.put("play_pause", "Play"); KEYS.put("stop", "Stop");
        KEYS.put("forward", "Forward"); KEYS.put("rewind", "Rewind"); KEYS.put("next", "Next"); KEYS.put("previous", "Prev");
        KEYS.put("volume_up", "VolumeUp"); KEYS.put("volume_down", "VolumeDown"); KEYS.put("mute", "Mute");
        KEYS.put("mute_on", "Mute"); KEYS.put("mute_off", "Mute");
        KEYS.put("channel_up", "ChannelUp"); KEYS.put("channel_down", "ChannelDown");
    }

    private static final Map<String, String> APPS = new LinkedHashMap<>();
    static {
        APPS.put("youtube", YOUTUBE); APPS.put("lionz", LIONZ); APPS.put("shahid", "net.mbc.shahidTV");
        APPS.put("browser", "com.phlox.tvwebbrowser"); APPS.put("live_tv", "com.sony.dtv.tvx");
    }

    /** Set by "cancel" / "stop that" on this job only: its steps stop before their next press. */
    volatile boolean cancelled;

    /** Actions that press remote keys through Sony IP control, so they need its key. */
    static boolean needsKeys(String action, String arg) {
        if (action.equals("key") && arg.equals("home")) return false;   // goes straight to Glass Home
        return !action.equals("none") && !action.equals("open_app") && !action.equals("youtube_home")
            && !action.equals("youtube_library") && !action.equals("youtube_search");
    }

    /** The app that should be in front after an action, for checking it really happened; null if none. */
    static String expectedPackage(String action, String arg) {
        if (action.equals("open_app")) return APPS.get(arg);
        if (action.startsWith("youtube_")) return YOUTUBE;
        if (action.startsWith("lionz_")) return LIONZ;
        return null;
    }

    static String appName(String pkg) {
        if (YOUTUBE.equals(pkg)) return "YouTube";
        if (LIONZ.equals(pkg)) return "Lionz TV";
        for (Map.Entry<String, String> e : APPS.entrySet()) if (e.getValue().equals(pkg)) return e.getKey().replace('_', ' ');
        return "The app";
    }

    /** Seconds one Right/Left press jumps, measured per app on this TV. */
    static int seekStep(String pkg) { return LIONZ.equals(pkg) ? 10 : 10; }

    static String prompt() {
        StringBuilder s = new StringBuilder();
        s.append("You are the voice assistant of a family's Sony Android TV. The user speaks English through Siri. ")
         .append("Reply with ONE JSON object only, no other text:\n")
         .append("{\"actions\": [{\"action\": \"<name>\", \"arg\": \"<text or empty>\", \"count\": <number or 0>}, ...], ")
         .append("\"say\": \"<one or two short sentences>\"}\n")
         .append("\"actions\" runs in order, so one request can do several things, e.g. open an app, search, play, then volume. ")
         .append("Use an empty list when nothing should happen.\nAllowed actions:\n");
        for (Map.Entry<String, String> e : LIST.entrySet()) s.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        s.append("How to decide:\n")
         .append("- Earlier messages are this conversation; use them for \"the next one\", \"go back\", \"again\", \"louder\".\n")
         .append("- When a screen picture is attached, read it: what app and page are shown, which item is highlighted ")
         .append("(brighter, bigger or framed). To pick \"the second video\" or an item you can see, move with key ")
         .append("up/down/left/right from the highlighted item, then key ok. Count carefully.\n")
         .append("- Lionz TV titles: Arabic shows are written in Arabic script; use that spelling in lionz_search.\n")
         .append("- Arabic on YouTube: Siri writes Arabic names in English letters. Write the search arg of youtube_search ")
         .append("and youtube_play in Arabic script when the user asks for Arabic (\"in Arabic\", \"Arabic version\", ")
         .append("\"with Arabic commentary\"; leave the word Arabic out of the arg) or when it is clearly an Arabic singer, ")
         .append("show, film, reciter or Arabic topic, e.g. \"Amr Diab songs\" -> \"اغاني عمرو دياب\", ")
         .append("\"Bab Al Hara episode 3\" -> \"باب الحارة الحلقة 3\", \"Quran Surah Al Kahf\" -> \"سورة الكهف\". ")
         .append("Keep everything else in English. \"say\" is always English with names in English letters.\n")
         .append("- If the user says thank you, goodbye or that they are done, use no actions and say a short friendly ")
         .append("sentence that contains the word \"Goodbye\" (this ends the conversation). Use \"Goodbye\" only then.\n")
         .append("- If the request is unclear, do nothing and ask one short question in \"say\".\n")
         .append("- Never type passwords, never sign in, never buy or subscribe, never open settings; explain instead.\n")
         .append("\"say\" is spoken aloud by Siri: say what you are doing, e.g. \"Opening Lionz and searching for Loki.\"");
        return s.toString();
    }
    private final HomeButtonService svc;
    private final SonyApi sony;

    Actions(HomeButtonService svc, SonyApi sony) { this.svc = svc; this.sony = sony; }

    /** Checks an action before it is queued; returns an error message, or null when it is allowed. */
    static String check(String action, String arg) {
        if (!LIST.containsKey(action)) return "Unknown action " + action;
        if (action.equals("key") && !KEYS.containsKey(arg)) return "Unknown button " + arg;
        if (action.equals("open_app") && !APPS.containsKey(arg)) return "Unknown app " + arg;
        if ((action.endsWith("_search") || action.equals("youtube_play")) && (arg == null || arg.trim().isEmpty())) return "Nothing to search for";
        if (arg != null && arg.length() > 120) return "Text too long";
        return null;
    }

    void run(String action, String arg, int count) throws IOException {
        if (cancelled) throw new IOException("it was stopped");
        switch (action) {
            case "none": return;
            case "key":
                if (arg.equals("home")) { svc.goHome(); return; }   // straight to Glass Home, without Sony's 5-second Home delay
                if (arg.equals("mute_on") || arg.equals("mute_off")) {   // Mute toggles, so only press it when needed
                    boolean muted = ((AudioManager) svc.getSystemService(Context.AUDIO_SERVICE)).isStreamMute(AudioManager.STREAM_MUSIC);
                    if (muted != arg.equals("mute_on")) sony.press("Mute");
                    return;
                }
                for (int i = 0; i < clamp(count, 1, 10); i++) { sony.press(KEYS.get(arg)); pause(350); }
                return;
            case "seek": seek(count); return;
            case "power_on": sony.powerOn(); return;
            case "power_off": sony.powerOff(); return;
            case "open_app": openApp(APPS.get(arg), false); return;
            case "youtube_home": youtube("https://www.youtube.com/"); return;
            case "youtube_library": youtube("https://www.youtube.com/feed/library"); return;
            case "youtube_search": youtube(searchUrl(arg)); return;
            case "youtube_play": youtubePlay(arg); return;
            case "youtube_page_down":
                for (int i = 0; i < 3; i++) { sony.press("Down"); pause(300); }
                return;
            case "lionz_live": lionzSection(0, Lionz.LIVE); return;
            case "lionz_movies": lionzSection(1, Lionz.MOVIES); return;
            case "lionz_series": lionzSection(2, Lionz.SERIES); return;
            case "lionz_live_favourites": lionzSection(0, Lionz.LIVE); lionzRow(Lionz.LIVE_FAVOURITES); return;
            case "lionz_movies_favourites": lionzSection(1, Lionz.MOVIES); lionzRow(Lionz.FAVOURITES); return;
            case "lionz_series_favourites": lionzSection(2, Lionz.SERIES); lionzRow(Lionz.FAVOURITES); return;
            case "lionz_continue": lionzSection(2, Lionz.SERIES); lionzRow(Lionz.CONTINUE); return;
            case "lionz_search": lionzSearch(arg); return;
            default: throw new IOException("Unknown action " + action);
        }
    }

    // ---- YouTube ----

    private static String searchUrl(String q) {
        try { return "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q.trim(), "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }

    private void youtube(String url) {
        svc.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(YOUTUBE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void youtubePlay(String q) throws IOException {
        youtube(searchUrl(q));
        // results take a few seconds; the first result already has focus, so OK plays it
        if (!waitPackage(YOUTUBE, 12000)) throw new IOException("YouTube didn't open");
        pause(4500);
        sony.press("Confirm");
    }

    // ---- Lionz TV ----

    /** Screens and positions on Lionz TV 4.0 at 1920x1080, measured on this TV. */
    static final class Lionz {
        static final String DASHBOARD = "NewDashboardActivity", LIVE = "NSTIJKPlayerSkyTvActivity",
            SERIES = "SeriesAllDataSingleActivity", MOVIES = "VodAllDataSingleActivity", SEARCH = "SearchActivity";
        static final int[] MASTER_SEARCH = { 1270, 100 }, SEARCH_BOX = { 956, 115 };
        // Down presses from the top of the category list. Live starts at "Search in categories";
        // Movies and Series have a close (X) button above it.
        static final int LIVE_FAVOURITES = 2, FAVOURITES = 3, CONTINUE = 4;
    }

    /**
     * Opens Lionz TV fresh on its dashboard, then a section by arrow keys: Down to the bottom row, Left to its
     * first button, Up twice to the Live TV tile, then Right once per tile (0 = Live, 1 = Movies, 2 = Series).
     * Taps open tiles too, but leave Lionz in touch mode, where the arrow keys that follow go astray.
     */
    private void lionzSection(int tile, String screen) throws IOException {
        lionzDashboard();
        for (int i = 0; i < 3; i++) { sony.press("Down"); pause(300); }
        for (int i = 0; i < 4; i++) { sony.press("Left"); pause(300); }
        for (int i = 0; i < 2; i++) { sony.press("Up"); pause(450); }
        for (int i = 0; i < tile; i++) { sony.press("Right"); pause(450); }
        sony.press("Confirm");
        if (!waitClass(screen, 10000)) throw new IOException("Lionz didn't open that section");
        pause(screen.equals(Lionz.LIVE) ? 6000 : 2500);   // Live loads its channel list slowly
    }

    /**
     * Opens a row of the category list on the left: Left moves into it, Up runs past the top,
     * then Down counts rows.
     */
    private void lionzRow(int downs) throws IOException {
        if (Lionz.LIVE.equals(svc.currentClass().substring(svc.currentClass().lastIndexOf('.') + 1))) {
            // Live starts on the channel list, where Left does nothing; Up/Down first moves focus to the
            // TV-guide box on the right, and from there Left reaches the category list
            for (int i = 0; i < 8; i++) { sony.press("Up"); pause(250); }
            for (int i = 0; i < 2; i++) { sony.press("Down"); pause(400); }
        }
        for (int i = 0; i < 4; i++) { sony.press("Left"); pause(400); }
        for (int i = 0; i < 8; i++) { sony.press("Up"); pause(250); }
        for (int i = 0; i < downs; i++) { sony.press("Down"); pause(450); }
        sony.press("Confirm");
    }

    private void lionzSearch(String q) throws IOException {
        lionzDashboard();
        svc.tap(Lionz.MASTER_SEARCH[0], Lionz.MASTER_SEARCH[1]);
        if (!waitClass(Lionz.SEARCH, 8000)) throw new IOException("Lionz search didn't open");
        pause(1500);
        svc.tap(Lionz.SEARCH_BOX[0], Lionz.SEARCH_BOX[1]);   // the box starts in voice mode; a tap opens the keyboard
        pause(1500);
        sony.typeText(q.trim());
    }

    private void lionzDashboard() throws IOException {
        openApp(LIONZ, true);
        if (!waitClass(Lionz.DASHBOARD, 20000)) throw new IOException("Lionz TV didn't reach its main screen");
        pause(1500);
    }

    // ---- shared ----

    private void openApp(String pkg, boolean fresh) throws IOException {
        Intent i = svc.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = svc.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) throw new IOException("App not installed: " + pkg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (fresh) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        svc.startActivity(i);
    }

    private void seek(int seconds) throws IOException {
        if (seconds == 0) return;
        int step = seekStep(svc.currentPackage());
        int presses = clamp(Math.round(Math.abs(seconds) / (float) step), 1, 30);
        String key = seconds > 0 ? "Right" : "Left";
        for (int i = 0; i < presses; i++) { sony.press(key); pause(450); }
        // YouTube only previews the new spot (and pauses) until OK confirms it
        if (YOUTUBE.equals(svc.currentPackage())) { pause(500); sony.press("Confirm"); }
    }

    static boolean playing(Context c) {
        return ((AudioManager) c.getSystemService(Context.AUDIO_SERVICE)).isMusicActive();
    }

    static JSONObject listJson() {
        JSONObject o = new JSONObject();
        try { for (Map.Entry<String, String> e : LIST.entrySet()) o.put(e.getKey(), e.getValue()); }
        catch (org.json.JSONException ignored) { }
        return o;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Waits for a screen like the helper does, but stops at once when this job is cancelled. */
    private boolean waitClass(String part, long ms) throws IOException {
        long end = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < end) {
            if (cancelled) throw new IOException("it was stopped");
            if (svc.waitForClass(part, 300)) return true;
        }
        return false;
    }

    private boolean waitPackage(String pkg, long ms) throws IOException {
        long end = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < end) {
            if (cancelled) throw new IOException("it was stopped");
            if (svc.waitForPackage(pkg, 300)) return true;
        }
        return false;
    }

    private void pause(long ms) throws IOException {
        SystemClock.sleep(ms);
        if (cancelled) throw new IOException("it was stopped");
    }
}
