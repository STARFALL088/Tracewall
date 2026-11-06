package tracewall.child.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import tracewall.child.R;

/**
 * A stacked bar chart of the last N minutes, drawn on a Canvas.
 *
 * The Week 9 "custom view" item, and the honest way to show "what did this
 * phone try to reach in the last hour" without pulling in a chart library for
 * another dependency. Teal = allowed, rose = blocked, newest on the right.
 */
public final class TrendView extends View {

    private static final int BUCKETS = 20;

    private final Paint allowedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();

    private int[] allowed = new int[0];
    private int[] blocked = new int[0];
    private int max = 1;

    public TrendView(Context c, @Nullable AttributeSet attrs) {
        super(c, attrs);
        allowedPaint.setColor(c.getColor(R.color.tracewall_allow));
        blockedPaint.setColor(c.getColor(R.color.tracewall_block));
        baselinePaint.setColor(c.getColor(tracewall.auth.R.color.tw_border));
        baselinePaint.setStrokeWidth(1f);
    }

    /**
     * @param allowedCounts per-bucket allowed counts, oldest first
     * @param blockedCounts per-bucket blocked counts, oldest first
     */
    public void setSeries(int[] allowedCounts, int[] blockedCounts) {
        this.allowed = allowedCounts == null ? new int[0] : allowedCounts;
        this.blocked = blockedCounts == null ? new int[0] : blockedCounts;
        int m = 1;
        // Scale the complete stack, not each series; mixed bars must fit the view.
        for (int i = 0; i < Math.max(allowed.length, blocked.length); i++) {
            int a = i < allowed.length ? allowed[i] : 0;
            int b = i < blocked.length ? blocked[i] : 0;
            m = Math.max(m, a + b);
        }
        this.max = m;
        invalidate();
    }

    public static int buckets() { return BUCKETS; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0) return;

        float bottom = getPaddingTop() + h;
        canvas.drawLine(getPaddingLeft(), bottom, getPaddingLeft() + w, bottom, baselinePaint);

        int n = Math.max(allowed.length, blocked.length);
        if (n == 0) return;

        float slot = w / n;
        float barW = Math.max(2f, slot * 0.62f);
        float inset = (slot - barW) / 2f;

        for (int i = 0; i < n; i++) {
            int a = i < allowed.length ? allowed[i] : 0;
            int b = i < blocked.length ? blocked[i] : 0;
            if (a == 0 && b == 0) continue;

            float x = getPaddingLeft() + i * slot + inset;
            float totalH = h * (a + b) / (float) max;
            float blockH = h * b / (float) max;
            float allowH = totalH - blockH;

            // Keep the stacked series in the same order as the legend.
            if (allowH > 0) {
                bar.set(x, bottom - blockH - allowH, x + barW, bottom - blockH);
                canvas.drawRoundRect(bar, 2f, 2f, allowedPaint);
            }
            if (blockH > 0) {
                bar.set(x, bottom - blockH, x + barW, bottom);
                canvas.drawRoundRect(bar, 2f, 2f, blockedPaint);
            }
        }
    }
}
