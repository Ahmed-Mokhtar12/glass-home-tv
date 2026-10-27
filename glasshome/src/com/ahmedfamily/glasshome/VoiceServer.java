package com.ahmedfamily.glasshome;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Voice control's small web server on the TV (port 8765). Your iPhone Shortcut talks to it:
 *   POST /ask    your words      -> the request your iPhone forwards to the AI (your AI key stays on the iPhone)
 *   POST /reply  the AI's answer -> the sentence Siri says; the chosen action then runs on the TV
 *   POST /do     {"action","arg","count"} -> run one action directly (for testing)
 *   POST /talk   your words      -> one whole turn on the TV (see Talk): {"say","end","status","heard"}
 *   GET  /actions                -> the allowed actions
 * Every request must carry the code shown on the Voice control screen in an "X-Token" header.
 */
final class VoiceServer {
    static final int PORT = 8765;
    static final String KEY_ON = "voice_on", KEY_TOKEN = "voice_token", KEY_PSK = "voice_psk", KEY_MODEL = "voice_model",
        KEY_VISION_MODEL = "voice_vision_model";
    static final String DEFAULT_MODEL = "typesafe/jev-router";
    private static final String TAG = "GlassVoice";

    private final HomeButtonService svc;
    private final SharedPreferences prefs;
    private final ExecutorService clients = Executors.newFixedThreadPool(2);
    private final ExecutorService runner = Executors.newSingleThreadExecutor();   // one action at a time
    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private volatile ServerSocket socket;

    private final Talk talk;

    VoiceServer(HomeButtonService svc, SharedPreferences prefs) { this.svc = svc; this.prefs = prefs; this.talk = new Talk(svc, this, prefs); }

    static String newToken() {
        String abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // no 0/O or 1/I, so it is easy to copy by hand
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(abc.charAt(r.nextInt(abc.length())));
        return b.toString();
    }

