package com.ahmedfamily.glasshome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

/**
 * Sends the remote's Home button, and any return to the old launcher, to Glass Home.
 * It gets window events from every app (to know which app is in front) and counts focus/selection events
 * (only how many, per app); it reads no screen content. It also hosts Voice control (VoiceServer), which only
 * runs when switched on in VoiceActivity.
 *
 * Sony handles the Home key before accessibility services see it, and Android then holds
 * other apps back for up to 5 seconds. So when the old launcher appears, the helper at once
 * covers it with a picture of Glass Home (and ignores keys) until Glass Home itself is back.
 */
public class HomeButtonService extends AccessibilityService {
    private static volatile HomeButtonService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private View cover;
    private final Runnable hide = new Runnable() { @Override public void run() { hideCover(); } };

    static final String OLD_HOME = "com.google.android.tvlauncher";
    static final String PREFS = "glasshome";

    // timestamps of the last three redirects, so a crashing home screen can't loop forever
    private final long[] recent = new long[3];
    private int next;

    // which app and screen are in front, from window events (Voice control checks its steps with these)
    private volatile String frontPackage = "", frontClass = "";
    private final Object frontLock = new Object();
    // how many window / focus events each app has sent; Voice control compares counts to see that something changed
    private final java.util.HashMap<String, int[]> events = new java.util.HashMap<>();
    private VoiceServer voice;

