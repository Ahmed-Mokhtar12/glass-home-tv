package com.ahmedfamily.glasshome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Glass Home: a family photo fills the screen, a new one each time the home screen returns, with a clock,
 * the family name and a dock of liquid-glass app icons on top. "All apps" opens a glass drawer.
 */
public class HomeActivity extends Activity {
    static final String FAMILY = "Our Family";   // the name shown under the clock: change it to yours
    static final String[] FAVOURITES = {
        "com.google.android.youtube.tv", "com.ipltv.ipltvbo19", "com.sony.dtv.tvx", "com.phlox.tvwebbrowser",
        "net.mbc.shahidTV", "com.smarterspro.smartersprotv", "com.mxtech.videoplayer.ad", "com.android.vending"
    };
    /** Picture of the home screen, taken when leaving it; the helper shows it instantly on Home. */
    static volatile Bitmap snapshot;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private float u;                    // one unit = one pixel on a 1920-wide screen
    private int sw, sh;
    private FrameLayout root;
    private ImageView bottomPhoto, topPhoto;
    private TextView clock, date, label;
    private GlassPanel labelPill, drawer;
    private LinearLayout dockRow;
    private GridLayout grid;
    private GlassTileView firstTile, allAppsTile;
    private PhotoStore photos;
    private SharedPreferences prefs;
    private boolean loading;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Point p = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(p);
        sw = Math.max(p.x, p.y);
        sh = Math.min(p.x, p.y);
        u = sw / 1920f;
        prefs = getSharedPreferences(HomeButtonService.PREFS, MODE_PRIVATE);
        photos = new PhotoStore(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B1A2E);
        bottomPhoto = photoLayer();
        topPhoto = photoLayer();
        root.addView(bottomPhoto, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(topPhoto, new FrameLayout.LayoutParams(MATCH, MATCH));
        // gentle shade behind the clock and the dock so white text stays readable on bright photos
        View topShade = new View(this);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0x80000000, 0x10000000, 0x00000000 }));
        root.addView(topShade, new FrameLayout.LayoutParams(MATCH, MATCH));
        View bottomShade = new View(this);
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x00000000, 0x8C000000 }));
        root.addView(bottomShade, new FrameLayout.LayoutParams(MATCH, Math.round(sh * 0.42f), Gravity.BOTTOM));

        buildClock();
        buildDock();
        buildDrawer();
        setContentView(root);
        fillDock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, f);
        updateClock();
        photos.importIncoming();
        // a different photo every time the home screen comes back: after an app, Home, or power-on
        showNextPhoto();
        Glass.invalidateAll(root);
        HomeButtonService.hideCoverSoon();
    }

    @Override
    protected void onPause() {
        unregisterReceiver(tick);
        takeSnapshot();
        super.onPause();
    }

    /** Half-size picture of the screen as it is now, for the helper's instant cover. */
    private void takeSnapshot() {
        int w = root.getWidth() / 2, h = root.getHeight() / 2;
        if (w == 0 || h == 0 || drawer.getVisibility() == View.VISIBLE) return;
        try {
            Bitmap s = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(s);
            c.scale(0.5f, 0.5f);
            root.draw(c);
            snapshot = s;
        } catch (RuntimeException | OutOfMemoryError ignored) { }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeDrawer();
        if (firstTile != null) firstTile.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
    }

    // ---- layout ----

    private void buildClock() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        date = text(36, "sans-serif-medium", 0xEEFFFFFF);
        clock = text(210, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clock.setSingleLine(true);
        date.setSingleLine(true);
        col.addView(date);
        // fixed width that fits any time, so a new minute never wraps or clips the digits
        int clockWidth = Math.round(clock.getPaint().measureText("00:00") * 1.08f) + px(24);
        col.addView(clock, new LinearLayout.LayoutParams(clockWidth, WRAP));

        GlassPanel pill = new GlassPanel(this, 40 * u, 0x24FFFFFF);
        TextView family = text(28, "sans-serif-light", Color.WHITE);
        family.setText(FAMILY);
        family.setLetterSpacing(0.03f);
        family.setPadding(px(26), px(9), px(26), px(11));
        pill.addView(family, new FrameLayout.LayoutParams(WRAP, WRAP));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(WRAP, WRAP);
        plp.topMargin = px(18);
        col.addView(pill, plp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
        lp.leftMargin = px(112);
        lp.topMargin = px(84);
        root.addView(col, lp);
    }

    private static final int TILE = 128, SMALL_TILE = 112;

    private void buildDock() {
        GlassPanel dock = new GlassPanel(this, 76 * u, 0x1EFFFFFF);
        dockRow = new LinearLayout(this);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setPadding(px(22), px(10), px(22), px(10));
        dock.addView(dockRow, new FrameLayout.LayoutParams(WRAP, WRAP));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = px(62);
        root.addView(dock, dlp);

        labelPill = new GlassPanel(this, 40 * u, 0x30FFFFFF);
        label = text(30, "sans-serif-medium", Color.WHITE);
        label.setPadding(px(30), px(10), px(30), px(13));
        labelPill.addView(label, new FrameLayout.LayoutParams(WRAP, WRAP));
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.START);
        llp.bottomMargin = px(62) + Math.round(TILE * 1.3f * u) + px(20) + px(16);
        labelPill.setAlpha(0f);
        root.addView(labelPill, llp);
    }

    private void buildDrawer() {
        drawer = new GlassPanel(this, 56 * u, 0x66101820);
        ScrollView scroll = new ScrollView(this);
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) { Glass.invalidateAll(grid); }
        });
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(70), px(50), px(70), px(50));
        TextView title = text(54, "sans-serif-light", Color.WHITE);
        title.setText("All apps");
        title.setPadding(px(14), 0, 0, px(20));
        col.addView(title);
        grid = new GridLayout(this);
        grid.setColumnCount(9);
        col.addView(grid);
        scroll.addView(col);
        drawer.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(MATCH, MATCH);
        lp.setMargins(px(70), px(60), px(70), px(60));
        drawer.setVisibility(View.GONE);
        root.addView(drawer, lp);
    }

    // ---- apps ----

    private void fillDock() {
        PackageManager pm = getPackageManager();
        dockRow.removeAllViews();
        firstTile = null;
        for (String pkg : FAVOURITES) {
            Intent i = launchIntent(pkg);
            if (i == null) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                addDockTile(pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai), i);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        allAppsTile = new GlassTileView(this, "All apps", gridIcon(), TILE * u, new Runnable() {
            @Override public void run() { openDrawer(); }
        });
        addDockTile(allAppsTile);
        Drawable gear;
        try { gear = pm.getApplicationIcon("com.android.tv.settings"); } catch (PackageManager.NameNotFoundException e) { gear = gridIcon(); }
        addDockTile("Settings", gear, new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        addDockTile("Voice control", micIcon(), new Intent(this, VoiceActivity.class));
        root.post(new Runnable() { @Override public void run() { if (firstTile != null) firstTile.requestFocus(); } });
    }

    private void addDockTile(String name, Drawable icon, final Intent intent) {
        addDockTile(new GlassTileView(this, name, icon, TILE * u, new Runnable() {
            @Override public void run() { launch(intent); }
        }));
    }

    private void addDockTile(GlassTileView t) {
        t.focusListener = new GlassTileView.FocusListener() {
            @Override public void onTileFocused(GlassTileView tile) { moveLabel(tile); }
        };
        dockRow.addView(t);
        if (firstTile == null) firstTile = t;
    }

    private void moveLabel(final GlassTileView tile) {
        if (drawer.getVisibility() == View.VISIBLE) return;
        if (tile.getWidth() == 0) {     // focused before the first layout: place the label once laid out
            tile.post(new Runnable() { @Override public void run() { moveLabel(tile); } });
            return;
        }
        label.setText(tile.label);
        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        labelPill.measure(spec, spec);
        int[] loc = new int[2];
        tile.getLocationOnScreen(loc);
        float x = loc[0] + tile.getWidth() / 2f - labelPill.getMeasuredWidth() / 2f;
        labelPill.animate().translationX(x).alpha(1f).setDuration(220)
            .setUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) { labelPill.invalidate(); }
            }).start();
    }

    private void openDrawer() {
        fillDrawer();
        labelPill.animate().alpha(0f).setDuration(120).start();
        dockRow.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        drawer.setVisibility(View.VISIBLE);
        drawer.setAlpha(0f);
        drawer.animate().alpha(1f).setDuration(220).start();
        Glass.invalidateAll(drawer);
        View first = grid.getChildCount() > 0 ? ((ViewGroup) grid.getChildAt(0)).getChildAt(0) : null;
        if (first != null) first.requestFocus();
    }

    private void closeDrawer() {
        if (drawer.getVisibility() != View.VISIBLE) return;
        drawer.setVisibility(View.GONE);
        dockRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (allAppsTile != null) allAppsTile.requestFocus();
    }

    private void fillDrawer() {
        grid.removeAllViews();
        int cell = Math.round(SMALL_TILE * 1.3f * u);
        for (final App a : listApps()) {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            GlassTileView t = new GlassTileView(this, a.label, a.icon, SMALL_TILE * u, new Runnable() {
                @Override public void run() { closeDrawer(); launch(a.intent); }
            });
            c.addView(t);
            TextView name = text(24, "sans-serif", 0xE6FFFFFF);
            name.setText(a.label);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            c.addView(name, new LinearLayout.LayoutParams(cell + px(16), WRAP));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(px(6), px(4), px(6), px(18));
            grid.addView(c, lp);
        }
    }

    static final class App {
        final String label; final Drawable icon; final Intent intent;
        App(String l, Drawable d, Intent i) { label = l; icon = d; intent = i; }
    }

    private List<App> listApps() {
        PackageManager pm = getPackageManager();
        Map<String, App> apps = new LinkedHashMap<>();
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (apps.containsKey(pkg) || pkg.equals(getPackageName()) || pkg.equals(HomeButtonService.OLD_HOME)) continue;
            Intent i = launchIntent(pkg);
            if (i == null) continue;
            apps.put(pkg, new App(ri.loadLabel(pm).toString(), ri.activityInfo.applicationInfo.loadIcon(pm), i));
        }
        List<App> list = new ArrayList<>(apps.values());
        Collections.sort(list, new java.util.Comparator<App>() {
            @Override public int compare(App a, App b) { return a.label.compareToIgnoreCase(b.label); }
        });
        return list;
    }

    private Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private void launch(Intent i) {
        try { startActivity(i); } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Can't open this app", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- photos ----

    private void showNextPhoto() {
        if (loading) return;
        loading = true;
        final File f = photos.next();
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap wall = f != null ? decodeCover(f) : null;
                if (wall == null) wall = fallbackWallpaper();
                final Bitmap w = wall, b = Glass.makeBlur(wall);
                runOnUiThread(new Runnable() { @Override public void run() { loading = false; crossfade(w, b); } });
            }
        }, "photo").start();
    }

    private void crossfade(final Bitmap wall, Bitmap blur) {
        Glass.blur = blur;
        Glass.invalidateAll(root);
        topPhoto.setImageBitmap(wall);
        topPhoto.setAlpha(0f);
        topPhoto.animate().alpha(1f).setDuration(900).withEndAction(new Runnable() {
            @Override public void run() {
                bottomPhoto.setImageBitmap(wall);
                topPhoto.setImageDrawable(null);
                topPhoto.setAlpha(0f);
            }
        }).start();
    }

    /** Decode a photo and crop it to fill the screen exactly. */
    private Bitmap decodeCover(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= sw && o.outHeight / (sample * 2) >= sh) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeFile(f.getPath(), d);
        if (src == null) return null;
        if (src.getWidth() == sw && src.getHeight() == sh) return src;
        Bitmap out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        float scale = Math.max(sw / (float) src.getWidth(), sh / (float) src.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((sw - src.getWidth() * scale) / 2f, (sh - src.getHeight() * scale) / 2f);
        new Canvas(out).drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** Shown until family photos are copied to the TV. */
    private Bitmap fallbackWallpaper() {
        Bitmap b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, sw, sh, new int[] { 0xFF0F2027, 0xFF203A43, 0xFF2C5364 }, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, sw, sh, p);
        p.setShader(new RadialGradient(sw * 0.72f, sh * 0.3f, sh * 0.7f, 0x8855C1FF, 0x0055C1FF, Shader.TileMode.CLAMP));
        c.drawCircle(sw * 0.72f, sh * 0.3f, sh * 0.7f, p);
        p.setShader(new RadialGradient(sw * 0.28f, sh * 0.85f, sh * 0.6f, 0x77B07CFF, 0x00B07CFF, Shader.TileMode.CLAMP));
        c.drawCircle(sw * 0.28f, sh * 0.85f, sh * 0.6f, p);
        return b;
    }

    // ---- helpers ----

    private void updateClock() {
        Date now = new Date();
        String pattern = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        clock.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
    }

    private ImageView photoLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        return v;
    }

    private TextView text(float size, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        t.setShadowLayer(14 * u, 0, 2 * u, 0x59000000);
        return t;
    }

    private int px(float units) { return Math.round(units * u); }

    /** "Voice control" symbol: a white microphone. */
    private Drawable micIcon() {
        int n = 120;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xE6FFFFFF);
        c.drawRoundRect(new RectF(42, 14, 78, 74), 18, 18, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(7);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawArc(new RectF(30, 34, 90, 90), 0, 180, false, p);
        c.drawLine(60, 90, 60, 106, p);
        c.drawLine(44, 106, 76, 106, p);
        return new BitmapDrawable(getResources(), b);
    }

    /** "All apps" symbol: a 3x3 grid of frosted squares. */
    private Drawable gridIcon() {
        int n = 120;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xE6FFFFFF);
        float cellSize = n / 3f, dot = cellSize * 0.56f;
        for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) {
            float cx = cellSize * x + cellSize / 2, cy = cellSize * y + cellSize / 2;
            c.drawRoundRect(new RectF(cx - dot / 2, cy - dot / 2, cx + dot / 2, cy + dot / 2), dot * 0.3f, dot * 0.3f, p);
        }
        return new BitmapDrawable(getResources(), b);
    }
}
