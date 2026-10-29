package com.ahmedfamily.glasshome;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** PC tests for the parts of Glass Home that don't need Android. Run: scripts\Test-Logic.ps1 */
public final class LogicTests {
    private static int pass, fail;

    public static void main(String[] args) {
        fastPath();
        endings();
        turns();
        screenPolicy();
        turnLog();
        modelPool();
        youtubeMenu();
        picks();
        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
    }

    private static String action(String words, boolean player, boolean tvOn) {
        FastPath.Step s = FastPath.decide(words, player, tvOn);
        return s == null ? "ai" : s.action + (s.arg.isEmpty() ? "" : " " + s.arg);
    }

    // the same local cases Test-Voice.ps1 checks on the TV
    private static void fastPath() {
        check("volume up", action("Volume up", false, true).equals("key volume_up"));
        check("turn it down a bit", action("turn it down a bit", false, true).equals("key volume_down"));
        check("mute", action("Mute", false, true).equals("key mute_on"));
        check("unmute", action("unmute", false, true).equals("key mute_off"));
        check("pause", action("pause", false, true).equals("key pause"));
        check("go back", action("go back", false, true).equals("key back"));
        check("go home = Glass Home", action("go home", false, true).equals("key home"));
        check("YouTube home goes to the AI", action("YouTube home", false, true).equals("ai"));
        check("turn off the TV", action("Turn off the TV", false, true).equals("power_off"));
        check("turn on the TV while off", action("turn on the TV", false, false).equals("power_on"));
        check("seek with a player", action("skip ahead 30 seconds", true, true).equals("seek"));
        check("seek without a player goes to the AI", action("skip ahead 30 seconds", false, true).equals("ai"));
        check("negation goes to the AI", action("don't turn off the TV", false, true).equals("ai"));
        check("compound goes to the AI", action("open YouTube and turn it up", false, true).equals("ai"));
        check("correction goes to the AI", action("No, I mean YouTube home", false, true).equals("ai"));
        check("put the TV on mute", action("put the TV on mute", false, true).equals("key mute_on"));
        check("Siri's \"paws\" = pause", action("paws", false, true).equals("key pause"));
        check("Siri's \"lauder please\" = louder", action("lauder please", false, true).equals("key volume_up"));
    }

    private static void endings() {
        check("farewell: thank you", FastPath.isFarewell("Thank you"));
        check("farewell: thanks so much, that's all", FastPath.isFarewell("Thanks so much, that's all"));
        check("farewell: all done, goodbye", FastPath.isFarewell("All done, goodbye"));
        check("not farewell: thanks, turn it down", !FastPath.isFarewell("thanks, turn it down"));
        check("not farewell: play goodbye", !FastPath.isFarewell("Play Goodbye by Mariah Carey"));
        check("cue: all done, goodbye", FastPath.hasFarewellCue("All done, goodbye"));
        check("cue: thanks, what's playing", FastPath.hasFarewellCue("thanks, what's playing?"));
        check("no cue: open YouTube", !FastPath.hasFarewellCue("open YouTube"));
        String g = FastPath.guardGoodbye("Playing Goodbye by Mariah Carey. GOODBYE good bye", false);
        check("guard removes every goodbye: " + g, !g.toLowerCase().contains("goodbye") && !g.toLowerCase().contains("good bye"));
        check("guard keeps an ending reply", FastPath.guardGoodbye("Enjoy! Goodbye.", true).equals("Enjoy! Goodbye."));
        check("guard is idempotent", FastPath.guardGoodbye(FastPath.guardGoodbye("Goodbye", false), false).equals("good-bye"));
    }

    private static void turns() {
        Turns t = new Turns();
        long now = 1_000_000;
        for (int i = 0; i < 8; i++) t.add(new Turns.Turn("w" + i, "did " + i, "done", "YouTube", "YouTube", now + i), now + i);
        check("keeps the last 6", t.size(now + 10) == 6 && t.recent(now + 10).get(0).words.equals("w2"));
        check("forgets after 15 quiet minutes", t.size(now + 8 + Turns.FORGET_MS + 1) == 0);
        check("no last app after forgetting", t.lastAppAfter(now + 8 + Turns.FORGET_MS + 2) == null);
        t.add(new Turns.Turn("open Lionz", "open_app lionz", "failed", "YouTube", "YouTube", now), now);
        String block = Turns.asPromptBlock(t.recent(now));
        check("failed turn is not a fact", block.contains("FAILED") && block.contains("then in front: YouTube"));
        check("prompt block has no JSON", !block.contains("{") && !block.contains("\"actions\""));
        check("empty block when nothing", Turns.asPromptBlock(Collections.<Turns.Turn>emptyList()).isEmpty());
        check("last app after", "YouTube".equals(t.lastAppAfter(now + 1)));
    }

