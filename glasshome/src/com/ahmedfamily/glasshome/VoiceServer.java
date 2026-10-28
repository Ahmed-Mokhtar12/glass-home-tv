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
 *   POST /talk   your words      -> one whole turn on the TV (see Talk): {"say","end","status","heard"}
 *   GET  /actions                -> the allowed actions
 *   GET  /ping                   -> "ok <app> <screen> history=<turns remembered>"
 *   GET  /log                    -> the last 200 turns: timings, model, result (your words only with Debug log on)
 * The old /ask, /reply and /do (the first Shortcut) are gone: everything that presses a key goes through /talk.
 * Every request must carry the code shown on the Voice control screen in an "X-Token" header.
 */
final class VoiceServer {
    static final int PORT = 8765;
    static final String KEY_ON = "voice_on", KEY_TOKEN = "voice_token", KEY_PSK = "voice_psk", KEY_MODEL = "voice_model",
        KEY_VISION_MODEL = "voice_vision_model", KEY_MODEL_POOL = "voice_model_pool", KEY_PICTURE_MODE = "voice_picture_mode",
        KEY_DEBUG = "voice_debug_log";
    static final String DEFAULT_MODEL = "typesafe/jev-router";
    private static final String TAG = "GlassVoice";

    private final HomeButtonService svc;
    private final SharedPreferences prefs;
    private final ExecutorService clients = Executors.newFixedThreadPool(2);
    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private volatile ServerSocket socket;

    private final Talk talk;

    VoiceServer(HomeButtonService svc, SharedPreferences prefs) { this.svc = svc; this.prefs = prefs; this.talk = new Talk(svc, prefs); }

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
            String testModel = null, testPool = null;   // dry-run model comparisons only
            for (String h; (h = line(in)) != null && !h.isEmpty(); ) {
                int colon = h.indexOf(':');
                if (colon < 0) continue;
                String name = h.substring(0, colon).trim().toLowerCase(Locale.ROOT), value = h.substring(colon + 1).trim();
                if (name.equals("content-length")) length = Integer.parseInt(value);
                if (name.equals("x-token")) token = value;
                if (name.equals("x-dry-run")) dry = value.equals("1");
                if (name.equals("x-test-model")) testModel = value;
                if (name.equals("x-test-pool")) testPool = value;
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
            if (!dry) { testModel = null; testPool = null; }
            route(c, method, path, text, dry, testModel, testPool);
        } catch (Exception e) {
            Log.w(TAG, "request", e);
            try { refuse(c, talking, 500, talking ? "Sorry, something went wrong on the TV. Please try again." : "Error: " + e.getMessage()); }
            catch (IOException ignored) { }
        } finally {
            try { c.close(); } catch (IOException ignored) { }
        }
    }

    private void route(Socket c, String method, String path, String body, boolean dry, String testModel, String testPool)
            throws IOException, JSONException {
        if (method.equals("POST") && path.equals("/talk")) {
            String words = body, fake = null;
            if (body.trim().startsWith("{")) {   // tests: {"words": ..., "fake": the AI answer to use instead of OpenRouter}
                JSONObject t = new JSONObject(body);
                words = t.optString("words");
                fake = t.has("fake") ? t.optString("fake") : null;
            }
            reply(c, 200, "application/json; charset=utf-8", talk.handle(words, dry, fake, testModel, testPool).toString());
        } else if (method.equals("GET") && path.equals("/actions")) {
            reply(c, 200, "application/json", Actions.listJson().toString(2));
        } else if (method.equals("GET") && path.equals("/ping")) {
            reply(c, 200, "text/plain", "ok " + svc.currentPackage() + " " + svc.currentClass()
                + " history=" + talk.turns.size(android.os.SystemClock.elapsedRealtime()));
        } else if (method.equals("GET") && path.equals("/log")) {
            reply(c, 200, "text/plain; charset=utf-8", talk.log.dump());
        } else if (path.equals("/ask") || path.equals("/reply") || path.equals("/do")) {
            reply(c, 410, "text/plain", "This Shortcut is out of date. Use the TV Shortcut (see SHORTCUT-TV2.md).");
        } else {
            reply(c, 404, "text/plain", "Not found");
        }
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
