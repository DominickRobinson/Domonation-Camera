package com.domonation.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public final class TickMarkView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] tickFractions = new float[]{0f, 0.125f, 0.25f, 0.375f, 0.5f,
            0.625f, 0.75f, 0.875f, 1f};
    private float defaultFraction = 0.5f;

    public TickMarkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(Color.rgb(17, 17, 17));
        paint.setStrokeCap(Paint.Cap.SQUARE);
    }

    public void configure(float[] fractions, float majorFraction) {
        tickFractions = fractions == null || fractions.length < 2 ? new float[]{0f, 1f} : fractions.clone();
        defaultFraction = Math.max(0f, Math.min(1f, majorFraction));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float bottom = getHeight() - density;
        for (float fraction : tickFractions) {
            float x = left + (right - left) * fraction;
            paint.setStrokeWidth(density);
            canvas.drawLine(x, bottom - 7f * density, x, bottom, paint);
        }
        float majorX = left + (right - left) * defaultFraction;
        paint.setStrokeWidth(2f * density);
        canvas.drawLine(majorX, bottom - 13f * density, majorX, bottom, paint);
    }
}
