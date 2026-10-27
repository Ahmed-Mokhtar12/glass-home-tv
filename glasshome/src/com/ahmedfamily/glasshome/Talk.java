package com.ahmedfamily.glasshome;

import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * POST /talk: one turn of the conversation, done entirely on the TV. Your words go in; the answer is
 * {"say": what Siri speaks, "end": true when the conversation is over, "status": done | failed | partial |
 * unverified | working | busy | none, "heard": your words}.
 * Simple requests skip the AI. Everything else is sent to OpenRouter by the TV, with the key kept in SecretStore.
 * Steps run one at a time and are checked; Siri is told the truth, within about 8 seconds.
 */
final class Talk {
    static final String KEY_AI = "voice_ai_key";
    private static final String TAG = "GlassVoice";
    private static final long BUDGET_MS = 6000;   // leaves room for a slow TV and the network within ~8 s
    private static final String[] GOODBYES = { "You're welcome! Goodbye.", "Anytime. Goodbye!", "Enjoy! Goodbye.", "My pleasure. Goodbye!" };

    private final HomeButtonService svc;
    private final VoiceServer server;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();   // TV steps, strictly one job at a time
    private volatile Future<?> job;
    private volatile Actions current;        // the running job's steps, so "cancel" can stop exactly that job
    private volatile boolean busy;           // true until the job's worker has really finished
    private volatile String lateNews = "";
    private final Random random = new Random();

    Talk(HomeButtonService svc, VoiceServer server, SharedPreferences prefs) { this.svc = svc; this.server = server; this.prefs = prefs; }

    /** One turn. dry: decide but don't press anything (for tests). fakeAi: the AI's answer to use instead of OpenRouter (tests). */
    // synchronized: a second request arriving at the same moment waits, then sees the first job and answers "busy"
    synchronized JSONObject handle(String words, boolean dry, String fakeAi) throws JSONException {
        final long start = SystemClock.elapsedRealtime();
        words = words == null ? "" : words.trim();
        if (words.isEmpty()) return result("Sorry, I didn't catch that.", false, "none", words);
        if (!dry) svc.banner("Heard: “" + words + "”", true);

        String news = lateNews;
        lateNews = "";
        if (FastPath.isFarewell(words)) {
            if (!dry) svc.banner("✓ Goodbye", false);
            server.remember(words, "{\"actions\":[],\"say\":\"Goodbye\"}");
            return result(news + GOODBYES[random.nextInt(GOODBYES.length)], true, "done", words);
        }

        String w = FastPath.clean(words);
        boolean cancel = w.matches(" (cancel|stop that|stop it|never mind|nevermind|forget it|abort)( please)?( that)? ");
        if (busy) {
            if (cancel) {
                Actions a = current;
                if (!dry && a != null) { a.cancelled = true; svc.banner("✗ Stopped", false); }
                return result(news + "OK, I stopped.", false, "done", words);
            }
            lateNews = news + lateNews;   // keep any news for the next real answer
            return result("I'm still busy with the last request. One moment, then ask again.", false, "busy", words);
        }
        if (cancel) return result("There's nothing running.", false, "none", words);

        boolean tvOn = ((PowerManager) svc.getSystemService(android.content.Context.POWER_SERVICE)).isInteractive();
        FastPath.Step fast = FastPath.decide(words, playerInFront(), tvOn);
        JSONArray steps;
        String say;
        if (fast != null) {
            steps = new JSONArray().put(step(fast.action, fast.arg, fast.count));
            say = fast.say;
            server.remember(words, new JSONObject().put("actions", steps).put("say", say).toString());
        } else {
            String content;
            try {
                content = fakeAi != null ? fakeAi : askAi(words, Math.max(3000, BUDGET_MS - (SystemClock.elapsedRealtime() - start) - 2000));
            } catch (IOException e) {
                Log.w(TAG, "AI: " + e.getMessage());
                if (!dry) svc.banner("✗ " + e.getMessage(), false);
                return result(news + e.getMessage(), false, "failed", words);
            }
            int a = content.indexOf('{'), b = content.lastIndexOf('}');
            if (a < 0 || b <= a) return result(news + (content.isEmpty() ? "Sorry, I didn't get that." : content), false, "none", words);
            JSONObject o;
            try { o = new JSONObject(content.substring(a, b + 1)); }
            catch (JSONException e) { return result(news + "Sorry, I didn't get that.", false, "none", words); }
            server.remember(words, content.substring(a, b + 1));
            steps = o.optJSONArray("actions");
            if (steps == null) steps = o.has("action") ? new JSONArray().put(o) : new JSONArray();
            say = o.optString("say", "Done.");
        }

        // keep only allowed steps; mention the first one that isn't
        final List<JSONObject> ok = new ArrayList<>();
        String problem = null;
        boolean hasPsk = !prefs.getString(VoiceServer.KEY_PSK, "").isEmpty();
        for (int i = 0; i < Math.min(steps.length(), 12); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s == null) continue;
            String action = s.optString("action", "none"), arg = s.optString("arg", "");
            String err = Actions.check(action, arg);
            if (err == null && !hasPsk && Actions.needsKeys(action, arg)) err = "the TV's IP-control key isn't saved on the Voice control screen";
            if (err != null) { if (problem == null) problem = err; continue; }
            if (!action.equals("none")) ok.add(s);
        }
        // "open Lionz" right before a Lionz shortcut (which opens Lionz itself) only costs time
        for (int i = ok.size() - 2; i >= 0; i--) {
            JSONObject s = ok.get(i), nextStep = ok.get(i + 1);
            String app = Actions.expectedPackage(s.optString("action"), s.optString("arg"));
            String nextAction = nextStep.optString("action");
            if (s.optString("action").equals("open_app") && app != null && !nextAction.equals("open_app") && !nextAction.equals("key")
                && app.equals(Actions.expectedPackage(nextAction, nextStep.optString("arg")))) ok.remove(i);
        }
        boolean end = false;
        for (JSONObject s : ok) if (s.optString("action").equals("power_off")) end = true;
        if (end && !say.contains("Goodbye")) say = say.trim() + " Goodbye.";

