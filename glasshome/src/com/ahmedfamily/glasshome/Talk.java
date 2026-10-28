package com.ahmedfamily.glasshome;

import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * POST /talk: one turn of the conversation, done entirely on the TV. Your words go in; the answer is
 * {"say": what Siri speaks, "end": true when the conversation is over, "status": done | failed | partial |
 * unverified | changed | working | busy | none, "heard": your words}.
 * Simple requests skip the AI. Everything else goes to OpenRouter's Jev router, limited to the models saved on the
 * Voice control screen, with the key kept in SecretStore. The whole turn shares one 6-second deadline; steps run one
 * at a time on one worker, are checked, and Siri is told the truth.
 */
final class Talk {
    static final String KEY_AI = "voice_ai_key";
    private static final String TAG = "GlassVoice";
    private static final long BUDGET_MS = 6000;   // leaves room for a slow TV and the network within ~8 s
    private static final String[] GOODBYES = { "You're welcome! Goodbye.", "Anytime. Goodbye!", "Enjoy! Goodbye.", "My pleasure. Goodbye!" };
    private static final String CHANGED = "The screen changed while I was thinking. Please say it again.";

    private final HomeButtonService svc;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();   // TV steps, strictly one job at a time
    private final Object lock = new Object();   // held only for a moment: checking or reserving "busy"
    private boolean busy;                       // true from the moment a live turn is accepted until its worker has finished
    private volatile boolean cancelRequested;   // "cancel" arrived while the AI was still thinking
    private volatile HttpURLConnection aiCall;  // the AI request in flight, so "cancel" can cut it off
    private volatile Future<?> job;
    private volatile Actions current;           // the running job's steps, so "cancel" can stop exactly that job
    private volatile String lateNews = "";
    private final Random random = new Random();
    final Turns turns = new Turns();
    final TurnLog log = new TurnLog();

    Talk(HomeButtonService svc, SharedPreferences prefs) { this.svc = svc; this.prefs = prefs; }

    /** One turn. dry: decide but don't press, remember or show anything (for tests). fakeAi: the AI's answer to use instead of OpenRouter (tests). */
    JSONObject handle(String words, boolean dry, String fakeAi) throws JSONException { return handle(words, dry, fakeAi, null, null); }

