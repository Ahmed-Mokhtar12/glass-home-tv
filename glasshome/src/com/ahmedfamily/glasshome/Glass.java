package com.ahmedfamily.glasshome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

/**
 * The "liquid glass" material. Android 10 has no live background blur, so the wallpaper is
 * blurred once into a small bitmap, and every glass shape paints the part of that bitmap
 * that lies behind it on screen, slightly magnified like a lens.
 */
final class Glass {
    static final int SCALE = 8;          // the blurred copy is 1/8 of the screen size
    static Bitmap blur;                  // blurred, extra-vivid copy of the current wallpaper

    private final int[] loc = new int[2];
    private final Matrix toBlur = new Matrix();
    private final Matrix inverse = new Matrix();
    private Bitmap shaderBitmap;
    private BitmapShader shader;

    /** Point `p` at the blurred wallpaper behind `v`; false while there is no wallpaper yet. */
    boolean shade(Paint p, View v, float cx, float cy, float lens) {
        if (blur == null) { p.setShader(null); return false; }
        if (shaderBitmap != blur) {
            shaderBitmap = blur;
            shader = new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        v.getLocationOnScreen(loc);
        // view-local point -> magnify around (cx, cy) -> screen position -> blurred-bitmap pixel
        toBlur.setTranslate(-cx, -cy);
        toBlur.postScale(1f / lens, 1f / lens);
        toBlur.postTranslate(cx + loc[0], cy + loc[1]);
        toBlur.postScale(1f / SCALE, 1f / SCALE);
        toBlur.invert(inverse);
        shader.setLocalMatrix(inverse);
        p.setShader(shader);
        return true;
    }

    /** Small, blurred and more saturated copy of the wallpaper, the way glass brightens what's behind it. */
    static Bitmap makeBlur(Bitmap wallpaper) {
        int w = Math.max(1, wallpaper.getWidth() / SCALE), h = Math.max(1, wallpaper.getHeight() / SCALE);
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.45f);
        ColorMatrix lift = new ColorMatrix(new float[] {
            1, 0, 0, 0, 14,
            0, 1, 0, 0, 14,
            0, 0, 1, 0, 14,
            0, 0, 0, 1, 0 });
        cm.postConcat(lift);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(small).drawBitmap(wallpaper, null, new android.graphics.Rect(0, 0, w, h), p);
        boxBlur(small, 4, 3);
        return small;
    }

    /** Repeated box blur (close to a Gaussian); each pass blurs rows and writes them transposed. */
    static void boxBlur(Bitmap b, int r, int passes) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < passes; i++) {
            blurRows(px, tmp, w, h, r);
            blurRows(tmp, px, h, w, r);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void blurRows(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w, ta = 0, tr = 0, tg = 0, tb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[row + Math.min(Math.max(i, 0), w - 1)];
                ta += c >>> 24; tr += (c >> 16) & 255; tg += (c >> 8) & 255; tb += c & 255;
            }
            for (int x = 0; x < w; x++) {
                out[x * h + y] = ((ta / div) << 24) | ((tr / div) << 16) | ((tg / div) << 8) | (tb / div);
                int add = in[row + Math.min(x + r + 1, w - 1)], rem = in[row + Math.max(x - r, 0)];
                ta += (add >>> 24) - (rem >>> 24);
                tr += ((add >> 16) & 255) - ((rem >> 16) & 255);
                tg += ((add >> 8) & 255) - ((rem >> 8) & 255);
                tb += (add & 255) - (rem & 255);
            }
        }
    }

    /** Glass views paint from the wallpaper, so they must redraw when it changes or they move. */
    static void invalidateAll(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateAll(g.getChildAt(i));
        }
    }
}
