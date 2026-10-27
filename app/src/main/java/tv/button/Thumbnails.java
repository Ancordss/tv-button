package tv.button;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Last-screen thumbnails, one small PNG per package, captured by KeyBridge whenever an app comes to
 * the foreground (an accessibility service may call takeScreenshot, and only for the screen that is
 * up). The switcher then shows what the app actually looked like, which is what makes the card read
 * as "go back to where I was".
 *
 * Files live in the app's own cache dir, are never uploaded, and are capped at 1280x720 JPEG q90: about
 * 150-250 KB each, so a full switcher costs a couple of megabytes, pruned to the apps it shows.
 */
final class Thumbnails {

    // The card is 1380x776 px on a 1080p TV: 640x360 was visibly soft once upscaled, 1280x720 is not.
    private static final int W = 1280;
    private static final int H = 720;

    private Thumbnails() {
    }

    private static File file(Context ctx, String pkg) {
        File dir = new File(ctx.getCacheDir(), "thumbs");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return new File(dir, pkg + ".jpg");
    }

    static void save(Context ctx, String pkg, Bitmap screen) {
        File out = file(ctx, pkg);
        if (out == null) {
            return;
        }
        try {
            Bitmap small = Bitmap.createScaledBitmap(screen, W, H, true);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                small.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            }
            if (small != screen) {
                small.recycle();
            }
        } catch (Exception ignored) {
        }
    }

    static Bitmap load(Context ctx, String pkg) {
        File f = file(ctx, pkg);
        if (f == null || !f.exists()) {
            return null;
        }
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    /** Keeps the cache to the apps the switcher is showing, plus a week; drops near-blank leftovers. */
    static void prune(Context ctx, java.util.Set<String> keep) {
        File dir = new File(ctx.getCacheDir(), "thumbs");
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        for (File f : files) {
            String name = f.getName();
            String pkg = name.replaceAll("\\.(png|jpg)$", "");
            boolean stale = f.lastModified() < cutoff || f.length() < 3072;
            boolean legacy = !name.endsWith(".jpg"); // PNG files from before the JPEG change
            if (stale || legacy || !keep.contains(pkg)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    static void forget(Context ctx, String pkg) {
        File f = file(ctx, pkg);
        if (f != null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
