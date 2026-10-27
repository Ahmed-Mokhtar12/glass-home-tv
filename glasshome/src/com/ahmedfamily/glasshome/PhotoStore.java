package com.ahmedfamily.glasshome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The family photos. The PC pushes a set into the app's shared folder (incoming/ plus a
 * manifest.txt written last); they are moved into the app's private storage, where other apps
 * on the TV can't read them. Photos are shown in a shuffled order so none repeats until all
 * have been shown.
 */
final class PhotoStore {
    private final File dir;
    private final File incoming;
    private final SharedPreferences prefs;

    PhotoStore(Context c) {
        dir = new File(c.getFilesDir(), "photos");
        dir.mkdirs();
        File ext = c.getExternalFilesDir(null);
        incoming = ext == null ? null : new File(ext, "incoming");
        if (incoming != null) incoming.mkdirs();
        prefs = c.getSharedPreferences(HomeButtonService.PREFS, Context.MODE_PRIVATE);
    }

    /** Replace the photo set with a newly pushed one, if the PC sent one. */
    boolean importIncoming() {
        if (incoming == null) return false;
        File manifest = new File(incoming, "manifest.txt");
        if (!manifest.exists()) return false;
        List<String> names = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new FileReader(manifest))) {
            for (String line; (line = in.readLine()) != null; ) {
                line = line.trim();
                if (line.endsWith(".jpg") && !line.contains("/")) names.add(line);
            }
        } catch (IOException e) {
            return false;
        }
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (!names.contains(f.getName())) f.delete();
        for (String n : names) {
            File src = new File(incoming, n);
            if (src.exists() && copy(src, new File(dir, n))) src.delete();
        }
        manifest.delete();
        prefs.edit().remove("queue").apply();
        return true;
    }

    /** The next photo to show, or null when there are none yet. */
    File next() {
        String[] all = dir.list();
        if (all == null || all.length == 0) return null;
        List<String> have = Arrays.asList(all);
        List<String> queue = new ArrayList<>();
        for (String n : prefs.getString("queue", "").split("\n")) if (have.contains(n)) queue.add(n);
        String last = prefs.getString("last", "");
        if (queue.isEmpty()) {
            queue.addAll(have);
            Collections.shuffle(queue);
            if (queue.size() > 1 && queue.get(0).equals(last)) Collections.swap(queue, 0, 1);
        }
        String pick = queue.remove(0);
        StringBuilder rest = new StringBuilder();
        for (String n : queue) rest.append(n).append('\n');
        prefs.edit().putString("queue", rest.toString()).putString("last", pick).apply();
        return new File(dir, pick);
    }

    private static boolean copy(File from, File to) {
        File part = new File(to.getPath() + ".part");
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } catch (IOException e) {
            part.delete();
            return false;
        }
        return part.renameTo(to);
    }
}
