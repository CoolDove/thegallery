package com.dove.thegallery;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

/**
 * Resolves the palette for the active system theme and builds the shared surface shapes.
 * Light and dark values live in res/values/colors.xml and res/values-night/colors.xml, so a
 * system theme switch only requires rebuilding the visible screen.
 */
final class Theme {
    static final float TILE_RADIUS_DP = 18f;
    static final float CARD_RADIUS_DP = 22f;
    static final float PHOTO_RADIUS_DP = 11f;

    final boolean night;
    final int background;
    final int backdropTile;
    final int surface;
    final int surfaceAlt;
    final int surfaceGlass;
    final int outline;
    final int textPrimary;
    final int textSecondary;
    final int textMuted;
    final int accent;
    final int accentWarm;
    final int danger;
    final int scrim;
    final int caption;
    final int glow;
    final int viewerBackground;
    private final float density;

    private Theme(Context context) {
        night = isNight(context);
        background = context.getColor(R.color.gallery_background);
        backdropTile = context.getColor(R.color.gallery_backdrop_tile);
        surface = context.getColor(R.color.gallery_surface);
        surfaceAlt = context.getColor(R.color.gallery_surface_alt);
        surfaceGlass = context.getColor(R.color.gallery_surface_glass);
        outline = context.getColor(R.color.gallery_outline);
        textPrimary = context.getColor(R.color.gallery_text_primary);
        textSecondary = context.getColor(R.color.gallery_text_secondary);
        textMuted = context.getColor(R.color.gallery_text_muted);
        accent = context.getColor(R.color.gallery_accent);
        accentWarm = context.getColor(R.color.gallery_accent_warm);
        danger = context.getColor(R.color.gallery_danger);
        scrim = context.getColor(R.color.gallery_scrim);
        caption = context.getColor(R.color.gallery_caption);
        glow = context.getColor(R.color.gallery_glow);
        viewerBackground = context.getColor(R.color.gallery_viewer);
        density = context.getResources().getDisplayMetrics().density;
    }

    static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    static Theme of(Context context) { return new Theme(context); }

    int dp(float value) { return Math.round(value * density); }

    /** Full-screen background: paper colour, oversized ghost tiles, and a faint accent bloom. */
    Drawable backdrop() { return new BackdropDrawable(); }

    GradientDrawable solid(int color, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    GradientDrawable bordered(int color, float radiusDp, float strokeDp, int strokeColor) {
        GradientDrawable shape = solid(color, radiusDp);
        shape.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return shape;
    }

    Drawable pill(int color) { return solid(color, 1000f); }

    Drawable dot() {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{accentWarm, accent});
        shape.setShape(GradientDrawable.OVAL);
        return shape;
    }

    /** Grid tile: plain card normally, gradient outline plus a coloured glow when selected. */
    Drawable tile(boolean active) {
        if (active) return selection(TILE_RADIUS_DP, surface, 2.5f);
        return bordered(surface, TILE_RADIUS_DP, 1f, outline);
    }

    /** Rounded surface with a warm-to-accent gradient outline; used for focus and selection. */
    Drawable selection(float radiusDp, int innerColor, float strokeDp) {
        int stroke = Math.max(2, dp(strokeDp));
        GradientDrawable outer = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{accentWarm, accent});
        outer.setCornerRadius(dp(radiusDp));
        GradientDrawable inner = new GradientDrawable();
        inner.setColor(innerColor);
        inner.setCornerRadius(Math.max(0, dp(radiusDp) - stroke));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{outer, inner});
        layers.setLayerInset(1, stroke, stroke, stroke, stroke);
        return layers;
    }

    private final class BackdropDrawable extends Drawable {
        private final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.drawColor(background);
            float tile = dp(232);
            float gap = dp(26);
            float radius = dp(48);
            float startX = -dp(72);
            int columns = (int) Math.ceil((bounds.width() + dp(80)) / (tile + gap)) + 1;
            tilePaint.setColor(backdropTile);
            for (int row = 0; row < 3; row++) {
                float top = dp(16) + row * (tile + gap) - (row == 1 ? dp(26) : 0);
                for (int column = 0; column < columns; column++) {
                    float left = startX + column * (tile + gap) + ((row & 1) == 1 ? (tile + gap) / 2f : 0);
                    canvas.drawRoundRect(left, top, left + tile, top + tile, radius, radius, tilePaint);
                }
            }
            int glowCenter = (accent & 0x00FFFFFF) | 0x14000000;
            int glowEdge = accent & 0x00FFFFFF;
            glowPaint.setShader(new RadialGradient(bounds.width() * 0.82f, dp(30), dp(430),
                    new int[]{glowCenter, glowEdge}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), glowPaint);
        }

        @Override public void setAlpha(int alpha) { }

        @Override public void setColorFilter(ColorFilter colorFilter) { }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
