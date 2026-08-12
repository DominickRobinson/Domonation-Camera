package com.domonation.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

final class ZoomImageView extends ImageView {
    private final Matrix imageTransform = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private float scale = 1f;
    private float lastX;
    private float lastY;
    private long lastTap;

    ZoomImageView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        float next = Math.max(1f, Math.min(6f, scale * detector.getScaleFactor()));
                        float factor = next / scale;
                        scale = next;
                        imageTransform.postScale(factor, factor,
                                detector.getFocusX(), detector.getFocusY());
                        setImageMatrix(imageTransform);
                        return true;
                    }
                });
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { fit(); }
    @Override public void setImageBitmap(Bitmap bitmap) { super.setImageBitmap(bitmap); post(this::fit); }

    private void fit() {
        if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) return;
        float base = Math.min(getWidth() / (float) getDrawable().getIntrinsicWidth(),
                getHeight() / (float) getDrawable().getIntrinsicHeight());
        float dx = (getWidth() - getDrawable().getIntrinsicWidth() * base) / 2f;
        float dy = (getHeight() - getDrawable().getIntrinsicHeight() * base) / 2f;
        imageTransform.reset();
        imageTransform.postScale(base, base);
        imageTransform.postTranslate(dx, dy);
        scale = 1f;
        setImageMatrix(imageTransform);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            long now = System.currentTimeMillis();
            if (now - lastTap < 300) fit();
            lastTap = now;
            lastX = event.getX();
            lastY = event.getY();
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE &&
                !scaleDetector.isInProgress() && scale > 1f) {
            imageTransform.postTranslate(event.getX() - lastX, event.getY() - lastY);
            setImageMatrix(imageTransform);
            lastX = event.getX();
            lastY = event.getY();
        }
        return true;
    }
}
