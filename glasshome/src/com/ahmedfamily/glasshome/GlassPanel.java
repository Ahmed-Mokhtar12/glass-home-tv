package com.ahmedfamily.glasshome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.widget.FrameLayout;

/** A rounded pane of liquid glass that can hold other views (the dock, labels, the app drawer). */
class GlassPanel extends FrameLayout {
    private final Glass glass = new Glass();
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float radius;
    private final int tintColor;
    private int sheenHeight = -1;

    GlassPanel(Context c, float radius, int tintColor) {
        super(c);
        this.radius = radius;
        this.tintColor = tintColor;
        setWillNotDraw(false);
        tint.setColor(tintColor);
        rim.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float rad = Math.min(radius, h / 2f);
        r.set(0, 0, w, h);
        if (glass.shade(body, this, w / 2f, h / 2f, 1.04f)) canvas.drawRoundRect(r, rad, rad, body);
        canvas.drawRoundRect(r, rad, rad, tint);
        if (sheenHeight != h) {
            sheenHeight = h;
            sheen.setShader(new LinearGradient(0, 0, 0, h * 0.6f, Color.argb(55, 255, 255, 255), 0, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(0, 0, w * 0.6f, h, new int[] {
                Color.argb(200, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(120, 255, 255, 255) },
                new float[] { 0f, 0.6f, 1f }, Shader.TileMode.CLAMP));
        }
        canvas.drawRoundRect(r, rad, rad, sheen);
        float sw = Math.max(1.5f, h / 110f);
        rim.setStrokeWidth(sw);
        r.inset(sw / 2, sw / 2);
        canvas.drawRoundRect(r, rad, rad, rim);
    }
}
