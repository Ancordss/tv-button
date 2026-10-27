package tv.button;

import android.graphics.Bitmap;

/**
 * Holds the latest capture, already small and blurred, so both the switcher and the setup screen can
 * sit on the same glass backdrop without holding two full-screen bitmaps.
 *
 * KeyBridge fills it from the accessibility screenshot; nothing reads it destructively, so opening the
 * help from the switcher keeps the same background.
 */
final class Backdrop {

    private static Bitmap small;

    private Backdrop() {
    }

    /** Stores a half-resolution, blurred copy of [screen] and drops the original. */
    static void capture(Bitmap screen) {
        if (screen == null) {
            return;
        }
        Bitmap half = Bitmap.createScaledBitmap(screen, 960, 540, true);
        Bitmap a = Bitmap.createScaledBitmap(half, 80, 45, true);
        Bitmap b = Bitmap.createScaledBitmap(a, 160, 90, true);
        Bitmap blurred = Bitmap.createScaledBitmap(b, 960, 540, true);
        small = blurred;
        screen.recycle();
    }

    static Bitmap get() {
        return small;
    }

    static void clear() {
        if (small != null && !small.isRecycled()) {
            small.recycle();
        }
        small = null;
    }
}
