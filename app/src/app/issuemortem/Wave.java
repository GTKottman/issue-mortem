package app.issuemortem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * The look's one decoration: solid gradient ribbons (deep purples behind, then purple, hot pink and orange) sweeping up
 * to the right and rolling like water. It only goes in empty space (a header's free end, the bottom of the start
 * page), never behind anything, and only animates while it's on screen.
 */
public class Wave extends View {
    static final int[] FIRE = {0xFF7C3AED, 0xFFE0245E, 0xFFFE4602, 0xFFFC840D};

    /**
     * Back to front: colours left to right, where it sits (0 top, 1 bottom), thickness, swell, drift (whole turns per
     * loop), sway, phase. Every motion repeats exactly every LOOP seconds, so it never jumps.
     */
    private static final Object[][] RIBBONS = {
            {new int[] {0xFF1E0B4A, 0xFF2B1468, 0xFF3D286E}, 0.55f, 0.55f, 0.10f, 1, 0.9f, 0.0f},
            {new int[] {0xFF2B1468, 0xFF4A2A8C, 0xFF5B2A9E}, 0.50f, 0.42f, 0.12f, -1, 1.1f, 1.7f},
            {new int[] {0xFF3D1A8A, 0xFF7C3AED, 0xFFB02A8F}, 0.42f, 0.30f, 0.14f, 1, 1.3f, 3.1f},
            {new int[] {FIRE[0], FIRE[1], FIRE[2]}, 0.34f, 0.22f, 0.16f, -2, 1.0f, 4.4f},
            {new int[] {FIRE[1], FIRE[2], FIRE[3]}, 0.26f, 0.12f, 0.18f, 2, 1.2f, 5.9f},
    };
    static final float LOOP = 12;
    private static final float STEP_DP = 12;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final LinearGradient[] shaders = new LinearGradient[RIBBONS.length];
    private float shadedWidth;

    public Wave(Context c) {
        super(c);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (w != shadedWidth) {
            for (int i = 0; i < RIBBONS.length; i++)
                shaders[i] = new LinearGradient(0, 0, w, 0, (int[]) RIBBONS[i][0], null, Shader.TileMode.CLAMP);
            shadedWidth = w;
        }
        // Where we are in the loop, 0..2π.
        float a = (float) (2 * Math.PI * ((SystemClock.uptimeMillis() % (long) (LOOP * 1000)) / (LOOP * 1000)));
        float step = STEP_DP * getResources().getDisplayMetrics().density;
        for (int i = 0; i < RIBBONS.length; i++) {
            Object[] r = RIBBONS[i];
            // Drift plus a slow sway: each ribbon speeds up, eases off and leans back like a tide, never at one speed.
            float phase = (int) r[4] * a + (float) r[5] * (float) Math.sin(a + (float) r[6]) + (float) r[6];
            ribbon(c, w, h, step, (float) r[1], (float) r[2], (float) r[3], phase);
            paint.setShader(shaders[i]);
            c.drawPath(path, paint);
        }
        if (isShown()) postInvalidateOnAnimation();
    }

    /**
     * One ribbon: its top edge rises from the bottom left to the top right with rolling swells, and it is filled down
     * to a lower edge that swells out of step, so the ribbon thickens and thins as it moves.
     */
    private void ribbon(Canvas c, float w, float h, float step, float sit, float thick, float swell, float phase) {
        int n = (int) Math.ceil(w / step);
        float[] xs = new float[n + 1], upper = new float[n + 1], lower = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            float x = Math.min(i * step, w), u = x / w;
            xs[i] = x;
            upper[i] = top(u, h, sit, swell, phase);
            lower[i] = upper[i] + h * thick * (0.6f + 0.4f * (float) Math.sin(u * 5 - phase * 2));
        }
        path.reset();
        path.moveTo(xs[0], upper[0]);
        smooth(xs, upper, 1);
        path.lineTo(xs[n], lower[n]);
        smooth(xs, lower, -1);
        path.close();
    }

    /** Curves through the points (each one a control point, joined at the midpoints), forwards or backwards. */
    private void smooth(float[] xs, float[] ys, int dir) {
        int n = xs.length - 1;
        for (int k = 1; k < n; k++) {
            int i = dir > 0 ? k : n - k, j = i + dir;
            path.quadTo(xs[i], ys[i], (xs[i] + xs[j]) / 2, (ys[i] + ys[j]) / 2);
        }
        int last = dir > 0 ? n : 0;
        path.lineTo(xs[last], ys[last]);
    }

    private static float top(float u, float h, float sit, float swell, float phase) {
        float rise = 1 - (float) Math.pow(u, 1.6);              // high on the right, low on the left
        float base = h * (0.05f + (sit + 0.45f) * rise * 0.9f);
        float roll = (float) (Math.sin(u * 6.5 + phase) * 0.6 + Math.sin(u * 2.3 - phase) * 0.4);
        return base + h * swell * roll;
    }
}
