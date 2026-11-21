package com.borislav007.bzview.viewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends real two-finger pinch gestures to the viewer screen and checks that the video
 * zooms in, zooms back out, and resets.
 */
@RunWith(AndroidJUnit4.class)
public class ZoomGestureTest {

    @Test
    public void pinchOutThenPinchInReturnsToNormalSize() {
        try (ActivityScenario<ViewerActivity> scenario = ActivityScenario.launch(ViewerActivity.class)) {
            scenario.onActivity(activity -> {
                View root = activity.findViewById(R.id.root);
                ImageView video = activity.findViewById(R.id.videoView);
                float cx = root.getWidth() / 2f;
                float cy = root.getHeight() / 2f;

                assertEquals(1f, video.getScaleX(), 0.001f);

                pinch(root, cx, cy, 150f, 900f);
                float zoomed = video.getScaleX();
                assertTrue("pinch out should zoom in, scale=" + zoomed, zoomed > 1.5f);

                pinch(root, cx, cy, 900f, 150f);
                float after = video.getScaleX();
                assertTrue("pinch in should zoom back out, scale=" + after, after < 1.01f);
            });
        }
    }

    @Test
    public void doubleTapZoomsInAndSecondDoubleTapResets() {
        try (ActivityScenario<ViewerActivity> scenario = ActivityScenario.launch(ViewerActivity.class)) {
            final AtomicReference<Float> zoomedScale = new AtomicReference<>();
            scenario.onActivity(activity -> {
                View root = activity.findViewById(R.id.root);
                ImageView video = activity.findViewById(R.id.videoView);
                float cx = root.getWidth() / 2f;
                float cy = root.getHeight() / 2f;

                doubleTap(root, cx, cy);
                zoomedScale.set(video.getScaleX());
                assertTrue("double tap should zoom in, scale=" + zoomedScale.get(), zoomedScale.get() > 2f);

                doubleTap(root, cx, cy);
                assertEquals("second double tap should reset", 1f, video.getScaleX(), 0.001f);
            });
        }
    }

    // ---------------------------------------------------------------- synthetic touch helpers

    private static void doubleTap(View target, float x, float y) {
        long t = SystemClock.uptimeMillis();
        tap(target, t, x, y);
        tap(target, t + 100, x, y);
    }

    private static void tap(View target, long downTime, float x, float y) {
        send(target, downTime, downTime, MotionEvent.ACTION_DOWN, new float[]{x}, new float[]{y});
        send(target, downTime, downTime + 30, MotionEvent.ACTION_UP, new float[]{x}, new float[]{y});
    }

    /** Two fingers on a horizontal line around (cx, cy); their distance goes from startSpan to endSpan. */
    private static void pinch(View target, float cx, float cy, float startSpan, float endSpan) {
        long down = SystemClock.uptimeMillis();
        long t = down;
        float left = cx - startSpan / 2f;
        float right = cx + startSpan / 2f;

        send(target, down, t, MotionEvent.ACTION_DOWN, new float[]{left}, new float[]{cy});
        t += 16;
        int pointerDown = MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        send(target, down, t, pointerDown, new float[]{left, right}, new float[]{cy, cy});

        int steps = 12;
        for (int i = 1; i <= steps; i++) {
            float span = startSpan + (endSpan - startSpan) * i / steps;
            t += 16;
            send(target, down, t, MotionEvent.ACTION_MOVE,
                    new float[]{cx - span / 2f, cx + span / 2f}, new float[]{cy, cy});
        }

        float endLeft = cx - endSpan / 2f;
        float endRight = cx + endSpan / 2f;
        t += 16;
        int pointerUp = MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        send(target, down, t, pointerUp, new float[]{endLeft, endRight}, new float[]{cy, cy});
        t += 16;
        send(target, down, t, MotionEvent.ACTION_UP, new float[]{endLeft}, new float[]{cy});
    }

    private static void send(View target, long downTime, long eventTime, int action, float[] xs, float[] ys) {
        int n = xs.length;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = xs[i];
            coords[i].y = ys[i];
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, n, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        target.dispatchTouchEvent(event);
        event.recycle();
    }
}
