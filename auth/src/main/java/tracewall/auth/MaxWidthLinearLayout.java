package tracewall.auth;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** A match-parent column that remains readable on tablets and still fits split-screen windows. */
public final class MaxWidthLinearLayout extends LinearLayout {
    private final int maxWidth;

    public MaxWidthLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray values = context.obtainStyledAttributes(attrs, R.styleable.MaxWidthLinearLayout);
        maxWidth = values.getDimensionPixelSize(R.styleable.MaxWidthLinearLayout_tracewallMaxWidth,
            Integer.MAX_VALUE);
        values.recycle();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (mode != MeasureSpec.UNSPECIFIED && width > maxWidth)
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidth, mode);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}