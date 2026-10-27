package com.ahmedfamily.glasshome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * The Voice control screen, opened from the Glass Home dock with the remote. Only here can you switch
 * Voice control on, see the code your iPhone Shortcut needs, and type the TV's IP-control key.
 */
public class VoiceActivity extends Activity {
    private SharedPreferences prefs;
    private TextView status, code, keyState, screen, aiKeyState;
    private Button onOff;
    private float u;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(HomeButtonService.PREFS, MODE_PRIVATE);
        if (prefs.getString(VoiceServer.KEY_TOKEN, "").isEmpty()) {
            prefs.edit().putString(VoiceServer.KEY_TOKEN, VoiceServer.newToken()).apply();
        }
        u = getResources().getDisplayMetrics().widthPixels / 1920f;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(140), px(70), px(140), px(70));
        col.addView(text("Voice control", 64, Color.WHITE));
        col.addView(text("Lets your iPhone and Siri control this TV over the Wi-Fi.", 30, 0xCCFFFFFF));

        status = text("", 32, 0xFF9BE7A0);
        col.addView(space(status, 30));
        onOff = button("", new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putBoolean(VoiceServer.KEY_ON, !prefs.getBoolean(VoiceServer.KEY_ON, false)).apply();
                HomeButtonService.updateVoice();
                v.postDelayed(new Runnable() { @Override public void run() { refresh(); } }, 400);
            }
        });
        col.addView(onOff);

        col.addView(space(text("Code for your iPhone Shortcut (X-Token):", 30, 0xCCFFFFFF), 40));
        code = text("", 72, Color.WHITE);
        code.setTypeface(Typeface.MONOSPACE);
        col.addView(code);
        col.addView(button("Make a new code", new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putString(VoiceServer.KEY_TOKEN, VoiceServer.newToken()).apply();
                refresh();
            }
        }));

        col.addView(space(text("TV's IP-control key (Settings > Network > IP control > Pre-Shared Key):", 30, 0xCCFFFFFF), 40));
        final EditText key = new EditText(this);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTextColor(Color.WHITE);
        key.setHint("type the key, then Save key");
        key.setHintTextColor(0x88FFFFFF);
        col.addView(key);
        keyState = text("", 28, 0xCCFFFFFF);
        col.addView(keyState);
        col.addView(button("Save key", new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putString(VoiceServer.KEY_PSK, key.getText().toString().trim()).apply();
                key.setText("");
                refresh();
            }
        }));

        col.addView(space(text("OpenRouter AI key (easiest: type or paste it with the Google TV app on your iPhone):", 30, 0xCCFFFFFF), 40));
        final EditText aiKey = new EditText(this);
        aiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        aiKey.setTextColor(Color.WHITE);
        aiKey.setHint("sk-or-...");
        aiKey.setHintTextColor(0x88FFFFFF);
        col.addView(aiKey);
        aiKeyState = text("", 28, 0xCCFFFFFF);
        col.addView(aiKeyState);
        col.addView(button("Save AI key", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String k = aiKey.getText().toString().trim();
                if (!k.isEmpty()) SecretStore.put(prefs, Talk.KEY_AI, k);
                aiKey.setText("");
                refresh();
            }
        }));
        col.addView(button("Remove AI key", new View.OnClickListener() {
            @Override public void onClick(View v) { SecretStore.put(prefs, Talk.KEY_AI, ""); refresh(); }
        }));

        col.addView(space(text("AI model on OpenRouter:", 30, 0xCCFFFFFF), 40));
        final EditText model = new EditText(this);
        model.setSingleLine(true);
        model.setTextColor(Color.WHITE);
        model.setText(prefs.getString(VoiceServer.KEY_MODEL, VoiceServer.DEFAULT_MODEL));
        col.addView(model);
        col.addView(button("Save model", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String m = model.getText().toString().trim();
                prefs.edit().putString(VoiceServer.KEY_MODEL, m.isEmpty() ? VoiceServer.DEFAULT_MODEL : m).apply();
                refresh();
            }
        }));

        col.addView(space(text("AI model when a screen picture is sent (empty = same as above):", 30, 0xCCFFFFFF), 40));
        final EditText vision = new EditText(this);
        vision.setSingleLine(true);
        vision.setTextColor(Color.WHITE);
        vision.setHint("e.g. anthropic/claude-haiku-5.5");
        vision.setHintTextColor(0x88FFFFFF);
        vision.setText(prefs.getString(VoiceServer.KEY_VISION_MODEL, ""));
        col.addView(vision);
        col.addView(button("Save picture model", new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putString(VoiceServer.KEY_VISION_MODEL, vision.getText().toString().trim()).apply();
                refresh();
            }
        }));
        screen = text("", 28, 0xCCFFFFFF);
        col.addView(space(screen, 30));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0B1A2E);
        scroll.addView(col);
        setContentView(scroll);
        refresh();
        onOff.requestFocus();
    }

    private void refresh() {
        boolean on = prefs.getBoolean(VoiceServer.KEY_ON, false);
        boolean running = HomeButtonService.voiceRunning();
        if (!on) status.setText("Off. The TV is not listening.");
        else if (running) status.setText("On. Listening at http://" + ip() + ":" + VoiceServer.PORT);
        else status.setText("On, but not listening: switch on Glass Home (Home button) in Accessibility settings.");
        status.setTextColor(on && running ? 0xFF9BE7A0 : 0xFFFFC27A);
        onOff.setText(on ? "Turn Voice control off" : "Turn Voice control on");
        code.setText(prefs.getString(VoiceServer.KEY_TOKEN, ""));
        keyState.setText(prefs.getString(VoiceServer.KEY_PSK, "").isEmpty() ? "No key saved yet." : "Key saved.");
        aiKeyState.setText(SecretStore.get(prefs, Talk.KEY_AI).isEmpty() ? "No AI key saved yet (needed for the new TV 2 Shortcut)."
            : "AI key saved (encrypted on the TV).");
        screen.setText(ScreenCapture.ready() ? "Screen pictures: on." : "Screen pictures: off (Voice control works without them).");
    }

    private static String ip() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (java.net.SocketException ignored) { }
        return "this TV";
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * u);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(10);
        b.setLayoutParams(lp);
        return b;
    }

    private View space(View v, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(top);
        v.setLayoutParams(lp);
        return v;
    }

    private int px(float units) { return Math.round(units * u); }
}
