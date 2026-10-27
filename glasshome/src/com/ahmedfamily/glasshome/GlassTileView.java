package com.ahmedfamily.glasshome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * One app icon as a drop of liquid glass: the wallpaper shows through a lens-like bezel,
 * the app's own icon sits inside with a glossy highlight, and focus swells the drop.
 */
final class GlassTileView extends View {
    interface FocusListener { void onTileFocused(GlassTileView tile); }

    final String label;
    private final float base;            // drop size when not focused
    private final Bitmap icon;
    private final Glass glass = new Glass();
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final BitmapShader iconShader;
    private final Matrix iconMatrix = new Matrix();
    private final RectF r = new RectF(), ir = new RectF();
    private final Rect shadowDst = new Rect();
    private static Bitmap shadowBitmap;
    private float focus;
    private ValueAnimator anim;
    FocusListener focusListener;

    GlassTileView(Context c, String label, Drawable d, float base, final Runnable action) {
        super(c);
        this.label = label;
        this.base = base;
        setFocusable(true);
        setClickable(true);
        setContentDescription(label);
        setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { action.run(); } });
        int size = Math.round(base * 0.74f * 1.2f);
        icon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(icon));
        iconShader = new BitmapShader(icon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        rim.setStyle(Paint.Style.STROKE);
        if (shadowBitmap == null) shadowBitmap = makeShadow();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int s = Math.round(base * 1.3f);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(focus, gained ? 1f : 0f);
        anim.setDuration(gained ? 240 : 180);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) { focus = (float) a.getAnimatedValue(); invalidate(); }
        });
        anim.start();
        if (gained && focusListener != null) focusListener.onTileFocused(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float s = base * (1f + 0.2f * focus), half = s / 2f, rad = s * 0.3f;
        r.set(cx - half, cy - half, cx + half, cy + half);

        // soft shadow under the drop
        int spread = Math.round(s * 0.16f);
        shadowDst.set(Math.round(r.left) - spread, Math.round(r.top) - spread + Math.round(s * 0.07f),
            Math.round(r.right) + spread, Math.round(r.bottom) + spread + Math.round(s * 0.07f));
        shadow.setAlpha(Math.round(90 + 80 * focus));
        canvas.drawBitmap(shadowBitmap, null, shadowDst, shadow);

        // the glass: magnified, blurred wallpaper behind the drop, then a milky tint and top sheen
        if (glass.shade(body, this, cx, cy, 1.18f)) canvas.drawRoundRect(r, rad, rad, body);
        fill.setShader(null);
        fill.setColor(Color.argb(Math.round(34 + 30 * focus), 255, 255, 255));
        canvas.drawRoundRect(r, rad, rad, fill);
        fill.setShader(new LinearGradient(0, r.top, 0, r.top + s * 0.55f,
            Color.argb(Math.round(85 + 50 * focus), 255, 255, 255), 0, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(r, rad, rad, fill);

        // the app's icon inside the drop, with a glossy highlight over its upper half
        float inset = s * 0.14f, irad = rad * 0.7f;
        ir.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset);
        iconMatrix.setRectToRect(new RectF(0, 0, icon.getWidth(), icon.getHeight()), ir, Matrix.ScaleToFit.FILL);
        iconShader.setLocalMatrix(iconMatrix);
        iconPaint.setShader(iconShader);
        canvas.drawRoundRect(ir, irad, irad, iconPaint);
        fill.setShader(new LinearGradient(0, ir.top, 0, ir.top + ir.height() * 0.5f,
            Color.argb(70, 255, 255, 255), 0, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(ir, irad, irad, fill);

        // bright rim, lit from the top-left like a water drop; a glow ring when focused
        float sw = s * (0.018f + 0.01f * focus);
        rim.setStrokeWidth(sw);
        rim.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, new int[] {
            Color.argb(235, 255, 255, 255), Color.argb(45, 255, 255, 255), Color.argb(160, 255, 255, 255) },
            new float[] { 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
        r.inset(sw / 2, sw / 2);
        canvas.drawRoundRect(r, rad, rad, rim);
        if (focus > 0.01f) {
            rim.setShader(null);
            rim.setColor(Color.argb(Math.round(70 * focus), 255, 255, 255));
            rim.setStrokeWidth(s * 0.035f);
            r.inset(-s * 0.035f, -s * 0.035f);
            canvas.drawRoundRect(r, rad * 1.1f, rad * 1.1f, rim);
        }
    }

    /** A blurred dark rounded square, drawn stretched under every drop. */
    private static Bitmap makeShadow() {
        int n = 96, pad = 20;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(150, 0, 0, 0));
        new Canvas(b).drawRoundRect(new RectF(pad, pad, n - pad, n - pad), 18, 18, p);
        Glass.boxBlur(b, 6, 3);
        return b;
    }
}