    /**
     * testModel / testPool: for dry-run model comparisons only (VoiceServer accepts them only with X-Dry-Run):
     * a model to ask directly instead of Jev, or a Jev allowed-models list to try. The saved settings are not changed.
     */
    JSONObject handle(String words, boolean dry, String fakeAi, String testModel, String testPool) throws JSONException {
        if (!dry) { testModel = null; testPool = null; }
        final long start = SystemClock.elapsedRealtime(), deadline = start + BUDGET_MS;
        words = words == null ? "" : words.trim();
        boolean debug = prefs.getBoolean(VoiceServer.KEY_DEBUG, false);
        final String id = log.begin(dry ? "dry" : "talk", words.isEmpty() ? 0 : words.split("\\s+").length, debug ? words : null, start);
        if (words.isEmpty()) return reply(id, "", "Sorry, I didn't catch that.", false, "none", words);

        String w = FastPath.clean(words);
        boolean cancel = w.matches(" (cancel|stop that|stop it|never mind|nevermind|forget it|abort)( please)?( that)? ");
        boolean farewell = FastPath.isFarewell(words);
        boolean reserved = false, started = false;
        synchronized (lock) {
            if (busy) {
                if (cancel) {
                    if (!dry) stopEverything();
                    return reply(id, "", "OK, I stopped.", false, "done", words);
                }
                // a goodbye is still answered (the job keeps going and its banner shows the result)
                if (!farewell) return reply(id, "", "I'm still busy with the last request. One moment, then ask again.", false, "busy", words);
            } else {
                if (cancel) return reply(id, "", "There's nothing running.", false, "none", words);
                // reserved before the AI is asked, so a second request gets "busy" at once instead of waiting
                if (!dry) { busy = true; reserved = true; cancelRequested = false; }
            }
        }
        try {
            String news = dry ? "" : takeNews();
            if (farewell) {
                if (!dry) { svc.banner(HomeButtonService.DONE, "Goodbye"); record(words, "", "answered", svc.currentPackage()); }
                return reply(id, news, GOODBYES[random.nextInt(GOODBYES.length)], true, "done", words);
            }

            final String appBefore = svc.currentPackage(), clsBefore = svc.currentClass();
            boolean tvOn = ((PowerManager) svc.getSystemService(android.content.Context.POWER_SERVICE)).isInteractive();
            FastPath.Step fast = FastPath.decide(words, playerInFront(), tvOn);
            final List<JSONObject> ok = new ArrayList<>();
            String say, model = "", cost = "";
            boolean pictureSent = false;
            boolean aiFarewell = false, checkScreen = false;
            if (fast != null) {
                if (!dry) svc.banner(HomeButtonService.HEARD, "“" + words + "”");
                ok.add(step(fast.action, fast.arg, fast.count));
                say = fast.say;
            } else {
                String jpeg = null;
                if (fakeAi == null) {
                    String mode = ScreenPolicy.normalise(prefs.getString(VoiceServer.KEY_PICTURE_MODE, ScreenPolicy.DEFAULT));
                    if (tvOn && ScreenPolicy.wantsPicture(mode, appBefore, clsBefore, words)) {
                        if (!dry) svc.clearBanner(Math.min(500, left(deadline) - 4000));   // the old banner mustn't cover the picture
                        jpeg = ScreenCapture.jpegBase64(Math.min(1500, left(deadline) - 3000));
                        pictureSent = jpeg != null;
                        log.stage(id, jpeg != null ? "picture" : "no_picture", SystemClock.elapsedRealtime());
                        if (jpeg == null && FastPath.needsScreen(words)) {
                            if (!dry) record(words, "", "failed", appBefore);
                            return reply(id, news, "I can't see the screen right now. Please try again in a moment.", false, "failed", words);
                        }
                    }
                }
                if (!dry) svc.banner(HomeButtonService.HEARD, "“" + words + "”");
                String content;
                try {
                    if (fakeAi != null) content = fakeAi;
                    else { String[] answer = askAi(words, jpeg, deadline, id, testModel, testPool); content = answer[0]; model = answer[1]; cost = answer[2]; }
                } catch (IOException e) {
                    if (cancelRequested) return reply(id, news, "OK, I stopped.", false, "done", words);
                    Log.w(TAG, "AI: " + e.getMessage());
                    if (!dry) { svc.banner(HomeButtonService.FAILED, e.getMessage()); record(words, "", "failed", appBefore); }
                    return reply(id, news, e.getMessage(), false, "failed", words);
                }
                log.stage(id, "model", SystemClock.elapsedRealtime());
                if (!model.isEmpty()) log.note(id, "model", model);
                if (cancelRequested) return reply(id, news, "OK, I stopped.", false, "done", words);
                if (testModel == null && !ModelPool.allowed(model, testPool != null ? ModelPool.parse(testPool) : pool())) {
                    // Jev answered from a model outside the list you chose: don't act on it
                    log.note(id, "outside_pool", "yes");
                    if (!dry) svc.banner(HomeButtonService.NOT_SURE, "Please say that again");
                    return reply(id, news, "Sorry, please say that again.", false, "failed", words);
                }
                int a = content.indexOf('{'), b = content.lastIndexOf('}');
                if (a < 0 || b <= a) {
                    String said = content.isEmpty() ? "Sorry, I didn't get that." : content;
                    if (!dry) { svc.banner(HomeButtonService.NOT_SURE, said); record(words, "", "answered", appBefore); }
                    return reply(id, news, said, false, "none", words);
                }
                JSONObject o;
                try { o = new JSONObject(content.substring(a, b + 1)); }
                catch (JSONException e) {
                    if (!dry) svc.banner(HomeButtonService.NOT_SURE, "Sorry, I didn't get that");
                    return reply(id, news, "Sorry, I didn't get that.", false, "none", words);
                }
                JSONArray steps = o.optJSONArray("actions");
                if (steps == null) steps = o.has("action") ? new JSONArray().put(o) : new JSONArray();
                say = o.optString("say", "Done.");
                aiFarewell = o.optBoolean("farewell", false);
                // the whole plan or nothing: skipping one bad step and running the rest can press OK on the wrong thing
                String problem = steps.length() > 12 ? "that needs too many steps" : null;
                boolean hasPsk = !prefs.getString(VoiceServer.KEY_PSK, "").isEmpty();
                for (int i = 0; problem == null && i < steps.length(); i++) {
                    JSONObject s = steps.optJSONObject(i);
                    if (s == null) { problem = "I got a step I couldn't read"; break; }
                    String action = s.optString("action", "none"), arg = s.optString("arg", "");
                    String err = Actions.check(action, arg);
                    if (err == null && !hasPsk && Actions.needsKeys(action, arg)) err = "the TV's IP-control key isn't saved on the Voice control screen";
                    if (err != null) problem = err;
                    else if (!action.equals("none")) ok.add(s);
                }
                if (problem != null) {
                    if (!dry) { svc.banner(HomeButtonService.FAILED, "Can't do that: " + problem); record(words, "", "failed", appBefore); }
                    return reply(id, news, "Sorry, I can't do that: " + problem + ".", false, "failed", words);
                }
                dropRedundantOpen(ok);
                for (JSONObject s : ok) if (Actions.pressesKeys(s.optString("action"))) checkScreen = true;
            }

            // the conversation ends on a power-off, or when the AI and your words both say goodbye and nothing runs
            boolean end = false;
            for (JSONObject s : ok) if (s.optString("action").equals("power_off")) end = true;
            if (!end && ok.isEmpty() && aiFarewell && FastPath.hasFarewellCue(words)) end = true;
            if (end && !say.contains("Goodbye")) say = say.trim() + " Goodbye.";

            if (dry) {
                JSONArray planned = new JSONArray();
                for (JSONObject s : ok) planned.put(s);
                JSONObject r = reply(id, "", say, end, "dry", words).put("actions", planned).put("fast", fast != null)
                    .put("ms", SystemClock.elapsedRealtime() - start).put("picture", pictureSent);
                if (!cost.isEmpty()) r.put("cost", cost);
                if (!model.isEmpty()) r.put("model", model);
                return r;
            }
            if (ok.isEmpty()) {
                svc.banner(HomeButtonService.DONE, say);
                record(words, "", "answered", appBefore);
                return reply(id, news, say, end, "done", words);
            }

            final Outcome outcome = new Outcome();
            final boolean wake = !tvOn && needsScreen(ok);
            final boolean guard = checkScreen && !wake;
            final String spoken = say, heard = words, did = describe(ok);
            final SonyApi sony = new SonyApi(prefs.getString(VoiceServer.KEY_PSK, ""));
            final Actions actions = new Actions(svc, sony);
            actions.picker = videoPicker(id);
            current = actions;
            svc.banner(HomeButtonService.WORKING, say);
            job = worker.submit(new Runnable() {
                @Override public void run() {
                    int done = 0;
                    try {
                        if (wake) wakeUp(sony);
                        // pressing keys on a screen you changed with the remote while the AI was thinking would pick the wrong thing
                        if (guard && (!svc.currentPackage().equals(appBefore) || !svc.currentClass().equals(clsBefore))) {
                            outcome.status = "changed";
                            outcome.note = "the screen changed";
                        } else {
                            log.stage(id, "first_step", SystemClock.elapsedRealtime());
                            for (JSONObject s : ok) {
                                runStep(actions, s, outcome);
                                done++;
                                if (done < ok.size()) actions.settle(1500);   // let the screen settle between steps
                            }
                        }
                    } catch (Exception e) {
                        outcome.status = done == 0 ? "failed" : "partial";
                        outcome.note = actions.cancelled ? "it was stopped" : friendly(e);
                        if (actions.cancelled) outcome.status = "stopped";
                        Log.w(TAG, "step failed: " + outcome.note);
                    }
                    String app = svc.currentPackage();
                    log.note(id, "events", svc.windowEvents(app) + "w/" + svc.focusEvents(app) + "f");
                    log.finish(id, outcome.status, SystemClock.elapsedRealtime());
                    record(heard, did, outcome.status, appBefore);
                    switch (outcome.status) {
                        case "done": svc.banner(HomeButtonService.DONE, spoken); break;
                        case "unverified": svc.banner(HomeButtonService.NOT_SURE, capital(outcome.note) + " · " + spoken); break;
                        case "changed": svc.banner(HomeButtonService.CHANGED, "Screen changed. Please say it again"); break;
                        default: svc.banner(HomeButtonService.FAILED, capital(outcome.note));
                    }
                    outcome.finished = true;
                    synchronized (lock) { busy = false; }
                }
            });
            started = true;

            try { job.get(Math.max(300, left(deadline)), TimeUnit.MILLISECONDS); } catch (Exception ignored) { }
            if (!outcome.finished) {
                // still working: answer now, report a failure on the next turn; the banner shows the result
                watchLate(outcome);
                return reply(id, news, sentence(say) + " This takes a few seconds.", end, "working", words);
            }
            switch (outcome.status) {
                case "done": return reply(id, news, say, end, "done", words);
                case "unverified": return reply(id, news, sentence(say) + " But " + outcome.note + ".", end, "unverified", words);
                case "changed": return reply(id, news, CHANGED, false, "changed", words);
                case "stopped": return reply(id, news, "OK, I stopped.", false, "done", words);
                case "partial": return reply(id, news, "I did part of it, but " + outcome.note + ".", false, "partial", words);
                default: return reply(id, news, "Sorry, " + outcome.note + ".", false, "failed", words);
            }
        } finally {
            if (reserved && !started) synchronized (lock) { busy = false; }
        }
    }

