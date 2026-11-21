package com.borislav007.bzview.camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Draws the current time (with seconds) on a dark rounded box in the top-left corner of a
 * video frame. Because it is drawn into the frame itself, a clock that stops moving on the
 * viewer means the stream has stalled. Used from a single analysis thread only.
 */
final class ClockOverlay {

    private final SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final Date date = new Date();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect textBounds = new Rect();
    private final RectF box = new RectF();

    ClockOverlay() {
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        boxPaint.setColor(0xCC000000); // dark, mostly opaque
    }

    /** Returns a bitmap with the clock drawn on it (the same bitmap when it is mutable). */
    Bitmap draw(Bitmap frame) {
        Bitmap target = frame;
        if (!target.isMutable()) {
            target = frame.copy(Bitmap.Config.ARGB_8888, true);
            frame.recycle();
        }
        date.setTime(System.currentTimeMillis());
        String text = format.format(date);
        float textSize = Math.max(18f, Math.min(target.getWidth(), target.getHeight()) * 0.06f);
        textPaint.setTextSize(textSize);
        textPaint.getTextBounds(text, 0, text.length(), textBounds);

        float pad = textSize * 0.4f;
        float margin = textSize * 0.4f;
        box.set(margin, margin,
                margin + textBounds.width() + pad * 2,
                margin + textBounds.height() + pad * 2);

        Canvas canvas = new Canvas(target);
        canvas.drawRoundRect(box, pad, pad, boxPaint);
        canvas.drawText(text, box.left + pad - textBounds.left, box.top + pad - textBounds.top, textPaint);
        return target;
    }
}
