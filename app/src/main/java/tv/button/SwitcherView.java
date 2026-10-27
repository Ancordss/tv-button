package tv.button;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.animation.ValueAnimator;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * The card stack, drawn in one view so the order is explicit: every card is painted in list order and
 * the focused one is painted last, which is what puts the open card on top of its overlapping
 * neighbours. Cards overlap by [OVERLAP] and the whole row slides with a decelerating animation when
 * the focus moves, so moving through the stack uncovers the pictures as it goes.
 *
 * Left/Right and OK are handled here: OK opens the focused app, holding OK closes it.
 */
class SwitcherView extends View {

    /** One app: its picture, badge, name and accent. */
    static class Card {
        final String pkg;
        final String label;
        final Bitmap icon;
        final Bitmap thumb;
        final int color;

        Card(String pkg, String label, Bitmap icon, Bitmap thumb, int color) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.thumb = thumb;
            this.color = color;
        }
    }

    interface Listener {
        void onFocus(Card card);

        void onOpen(Card card);

        void onClose(Card card);
    }

    private static final int CARD_W = 690;
    private static final int CARD_H = 388;
    /** How far each card of the pile sticks out to the left of the one in front of it. */
    private static final int STEP = 92;
    private static final int RADIUS = 28;
    private static final long ANIM_MS = 240;
    private static final long LONG_PRESS_MS = 450;

    private final List<Card> cards = new ArrayList<>();
    private final Bitmap backdrop;
    private final Listener listener;

    private int focus = 0;
    /** Animated copy of [focus]: the whole pile slides when the selection changes. */
    private float shift = 0;
    private ValueAnimator anim;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingLongPress;
    private boolean longFired;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gradient = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    SwitcherView(Context ctx, List<Card> cards, Bitmap backdrop, Listener listener) {
        super(ctx);
        this.cards.addAll(cards);
        this.backdrop = backdrop;
        this.listener = listener;
        setFocusable(true);
        setFocusableInTouchMode(true);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setColor(Color.WHITE);
        text.setTextSize(dp(20));
        text.setShadowLayer(dp(8), 0, dp(1), 0xCC000000);
        if (!cards.isEmpty()) {
            listener.onFocus(cards.get(0));
        }
    }

    /** Drops one app from the stack (it was just closed) without rebuilding the screen. */
    void remove(String pkg) {
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).pkg.equals(pkg)) {
                cards.remove(i);
                if (focus >= cards.size()) {
                    focus = Math.max(0, cards.size() - 1);
                }
                if (!cards.isEmpty()) {
                    listener.onFocus(cards.get(focus));
                }
                shift = focus;
                invalidate();
                return;
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        shift = focus;
    }

    /**
     * Left edge of card [i]. The focused card sits just right of centre (so the pile has room on the
     * left) and every older card is one [STEP] further left, behind it.
     */
    private float leftOf(int i, float cardW, float step) {
        return getWidth() * 0.56f - cardW / 2f + (shift - i) * step;
    }

    /** Cards further back are dimmer and slightly smaller, so the pile reads as depth. */
    private static float alphaFor(int depth) {
        return Math.max(0.10f, 0.92f - 0.15f * depth);
    }

    private static float scaleFor(int depth) {
        return Math.max(0.86f, 1f - 0.02f * depth);
    }

    private void move(int delta) {
        int next = Math.max(0, Math.min(cards.size() - 1, focus + delta));
        if (next == focus) {
            return;
        }
        focus = next;
        listener.onFocus(cards.get(focus));
        if (anim != null) {
            anim.cancel();
        }
        anim = ValueAnimator.ofFloat(shift, focus);
        anim.setDuration(ANIM_MS);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            shift = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // The pile sits to the left: Left walks back through older apps, Right goes forward.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            move(1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            move(-1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getRepeatCount() == 0) {
                longFired = false;
                pendingLongPress = () -> {
                    longFired = true;
                    if (focus < cards.size()) {
                        listener.onClose(cards.get(focus));
                    }
                };
                handler.postDelayed(pendingLongPress, LONG_PRESS_MS);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (pendingLongPress != null) {
                handler.removeCallbacks(pendingLongPress);
                pendingLongPress = null;
            }
            if (!longFired && focus < cards.size()) {
                listener.onOpen(cards.get(focus));
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) {
            anim.cancel();
        }
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cardW = dp(CARD_W);
        float cardH = dp(CARD_H);
        float step = dp(STEP);

        for (int i = cards.size() - 1; i >= 0; i--) {
            if (i == focus) {
                continue; // painted last, on top of the pile
            }
            int depth = Math.abs(i - focus);
            drawCard(c, cards.get(i), leftOf(i, cardW, step), cardW, cardH,
                    alphaFor(depth), scaleFor(depth), false); // only the front card is named
        }
        if (focus < cards.size()) {
            drawCard(c, cards.get(focus), leftOf(focus, cardW, step), cardW, cardH, 1f, 1f, true);
        }
    }

    private void drawCard(Canvas c, Card card, float x, float cardW, float cardH,
                          float alpha, float scale, boolean labelled) {
        float radius = dp(RADIUS);
        c.save();
        c.translate(x + cardW / 2f, (getHeight() - cardH) / 2f + cardH / 2f);
        c.scale(scale, scale);
        c.translate(-cardW / 2f, -cardH / 2f);
        c.saveLayerAlpha(0, 0, cardW, cardH, (int) (255 * alpha));

        box.set(0, 0, cardW, cardH);
        clip.reset();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        c.clipPath(clip);

        if (card.thumb != null) {
            float s = Math.max(cardW / card.thumb.getWidth(), cardH / card.thumb.getHeight());
            float dw = card.thumb.getWidth() * s;
            float dh = card.thumb.getHeight() * s;
            src.set(0, 0, card.thumb.getWidth(), card.thumb.getHeight());
            dst.set((cardW - dw) / 2f, (cardH - dh) / 2f, (cardW + dw) / 2f, (cardH + dh) / 2f);
            c.drawBitmap(card.thumb, src, dst, paint);
        } else {
            // Opaque: a translucent field would let the card behind show through and ghost its text.
            gradient.setShader(new LinearGradient(0, 0, 0, cardH,
                    lift(card.color, 0.22f), dim(card.color, 0.45f), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, cardW, cardH, gradient);
            gradient.setShader(null);
            if (card.icon != null) {
                float side = dp(120);
                dst.set((cardW - side) / 2f, (cardH - side) / 2f - dp(10),
                        (cardW + side) / 2f, (cardH + side) / 2f - dp(10));
                c.save();
                Path iconClip = new Path();
                iconClip.addRoundRect(dst, dp(26), dp(26), Path.Direction.CW);
                c.clipPath(iconClip);
                c.drawBitmap(card.icon, null, dst, paint);
                c.restore();
            }
        }

        // Top scrim, so the badge and the name read without covering the app's own dock.
        gradient.setShader(new LinearGradient(0, 0, 0, cardH * 0.22f,
                0x99000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, cardW, cardH * 0.22f, gradient);
        // Glass tint and the top sheen, as GlassOS's GlassPainter lays them.
        fill.setColor(0x14FFFFFF);
        c.drawRect(0, 0, cardW, cardH, fill);
        gradient.setShader(new LinearGradient(0, 0, 0, cardH * 0.45f,
                0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, cardW, cardH * 0.45f, gradient);
        c.restore(); // clip

        if (!labelled) {
            c.restore(); // the transform opened at the top of this method
            return;
        }

        // Badge + name, top-left.
        float badge = dp(34);
        float bx = dp(22);
        float by = dp(22);
        if (card.icon != null) {
            dst.set(bx, by, bx + badge, by + badge);
            c.save();
            Path badgeClip = new Path();
            badgeClip.addRoundRect(dst, dp(9), dp(9), Path.Direction.CW);
            c.clipPath(badgeClip);
            c.drawBitmap(card.icon, null, dst, paint);
            c.restore();
        }
        c.drawText(card.label, bx + badge + dp(14),
                by + badge / 2f - (text.ascent() + text.descent()) / 2f, text);

        // Rim: bright at the top-left, gone at the bottom-right.
        Paint rim = paint;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(dp(2));
        rim.setShader(new LinearGradient(0, 0, cardW, cardH,
                0x73FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(box, radius, radius, rim);
        rim.setShader(null);
        rim.setStyle(Paint.Style.FILL);

        c.restore(); // the transform opened at the top of this method
    }

    /** Opaque shade of [c], lightened by [by] (0..1 of full white). */
    private static int lift(int c, float by) {
        return Color.rgb(
                Math.min(255, (int) (Color.red(c) + 255 * by)),
                Math.min(255, (int) (Color.green(c) + 255 * by)),
                Math.min(255, (int) (Color.blue(c) + 255 * by)));
    }

    /** Opaque shade of [c], darkened by [by] (0..1 of its own value). */
    private static int dim(int c, float by) {
        return Color.rgb(
                Math.max(0, (int) (Color.red(c) * (1 - by))),
                Math.max(0, (int) (Color.green(c) * (1 - by))),
                Math.max(0, (int) (Color.blue(c) * (1 - by))));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
