package com.ahmedfamily.glasshome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Presses remote buttons and types text through Sony's own IP control on this TV (127.0.0.1),
 * using the pre-shared key you typed on the setup screen. Nothing here leaves the TV.
 */
final class SonyApi {
    private static final String BASE = "http://127.0.0.1/sony/";
    private final String psk;
    private static volatile Map<String, String> codes;   // button name -> IRCC code, read once from the TV

    SonyApi(String psk) { this.psk = psk == null ? "" : psk; }

    boolean hasKey() { return !psk.isEmpty(); }

    /** Press one remote button by Sony's name for it, e.g. "Confirm", "Right", "VolumeUp". */
    void press(String name) throws IOException {
        String code = codes().get(name);
        if (code == null) throw new IOException("This TV has no button called " + name);
        String soap = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
            + "<u:X_SendIRCC xmlns:u=\"urn:schemas-sony-com:service:IRCC:1\"><IRCCCode>" + code + "</IRCCCode>"
            + "</u:X_SendIRCC></s:Body></s:Envelope>";
        Map<String, String> h = new HashMap<>();
        h.put("SOAPACTION", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"");
        post("IRCC", "text/xml; charset=UTF-8", soap, h);
    }

    /** Type text into the box that has focus (works for Arabic too). */
    void typeText(String text) throws IOException {
        JSONObject req = json("setTextForm", "1.0", new JSONArray().put(text));
        String r = post("appControl", "application/json", req.toString(), new HashMap<String, String>());
        if (r.contains("\"error\"")) throw new IOException("The TV refused the text: " + r);
    }

    void powerOff() throws IOException { power(false); }

    /** Wakes the TV from standby (Voice control keeps listening while the screen is off). */
    void powerOn() throws IOException { power(true); }

    private void power(boolean on) throws IOException {
        JSONObject p = new JSONObject();
        try { p.put("status", on); } catch (org.json.JSONException ignored) { }
        post("system", "application/json", json("setPowerStatus", "1.0", new JSONArray().put(p)).toString(), new HashMap<String, String>());
    }

    private Map<String, String> codes() throws IOException {
        Map<String, String> c = codes;
        if (c != null) return c;
        String r = post("system", "application/json", json("getRemoteControllerInfo", "1.0", new JSONArray()).toString(), new HashMap<String, String>());
        c = new HashMap<>();
        try {
            JSONArray list = new JSONObject(r).getJSONArray("result").getJSONArray(1);
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                c.put(o.getString("name"), o.getString("value"));
            }
        } catch (org.json.JSONException e) {
            throw new IOException("Couldn't read the TV's button list");
        }
        codes = c;
        return c;
    }

    private static JSONObject json(String method, String version, JSONArray params) {
        JSONObject o = new JSONObject();
        try {
            o.put("method", method);
            o.put("id", 1);
            o.put("params", params);
            o.put("version", version);
        } catch (org.json.JSONException ignored) { }
        return o;
    }

    private String post(String path, String type, String body, Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", type);
        if (!psk.isEmpty()) c.setRequestProperty("X-Auth-PSK", psk);
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream o = c.getOutputStream()) { o.write(b); }
        int status = c.getResponseCode();
        String reply = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (status == 403) throw new IOException("Wrong IP-control key. Type it again on the Voice control screen.");
        if (status >= 400) throw new IOException("TV answered " + status);
        return reply;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
