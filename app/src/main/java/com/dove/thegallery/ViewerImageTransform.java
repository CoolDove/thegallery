package com.dove.thegallery;

import android.graphics.Matrix;

/**
 * Zoom and pan state for the full-screen viewer image.
 *
 * <p>The scale counts screen pixels per source pixel, so 1 is 100% and {@link #fitScale()} is
 * best fit. Best fit is the minimum scale: a step that would cross best fit or 100% lands on
 * that stop instead, and anything at best fit stays centered.
 */
final class ViewerImageTransform {
    static final int ZOOM_IN = 1;
    static final int ZOOM_OUT = -1;
    private static final float STEP = 1.25f;
    private static final float MAX_FIT_MULTIPLIER = 8f;
    /** Zooming out stops at half of best fit; anything below best fit stays centered. */
    private static final float MIN_FIT_RATIO = 0.5f;
    private static final float EPSILON = 0.001f;

    /** One zoom press: where the scale goes and whether it snapped onto a key stop. */
    static final class ZoomStep {
        final float from, to;
        final boolean snapped, changed;
        ZoomStep(float from, float to, boolean snapped, boolean changed) {
            this.from = from; this.to = to; this.snapped = snapped; this.changed = changed;
        }
    }

    private final Matrix matrix = new Matrix();
    private int imageWidth, imageHeight, viewWidth, viewHeight;
    private float fit = 1f, scale = 1f, offsetX, offsetY;

    Matrix matrix() { return matrix; }

    boolean hasImage() { return imageWidth > 0 && imageHeight > 0; }

    float scale() { return scale; }

    float fitScale() { return fit; }

    /** True when the image is larger than best fit, so it may pan. */
    boolean isZoomed() { return hasImage() && scale > fit + EPSILON; }

    /** True when the size and position are not the best-fit state B restores. */
    boolean isAdjusted() { return hasImage() && Math.abs(scale - fit) > EPSILON; }

    boolean isFit() { return hasImage() && Math.abs(scale - fit) <= EPSILON; }

    /** Forget the current image; the next {@link #setImage} starts centered at best fit. */
    void reset() {
        imageWidth = 0;
        imageHeight = 0;
        fit = 1f;
        scale = 1f;
        offsetX = 0f;
        offsetY = 0f;
        apply();
    }

    void setViewport(int width, int height) {
        if (width <= 0 || height <= 0 || (width == viewWidth && height == viewHeight)) return;
        float ratio = hasImage() && fit > 0f ? scale / fit : 1f;
        float anchorX = anchorX(), anchorY = anchorY();
        viewWidth = width;
        viewHeight = height;
        if (!hasImage()) return;
        fit = computeFit();
        scale = clampScale(fit * ratio);
        focusOn(anchorX, anchorY);
        apply();
    }

    /**
     * Sets the bitmap being shown. The first bitmap of a photo starts at best fit; a later,
     * higher-resolution bitmap for the same photo keeps the relative zoom and focal point.
     */
    void setImage(int width, int height) {
        if (width <= 0 || height <= 0) return;
        boolean had = hasImage();
        float ratio = had && fit > 0f ? scale / fit : 1f;
        float anchorX = had ? anchorX() : 0.5f, anchorY = had ? anchorY() : 0.5f;
        imageWidth = width;
        imageHeight = height;
        fit = computeFit();
        scale = clampScale(fit * ratio);
        focusOn(anchorX, anchorY);
        apply();
    }

    void setScale(float value) {
        if (!hasImage()) return;
        float anchorX = anchorX(), anchorY = anchorY();
        scale = clampScale(value);
        focusOn(anchorX, anchorY);
        apply();
    }

    /** Restores best fit and centers the image. */
    void fit() {
        if (!hasImage()) return;
        scale = fit;
        offsetX = (viewWidth - imageWidth * scale) / 2f;
        offsetY = (viewHeight - imageHeight * scale) / 2f;
        apply();
    }

    ZoomStep zoomStep(int direction) {
        float from = scale;
        if (!hasImage() || viewWidth <= 0) return new ZoomStep(from, from, false, false);
        float maximum = maxScale();
        float target;
        boolean snapped = false;
        if (direction == ZOOM_IN) {
            target = from * STEP;
            if (from < fit && target > fit) {
                target = fit;
                snapped = true;
            } else if (from < 1f && target > 1f && 1f <= maximum && 1f >= minScale()) {
                target = 1f;
                snapped = true;
            }
            if (target > maximum) {
                target = maximum;
                snapped = true;
            }
            if (target <= from + EPSILON) return new ZoomStep(from, from, false, false);
        } else {
            target = from / STEP;
            if (from > 1f && target < 1f && 1f >= minScale()) {
                target = 1f;
                snapped = true;
            } else if (from > fit && target < fit) {
                target = fit;
                snapped = true;
            }
            if (target < minScale()) {
                target = minScale();
                snapped = true;
            }
            if (target >= from - EPSILON) return new ZoomStep(from, from, false, false);
        }
        return new ZoomStep(from, target, snapped, true);
    }

    /** Moves the image by screen pixels; only possible above best fit. */
    boolean panBy(float dx, float dy) {
        if (!isZoomed() || viewWidth <= 0) return false;
        float previousX = offsetX, previousY = offsetY;
        offsetX += dx;
        offsetY += dy;
        clampOffsets();
        if (Math.abs(offsetX - previousX) < 0.5f && Math.abs(offsetY - previousY) < 0.5f) return false;
        apply();
        return true;
    }

    /** Screen pixels per source pixel, as a whole percentage of native resolution. */
    int percent() { return Math.round(scale * 100f); }

    private float computeFit() {
        if (!hasImage() || viewWidth <= 0 || viewHeight <= 0) return 1f;
        return Math.min(viewWidth / (float) imageWidth, viewHeight / (float) imageHeight);
    }

    private float maxScale() { return Math.max(fit * MAX_FIT_MULTIPLIER, 1f); }

    private float minScale() { return fit * MIN_FIT_RATIO; }

    private float clampScale(float value) {
        return Math.max(minScale(), Math.min(value, maxScale()));
    }

    private float anchorX() {
        if (!hasImage() || viewWidth <= 0 || scale <= 0f) return 0.5f;
        return (viewWidth / 2f - offsetX) / (scale * imageWidth);
    }

    private float anchorY() {
        if (!hasImage() || viewHeight <= 0 || scale <= 0f) return 0.5f;
        return (viewHeight / 2f - offsetY) / (scale * imageHeight);
    }

    /** Puts the given normalized image point at the viewport center, then clamps to bounds. */
    private void focusOn(float anchorX, float anchorY) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            offsetX = 0f;
            offsetY = 0f;
            return;
        }
        offsetX = viewWidth / 2f - anchorX * imageWidth * scale;
        offsetY = viewHeight / 2f - anchorY * imageHeight * scale;
        clampOffsets();
    }

    /** Never shows empty space beside an oversized image; a smaller one stays centered. */
    private void clampOffsets() {
        float scaledWidth = imageWidth * scale;
        float scaledHeight = imageHeight * scale;
        if (scaledWidth <= viewWidth) offsetX = (viewWidth - scaledWidth) / 2f;
        else offsetX = Math.max(viewWidth - scaledWidth, Math.min(offsetX, 0f));
        if (scaledHeight <= viewHeight) offsetY = (viewHeight - scaledHeight) / 2f;
        else offsetY = Math.max(viewHeight - scaledHeight, Math.min(offsetY, 0f));
    }

    private void apply() {
        matrix.setScale(scale, scale);
        matrix.postTranslate(offsetX, offsetY);
    }
}
