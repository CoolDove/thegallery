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
    /** One step changes the scale by 25 percentage points, so 100% -> 125% -> 150%. */
    private static final float STEP = 0.25f;
    /** Zooming out stops at half of best fit. */
    private static final float MIN_RATIO = 0.5f;
    /** Zooming in stops at 300% of the image's own pixels. */
    private static final float MAX_SCALE = 3f;
    private static final float EPSILON = 0.001f;

    /** Outcome of one zoom press: whether the ratio moved, and whether it landed on a stop. */
    static final class ZoomStep {
        final boolean changed;
        final boolean snapped;
        /** True when the new size is exactly best fit or 100%, so a held zoom should dwell. */
        final boolean stopped;
        final float from, to;
        ZoomStep(boolean changed, boolean snapped, boolean stopped, float from, float to) {
            this.changed = changed;
            this.snapped = snapped;
            this.stopped = stopped;
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
        applyMatrix(view, fit * ratio);
    }

    /** Builds and pushes the matrix for a given pixel scale, then stores the clamped pan. */
    private void applyMatrix(ImageView view, float scale) {
        float centredX = centredX(scale), centredY = centredY(scale);
        float tx = translationX(scale), ty = translationY(scale);
        panX = tx - centredX;
        panY = ty - centredY;
        matrix.setScale(scale, scale);
        matrix.postTranslate(tx, ty);
        view.setImageMatrix(matrix);
        percent = Math.round(scale * 100f);
    }

    private float centredX(float scale) { return (viewWidth - imageWidth * scale) / 2f; }

    private float centredY(float scale) { return (viewHeight - imageHeight * scale) / 2f; }

    /** Never shows empty space beside an oversized image; a smaller one stays centred. */
    private float translationX(float scale) {
        float width = imageWidth * scale;
        if (width <= viewWidth) return centredX(scale);
        return clamp(centredX(scale) + panX, viewWidth - width, 0f);
    }

    private float translationY(float scale) {
        float height = imageHeight * scale;
        if (height <= viewHeight) return centredY(scale);
        return clamp(centredY(scale) + panY, viewHeight - height, 0f);
    }

    /** True when the ratio cannot move further in this direction. */
    boolean atLimit(int direction) {
        return direction == ZOOM_IN ? ratio >= maxRatio() - EPSILON : ratio <= MIN_RATIO + EPSILON;
    }

    /** True when the image is showing at exactly 100% of its own pixels. */
    boolean isPixelPerfect() { return Math.abs(fit * ratio - 1f) <= 0.002f; }

    /**
     * Sets an explicit ratio while keeping the image point under the viewport centre fixed,
     * so zooming grows and shrinks around what the user is looking at.
     */
    void setRatio(ImageView view, float value) {
        if (!measure(view)) return;
        changeRatio(view, clamp(value, MIN_RATIO, maxRatio()));
    }

    private void changeRatio(ImageView view, float newRatio) {
        float scale = fit * ratio;
        float width = imageWidth * scale, height = imageHeight * scale;
        float anchorU = width <= 0f ? 0.5f : (viewWidth / 2f - translationX(scale)) / width;
        float anchorV = height <= 0f ? 0.5f : (viewHeight / 2f - translationY(scale)) / height;
        ratio = newRatio;
        float scaled = fit * ratio;
        float newWidth = imageWidth * scaled, newHeight = imageHeight * scaled;
        panX = (viewWidth / 2f - anchorU * newWidth) - centredX(scaled);
        panY = (viewHeight / 2f - anchorV * newHeight) - centredY(scaled);
        applyMatrix(view, scaled);
    }

    /**
     * One zoom press. A step that would cross best fit or 100% lands exactly on that stop, so
     * every press produces a visible change until the ratio reaches a limit.
     */
    ZoomStep zoom(ImageView view, int direction) {
        if (!measure(view)) return new ZoomStep(false, false, false, ratio, ratio);
        float scale = fit * ratio;
        float minimum = fit * MIN_RATIO;
        float maximum = Math.max(MAX_SCALE, fit);
        float target = direction == ZOOM_IN ? scale + STEP : scale - STEP;
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
        changeRatio(view, target / fit);
        boolean changed = Math.abs(ratio - from) > EPSILON;
        boolean stopped = changed && (isPixelPerfect() || !isAdjusted());
        return new ZoomStep(changed, snapped, stopped, from, ratio);
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

    private float maxScale() { return Math.max(MAX_SCALE, fit); }

    private float maxRatio() { return maxScale() / fit; }

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
