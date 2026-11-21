package com.borislav007.bzview.viewer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;

/**
 * Pinch to zoom, drag to pan while zoomed, double-tap to zoom in or reset.
 * Works on the view itself (scale + translation), so every new video frame keeps the zoom.
 */
final class ZoomPanController implements View.OnTouchListener {

    private static final float MAX_ZOOM = 6f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;

    private final ImageView view;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    private float scale = 1f;
    private float tx = 0f;
    private float ty = 0f;

    @SuppressLint("ClickableViewAccessibility")
    ZoomPanController(Context context, ImageView view) {
        this.view = view;

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        zoomTo(scale * detector.getScaleFactor(),
                                detector.getFocusX() - ZoomPanController.this.view.getWidth() / 2f,
                                detector.getFocusY() - ZoomPanController.this.view.getHeight() / 2f);
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (scale > 1f && !scaleDetector.isInProgress()) {
                    tx -= distanceX;
                    ty -= distanceY;
                    apply();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (scale > 1f) {
                    reset();
                } else {
                    zoomTo(DOUBLE_TAP_ZOOM,
                            e.getX() - ZoomPanController.this.view.getWidth() / 2f,
                            e.getY() - ZoomPanController.this.view.getHeight() / 2f);
                }
                return true;
            }
        });

        view.setOnTouchListener(this);
        // Keep the picture inside its bounds when the view changes size (e.g. full screen).
        view.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> apply());
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    /** Back to normal size. */
    void reset() {
        scale = 1f;
        tx = 0f;
        ty = 0f;
        apply();
    }

    /** Call when the picture's size may have changed (for example a different camera). */
    void refresh() {
        apply();
    }

    /**
     * Zooms to {@code newScale}, keeping the point (fx, fy) - measured from the view's center -
     * under the user's fingers.
     */
    private void zoomTo(float newScale, float fx, float fy) {
        float s = Math.max(1f, Math.min(MAX_ZOOM, newScale));
        float ratio = s / scale;
        tx = fx - (fx - tx) * ratio;
        ty = fy - (fy - ty) * ratio;
        scale = s;
        apply();
    }

    /** Limits panning so the picture can never be dragged out of view, then applies the transform. */
    private void apply() {
        float viewW = view.getWidth();
        float viewH = view.getHeight();
        float contentW = viewW;
        float contentH = viewH;
        Drawable d = view.getDrawable();
        if (d != null && d.getIntrinsicWidth() > 0 && d.getIntrinsicHeight() > 0 && viewW > 0 && viewH > 0) {
            float fit = Math.min(viewW / d.getIntrinsicWidth(), viewH / d.getIntrinsicHeight());
            contentW = d.getIntrinsicWidth() * fit;
            contentH = d.getIntrinsicHeight() * fit;
        }
        float maxX = Math.max(0f, (contentW * scale - viewW) / 2f);
        float maxY = Math.max(0f, (contentH * scale - viewH) / 2f);
        tx = Math.max(-maxX, Math.min(maxX, tx));
        ty = Math.max(-maxY, Math.min(maxY, ty));
        view.setScaleX(scale);
        view.setScaleY(scale);
        view.setTranslationX(tx);
        view.setTranslationY(ty);
    }
}
