package tv.button;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * tvOS-style app switcher: one stack of big cards, each showing the app's own last screen (captured by
 * KeyBridge while the app was in front), over the blurred screen it was opened on and lit by the focused
 * app's accent colour.
 *
 * Opened by a double Home press; this firmware has no switcher of its own once the stock launcher is
 * disabled (KEYCODE_APP_SWITCH is a no-op). OK opens, holding OK closes, Back leaves. No live
 * previews: another app's screen needs MediaProjection, which asks the user per app.
 */
public class RecentsActivity extends Activity {

    static final String PREFS = "tvbutton";
    static final String KEY_SETUP_DONE = "setupDone";

    private static final long WINDOW_MS = 24L * 60 * 60 * 1000; // "recent" = used in the last day
    private static final int MAX_CARDS = 12;

    private GlowView glow;
    private SwitcherView switcher;
    private Bitmap backdrop;
    private final List<Bitmap> loaded = new ArrayList<>();
    /** Apps closed from the switcher: hidden here even when their process comes straight back. */
    private final Set<String> closed = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // First time this ever runs: the instructions come before the switcher does.
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SETUP_DONE, false)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        backdrop = Backdrop.get(); // half resolution and already blurred, shared with the help screen

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0A0B);
        root.addView(new GlassBackdropView(this, backdrop, accentOf(), 0xB3000000),
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        glow = new GlowView(this);
        root.addView(glow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        List<SwitcherView.Card> cards = buildCards();
        if (cards.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.switcher_empty);
            empty.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            empty.setTextColor(0x8CFFFFFF);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            empty.setGravity(Gravity.CENTER);
            root.addView(empty, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            switcher = new SwitcherView(this, cards, backdrop, listener);
            switcher.setId(View.generateViewId());
            root.addView(switcher, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            switcher.requestFocus();
        }

        TextView help = new TextView(this);
        help.setText("!");
        help.setId(View.generateViewId());
        help.setContentDescription(getString(R.string.switcher_help_button));
        help.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        help.setTextColor(0xE6FFFFFF);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        help.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x26FFFFFF);
        circle.setStroke(dp(2), 0x4DFFFFFF);
        help.setBackground(circle);
        help.setFocusable(true);
        help.setClickable(true);
        help.setOnClickListener(v -> startActivity(
                new Intent(this, SetupActivity.class).putExtra(SetupActivity.EXTRA_HELP, true)));
        if (switcher != null) {
            help.setNextFocusUpId(switcher.getId());
            switcher.setNextFocusDownId(help.getId());
        }
        FrameLayout.LayoutParams helpLp = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.BOTTOM | Gravity.END);
        helpLp.setMargins(0, 0, dp(36), dp(30));
        root.addView(help, helpLp);

        setContentView(root);
    }

    private final SwitcherView.Listener listener = new SwitcherView.Listener() {
        @Override
        public void onFocus(SwitcherView.Card card) {
            int[] pos = new int[2];
            glow.getLocationOnScreen(pos);
            glow.set(card.color, getWindowWidth() / 2f, getWindowHeight() / 2f);
        }

        @Override
        public void onOpen(SwitcherView.Card card) {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(card.pkg);
            if (launch == null) {
                launch = getPackageManager().getLaunchIntentForPackage(card.pkg);
            }
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                finish();
            }
        }

        @Override
        public void onClose(SwitcherView.Card card) {
            ActivityManager am = getSystemService(ActivityManager.class);
            if (am != null) {
                am.killBackgroundProcesses(card.pkg);
            }
            Thumbnails.forget(RecentsActivity.this, card.pkg);
            closed.add(card.pkg);
            if (switcher != null) {
                switcher.remove(card.pkg); // one card leaves; the stack and its pictures stay put
            }
            Toast.makeText(RecentsActivity.this,
                    getString(R.string.switcher_closed, card.label), Toast.LENGTH_SHORT).show();
        }
    };

    /** TV Button has no icon art to sample, so the glow is its own neutral slate. */
    private static int accentOf() {
        return 0xFF3A4C7A;
    }

    private int getWindowWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int getWindowHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private List<SwitcherView.Card> buildCards() {
        List<SwitcherView.Card> cards = new ArrayList<>();
        PackageManager pm = getPackageManager();
        List<String> pkgs = recentPackages();
        Set<String> keep = new HashSet<>(pkgs);
        keep.addAll(closed);
        Thumbnails.prune(this, keep); // the cache never grows past what the switcher shows
        for (String pkg : pkgs) {
            Bitmap thumb = Thumbnails.load(this, pkg);
            if (thumb != null) {
                loaded.add(thumb);
            }
            Drawable icon = null;
            String label = pkg;
            int color = 0xFF2A3350;
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                icon = pm.getApplicationIcon(pkg);
            } catch (Exception ignored) {
            }
            Bitmap iconBmp = icon == null ? null : render(icon, 256, 256);
            if (iconBmp != null) {
                loaded.add(iconBmp);
                color = dominant(iconBmp);
            }
            cards.add(new SwitcherView.Card(pkg, label, iconBmp, thumb, color));
        }
        return cards;
    }

    @Override
    protected void onDestroy() {
        for (Bitmap b : loaded) {
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
        }
        loaded.clear();
        super.onDestroy();
    }

    private static Bitmap render(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    /** Average of the opaque, reasonably saturated pixels; falls back to all opaque pixels. */
    private static int dominant(Bitmap source) {
        Bitmap small = Bitmap.createScaledBitmap(source, 24, 24, true);
        long r = 0, g = 0, bl = 0, n = 0;
        long r2 = 0, g2 = 0, b2 = 0, n2 = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                int c = small.getPixel(x, y);
                if (Color.alpha(c) < 200) {
                    continue;
                }
                Color.colorToHSV(c, hsv);
                r2 += Color.red(c);
                g2 += Color.green(c);
                b2 += Color.blue(c);
                n2++;
                if (hsv[1] < 0.25f || hsv[2] < 0.15f) {
                    continue;
                }
                r += Color.red(c);
                g += Color.green(c);
                bl += Color.blue(c);
                n++;
            }
        }
        if (n > 0) {
            return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        }
        if (n2 > 0) {
            return Color.rgb((int) (r2 / n2), (int) (g2 / n2), (int) (b2 / n2));
        }
        return 0xFF2A3350;
    }

    /** Full-screen ambient glow: the focused card's accent, as the Top Shelf glow sits behind Home. */
    private static class GlowView extends View {
        private int color = 0;
        private float cx = -1, cy = -1;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlowView(Context ctx) {
            super(ctx);
        }

        void set(int color, float x, float y) {
            this.color = color;
            this.cx = x;
            this.cy = y;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (cx < 0) {
                return;
            }
            float radius = Math.max(getWidth(), getHeight()) * 0.62f;
            int inner = (color & 0x00FFFFFF) | 0x3D000000;
            paint.setShader(new RadialGradient(cx, cy, radius,
                    new int[]{inner, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
        }
    }

    /** Packages launched in the last day, most recent first, one entry per package. */
    private List<String> recentPackages() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        if (usm == null) {
            return new ArrayList<>();
        }
        long now = System.currentTimeMillis();
        Map<String, Long> lastUsed = new HashMap<>();
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - WINDOW_MS, now);
        if (stats != null) {
            for (UsageStats s : stats) {
                if (s.getLastTimeUsed() < now - WINDOW_MS) {
                    continue;
                }
                Long prev = lastUsed.get(s.getPackageName());
                if (prev == null || s.getLastTimeUsed() > prev) {
                    lastUsed.put(s.getPackageName(), s.getLastTimeUsed());
                }
            }
        }
        List<String> pkgs = new ArrayList<>();
        for (Map.Entry<String, Long> e : lastUsed.entrySet()) {
            String pkg = e.getKey();
            if (!pkg.equals(getPackageName()) && !closed.contains(pkg) && isLaunchable(pkg)) {
                pkgs.add(pkg);
            }
        }
        pkgs.sort((a, b) -> Long.compare(lastUsed.get(b), lastUsed.get(a)));
        return pkgs.size() > MAX_CARDS ? new ArrayList<>(pkgs.subList(0, MAX_CARDS)) : pkgs;
    }

    private boolean isLaunchable(String pkg) {
        PackageManager pm = getPackageManager();
        return pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
