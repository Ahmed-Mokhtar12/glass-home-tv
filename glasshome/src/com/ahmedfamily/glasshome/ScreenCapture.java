package com.ahmedfamily.glasshome;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Takes a small picture of the TV screen for Voice control, so the AI can see what is shown.
 * Android needs a foreground service for this; the permission is granted once (see CHEATSHEET.md).
 * Pictures are made only when you speak to Voice control, are never saved, and DRM video shows as black.
 */
public class ScreenCapture extends Service {
    private static final String TAG = "GlassVoice";
    static final int W = 960, H = 540;
    private static volatile MediaProjection projection;
    private static HandlerThread thread;

    static boolean ready() { return projection != null; }

    /** Asks Android for screen capture. With the one-time permission this shows nothing and returns at once. */
    static void request(Context c) {
        if (projection != null) return;
        c.startActivity(new Intent(c, Ask.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    /** A JPEG of the screen as base64, or null if capture isn't available. Call off the main thread. */
    static String jpegBase64() {
        MediaProjection p = projection;
        if (p == null) return null;
        final ImageReader reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2);
        final CountDownLatch got = new CountDownLatch(1);
        final AtomicReference<Bitmap> shot = new AtomicReference<>();
        Handler h = new Handler(thread.getLooper());
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader r) {
                Image img = r.acquireLatestImage();
                if (img == null || shot.get() != null) { if (img != null) img.close(); return; }
                Image.Plane plane = img.getPlanes()[0];
                ByteBuffer buf = plane.getBuffer();
                int rowPixels = plane.getRowStride() / plane.getPixelStride();
                Bitmap b = Bitmap.createBitmap(rowPixels, H, Bitmap.Config.ARGB_8888);
                b.copyPixelsFromBuffer(buf);
                img.close();
                shot.set(rowPixels == W ? b : Bitmap.createBitmap(b, 0, 0, W, H));
                got.countDown();
            }
        }, h);
        VirtualDisplay vd = null;
        try {
            vd = p.createVirtualDisplay("voice-screen", W, H, 160, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, h);
            if (!got.await(3, TimeUnit.SECONDS)) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            shot.get().compress(Bitmap.CompressFormat.JPEG, 60, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "screen picture failed", e);
            return null;
        } finally {
            if (vd != null) vd.release();
            reader.close();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("voice", "Voice control", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "voice").setContentTitle("Voice control can see the screen")
            .setSmallIcon(android.R.drawable.ic_menu_view).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        if (intent != null && intent.hasExtra("data") && projection == null) {
            if (thread == null) { thread = new HandlerThread("voice-screen"); thread.start(); }
            MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
            MediaProjection p = m.getMediaProjection(intent.getIntExtra("code", 0), (Intent) intent.getParcelableExtra("data"));
            if (p != null) {
                p.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() { projection = null; }
                }, new Handler(thread.getLooper()));
                projection = p;
                Log.i(TAG, "screen capture ready");
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        MediaProjection p = projection;
        projection = null;
        if (p != null) p.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    /** Invisible screen that asks Android for capture and hands the answer to the service. */
    public static class Ask extends Activity {
        @Override
        protected void onCreate(Bundle s) {
            super.onCreate(s);
            startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 1);
        }

        @Override
        protected void onActivityResult(int req, int code, Intent data) {
            if (code == RESULT_OK && data != null) {
                startForegroundService(new Intent(this, ScreenCapture.class).putExtra("code", code).putExtra("data", data));
            } else {
                Log.w(TAG, "screen capture not allowed");
            }
            finish();
            overridePendingTransition(0, 0);
        }
    }
}
