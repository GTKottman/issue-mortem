package app.issuemortem;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Every saved or sent note, kept inside the app so it can be opened and edited again: the original picture, the
 * ink as JSON, the note text and a small thumbnail, in files/history/<name>/. Survives app updates.
 */
public class History {
    public static final class Entry {
        public final String name;
        final File dir;

        Entry(File dir) {
            this.dir = dir;
            this.name = dir.getName();
        }

        public Bitmap thumb() {
            return BitmapFactory.decodeFile(new File(dir, "thumb.jpg").getPath());
        }

        public Bitmap shot() {
            return BitmapFactory.decodeFile(new File(dir, "shot.png").getPath());
        }

        public String ink() throws IOException {
            return read("ink.json");
        }

        public String note() {
            try {
                return read("note.txt");
            } catch (IOException e) {
                return "";
            }
        }

        private String read(String file) throws IOException {
            return new String(Files.readAllBytes(new File(dir, file).toPath()), StandardCharsets.UTF_8);
        }
    }

    private final File root;

    public History(Context c) {
        root = new File(c.getFilesDir(), "history");
    }

    public void save(String name, Bitmap shot, Bitmap picture, String inkJson, String note) throws IOException {
        File dir = new File(root, name);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("couldn't make " + dir);
        try (OutputStream o = new FileOutputStream(new File(dir, "shot.png"))) {
            shot.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        int w = 640, h = Math.round(picture.getHeight() * 640f / picture.getWidth());
        Bitmap small = Bitmap.createScaledBitmap(picture, w, h, true);
        try (OutputStream o = new FileOutputStream(new File(dir, "thumb.jpg"))) {
            small.compress(Bitmap.CompressFormat.JPEG, 85, o);
        }
        Files.write(new File(dir, "ink.json").toPath(), inkJson.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "note.txt").toPath(), note.getBytes(StandardCharsets.UTF_8));
    }

    /** Newest first. */
    public List<Entry> list() {
        File[] dirs = root.listFiles(File::isDirectory);
        List<Entry> out = new ArrayList<>();
        if (dirs == null) return out;
        Arrays.sort(dirs, Collections.reverseOrder());
        for (File d : dirs) if (new File(d, "shot.png").isFile()) out.add(new Entry(d));
        return out;
    }

    public void delete(Entry e) {
        File[] files = e.dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        e.dir.delete();
    }
}
