package tv.button;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

/**
 * Full-screen glass backdrop: the blurred capture KeyBridge kept, dimmed, with a soft accent glow in
 * the middle. Falls back to a dark gradient when no capture has been taken yet (first boot after
 * install), so both screens always have the same look.
 */
class GlassBackdropView extends View {

    private final Bitmap bmp;
    private final int accent;
    private final int scrim;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();

    GlassBackdropView(Context ctx, Bitmap bmp, int accent, int scrim) {
        super(ctx);
        this.bmp = bmp;
        this.accent = accent;
        this.scrim = scrim;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (bmp != null && !bmp.isRecycled()) {
            dst.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(bmp, null, dst, paint);
        } else {
            paint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                    0xFF141416, 0xFF050506, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
        }

        glow.setShader(new RadialGradient(getWidth() / 2f, getHeight() * 0.45f,
                Math.max(getWidth(), getHeight()) * 0.6f,
                new int[]{(accent & 0x00FFFFFF) | 0x3D000000, 0x00000000},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, getWidth(), getHeight(), glow);

        paint.setColor(scrim);
        c.drawRect(0, 0, getWidth(), getHeight(), paint);
    }
}
