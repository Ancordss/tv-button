package tv.button;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * First-run instructions, and the same text behind the switcher's "!" button — drawn as glass over the
 * shared blurred backdrop, so it looks like the switcher and not like a settings page.
 *
 * Every string comes from resources, so the text follows the TV's language (values/ English,
 * values-es/ Spanish). The two permissions it explains are granted from a computer with adb, as the
 * README says, but the buttons here open the TV's own pages for anyone doing it with the remote.
 */
public class SetupActivity extends Activity {

    static final String EXTRA_HELP = "help"; // from the "!" button: same steps, no first-run flag

    private static final int STEP_WELCOME = 0;
    private static final int STEP_GESTURES = 1;
    private static final int STEP_ACCESS = 2;
    private static final int STEP_DONE = 3;
    private static final int ACCENT = 0xFF3A4C7A;

    private int step = STEP_WELCOME;
    private boolean help;

    private TextView title;
    private TextView body;
    private Button back;
    private Button continueButton;
    private Button accessibility;
    private Button usage;
    private Button preview;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        help = getIntent().getBooleanExtra(EXTRA_HELP, false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0A0B);
        root.addView(new GlassBackdropView(this, Backdrop.get(), ACCENT, 0xB3000000),
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The glass card the text sits on.
        FrameLayout panel = new FrameLayout(this);
        panel.addView(new GlassPanel(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(56), dp(48), dp(56), dp(40));
        panel.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        title = new TextView(this);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setTextColor(0xF2FFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38);
        column.addView(title);

        body = new TextView(this);
        body.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        body.setTextColor(0xD9FFFFFF);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        body.setLineSpacing(dp(5), 1f);
        body.setPadding(0, dp(18), 0, 0);
        column.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(20), 0, 0);
        column.addView(actions);

        back = pill(R.string.action_back);
        back.setOnClickListener(v -> {
            if (step > STEP_WELCOME) {
                step--;
                show();
            } else {
                finish();
            }
        });
        actions.addView(back);

        accessibility = pill(R.string.action_accessibility);
        accessibility.setOnClickListener(v -> open(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        actions.addView(accessibility);

        usage = pill(R.string.action_usage);
        usage.setOnClickListener(v -> open(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        actions.addView(usage);

        preview = pill(R.string.action_preview);
        preview.setOnClickListener(v -> {
            markDone();
            startActivity(new Intent(this, RecentsActivity.class));
            finish();
        });
        actions.addView(preview);

        continueButton = pill(R.string.action_continue);
        continueButton.setOnClickListener(v -> {
            if (step < STEP_DONE) {
                step++;
                show();
            } else {
                markDone();
                finish();
            }
        });
        actions.addView(continueButton);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        panelLp.setMargins(dp(140), dp(90), dp(140), dp(90));
        root.addView(panel, panelLp);

        setContentView(root);
        show();
    }

    private void open(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Exception ignored) {
            // Some TVs hide one of these pages; the text still says what to do.
        }
    }

    private void markDone() {
        SharedPreferences p = getSharedPreferences(RecentsActivity.PREFS, MODE_PRIVATE);
        p.edit().putBoolean(RecentsActivity.KEY_SETUP_DONE, true).apply();
    }

    private void show() {
        switch (step) {
            case STEP_WELCOME:
                title.setText(R.string.setup_welcome_title);
                body.setText(R.string.setup_welcome_body);
                break;
            case STEP_GESTURES:
                title.setText(R.string.setup_gestures_title);
                body.setText(R.string.setup_gestures_body);
                break;
            case STEP_ACCESS:
                title.setText(R.string.setup_access_title);
                body.setText(R.string.setup_access_body);
                break;
            default:
                title.setText(R.string.setup_done_title);
                body.setText(R.string.setup_done_body);
                break;
        }
        show(back, step > STEP_WELCOME);
        show(accessibility, step == STEP_ACCESS);
        show(usage, step == STEP_ACCESS);
        show(preview, step >= STEP_GESTURES);
        continueButton.setText(step == STEP_DONE ? R.string.action_done
                : help && step == STEP_WELCOME ? R.string.action_close
                : R.string.action_continue);
        continueButton.requestFocus();
    }

    private static void show(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** A glass pill: translucent white, a white rim on focus, and a small scale on the way in. */
    private Button pill(int text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(24), dp(10), dp(24), dp(10));
        b.setBackground(pillBackground(false));
        b.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(pillBackground(focused));
            v.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f)
                    .setDuration(140).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable pillBackground(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(24));
        d.setColor(focused ? 0x4DFFFFFF : 0x1FFFFFFF);
        d.setStroke(dp(2), focused ? 0x99FFFFFF : 0x33FFFFFF);
        return d;
    }

    /** The panel itself: the same glass as the switcher's cards, without the picture behind it. */
    private static class GlassPanel extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF box = new RectF();

        GlassPanel(Context ctx) {
            super(ctx);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float r = dp(32);
            box.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(box, r, r, Path.Direction.CW);

            c.save();
            c.clipPath(clip);
            fill.setColor(0x1FFFFFFF);
            c.drawRect(0, 0, w, h, fill);
            sheen.setShader(new LinearGradient(0, 0, 0, h * 0.4f,
                    0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h * 0.4f, sheen);
            c.restore();

            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(dp(2));
            rim.setShader(new LinearGradient(0, 0, w, h,
                    0x73FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(box, r, r, rim);
            rim.setShader(null);
        }

        private int dp(int v) {
            return Math.round(v * getResources().getDisplayMetrics().density);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
