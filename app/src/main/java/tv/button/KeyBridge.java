package tv.button;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Repurposes the Mi remote's two spare gestures:
 *
 * - the Xiaomi TV+ button (KEYCODE_BUTTON_5, from /vendor/usr/keylayout/Vendor_2717_Product_32b9.kl:
 *   "key 580 BUTTON_5" / "key 150 BUTTON_5") opens TARGET. The vendor app that used to answer it,
 *   com.mitv.tvhome.mitvplus, is disabled by the debloat list.
 * - a double Home press opens the task switcher, after grabbing the screen so the switcher can show
 *   it blurred behind the cards.
 *
 * A key filter is the only way to see a generic button code while another app has the focus: generic
 * codes go to the focused window and nowhere else. Everything except BUTTON_5 and Home falls through
 * untouched.
 */
public class KeyBridge extends AccessibilityService {

    private static final String TARGET = "com.stremio.one";
    private static final android.content.ComponentName TARGET_ACTIVITY =
            new android.content.ComponentName(TARGET, "com.stremio.tv.MainActivity");
    private static final long DOUBLE_PRESS_MS = 600;
    private static final long CAPTURE_DELAY_MS = 2500;   // apps that load a splash need this long before the frame is real
    private static final long CAPTURE_THROTTLE_MS = 3000; // accessibility screenshots are rate limited
    private static final long CAPTURE_REFRESH_MS = 30000; // a card's picture is refreshed at most this often
    private long lastHomeUp = 0;
    private long lastCaptureAt = 0;
    private String lastCaptured = "";
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence name = event.getPackageName();
        if (name == null) {
            return;
        }
        String pkg = name.toString();
        long since = SystemClock.uptimeMillis() - lastCaptureAt;
        if (pkg.equals(getPackageName())
                || (pkg.equals(lastCaptured) && since < CAPTURE_REFRESH_MS)
                || since < CAPTURE_THROTTLE_MS) {
            return;
        }
        // Let the app draw its first screen before grabbing it.
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> capture(pkg), CAPTURE_DELAY_MS);
    }

    @Override
    public void onInterrupt() {
        // Required by the API; nothing to interrupt.
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            return onHome(event);
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_BUTTON_5 || event.getAction() != KeyEvent.ACTION_UP) {
            return false;
        }
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(TARGET);
        if (launch == null) {
            launch = getPackageManager().getLaunchIntentForPackage(TARGET);
        }
        if (launch == null) {
            // Explicit component: works even when package visibility hides the app's intents.
            launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(TARGET_ACTIVITY);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launch);
        } catch (Exception e) {
            // Nothing to open: swallow the key rather than let it reach an app that would act on it.
            return true;
        }
        return true;
    }

    /** Double Home within 600 ms opens the task switcher; a single press is left alone. */
    private boolean onHome(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP) {
            return false;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastHomeUp <= DOUBLE_PRESS_MS) {
            lastHomeUp = 0;
            captureThenOpen();
            return true; // consumed: the second press opened the switcher instead of going Home
        }
        lastHomeUp = now;
        return false;
    }

    /** True when the frame carries no picture: what a FLAG_SECURE window returns. */
    private static boolean isBlank(Bitmap b) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(b, 32, 18, true);
            double min = 1, max = 0, sum = 0;
            for (int y = 0; y < 18; y++) {
                for (int x = 0; x < 32; x++) {
                    int c = small.getPixel(x, y);
                    double l = (0.2126 * android.graphics.Color.red(c)
                            + 0.7152 * android.graphics.Color.green(c)
                            + 0.0722 * android.graphics.Color.blue(c)) / 255.0;
                    min = Math.min(min, l);
                    max = Math.max(max, l);
                    sum += l;
                }
            }
            double mean = sum / (32.0 * 18.0);
            return max - min < 0.06 || mean < 0.02; // uniform, or what a FLAG_SECURE window returns
        } catch (Exception e) {
            return false;
        }
    }

    /** Saves this app's current screen as its card art in the switcher. */
    private void capture(String pkg) {
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            try {
                                Bitmap hw = Bitmap.wrapHardwareBuffer(
                                        result.getHardwareBuffer(), result.getColorSpace());
                                if (hw != null) {
                                    Bitmap shot = hw.copy(Bitmap.Config.ARGB_8888, false);
                                    if (isBlank(shot)) {
                                        // DRM apps (Netflix, Disney+) hand out a black frame: no card art.
                                        Thumbnails.forget(KeyBridge.this, pkg);
                                    } else {
                                        Thumbnails.save(KeyBridge.this, pkg, shot);
                                    }
                                    lastCaptured = pkg;
                                    lastCaptureAt = SystemClock.uptimeMillis();
                                }
                            } catch (Exception ignored) {
                            } finally {
                                try {
                                    result.getHardwareBuffer().close();
                                } catch (Exception ignored) {
                                }
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    /**
     * Grabs the screen the switcher is about to cover, so RecentsActivity can draw it blurred behind
     * the cards. Any failure just opens the switcher on its own gradient.
     */
    private void captureThenOpen() {
        if (Build.VERSION.SDK_INT < 30) {
            openSwitcher();
            return;
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            try {
                                Bitmap hw = Bitmap.wrapHardwareBuffer(
                                        result.getHardwareBuffer(), result.getColorSpace());
                                if (hw != null) {
                                    Backdrop.capture(hw.copy(Bitmap.Config.ARGB_8888, false));
                                }
                            } catch (Exception ignored) {
                            } finally {
                                try {
                                    result.getHardwareBuffer().close();
                                } catch (Exception ignored) {
                                }
                            }
                            openSwitcher();
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            openSwitcher();
                        }
                    });
        } catch (Exception e) {
            openSwitcher();
        }
    }

    private void openSwitcher() {
        try {
            startActivity(new Intent(this, RecentsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (Exception e) {
            // Never eat Home on a failure: the user would be stuck.
        }
    }
}