    private static void screenPolicy() {
        String yt = "com.google.android.youtube.tv", lz = "com.ipltv.ipltvbo19";
        String ytCls = "com.google.android.apps.youtube.tv.activity.ShellActivity";
        check("never sends nothing", !ScreenPolicy.wantsPicture("never", yt, ytCls, "play the second one"));
        check("keywords: referring words", ScreenPolicy.wantsPicture("keywords", yt, ytCls, "play the second one"));
        check("keywords: plain request on YouTube", !ScreenPolicy.wantsPicture("keywords", yt, ytCls, "go back to its home"));
        check("media_apps: any AI request on YouTube", ScreenPolicy.wantsPicture("media_apps", yt, ytCls, "go back to its home"));
        check("media_apps: known Lionz screen", ScreenPolicy.wantsPicture("media_apps", lz, "com.x.SeriesAllDataSingleActivity", "next"));
        check("media_apps: unknown Lionz screen", !ScreenPolicy.wantsPicture("media_apps", lz, "com.x.LoginActivity", "next"));
        check("media_apps: Glass Home uses keywords", !ScreenPolicy.wantsPicture("media_apps", "com.ahmedfamily.glasshome", "HomeActivity", "open lionz"));
        check("settings never", !ScreenPolicy.wantsPicture("media_apps", "com.android.tv.settings", "Main", "select the second one"));
        check("Voice control screen never", !ScreenPolicy.wantsPicture("keywords", "com.ahmedfamily.glasshome", "com.ahmedfamily.glasshome.VoiceActivity", "this"));
        check("unknown mode becomes the default", ScreenPolicy.normalise("x").equals(ScreenPolicy.DEFAULT));
    }

    private static void turnLog() {
        TurnLog log = new TurnLog();
        String first = log.begin("talk", 3, null, 0);
        log.stage(first, "model", 1200);
        log.finish(first, "done", 2000);
        String d = log.dump();
        check("stages in order", d.contains("model+1200ms end+2000ms"));
        check("no words when debug is off", !d.contains("\""));
        String id = log.begin("talk", 2, "my key sk-test-not-a-real-key", 0);
        log.note(id, "model", "anthropic/claude-haiku-5.5");
        check("keys are never kept", !log.dump().contains("sk-test") && log.dump().contains("[key]"));
        for (int i = 0; i < 250; i++) log.begin("dry", 1, null, i);
        check("keeps at most 200", log.size() == 200);
    }

    // readings measured on the TV on 28 Oct: menu open on Library, versus Library stacked over a video
    private static void youtubeMenu() {
        check("menu open on Library", YouTubeMenu.libraryMenuOpen(241, 15));
        check("stacked Library page is not the menu", !YouTubeMenu.libraryMenuOpen(3, 98));
        check("dark screen is not the menu", !YouTubeMenu.libraryMenuOpen(0, 0));
        check("all-bright screen is not the menu", !YouTubeMenu.libraryMenuOpen(230, 220));
    }

    private static void picks() {
        check("pick: right right ok", FastPath.validPick(Arrays.asList("right", "right", "ok")));
        check("pick: just ok", FastPath.validPick(Arrays.asList("ok")));
        check("pick: empty is not a pick", !FastPath.validPick(Collections.<String>emptyList()));
        check("pick: must end with ok", !FastPath.validPick(Arrays.asList("right", "right")));
        check("pick: one ok only", !FastPath.validPick(Arrays.asList("ok", "right", "ok")));
        check("pick: no other keys", !FastPath.validPick(Arrays.asList("back", "ok")));
        check("pick: at most 8", !FastPath.validPick(Arrays.asList("right", "right", "right", "right", "right", "right", "right", "right", "ok")));
    }

    private static void modelPool() {
        List<String> pool = ModelPool.parse(ModelPool.DEFAULT + ", anthropic/claude-haiku-5.5");
        check("parse and dedupe", pool.equals(Arrays.asList("anthropic/claude-haiku-5.5", "google/gemini-3.8-flash")));
        check("allowed exact", ModelPool.allowed("google/gemini-3.8-flash", pool));
        check("allowed dated", ModelPool.allowed("anthropic/claude-haiku-5.5-20261001", pool));
        check("outside the list", !ModelPool.allowed("openai/gpt-5.6-luna", pool));
        check("unknown served model is allowed", ModelPool.allowed("", pool) && ModelPool.allowed("typesafe/jev-router", pool));
        check("empty pool allows all", ModelPool.allowed("openai/gpt-5.6-luna", ModelPool.parse("")));
    }
}