        if (dry) {
            JSONArray planned = new JSONArray();
            for (JSONObject s : ok) planned.put(s);
            JSONObject r = result(say, end, "dry", words).put("actions", planned).put("fast", fast != null);
            if (problem != null) r.put("problem", problem);
            return r;
        }
        if (ok.isEmpty()) {
            if (problem != null) return result(news + "Sorry, I can't do that: " + problem + ".", false, "failed", words);
            if (say.contains("Goodbye")) end = true;   // the AI only says Goodbye when you are done
            svc.banner("✓ " + say, false);
            return result(news + say, end, "done", words);
        }

        final Outcome outcome = new Outcome();
        final boolean wake = !tvOn && needsScreen(ok);
        final String spoken = say;
        final SonyApi sony = new SonyApi(prefs.getString(VoiceServer.KEY_PSK, ""));
        final Actions actions = new Actions(svc, sony);
        current = actions;
        busy = true;
        svc.banner("Working… " + say, true);
        job = worker.submit(new Runnable() {
            @Override public void run() {
                int done = 0;
                try {
                    if (wake) wakeUp(sony);
                    for (JSONObject s : ok) {
                        runStep(actions, s, outcome);
                        done++;
                        if (done < ok.size()) SystemClock.sleep(1500);   // let the screen settle between steps
                    }
                } catch (Exception e) {
                    outcome.status = done == 0 ? "failed" : "partial";
                    outcome.note = actions.cancelled ? "it was stopped" : friendly(e);
                    Log.w(TAG, "step failed: " + outcome.note);
                }
                outcome.finished = true;
                busy = false;
                svc.banner(outcome.status.equals("done") ? "✓ " + spoken
                    : outcome.status.equals("unverified") ? "✓ " + spoken + " (" + outcome.note + ")"
                    : "✗ " + capital(outcome.note), false);
            }
        });

