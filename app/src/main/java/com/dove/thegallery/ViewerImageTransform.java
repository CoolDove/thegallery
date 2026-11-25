package com.dove.thegallery;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/**
 * Zoom and pan state for the full-screen viewer image.
 *
 * <p>The only stored state is a zoom ratio relative to best fit (1 = the whole image fitted to
 * the viewport) and a pan offset from the centred position. Best fit, the pixel scale, the
 * on-screen size and the pan bounds are derived from the ImageView and its bitmap on every
 * apply, so no cached measurement can go stale and block zooming.
 */
final class ViewerImageTransform {
    static final int ZOOM_IN = 1;
    static final int ZOOM_OUT = -1;
    private static final float STEP = 1.25f;
    /** Zooming out stops at half of best fit. */
    private static final float MIN_RATIO = 0.5f;
    /** Zooming in stops at eight times best fit, and never below 100%. */
    private static final float MAX_RATIO = 8f;
    private static final float EPSILON = 0.001f;

    /** Outcome of one zoom press: whether the ratio moved, and whether it landed on a stop. */
    static final class ZoomStep {
        final boolean changed;
        final boolean snapped;
        final float from, to;
        ZoomStep(boolean changed, boolean snapped, float from, float to) {
            this.changed = changed;
            this.snapped = snapped;
            this.from = from;
            this.to = to;
        }
    }

    private final Matrix matrix = new Matrix();
    /** Zoom relative to best fit; 1 is best fit, 2 is twice best fit. */
    private float ratio = 1f;
    /** Pan from the centred position, in screen pixels, only meaningful above best fit. */
    private float panX, panY;
    /** Geometry of the last measurement, refreshed on every apply. */
    private int imageWidth, imageHeight, viewWidth, viewHeight;
    private float fit = 1f;
    private int percent = 100;

    /** Forget zoom and pan; the next apply fits the image to the viewport again. */
    void reset() {
        ratio = 1f;
        panX = 0f;
        panY = 0f;
    }

    float ratio() { return ratio; }

    /** Current scale as a percentage of the image's native pixels. */
    int percent() { return percent; }

    boolean isAdjusted() { return Math.abs(ratio - 1f) > EPSILON; }

    /** True when the image is larger than best fit, so it may pan. */
    boolean isZoomed() { return ratio > 1f + EPSILON; }

    /** Fits the whole image to the viewport and centres it. */
    void fitToView(ImageView view) {
        ratio = 1f;
        panX = 0f;
        panY = 0f;
        apply(view);
    }

    /** Applies the stored ratio and pan to the view, clamped to its current geometry. */
    void apply(ImageView view) {
        if (!measure(view)) return;
        float scale = fit * ratio;
        float scaledWidth = imageWidth * scale;
        float scaledHeight = imageHeight * scale;
        float centredX = (viewWidth - scaledWidth) / 2f;
        float centredY = (viewHeight - scaledHeight) / 2f;
        float tx = scaledWidth <= viewWidth
                ? centredX : clamp(centredX + panX, viewWidth - scaledWidth, 0f);
        float ty = scaledHeight <= viewHeight
                ? centredY : clamp(centredY + panY, viewHeight - scaledHeight, 0f);
        panX = tx - centredX;
        panY = ty - centredY;
        matrix.setScale(scale, scale);
        matrix.postTranslate(tx, ty);
        view.setImageMatrix(matrix);
        percent = Math.round(scale * 100f);
    }

    /** True when the ratio cannot move further in this direction. */
    boolean atLimit(int direction) {
        return direction == ZOOM_IN ? ratio >= maxRatio() - EPSILON : ratio <= MIN_RATIO + EPSILON;
    }

    /** Sets an explicit ratio, clamped to the zoom range. Used by the snap animation. */
    void setRatio(ImageView view, float value) {
        if (!measure(view)) return;
        ratio = clamp(value, MIN_RATIO, maxRatio());
        apply(view);
    }

    /**
     * One zoom press. A step that would cross best fit or 100% lands exactly on that stop, so
     * every press produces a visible change until the ratio reaches a limit.
     */
    ZoomStep zoom(ImageView view, int direction) {
        if (!measure(view)) return new ZoomStep(false, false, ratio, ratio);
        float scale = fit * ratio;
        float minimum = fit * MIN_RATIO;
        float maximum = Math.max(fit * MAX_RATIO, 1f);
        float target = direction == ZOOM_IN ? scale * STEP : scale / STEP;
        boolean snapped = false;
        if (direction == ZOOM_IN) {
            if (scale < fit && target > fit) {
                target = fit;
                snapped = true;
            } else if (scale < 1f && target > 1f && 1f >= minimum && 1f <= maximum) {
                target = 1f;
                snapped = true;
            }
            if (target > maximum) {
                target = maximum;
                snapped = true;
            }
        } else {
            if (scale > 1f && target < 1f && 1f >= minimum && 1f <= maximum) {
                target = 1f;
                snapped = true;
            } else if (scale > fit && target < fit) {
                target = fit;
                snapped = true;
            }
            if (target < minimum) {
                target = minimum;
                snapped = true;
            }
        }
        target = clamp(target, minimum, maximum);
        float from = ratio;
        ratio = target / fit;
        apply(view);
        return new ZoomStep(Math.abs(ratio - from) > EPSILON, snapped, from, ratio);
    }

    /** Moves the image by screen pixels; only possible above best fit. */
    boolean panBy(ImageView view, float dx, float dy) {
        if (!measure(view) || !isZoomed()) return false;
        float beforeX = panX, beforeY = panY;
        panX += dx;
        panY += dy;
        apply(view);
        return Math.abs(panX - beforeX) > 0.5f || Math.abs(panY - beforeY) > 0.5f;
    }

    private float maxRatio() { return Math.max(MAX_RATIO, 1f / fit); }

    /** Reads the bitmap and view sizes straight from the view; false when unavailable. */
    private boolean measure(ImageView view) {
        Drawable drawable = view.getDrawable();
        int width = drawable == null ? 0 : drawable.getIntrinsicWidth();
        int height = drawable == null ? 0 : drawable.getIntrinsicHeight();
        if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
            Bitmap bitmap = ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
            if (bitmap != null) {
                width = bitmap.getWidth();
                height = bitmap.getHeight();
            }
        }
        int viewW = view.getWidth(), viewH = view.getHeight();
        if (width <= 0 || height <= 0 || viewW <= 0 || viewH <= 0) return false;
        imageWidth = width;
        imageHeight = height;
        viewWidth = viewW;
        viewHeight = viewH;
        fit = Math.min(viewW / (float) width, viewH / (float) height);
        return fit > 0f;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