    @Override
    protected void onServiceConnected() {
        instance = this;
        voice = new VoiceServer(this, getSharedPreferences(PREFS, MODE_PRIVATE));
        updateVoice();
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (voice != null) voice.shutdown();
        main.removeCallbacksAndMessages(null);
        if (banner != null) { try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(banner); } catch (RuntimeException ignored) { } banner = null; }
        hideCover();
        super.onDestroy();
    }

    /** Starts or stops Voice control's server to match the switch on the Voice control screen. */
    static void updateVoice() {
        HomeButtonService s = instance;
        if (s == null || s.voice == null) return;
        if (s.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(VoiceServer.KEY_ON, false)) {
            s.voice.start();
            ScreenCapture.request(s);   // with the one-time permission this is silent
        } else {
            s.voice.stop();
            s.stopService(new Intent(s, ScreenCapture.class));
        }
    }

    static boolean voiceRunning() {
        HomeButtonService s = instance;
        return s != null && s.voice != null && s.voice.running();
    }

    // ---- Voice control banner: a small glass pill at the top of the screen ----

    private android.widget.TextView banner;
    private final Runnable hideBanner = new Runnable() { @Override public void run() { removeBanner(); } };

    /** What the banner is telling you; each has its own colour and sign, so you can read it from the sofa. */
    static final int HEARD = 0, WORKING = 1, DONE = 2, NOT_SURE = 3, FAILED = 4, CHANGED = 5;
    private static final int[] FILL = { 0xCC10202E, 0xCC0E2A4A, 0xCC0F3322, 0xCC3A2A08, 0xCC3A1010, 0xCC3A2A08 };
    private static final int[] EDGE = { 0x88FFFFFF, 0xFF5AA9FF, 0xFF6FDC8C, 0xFFFFC04D, 0xFFFF6B6B, 0xFFFFC04D };
    private static final String[] SIGN = { "", "… ", "✓ ", "? ", "✗ ", "! " };

    /**
     * Shows a short status line on the TV. Heard and Working stay (up to a minute) until the next banner;
     * results fade after a few seconds.
     */
    void banner(final int state, final String text) {
        final boolean sticky = state == HEARD || state == WORKING;
        main.post(new Runnable() {
            @Override public void run() {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                android.util.DisplayMetrics m = getResources().getDisplayMetrics();
                float u = Math.max(m.widthPixels, m.heightPixels) / 1920f;
                if (banner == null) {
                    android.widget.TextView t = new android.widget.TextView(HomeButtonService.this);
                    t.setTextColor(0xFFFFFFFF);
                    t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 30 * u);
                    t.setSingleLine(true);
                    t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    t.setMaxWidth(Math.round(1400 * u));
                    t.setPadding(Math.round(34 * u), Math.round(14 * u), Math.round(34 * u), Math.round(16 * u));
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                    lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
                    lp.y = Math.round(36 * u);
                    try { wm.addView(t, lp); } catch (RuntimeException e) { return; }
                    banner = t;
                }
                int s = Math.max(0, Math.min(state, FILL.length - 1));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(FILL[s]);
                bg.setCornerRadius(40 * u);
                bg.setStroke(Math.max(2, Math.round(3 * u)), EDGE[s]);
                banner.setBackground(bg);
                banner.setText(SIGN[s] + text);
                banner.animate().cancel();   // a cancelled fade never runs its end action, so it can't remove this banner
                banner.setAlpha(1f);
                main.removeCallbacks(hideBanner);
                main.postDelayed(hideBanner, sticky ? 60000 : 5000);
            }
        });
    }

    /**
     * Takes the banner off the screen at once (no fade) and waits until that has happened, at most maxMs,
     * so a screen picture taken right after doesn't show it.
     */
    void clearBanner(long maxMs) {
        final java.util.concurrent.CountDownLatch gone = new java.util.concurrent.CountDownLatch(1);
        main.post(new Runnable() {
            @Override public void run() {
                main.removeCallbacks(hideBanner);
                android.widget.TextView t = banner;
                banner = null;
                if (t != null) {
                    t.animate().cancel();
                    try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(t); } catch (RuntimeException ignored) { }
                }
                gone.countDown();
            }
        });
        try {
            if (gone.await(Math.max(0, maxMs), java.util.concurrent.TimeUnit.MILLISECONDS)) SystemClock.sleep(Math.min(80, maxMs));
        } catch (InterruptedException ignored) { }
    }

    private void removeBanner() {
        final android.widget.TextView t = banner;
        if (t == null) return;
        t.animate().alpha(0f).setDuration(400).withEndAction(new Runnable() {
            @Override public void run() {
                try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(t); } catch (RuntimeException ignored) { }
                if (banner == t) banner = null;
            }
        }).start();
    }

    String currentPackage() { return frontPackage; }

    String currentClass() { return frontClass; }

    /** Waits until an app's screen whose class name contains this text is in front. */
    boolean waitForClass(String part, long timeoutMs) {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (frontLock) {
            while (!frontClass.contains(part)) {
                long left = end - SystemClock.elapsedRealtime();
                if (left <= 0) return false;
                try { frontLock.wait(left); } catch (InterruptedException e) { return false; }
            }
        }
        return true;
    }

    boolean waitForPackage(String pkg, long timeoutMs) {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (frontLock) {
            while (!frontPackage.equals(pkg)) {
                long left = end - SystemClock.elapsedRealtime();
                if (left <= 0) return false;
                try { frontLock.wait(left); } catch (InterruptedException e) { return false; }
            }
        }
        return true;
    }

    /** How many window events this app has sent so far (only ever grows). */
    int windowEvents(String pkg) { synchronized (frontLock) { int[] c = events.get(pkg); return c == null ? 0 : c[0]; } }

    /** How many focus / selection events this app has sent so far (only ever grows). */
    int focusEvents(String pkg) { synchronized (frontLock) { int[] c = events.get(pkg); return c == null ? 0 : c[1]; } }

    /**
     * Waits for a window event from this app newer than "since" (a windowEvents count taken before an action),
     * with the app in front at the end. Proves the screen changed, not which page it changed to.
     */
    boolean waitForNewWindow(String pkg, int since, long timeoutMs) {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (frontLock) {
            while (windowEvents(pkg) <= since || !frontPackage.equals(pkg)) {
                long left = end - SystemClock.elapsedRealtime();
                if (left <= 0) return false;
                try { frontLock.wait(left); } catch (InterruptedException e) { return false; }
            }
        }
        return true;
    }

    private void count(String pkg, int which) {
        int[] c = events.get(pkg);
        if (c == null) { c = new int[2]; events.put(pkg, c); }
        c[which]++;
    }

    /** A short touch at a point on the 1920x1080 screen (Lionz TV accepts these). */
    void tap(float x, float y) {
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        float scale = Math.max(m.widthPixels, m.heightPixels) / 1920f;
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(x * scale, y * scale);
        dispatchGesture(new android.accessibilityservice.GestureDescription.Builder()
            .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    /** Called by Glass Home once it is back in front. */
    static void hideCoverSoon() {
        HomeButtonService s = instance;
        if (s != null) {
            s.main.removeCallbacks(s.hide);
            s.main.postDelayed(s.hide, 250);
        }
    }

    private void showCover() {
        Bitmap snap = HomeActivity.snapshot;
        if (snap == null || cover != null) return;
        ImageView v = new ImageView(this);
        v.setImageBitmap(snap);
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        v.setFocusable(true);
        v.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View view, int code, KeyEvent ev) { return true; }  // ignore keys until Glass Home is ready
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE);
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(v, lp);
        } catch (RuntimeException e) {
            return;
        }
        cover = v;
        v.requestFocus();
        main.removeCallbacks(hide);
        main.postDelayed(hide, 8000);   // never leave the cover up if Glass Home doesn't arrive
    }

    private void hideCover() {
        if (cover == null) return;
        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover); } catch (RuntimeException ignored) { }
        cover = null;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) openHome();
        return true; // consume both DOWN and UP so the system never acts on it
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        int type = e.getEventType();
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED || type == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            // only counted (for the voice log), never used to decide which app is in front
            synchronized (frontLock) { count(pkg.toString(), 1); frontLock.notifyAll(); }
            return;
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        // ignore the invisible screen-capture helper, which only flashes by
        if (e.getClassName() != null && e.getClassName().toString().contains(".")
                && !e.getClassName().toString().contains("ScreenCapture")
                // our own banner is a plain view; other apps may report plain views for their windows too
                && !(getPackageName().contentEquals(pkg) && e.getClassName().toString().startsWith("android."))) {
            synchronized (frontLock) {
                frontPackage = pkg.toString();
                frontClass = e.getClassName().toString();
                count(frontPackage, 0);
                frontLock.notifyAll();
            }
        }
        if (!OLD_HOME.contentEquals(pkg)) return;
        long now = SystemClock.elapsedRealtime();
        if (recent[next] != 0 && now - recent[next] < 10000) return;
        recent[next] = now;
        next = (next + 1) % recent.length;
        showCover();
        openHome();
    }

    @Override
    public void onInterrupt() { }

    /** Voice control's "go home". */
    void goHome() { openHome(); }

    private void openHome() {
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