    synchronized void start() {
        if (socket != null) return;
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
            socket = s;
        } catch (IOException e) {
            Log.w(TAG, "can't listen on " + PORT, e);
            return;
        }
        new Thread(new Runnable() { @Override public void run() { acceptLoop(); } }, "voice-accept").start();
        Log.i(TAG, "listening on " + PORT);
    }

    /** The helper is going away for good: stop listening and stop any running job. */
    void shutdown() {
        stop();
        talk.shutdown();
        clients.shutdownNow();
        runner.shutdownNow();
    }

    synchronized void stop() {
        ServerSocket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    boolean running() { return socket != null; }

    private void acceptLoop() {
        ServerSocket s;
        while ((s = socket) != null) {
            try {
                final Socket c = s.accept();
                clients.execute(new Runnable() { @Override public void run() { handle(c); } });
            } catch (IOException e) {
                if (socket != null) Log.w(TAG, "accept", e);
            }
        }
    }

    // ---- HTTP ----

    private void handle(Socket c) {
        boolean talking = false;
        try {
            c.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(c.getInputStream());
            String requestLine = line(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            String method = parts[0], path = parts.length > 1 ? parts[1] : "/";
            talking = path.equals("/talk");
            int length = 0;
            boolean chunked = false;
            String token = "";
            boolean dry = false;
            for (String h; (h = line(in)) != null && !h.isEmpty(); ) {
                int colon = h.indexOf(':');
                if (colon < 0) continue;
                String name = h.substring(0, colon).trim().toLowerCase(Locale.ROOT), value = h.substring(colon + 1).trim();
                if (name.equals("content-length")) length = Integer.parseInt(value);
                if (name.equals("x-token")) token = value;
                if (name.equals("x-dry-run")) dry = value.equals("1");
                if (name.equals("transfer-encoding") && value.toLowerCase(Locale.ROOT).contains("chunked")) chunked = true;
            }
            if (length < 0 || length > 64 * 1024) { reply(c, 413, "text/plain", "Too big"); return; }
            byte[] body;
            if (chunked) {
                body = readChunked(in);   // iPhone Shortcuts may send the body in chunks
            } else {
                body = new byte[length];
                for (int off = 0, n; off < length && (n = in.read(body, off, length - off)) > 0; ) off += n;
            }
            String text = new String(body, StandardCharsets.UTF_8);

            // /talk always answers with something Siri can say, so the Shortcut never breaks on an error
            boolean talkPath = path.equals("/talk");
            if (!prefs.getBoolean(KEY_ON, false)) { refuse(c, talkPath, 503, "Voice control is switched off on the TV."); return; }
            if (!sameSecret(token, prefs.getString(KEY_TOKEN, ""))) { refuse(c, talkPath, 401, "Wrong code. Copy the code from the TV's Voice control screen into the Shortcut."); return; }
            // dry-run tests press nothing, so they don't count towards the limit
            if (!dry && tooMany()) { refuse(c, talkPath, 429, "Too many requests. Please wait a minute."); return; }
            route(c, method, path, text, dry);
        } catch (Exception e) {
            Log.w(TAG, "request", e);
            try { refuse(c, talking, 500, talking ? "Sorry, something went wrong on the TV. Please try again." : "Error: " + e.getMessage()); }
            catch (IOException ignored) { }
        } finally {
            try { c.close(); } catch (IOException ignored) { }
        }
    }

    private void route(Socket c, String method, String path, String body, boolean dry) throws IOException, JSONException {
        if (method.equals("POST") && path.equals("/talk")) {
            String words = body, fake = null;
            if (body.trim().startsWith("{")) {   // tests: {"words": ..., "fake": the AI answer to use instead of OpenRouter}
                JSONObject t = new JSONObject(body);
                words = t.optString("words");
                fake = t.has("fake") ? t.optString("fake") : null;
            }
            reply(c, 200, "application/json; charset=utf-8", talk.handle(words, dry, fake).toString());
        } else if (method.equals("GET") && path.equals("/actions")) {
            reply(c, 200, "application/json", Actions.listJson().toString(2));
        } else if (method.equals("GET") && path.equals("/ping")) {
            reply(c, 200, "text/plain", "ok " + svc.currentPackage() + " " + svc.currentClass());
        } else if (method.equals("POST") && path.equals("/ask")) {
            // plain text on purpose: Shortcuts then passes it on unchanged instead of turning it into a dictionary
            pendingWords = body.trim();
            reply(c, 200, "text/plain; charset=utf-8", aiRequest(pendingWords, true).toString());
        } else if (method.equals("POST") && path.equals("/reply")) {
            reply(c, 200, "text/plain; charset=utf-8", endIfDone(fromAi(body)));
        } else if (method.equals("POST") && path.equals("/do")) {
            JSONObject o = new JSONObject(body);
            String err = queue(o.optString("action"), o.optString("arg"), o.optInt("count", 0));
            reply(c, err == null ? 200 : 400, "text/plain; charset=utf-8", err == null ? "OK" : err);
        } else {
            reply(c, 404, "text/plain", "Not found");
        }
    }

    // ---- AI ----

    /** The last few exchanges, so "the next one" or "go back" make sense. Forgotten after 15 quiet minutes. */
    private final ArrayDeque<String[]> history = new ArrayDeque<>();   // {your words, the AI's answer}
    private long lastTalk;
    private volatile String pendingWords = "";
    private static final int HISTORY = 6;
    private static final long FORGET_MS = 15 * 60 * 1000;

    /** The chat request the iPhone sends to OpenRouter. The prompt lives on the TV so it can be improved later. */
    JSONObject aiRequest(String words, boolean withPicture) throws JSONException {
        String picture = withPicture ? ScreenCapture.jpegBase64() : null;
        String vision = prefs.getString(KEY_VISION_MODEL, "").trim();
        String model = picture != null && !vision.isEmpty() ? vision : prefs.getString(KEY_MODEL, DEFAULT_MODEL);
        JSONObject r = new JSONObject();
        r.put("model", model);
        r.put("max_tokens", 500);
        r.put("temperature", 0);
        String screen = "Now in front: app " + svc.currentPackage() + ", screen " + svc.currentClass()
            + (Actions.playing(svc) ? ", sound is playing." : ".")
            + (picture != null ? " A picture of the TV screen is attached (a playing YouTube video itself shows as black)."
                               : " No screen picture this time.");
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system").put("content", prompt() + "\n" + screen));
        synchronized (history) {
            if (System.currentTimeMillis() - lastTalk > FORGET_MS) history.clear();
            for (String[] turn : history) {
                messages.put(new JSONObject().put("role", "user").put("content", turn[0]));
                messages.put(new JSONObject().put("role", "assistant").put("content", turn[1]));
            }
        }
        String said = words.isEmpty() ? "(nothing was heard)" : words;
        if (picture == null) {
            messages.put(new JSONObject().put("role", "user").put("content", said));
        } else {
            messages.put(new JSONObject().put("role", "user").put("content", new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", said))
                .put(new JSONObject().put("type", "image_url").put("image_url",
                    new JSONObject().put("url", "data:image/jpeg;base64," + picture)))));
        }
        r.put("messages", messages);
        if (model.equals(DEFAULT_MODEL)) {
            r.put("plugins", new JSONArray().put(new JSONObject().put("id", "jev-router").put("cost_tier", "low")));
        }
        return r;
    }

    private String prompt() {
        File custom = new File(svc.getFilesDir(), "assistant-prompt.txt");
        if (custom.isFile()) {
            try (InputStream in = new FileInputStream(custom)) { return readAll(in); } catch (IOException ignored) { }
        }
        return Actions.prompt();
    }

    /**
     * The Shortcut stops when the reply contains "Goodbye". Don't leave that to the AI: when you say thank you,
     * bye, stop or that you're done, the reply always ends with Goodbye.
     */
    private String endIfDone(String say) {
        String w = " " + pendingWords.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z' ]", " ") + " ";
        boolean done = w.contains(" thank") || w.contains(" bye ") || w.contains(" goodbye") || w.contains(" good bye")
            || w.contains(" stop listening") || w.contains(" that's all") || w.contains(" thats all") || w.contains(" i'm done")
            || w.contains(" im done") || w.contains(" that's it ") || w.contains(" nothing else") || w.contains(" no thanks");
        if (done && !say.contains("Goodbye")) say = say.trim() + " Goodbye.";
        if (done) Log.i(TAG, "conversation ended");
        return say;
    }

    /** Reads the AI's answer, queues its actions in order and returns what Siri should say. */
    private String fromAi(String response) {
        String content;
        try {
            JSONObject r = new JSONObject(response);
            if (r.has("error")) return "The AI service said: " + r.getJSONObject("error").optString("message", "error");
            content = r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
        } catch (JSONException e) {
            return "I couldn't read the AI's answer.";
        }
        int a = content.indexOf('{'), b = content.lastIndexOf('}');
        if (a < 0 || b <= a) return content.isEmpty() ? "Sorry, I didn't get that." : content;
        try {
            JSONObject o = new JSONObject(content.substring(a, b + 1));
            remember(content.substring(a, b + 1));
            JSONArray steps = o.optJSONArray("actions");
            if (steps == null) steps = new JSONArray().put(o);   // the older one-action answer
            String say = o.optString("say", "Done.");
            // the Shortcut ends the conversation when it hears "Goodbye"; after power off there is no TV to talk to
            if (steps.toString().contains("\"power_off\"") && !say.contains("Goodbye")) say = say + " Goodbye.";
            String err = queue(steps);
            return err == null ? say : say + " But I can't do all of it: " + err;
        } catch (JSONException e) {
            return "Sorry, I didn't get that.";
        }
    }

    private void remember(String answer) { remember(pendingWords, answer); }

    void remember(String words, String answer) {
        synchronized (history) {
            if (System.currentTimeMillis() - lastTalk > FORGET_MS) history.clear();
            history.addLast(new String[] { words.isEmpty() ? "(nothing was heard)" : words, answer });
            while (history.size() > HISTORY) history.pollFirst();
            lastTalk = System.currentTimeMillis();
        }
    }

    // ---- running actions ----

    private String queue(final String action, final String arg, final int count) {
        try {
            return queue(new JSONArray().put(new JSONObject().put("action", action).put("arg", arg).put("count", count)));
        } catch (JSONException e) {
            return e.getMessage();
        }
    }

    /** Checks every step, then runs the allowed ones one after another. Returns a problem to mention, or null. */
    private String queue(JSONArray steps) {
        final SonyApi sony = new SonyApi(prefs.getString(KEY_PSK, ""));
        final java.util.List<JSONObject> ok = new java.util.ArrayList<>();
        String problem = null;
        for (int i = 0; i < Math.min(steps.length(), 12); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s == null) continue;
            String action = s.optString("action", "none"), arg = s.optString("arg", "");
            String err = Actions.check(action, arg);
            if (err == null && !sony.hasKey() && !action.equals("none") && !action.startsWith("youtube_") && !action.equals("open_app")) {
                err = "type the TV's IP-control key on the Voice control screen first";
            }
            if (err != null) { if (problem == null) problem = err; continue; }
            if (!action.equals("none")) ok.add(s);
        }
        if (ok.isEmpty()) return problem;
        runner.execute(new Runnable() {
            @Override public void run() {
                Actions actions = new Actions(svc, sony);
                for (int i = 0; i < ok.size(); i++) {
                    JSONObject s = ok.get(i);
                    String action = s.optString("action"), arg = s.optString("arg", "");
                    int count = s.optInt("count", 0);
                    Log.i(TAG, "run " + action + " '" + arg + "' " + count);
                    try { actions.run(action, arg, count); }
                    catch (Exception e) { Log.w(TAG, action + " failed: " + e.getMessage()); return; }
                    if (i < ok.size() - 1) android.os.SystemClock.sleep(1500);   // let the screen settle between steps
                }
            }
        });
        return problem;
    }
    /** At most 30 requests a minute. */
    private synchronized boolean tooMany() {
        long now = System.currentTimeMillis();
        while (!recent.isEmpty() && now - recent.peekFirst() > 60000) recent.pollFirst();
        if (recent.size() >= 30) return true;
        recent.addLast(now);
        return false;
    }

    private static boolean sameSecret(String a, String b) {
        if (b == null || b.isEmpty()) return false;
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return MessageDigest.isEqual(d.digest(a.getBytes(StandardCharsets.UTF_8)), d.digest(b.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { return false; }
    }

    private static void refuse(Socket c, boolean talk, int status, String message) throws IOException {
        if (!talk) { reply(c, status, "text/plain", message); return; }
        try {
            reply(c, 200, "application/json; charset=utf-8", new JSONObject().put("say", message).put("end", true)
                .put("status", "failed").put("heard", "").toString());
        } catch (JSONException e) { reply(c, status, "text/plain", message); }
    }

    private static void reply(Socket c, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\nContent-Type: " + type
            + "\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        OutputStream o = c.getOutputStream();
        o.write(head.getBytes(StandardCharsets.US_ASCII));
        o.write(b);
        o.flush();
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int ch; (ch = in.read()) != -1; ) {
            if (ch == '\n') return new String(b.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
            if (b.size() > 8192) throw new IOException("header too long");
            b.write(ch);
        }
        return b.size() == 0 ? null : b.toString("UTF-8");
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (String size; (size = line(in)) != null; ) {
            int semi = size.indexOf(';');
            int n = Integer.parseInt((semi < 0 ? size : size.substring(0, semi)).trim(), 16);
            if (n == 0) break;
            if (b.size() + n > 64 * 1024) throw new IOException("body too big");
            for (int i = 0; i < n; i++) {
                int ch = in.read();
                if (ch < 0) throw new IOException("body cut short");
                b.write(ch);
            }
            line(in);   // the line break after each chunk
        }
        return b.toByteArray();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