        long left = BUDGET_MS - (SystemClock.elapsedRealtime() - start);
        try { job.get(Math.max(800, left), TimeUnit.MILLISECONDS); } catch (Exception ignored) { }
        if (!outcome.finished) {
            // still working: answer now, report a failure on the next turn; the banner shows the result
            watchLate(outcome);
            return result(news + sentence(say) + " This takes a few seconds.", end, "working", words);
        }
        if (problem != null && outcome.status.equals("done")) {
            return result(news + sentence(say) + " But I couldn't do all of it: " + problem + ".", end, "partial", words);
        }
        switch (outcome.status) {
            case "done": return result(news + say, end, "done", words);
            case "unverified": return result(news + sentence(say) + " But " + outcome.note + ".", end, "unverified", words);
            case "partial": return result(news + "I did part of it, but " + outcome.note + ".", false, "partial", words);
            default: return result(news + "Sorry, " + outcome.note + ".", false, "failed", words);
        }
    }

    /** Stops everything when Voice control or the helper goes away. */
    void shutdown() {
        Actions a = current;
        if (a != null) a.cancelled = true;
        worker.shutdownNow();
    }

    private static final class Outcome {
        volatile String status = "done", note = "";
        volatile boolean finished;
    }

    private void watchLate(final Outcome outcome) {
        new Thread(new Runnable() {
            @Override public void run() {
                long until = SystemClock.elapsedRealtime() + 120000;
                while (!outcome.finished && SystemClock.elapsedRealtime() < until) SystemClock.sleep(300);
                if (outcome.finished && !outcome.status.equals("done") && !outcome.status.equals("unverified")) {
                    lateNews = "By the way, the last request didn't work: " + outcome.note + ". ";
                }
            }
        }, "voice-late").start();
    }

    private void runStep(Actions actions, JSONObject s, Outcome outcome) throws Exception {
        String action = s.optString("action"), arg = s.optString("arg", "");
        int count = s.optInt("count", 0);
        Log.i(TAG, "run " + action + " '" + arg + "' " + count);
        actions.run(action, arg, count);
        String pkg = Actions.expectedPackage(action, arg);
        if (pkg != null && !svc.waitForPackage(pkg, 6000)) throw new IOException(Actions.appName(pkg) + " didn't open");
        if (action.equals("youtube_play")) {
            long until = SystemClock.elapsedRealtime() + 6000;
            while (!Actions.playing(svc) && SystemClock.elapsedRealtime() < until) SystemClock.sleep(300);
            if (!Actions.playing(svc)) { outcome.status = "unverified"; outcome.note = "I couldn't confirm it's playing"; }
        }
    }

    private void wakeUp(SonyApi sony) throws IOException {
        sony.powerOn();
        PowerManager pm = (PowerManager) svc.getSystemService(android.content.Context.POWER_SERVICE);
        long until = SystemClock.elapsedRealtime() + 10000;
        while (!pm.isInteractive() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(300);
        if (!pm.isInteractive()) throw new IOException("the TV didn't wake up");
        SystemClock.sleep(3000);   // let the home screen and network settle
    }

    private static boolean needsScreen(List<JSONObject> steps) {
        for (JSONObject s : steps) {
            String a = s.optString("action");
            if (!a.equals("power_on") && !a.equals("power_off") && !a.equals("none")) return true;
        }
        return false;
    }

    /** A video is in front, so "forward 30 seconds" means seek. */
    private boolean playerInFront() {
        String pkg = svc.currentPackage(), cls = svc.currentClass();
        if (Actions.LIONZ.equals(pkg)) return cls.contains("Player") || cls.contains("Exo");
        return Actions.playing(svc);
    }

    // ---- OpenRouter ----

    private String askAi(String words, long timeoutMs) throws IOException, JSONException {
        String key = SecretStore.get(prefs, KEY_AI);
        if (key.isEmpty()) throw new IOException("I need the AI key first. Add it on the TV's Voice control screen");
        JSONObject req = server.aiRequest(words, FastPath.needsScreen(words));
        HttpURLConnection c = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/chat/completions").openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout((int) timeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Title", "Glass Home TV");
        try {
            try (OutputStream o = c.getOutputStream()) { o.write(req.toString().getBytes(StandardCharsets.UTF_8)); }
            int status = c.getResponseCode();
            String body = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
            if (status == 401 || status == 403) throw new IOException("The AI key was refused. Check it on the Voice control screen");
            if (status == 402) throw new IOException("The AI account is out of credit");
            if (status >= 400) throw new IOException("I can't reach the AI right now. Simple things like volume and pause still work");
            JSONObject r = new JSONObject(body);
            return r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("The AI took too long. Please try again");
        } catch (java.net.UnknownHostException e) {
            throw new IOException("The TV can't reach the internet right now");
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---- small helpers ----

    private static JSONObject step(String action, String arg, int count) throws JSONException {
        return new JSONObject().put("action", action).put("arg", arg).put("count", count);
    }

    private static JSONObject result(String say, boolean end, String status, String heard) throws JSONException {
        // "end" is only sent when the conversation is over, so the Shortcut can simply test "has any value"
        JSONObject r = new JSONObject().put("say", say.trim()).put("status", status).put("heard", heard);
        if (end) r.put("end", true);
        return r;
    }

    private static String friendly(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return "something went wrong on the TV";
        return Character.toLowerCase(m.charAt(0)) + m.substring(1);
    }

    private static String sentence(String s) {
        s = s.trim();
        return s.isEmpty() || ".!?".indexOf(s.charAt(s.length() - 1)) >= 0 ? s : s + ".";
    }

    private static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