    private String takeNews() {
        String n = lateNews;
        lateNews = "";
        return n;
    }

    /** "cancel": cut off the AI if it is still thinking, and stop the running job before its next press. */
    private void stopEverything() {
        cancelRequested = true;
        HttpURLConnection c = aiCall;
        if (c != null) c.disconnect();
        Actions a = current;
        if (a != null) a.cancelled = true;
        svc.banner(HomeButtonService.FAILED, "Stopped");
    }

    /** Stops everything when Voice control or the helper goes away. */
    void shutdown() {
        stopEverything();
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
                if (outcome.finished && (outcome.status.equals("failed") || outcome.status.equals("partial") || outcome.status.equals("changed"))) {
                    lateNews = "By the way, the last request didn't work: " + outcome.note + ". ";
                }
            }
        }, "voice-late").start();
    }

    private void runStep(Actions actions, JSONObject s, Outcome outcome) throws Exception {
        String action = s.optString("action"), arg = s.optString("arg", "");
        int count = s.optInt("count", 0);
        Log.i(TAG, "run " + action + " '" + arg + "' " + count);
        String pkg = Actions.expectedPackage(action, arg);
        boolean wasInFront = pkg != null && pkg.equals(svc.currentPackage());
        int before = pkg == null ? 0 : svc.windowEvents(pkg);
        actions.run(action, arg, count);
        if (action.equals("youtube_home") || action.equals("youtube_tab")) {
            // done only when the side menu was seen open on Library and walked to the section (measured route, 28 Oct)
            if (!actions.youtubeTabReached) unsure(outcome, action.equals("youtube_home") ? "I couldn't confirm the home page"
                : "I couldn't confirm that section opened");
            return;
        }
        if (pkg != null) {
            if (!wasInFront) {
                if (!svc.waitForPackage(pkg, 6000)) throw new IOException(Actions.appName(pkg) + " didn't open");
            } else if (!action.equals("open_app") && !svc.waitForNewWindow(pkg, before, 1500)) {
                // the app was already open and nothing visibly changed: maybe it worked, but say so honestly
                unsure(outcome, "I couldn't confirm the page changed");
            }
        }
        if (action.equals("youtube_play")) {
            if (!actions.playPicked) unsure(outcome, "I couldn't check which video I picked");
            long until = SystemClock.elapsedRealtime() + 6000;
            while (!Actions.playing(svc) && SystemClock.elapsedRealtime() < until) actions.settle(300);
            if (!Actions.playing(svc)) unsure(outcome, "I couldn't confirm it's playing");
        }
    }

    private static void unsure(Outcome outcome, String note) {
        if (outcome.status.equals("done")) { outcome.status = "unverified"; outcome.note = note; }
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

    /** "open Lionz" right before a Lionz shortcut (which opens Lionz itself) only costs time. */
    private static void dropRedundantOpen(List<JSONObject> ok) {
        for (int i = ok.size() - 2; i >= 0; i--) {
            JSONObject s = ok.get(i), nextStep = ok.get(i + 1);
            String app = Actions.expectedPackage(s.optString("action"), s.optString("arg"));
            String nextAction = nextStep.optString("action");
            if (s.optString("action").equals("open_app") && app != null && !nextAction.equals("open_app") && !nextAction.equals("key")
                && app.equals(Actions.expectedPackage(nextAction, nextStep.optString("arg")))) ok.remove(i);
        }
    }

    /** A video is in front, so "forward 30 seconds" means seek. */
    private boolean playerInFront() {
        String pkg = svc.currentPackage(), cls = svc.currentClass();
        if (Actions.LIONZ.equals(pkg)) return cls.contains("Player") || cls.contains("Exo");
        return Actions.playing(svc);
    }

    // ---- memory ----

    /** Remembers what really happened, after it happened. Dry runs never get here. */
    private void record(String words, String did, String result, String appBefore) {
        turns.add(new Turns.Turn(words, did, result, appName(appBefore), appName(svc.currentPackage()),
            SystemClock.elapsedRealtime()), SystemClock.elapsedRealtime());
    }

    private static String describe(List<JSONObject> steps) {
        StringBuilder s = new StringBuilder();
        for (JSONObject o : steps) {
            if (s.length() > 0) s.append(", ");
            s.append(o.optString("action"));
            String arg = o.optString("arg", "");
            if (!arg.isEmpty()) s.append(' ').append(arg);
            int count = o.optInt("count", 0);
            if (count != 0) s.append(" x").append(count);
        }
        return s.toString();
    }

    private static String appName(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "unknown";
        if (pkg.equals("com.ahmedfamily.glasshome")) return "Glass Home (the home screen)";
        String n = Actions.appName(pkg);
        return n.equals("The app") ? pkg : n;
    }

    // ---- OpenRouter ----

    private List<String> pool() {
        return ModelPool.parse(prefs.getString(VoiceServer.KEY_MODEL_POOL, ModelPool.DEFAULT));
    }

    /** The chat request for OpenRouter. The prompt lives on the TV so it can be improved later. */
    JSONObject aiRequest(String words, String picture, boolean schema) throws JSONException { return aiRequest(words, picture, schema, null, null); }

    private JSONObject aiRequest(String words, String picture, boolean schema, String testModel, String testPool) throws JSONException {
        String vision = prefs.getString(VoiceServer.KEY_VISION_MODEL, "").trim();
        String model = testModel != null ? testModel : prefs.getString(VoiceServer.KEY_MODEL, VoiceServer.DEFAULT_MODEL);
        // Jev reads pictures itself (within its allowed models), so the picture model only replaces another main model
        if (testModel == null && picture != null && !vision.isEmpty() && !model.equals(VoiceServer.DEFAULT_MODEL)) model = vision;
        JSONObject r = new JSONObject();
        r.put("model", model);
        r.put("max_tokens", 500);
        r.put("temperature", 0);
        long now = SystemClock.elapsedRealtime();
        String app = appName(svc.currentPackage());
        StringBuilder system = new StringBuilder(prompt()).append('\n')
            .append("Now in front: ").append(app).append(" (").append(svc.currentPackage()).append("), screen ").append(svc.currentClass())
            .append(Actions.playing(svc) ? ", sound is playing." : ".")
            .append(picture != null ? " A picture of the TV screen is attached (a playing YouTube video itself shows as black)."
                                    : " No screen picture this time.");
        String last = turns.lastAppAfter(now);
        if (last != null && !last.equals(app)) {
            system.append("\nThe app in front changed since the last turn: it was ").append(last).append(", now it is ").append(app)
                .append(". Words like \"this\", \"next\", \"the second one\" and \"go back\" refer to ").append(app).append('.');
        }
        String earlier = Turns.asPromptBlock(turns.recent(now));
        if (!earlier.isEmpty()) system.append('\n').append(earlier);
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system").put("content", system.toString()));
        if (picture == null) {
            messages.put(new JSONObject().put("role", "user").put("content", words));
        } else {
            messages.put(new JSONObject().put("role", "user").put("content", new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", words))
                .put(new JSONObject().put("type", "image_url").put("image_url",
                    new JSONObject().put("url", "data:image/jpeg;base64," + picture)))));
        }
        r.put("messages", messages);
        if (model.equals(VoiceServer.DEFAULT_MODEL)) {
            JSONObject jev = new JSONObject().put("id", "jev-router").put("cost_tier", "low");
            List<String> pool = testPool != null ? ModelPool.parse(testPool) : pool();
            if (!pool.isEmpty()) jev.put("allowed_models", new JSONArray(pool));
            r.put("plugins", new JSONArray().put(jev));
        }
        r.put("usage", new JSONObject().put("include", true));   // OpenRouter then reports the cost of this call
        if (schema) r.put("response_format", schema());
        return r;
    }

    /** The answer's shape, so the AI can only return a plan the TV understands (still checked step by step). */
    private static JSONObject schema() throws JSONException {
        JSONArray names = new JSONArray();
        for (Map.Entry<String, String> e : Actions.LIST.entrySet()) names.put(e.getKey());
        JSONObject step = new JSONObject().put("type", "object")
            .put("properties", new JSONObject()
                .put("action", new JSONObject().put("type", "string").put("enum", names))
                .put("arg", new JSONObject().put("type", "string"))
                .put("count", new JSONObject().put("type", "integer")))
            .put("required", new JSONArray().put("action").put("arg").put("count"))
            .put("additionalProperties", false);
        JSONObject plan = new JSONObject().put("type", "object")
            .put("properties", new JSONObject()
                .put("actions", new JSONObject().put("type", "array").put("items", step))
                .put("say", new JSONObject().put("type", "string"))
                .put("farewell", new JSONObject().put("type", "boolean")))
            .put("required", new JSONArray().put("actions").put("say").put("farewell"))
            .put("additionalProperties", false);
        return new JSONObject().put("type", "json_schema")
            .put("json_schema", new JSONObject().put("name", "tv_plan").put("strict", true).put("schema", plan));
    }

    private String prompt() {
        File custom = new File(svc.getFilesDir(), "assistant-prompt.txt");
        if (custom.isFile()) {
            try (InputStream in = new FileInputStream(custom)) { return read(in); } catch (IOException ignored) { }
        }
        return Actions.prompt();
    }

    /** {content, served model}. Retries once without the answer schema if the AI service refuses it, time allowing. */
    private String[] askAi(String words, String picture, long deadline, String id, String testModel, String testPool) throws IOException, JSONException {
        String key = SecretStore.get(prefs, KEY_AI);
        if (key.isEmpty()) throw new IOException("I need the AI key first. Add it on the TV's Voice control screen");
        try {
            return callAi(key, aiRequest(words, picture, true, testModel, testPool), deadline, id);
        } catch (SchemaRefused e) {
            log.note(id, "schema", "refused");
            return callAi(key, aiRequest(words, picture, false, testModel, testPool), deadline, id);
        }
    }

    private static final String PICK_PROMPT = "You see a picture of YouTube on a TV, showing search results. The focused item has "
        + "a thick white frame. Results marked \"Sponsored\" are ads. Give the remote presses that move the focus from the focused "
        + "item to the FIRST normal video in the \"Search results for\" row (not Sponsored, not a channel), then press ok. "
        + "If the wide side menu (with section names) is open, start with right. Use only up, down, left, right and ok; "
        + "at most 8 presses; the last one is ok. Reply with JSON only: {\"keys\": [...]}. "
        + "If you can't see search results, reply {\"keys\": []}.";

    /** For youtube_play: asks the AI (the same restricted Jev) which presses reach the first real video. */
    private Actions.Picker videoPicker(final String id) {
        return new Actions.Picker() {
            @Override public List<String> keysToFirstVideo(String jpeg) {
                try {
                    String key = SecretStore.get(prefs, KEY_AI);
                    if (key.isEmpty()) return null;
                    JSONObject r = new JSONObject().put("model", prefs.getString(VoiceServer.KEY_MODEL, VoiceServer.DEFAULT_MODEL))
                        .put("max_tokens", 100).put("temperature", 0);
                    r.put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", PICK_PROMPT))
                        .put(new JSONObject().put("role", "user").put("content", new JSONArray()
                            .put(new JSONObject().put("type", "text").put("text", "Which presses?"))
                            .put(new JSONObject().put("type", "image_url").put("image_url",
                                new JSONObject().put("url", "data:image/jpeg;base64," + jpeg))))));
                    if (r.getString("model").equals(VoiceServer.DEFAULT_MODEL)) {
                        JSONObject jev = new JSONObject().put("id", "jev-router").put("cost_tier", "low");
                        List<String> pool = pool();
                        if (!pool.isEmpty()) jev.put("allowed_models", new JSONArray(pool));
                        r.put("plugins", new JSONArray().put(jev));
                    }
                    JSONObject keySchema = new JSONObject().put("type", "string")
                        .put("enum", new JSONArray().put("up").put("down").put("left").put("right").put("ok"));
                    r.put("response_format", new JSONObject().put("type", "json_schema").put("json_schema", new JSONObject()
                        .put("name", "tv_pick").put("strict", true).put("schema", new JSONObject().put("type", "object")
                            .put("properties", new JSONObject().put("keys", new JSONObject().put("type", "array").put("items", keySchema)))
                            .put("required", new JSONArray().put("keys")).put("additionalProperties", false))));
                    String[] answer = callAi(key, r, SystemClock.elapsedRealtime() + 7000, id);
                    log.note(id, "pick_model", answer[1]);
                    if (!ModelPool.allowed(answer[1], pool())) return null;
                    String c = answer[0];
                    int a = c.indexOf('{'), b = c.lastIndexOf('}');
                    if (a < 0 || b <= a) return null;
                    JSONArray keys = new JSONObject(c.substring(a, b + 1)).optJSONArray("keys");
                    List<String> out = new ArrayList<>();
                    for (int i = 0; keys != null && i < keys.length(); i++) out.add(keys.optString(i));
                    log.note(id, "pick", out.toString().replace(" ", ""));
                    return FastPath.validPick(out) ? out : null;
                } catch (Exception e) {
                    log.note(id, "pick_error", e.getMessage());
                    return null;
                }
            }
        };
    }

    private static final class SchemaRefused extends IOException { SchemaRefused() { super("schema refused"); } }

    private String[] callAi(String key, JSONObject req, long deadline, String id) throws IOException, JSONException {
        long timeout = left(deadline) - 500;   // keep half a second to answer Siri
        if (timeout < 1200 || cancelRequested) throw new IOException("The AI took too long. Please try again");
        HttpURLConnection c = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/chat/completions").openConnection();
        aiCall = c;
        c.setConnectTimeout((int) Math.min(2500, timeout));
        c.setReadTimeout((int) timeout);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Title", "Glass Home TV");
        c.setRequestProperty("X-OpenRouter-Metadata", "enabled");
        try {
            try (OutputStream o = c.getOutputStream()) { o.write(req.toString().getBytes(StandardCharsets.UTF_8)); }
            int status = c.getResponseCode();
            String body = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
            if (status >= 400) {
                // keep why OpenRouter refused (never the key), so a wrong model list or schema shows up in GET /log
                log.note(id, "http", status + " " + (body.length() > 150 ? body.substring(0, 150) : body));
                Log.w(TAG, "AI http " + status + ": " + TurnLog.clean(body));
            }
            if (status == 401 || status == 403) throw new IOException("The AI key was refused. Check it on the Voice control screen");
            if (status == 402) throw new IOException("The AI account is out of credit");
            if (status == 400 && req.has("response_format") && left(deadline) > 2000) throw new SchemaRefused();
            if (status >= 400) throw new IOException("I can't reach the AI right now. Simple things like volume and pause still work");
            JSONObject r = new JSONObject(body);
            String content = r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
            JSONObject usage = r.optJSONObject("usage");
            String cost = usage != null && usage.has("cost") ? String.valueOf(usage.optDouble("cost")) : "";
            if (!cost.isEmpty()) log.note(id, "cost", cost);
            return new String[] { content, r.optString("model", ""), cost };
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("The AI took too long. Please try again");
        } catch (java.net.UnknownHostException e) {
            throw new IOException("The TV can't reach the internet right now");
        } finally {
            aiCall = null;
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

    private static long left(long deadline) { return deadline - SystemClock.elapsedRealtime(); }

    private static JSONObject step(String action, String arg, int count) throws JSONException {
        return new JSONObject().put("action", action).put("arg", arg).put("count", count);
    }

    /** Every answer goes out through here: news first, and "Goodbye" only when the conversation really ends. */
    private JSONObject reply(String id, String news, String say, boolean end, String status, String heard) throws JSONException {
        String text = FastPath.guardGoodbye((news + say).trim(), end);
        long now = SystemClock.elapsedRealtime();
        log.stage(id, "replied", now);
        if (!status.equals("working")) log.finish(id, status, now);
        // "end" is only sent when the conversation is over, so the Shortcut can simply test "has any value"
        JSONObject r = new JSONObject().put("say", text).put("status", status).put("heard", heard);
        if (end) r.put("end", true);
        return r;
    }

    private static String friendly(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return "something went wrong on the TV";
        // "Lionz TV didn't open" -> "lionz TV..." would read badly; keep "I" and names like "YouTube" as they are
        if (m.startsWith("I ") || m.startsWith("I'") || (m.length() > 1 && Character.isUpperCase(m.charAt(1)))) return m;
        return Character.toLowerCase(m.charAt(0)) + m.substring(1);
    }

    private static String sentence(String s) {
        s = s.trim();
        return s.isEmpty() || ".!?".indexOf(s.charAt(s.length() - 1)) >= 0 ? s : s + ".";
    }

    private static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
