package com.cooldove.thegallery;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Set;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.NetworkInterface;
import java.net.Inet4Address;
import java.util.Enumeration;
import java.security.SecureRandom;
import java.util.Locale;
import java.io.BufferedInputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

public final class MainActivity extends Activity {
    private static final int SHARE_PORT = 8765;
    private static final int SHARE_FALLBACK_PORTS = 20;
    private static final long PHOTO_EXIT_DELAY_MS = 200;
    private static final long PHOTO_FADE_MS = 110;
    static final int EXIT_HOLD_DURATION_MS = 1000;
    static final int LONG_PRESS_MS = 550;
    static final int HOLD_HINT_DELAY_MS = 150;
    private static final int PICK_FOLDER = 1;
    private static final int PICK_SOURCE = 2;
    private static final String PREFS = "gallery";
    static final String[] SORTS = {"Newest", "Oldest", "Name A-Z", "Name Z-A"};
    static final String[] DISPLAY_MODES = {"Simple", "Normal", "Detailed"};
    private static final long OVERLAY_FADE_MS = 140;
    final ArrayList<Photo> photos = new ArrayList<>();
    private final ArrayList<Uri> sources = new ArrayList<>();
    private final Handler handler = new Handler();
    private final ExecutorService loader = Executors.newFixedThreadPool(3);
    private final ConcurrentHashMap<String, Boolean> pendingThumbnails = new ConcurrentHashMap<>();
    private final Semaphore thumbnailSlots = new Semaphore(2);
    private final android.util.LruCache<String, Bitmap> cache = new android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private FrameLayout root;
    GridView grid;
    private TextView status;
    private TextView summary;
    private Uri folder;
    int selected, sort;
    private ServerSocket shareServer;
    private Thread shareThread;
    private String shareUrl;
    Screen current = Screen.GALLERY;
    private boolean sourcesDirty;
    private boolean scanInProgress;
    private TextView refreshIndicator;
    private long refreshGeneration;
    private final HashMap<String, Integer> sourceFileCounts = new HashMap<>();
    private boolean countQueryInProgress;
    private Runnable directoryCountPoll;
    final ArrayList<View> settingsActions = new ArrayList<>();
    int settingsFocus;
    private int gridColumns = 1;
    private ImageView fullImage;
    private ImageView incomingImage;
    private int photoTransition;
    private Runnable photoExitRunnable;
    private View deleteOverlay;
    int displayMode = 1;
    private android.content.SharedPreferences preferences;
    private InteractionSystem interaction;
    private GalleryController galleryController;
    private ViewerController viewerController;
    private SettingsController settingsController;
    private ShareController shareController;
    private boolean slideshowOverlayVisible;
    private boolean slideshowPlaying;
    private int slideshowPosition;
    private final Runnable slideshowTick = new Runnable() {
        @Override public void run() {
            if (!slideshowOverlayVisible || !slideshowPlaying || photos.isEmpty()) return;
            slideshowPosition = (slideshowPosition + 1) % Math.min(photos.size(), slideshowPhotoCount());
            showSlideshowPhoto();
            handler.postDelayed(this, slideshowIntervalSeconds() * 1000L);
        }
    };

    static final class Photo {
        final Uri uri; final String name; final long modified;
        Photo(Uri uri, String name, long modified) { this.uri = uri; this.name = name; this.modified = modified; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getPreferences(0);
        sort = Math.max(0, Math.min(preferences.getInt("sort", 0), SORTS.length - 1));
        displayMode = Math.max(0, Math.min(preferences.getInt("display_mode", 1), DISPLAY_MODES.length - 1));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this);
        setContentView(root);
        interaction = new InteractionSystem(this, root);
        galleryController = new GalleryController(this, interaction);
        viewerController = new ViewerController(this, interaction);
        settingsController = new SettingsController(this, interaction);
        shareController = new ShareController(this, interaction);
        Set<String> savedSources = getPreferences(0).getStringSet("sources", Collections.emptySet());
        if (!savedSources.isEmpty()) {
            for (String source : savedSources) sources.add(Uri.parse(source));
            folder = sources.get(0); scan();
        } else if (!getPreferences(0).getString("folder", "").isEmpty()) {
            folder = Uri.parse(getPreferences(0).getString("folder", "")); sources.add(folder); scan();
        }
        else empty("Choose a folder to browse images", true);
    }

    private void empty(String text, boolean allowPicker) {
        root.removeAllViews();
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER);
        TextView title = label(text, 25); box.addView(title);
        if (allowPicker) { View choose = prompt("switch_a_96dpi", "Choose folder", 20); choose.setFocusable(true); choose.setFocusableInTouchMode(true); choose.setOnClickListener(v -> pickFolder()); box.addView(choose); choose.requestFocus(); }
        root.addView(box, new FrameLayout.LayoutParams(-1, -1));
        status = title;
    }

    TextView label(String text, int size) { TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(0xffffffff); v.setGravity(Gravity.CENTER); v.setPadding(20, 18, 20, 18); return v; }

    void pickFolder() {
        resetButtonHoldForUiChange();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        Uri current = folder;
        if (current != null && android.os.Build.VERSION.SDK_INT >= 26) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, current);
        startActivityForResult(intent, PICK_FOLDER);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        // Returning from the picker can leave stale controller state; reset it once here.
        if (interaction != null) interaction.reset();
        if ((request == PICK_FOLDER || request == PICK_SOURCE) && result == RESULT_OK && data != null && data.getData() != null) {
            folder = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(folder, flags); } catch (SecurityException ignored) { }
            if (request == PICK_SOURCE) {
                if (!sources.contains(folder)) { sources.add(folder); sourcesDirty = true; }
                persistSources();
                if (current == Screen.SETTINGS) showSettings();
            } else {
                sources.clear(); sources.add(folder); persistSources(); sourcesDirty = true; scan();
            }
        } else if (request == PICK_SOURCE && current == Screen.SETTINGS) {
            // The document picker may finish without a selection; rebuild the settings
            // screen either way so its controller focus and Back control are restored.
            showSettings();
        } else if (request == PICK_FOLDER && folder == null) empty("No folder selected", true);
    }

    private void scan() {
        if (scanInProgress) { sourcesDirty = true; return; }
        scanInProgress = true;
        long generation = ++refreshGeneration;
        String selectedUri = selected >= 0 && selected < photos.size() ? photos.get(selected).uri.toString() : null;
        updateRefreshIndicator(true);
        ArrayList<Uri> scanSources = new ArrayList<>(sources);
        loader.execute(() -> {
            ArrayList<Photo> found = new ArrayList<>();
            try {
                for (Uri source : scanSources) {
                    String id = DocumentsContract.getTreeDocumentId(source);
                    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(source, id);
                    try (android.database.Cursor c = getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                        if (c != null) while (c.moveToNext()) {
                            String mime = c.getString(2), name = c.getString(1);
                            if (mime != null && mime.startsWith("image/")) found.add(new Photo(DocumentsContract.buildDocumentUriUsingTree(source, c.getString(0)), name == null ? "" : name, c.getLong(3)));
                        }
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (generation != refreshGeneration) return;
                    scanInProgress = false; updateRefreshIndicator(false);
                    if (photos.isEmpty()) empty("Folder unavailable. Choose another folder.", true);
                    if (sourcesDirty) { sourcesDirty = false; scan(); }
                }); return;
            }
            runOnUiThread(() -> {
                if (generation != refreshGeneration) return;
                photos.clear(); photos.addAll(found);
                sortPhotos();
                selected = 0;
                if (selectedUri != null) for (int i = 0; i < photos.size(); i++) if (selectedUri.equals(photos.get(i).uri.toString())) { selected = i; break; }
                scanInProgress = false;
                sourcesDirty = false;
                if (current == Screen.GALLERY) {
                    if (grid == null || !grid.isAttachedToWindow()) {
                        showGrid();
                    } else {
                        ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
                        if (photos.isEmpty()) { grid = null; showGrid(); }
                        else { selected = Math.min(selected, photos.size() - 1); grid.setSelection(selected); updateGridHighlight(); }
                        refreshSummary();
                    }
                }
                updateRefreshIndicator(false);
                if (sourcesDirty && current == Screen.GALLERY) { sourcesDirty = false; scan(); }
            });
        });
    }

    private void requestRefresh(boolean onlyIfDirty) {
        if (current != Screen.GALLERY) { sourcesDirty = true; return; }
        if (root == null) return;
        if (onlyIfDirty && !sourcesDirty) return;
        if (scanInProgress) { sourcesDirty = true; return; }
        sourcesDirty = false;
        scan();
    }

    @Override protected void onResume() {
        super.onResume();
        if (root != null) requestRefresh(true);
    }

    @Override protected void onPause() {
        super.onPause();
    }

    private void startDirectoryCountPolling() {
        if (directoryCountPoll != null) handler.removeCallbacks(directoryCountPoll);
        directoryCountPoll = new Runnable() {
            @Override public void run() {
                if (current == Screen.GALLERY && !sources.isEmpty()) querySourceFileCounts(true);
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(directoryCountPoll);
    }

    private void querySourceFileCounts(boolean refreshOnChange) {
        if (countQueryInProgress) return;
        countQueryInProgress = true;
        ArrayList<Uri> snapshot = new ArrayList<>(sources);
        loader.execute(() -> {
            HashMap<String, Integer> queriedCounts = new HashMap<>();
            for (Uri source : snapshot) {
                try {
                    String id = DocumentsContract.getTreeDocumentId(source);
                    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(source, id);
                    int count = 0;
                    try (android.database.Cursor cursor = getContentResolver().query(children,
                            new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                        if (cursor != null) count = cursor.getCount();
                    }
                    queriedCounts.put(source.toString(), count);
                } catch (Exception ignored) { }
            }
            runOnUiThread(() -> {
                countQueryInProgress = false;
                boolean changed = false;
                for (Uri source : snapshot) {
                    Integer count = queriedCounts.get(source.toString());
                    if (count == null) continue;
                    Integer previous = sourceFileCounts.put(source.toString(), count);
                    if (previous != null && !previous.equals(count)) changed = true;
                }
                if (changed && refreshOnChange && current == Screen.GALLERY) requestRefresh(false);
            });
        });
    }

    private void sortPhotos() {
        Comparator<Photo> cmp;
        switch (sort) {
            case 1: cmp = Comparator.comparingLong(p -> p.modified); break;
            case 2: cmp = Comparator.comparing(p -> p.name.toLowerCase(java.util.Locale.ROOT)); break;
            case 3: cmp = Comparator.comparing((Photo p) -> p.name.toLowerCase(java.util.Locale.ROOT)).reversed(); break;
            default: cmp = Comparator.comparingLong((Photo p) -> p.modified).reversed();
        }
        photos.sort(cmp);
    }

    private void persistSources() {
        Set<String> saved = new HashSet<>();
        for (Uri source : sources) saved.add(source.toString());
        getPreferences(0).edit().putStringSet("sources", saved).putString("folder", sources.isEmpty() ? "" : sources.get(0).toString()).apply();
    }

    void showSettings() {
        enterScreen(Screen.SETTINGS);
        if (directoryCountPoll != null) { handler.removeCallbacks(directoryCountPoll); directoryCountPoll = null; }
        settingsActions.clear();
        root.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(32), dp(12), dp(32), dp(12));
        page.setBackgroundColor(0xff181a1d);
        TextView heading = label("Settings", 28);
        page.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        TextView slideshowSettings = label("Slideshow Controls  ›", 20);
        focusable(slideshowSettings);
        settingsActions.add(slideshowSettings);
        slideshowSettings.setOnClickListener(v -> showSlideshowSettings());
        page.addView(slideshowSettings, new LinearLayout.LayoutParams(-1, -2));
        TextView category = label("Sources", 21);
        category.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(category, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < sources.size(); i++) {
            final Uri source = sources.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = label(source.getLastPathSegment() == null ? source.toString() : source.getLastPathSegment(), 17);
            name.setGravity(Gravity.CENTER_VERTICAL);
            name.setSingleLine(true);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView remove = label("Remove", 17);
            focusable(remove);
            settingsActions.add(remove);
            remove.setOnClickListener(v -> {
                sources.remove(source); sourcesDirty = true;
                persistSources();
                if (sources.isEmpty()) { photos.clear(); showGrid(); }
                else folder = sources.get(0);
                showSettings();
            });
            row.addView(remove);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.topMargin = dp(6);
            page.addView(row, rowParams);
        }
        TextView add = label("Add source", 20);
        focusable(add);
        settingsActions.add(add);
        add.setOnClickListener(v -> pickSource());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, -2);
        addParams.topMargin = dp(6);
        page.addView(add, addParams);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        settingsFocus = Math.max(0, settingsActions.size() - 1);
        focusSettingsAction();
        settingsController.onEnter();
    }

    private void showSlideshowSettings() {
        slideshowOverlayVisible = false;
        slideshowPlaying = false;
        handler.removeCallbacks(slideshowTick);
        enterScreen(Screen.SETTINGS);
        settingsActions.clear();
        root.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(32), dp(12), dp(32), dp(12));
        page.setBackgroundColor(0xff181a1d);
        page.addView(label("Slideshow Controls", 28), new LinearLayout.LayoutParams(-1, -2));
        addStepper(page, "Photo count", "slideshow_photo_count", 9, 1, 30);
        addStepper(page, "Interval (seconds)", "slideshow_interval_seconds", 8, 1, 60);
        TextView back = label("‹  Settings", 18);
        focusable(back);
        settingsActions.add(back);
        back.setOnClickListener(v -> showSettings());
        page.addView(back, new LinearLayout.LayoutParams(-1, -2));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        settingsFocus = 0;
        focusSettingsAction();
        settingsController.onEnter();
    }

    private void addStepper(LinearLayout page, String title, String preferenceKey, int fallback, int min, int max) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(title, 19);
        name.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = label("", 19);
        Runnable refresh = () -> value.setText(String.valueOf(preferences.getInt(preferenceKey, fallback)));
        refresh.run();
        TextView minus = label("−", 24);
        TextView plus = label("+", 24);
        focusable(minus);
        focusable(plus);
        settingsActions.add(minus);
        settingsActions.add(plus);
        minus.setOnClickListener(v -> { preferences.edit().putInt(preferenceKey, Math.max(min, preferences.getInt(preferenceKey, fallback) - 1)).apply(); refresh.run(); });
        plus.setOnClickListener(v -> { preferences.edit().putInt(preferenceKey, Math.min(max, preferences.getInt(preferenceKey, fallback) + 1)).apply(); refresh.run(); });
        row.addView(minus); row.addView(value); row.addView(plus);
        page.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private int slideshowPhotoCount() { return Math.max(1, Math.min(30, preferences.getInt("slideshow_photo_count", 9))); }
    private int slideshowIntervalSeconds() { return Math.max(1, Math.min(60, preferences.getInt("slideshow_interval_seconds", 8))); }

    void showSlideshowControls() {
        resetButtonHoldForUiChange();
        if (photos.isEmpty()) return;
        handler.removeCallbacks(slideshowTick);
        slideshowOverlayVisible = true;
        slideshowPlaying = false;
        slideshowPosition = 0;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setTag("slideshow_controls");
        overlay.setBackgroundColor(0xee101214);
        ImageView image = new ImageView(this);
        image.setTag("slideshow_image");
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        overlay.addView(image, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setBackgroundColor(0xaa101214);
        TextView previous = label("‹", 32), play = label("▶", 24), next = label("›", 32), settings = label("⚙", 24), close = label("×", 30);
        for (TextView control : new TextView[]{previous, play, next, settings, close}) controls.addView(control);
        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(-1, dp(72), Gravity.BOTTOM);
        overlay.addView(controls, controlParams);
        previous.setOnClickListener(v -> { slideshowPosition = Math.floorMod(slideshowPosition - 1, slideshowLimit()); showSlideshowPhoto(); });
        next.setOnClickListener(v -> { slideshowPosition = (slideshowPosition + 1) % slideshowLimit(); showSlideshowPhoto(); });
        play.setOnClickListener(v -> { slideshowPlaying = !slideshowPlaying; play.setText(slideshowPlaying ? "Ⅱ" : "▶"); handler.removeCallbacks(slideshowTick); if (slideshowPlaying) handler.postDelayed(slideshowTick, slideshowIntervalSeconds() * 1000L); });
        settings.setOnClickListener(v -> showSlideshowSettings());
        close.setOnClickListener(v -> closeSlideshowControls());
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        showSlideshowPhoto();
        play.performClick();
        previous.setFocusable(true); play.setFocusable(true); next.setFocusable(true); settings.setFocusable(true); close.setFocusable(true);
        play.requestFocus();
    }

    private int slideshowLimit() { return Math.max(1, Math.min(photos.size(), slideshowPhotoCount())); }
    private void showSlideshowPhoto() {
        View overlay = root.findViewWithTag("slideshow_controls");
        if (overlay == null || photos.isEmpty()) return;
        ImageView image = overlay.findViewWithTag("slideshow_image");
        Photo photo = photos.get(slideshowPosition % slideshowLimit());
        loader.execute(() -> {
            Bitmap bitmap = decode(photo, 2400);
            runOnUiThread(() -> { if (slideshowOverlayVisible && image != null && bitmap != null) image.setImageBitmap(bitmap); });
        });
    }
    private void closeSlideshowControls() { slideshowOverlayVisible = false; slideshowPlaying = false; handler.removeCallbacks(slideshowTick); View overlay = root.findViewWithTag("slideshow_controls"); if (overlay != null) root.removeView(overlay); grid.requestFocus(); }

    void focusSettingsAction() {
        if (settingsActions.isEmpty()) return;
        settingsFocus = Math.max(0, Math.min(settingsFocus, settingsActions.size() - 1));
        View action = settingsActions.get(settingsFocus);
        action.requestFocus();
        action.setBackgroundColor(0xff555a60);
    }

    private void focusable(View view) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setBackgroundColor(0xff303338);
        view.setOnFocusChangeListener((v, focused) -> v.setBackgroundColor(focused ? 0xff555a60 : 0xff303338));
    }

    private void pickSource() {
        resetButtonHoldForUiChange();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        if (folder != null && android.os.Build.VERSION.SDK_INT >= 26) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder);
        startActivityForResult(intent, PICK_SOURCE);
    }

    void closeSettings() {
        resetButtonHoldForUiChange();
        showGrid();
    }

    void showGrid() {
        if (sourcesDirty && !scanInProgress) { sourcesDirty = false; scan(); }
        enterScreen(Screen.GALLERY);
        deleteOverlay = null;
        root.removeAllViews();
        querySourceFileCounts(false);
        startDirectoryCountPolling();
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setBackgroundColor(0xff181a1d);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        View settingsButton = prompt("switch_plus_96dpi", "Settings", 18); settingsButton.setFocusable(true); settingsButton.setOnClickListener(v -> showSettings()); bar.addView(settingsButton);
        View slideshowButton = prompt("switch_x", "Slideshow", 18); slideshowButton.setFocusable(true); slideshowButton.setOnClickListener(v -> showSlideshowControls()); bar.addView(slideshowButton);
        status = null;
        refreshIndicator = label("Refreshing…", 13);
        refreshIndicator.setVisibility(View.GONE);
        bar.addView(refreshIndicator);
        summary = label("", 16); summary.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); bar.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
        refreshSummary();
        layout.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        grid = new GridView(this); gridColumns = Math.max(1, getResources().getDisplayMetrics().widthPixels / dp(displayMode == 2 ? 300 : 210)); grid.setNumColumns(gridColumns); grid.setColumnWidth(dp(displayMode == 2 ? 280 : 190)); grid.setHorizontalSpacing(dp(10)); grid.setVerticalSpacing(dp(10)); grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH); grid.setPadding(dp(16), dp(12), dp(16), dp(12)); grid.setClipToPadding(false); grid.setChoiceMode(GridView.CHOICE_MODE_SINGLE); grid.setSelector(new android.graphics.drawable.StateListDrawable()); grid.setDrawSelectorOnTop(false); grid.setAdapter(new PhotoAdapter());
        grid.setOnItemClickListener((parent, view, position, id) -> { selected = position; openPhoto(); });
        grid.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { selected = position; updateGridHighlight(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        layout.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1)); root.addView(layout);
        if (photos.isEmpty()) { empty("No images here. Choose another folder.", true); return; }
        selected = Math.min(selected, photos.size() - 1);
        grid.setFocusable(true); grid.setFocusableInTouchMode(true);
        grid.setSelection(selected); grid.requestFocus(); grid.post(() -> { if (grid != null) { grid.requestFocus(); updateGridHighlight(); } });
        galleryController.onEnter();
    }

    private void refreshSummary() {
        if (summary == null) return;
        summary.setText(SORTS[sort] + " | " + photos.size() + " images");
    }

    private void updateRefreshIndicator(boolean refreshing) {
        if (refreshIndicator != null) refreshIndicator.setVisibility(refreshing ? View.VISIBLE : View.GONE);
    }

    private void updateGridHighlight() {
        if (grid == null) return;
        int first = grid.getFirstVisiblePosition();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            boolean active = first + i == selected;
            GradientDrawable background = child.getBackground() instanceof GradientDrawable
                    ? (GradientDrawable) child.getBackground() : new GradientDrawable();
            background.setColor(0xff35383d);
            background.setCornerRadius(dp(6));
            background.setStroke(active ? dp(3) : 0, active ? 0xffaeb3b8 : 0x00000000);
            if (child.getBackground() != background) child.setBackground(background);
            if (android.os.Build.VERSION.SDK_INT >= 21) child.setClipToOutline(true);
        }
    }

    int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    ImageView icon(String iconName) {
        ImageView icon = new ImageView(this);
        int id = getResources().getIdentifier(iconName, "drawable", getPackageName());
        if (id != 0) icon.setImageResource(id);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        return icon;
    }

    private View prompt(String iconName, String text, int textSize) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(6), dp(10), dp(6));
        ImageView icon = icon(iconName);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        iconParams.rightMargin = dp(7);
        row.addView(icon, iconParams);
        TextView caption = label(text, textSize);
        caption.setPadding(0, 0, 0, 0);
        row.addView(caption);
        return row;
    }

    private final class PhotoAdapter extends BaseAdapter {
        @Override public int getCount() { return photos.size(); }
        @Override public Object getItem(int p) { return photos.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            FrameLayout cell = recycled instanceof FrameLayout ? (FrameLayout) recycled : new FrameLayout(MainActivity.this);
            ImageView image;
            if (cell.getChildCount() == 0) {
                image = new ImageView(MainActivity.this);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(-1, dp(displayMode == 2 ? 210 : 143), Gravity.TOP);
                cell.addView(image, imageParams);
                TextView name = label("", 13);
                name.setGravity(Gravity.TOP | Gravity.LEFT);
                name.setPadding(dp(10), dp(0), dp(10), dp(2));
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, dp(42), Gravity.BOTTOM);
                cell.addView(name, lp);
                TextView metadata = label("", 12);
                metadata.setGravity(Gravity.BOTTOM | Gravity.LEFT);
                metadata.setPadding(dp(10), 0, dp(10), dp(2));
                cell.addView(metadata, new FrameLayout.LayoutParams(-1, dp(22), Gravity.BOTTOM));
            }
            image = (ImageView) cell.getChildAt(0); TextView name = (TextView) cell.getChildAt(1); TextView metadata = (TextView) cell.getChildAt(2); Photo photo = photos.get(position); name.setText(photo.name);
            name.setVisibility(displayMode == 0 ? View.GONE : View.VISIBLE);
            metadata.setVisibility(displayMode == 2 ? View.VISIBLE : View.GONE);
            if (displayMode == 2) metadata.setText(imageMetadata(photo));
            GradientDrawable cellBackground = new GradientDrawable();
            cellBackground.setColor(0xff35383d);
            cellBackground.setCornerRadius(dp(6));
            cell.setBackground(cellBackground);
            if (android.os.Build.VERSION.SDK_INT >= 21) cell.setClipToOutline(true);
            int rowHeight = displayMode == 2 ? 270 : displayMode == 0 ? 149 : 187;
            cell.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(rowHeight)));
            FrameLayout.LayoutParams imageParams = (FrameLayout.LayoutParams) image.getLayoutParams();
            imageParams.setMargins(dp(6), dp(6), dp(6), dp(displayMode == 0 ? 6 : displayMode == 2 ? 66 : 50));
            imageParams.height = dp(displayMode == 2 ? 210 : 143);
            image.setLayoutParams(imageParams);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cell.setTag(photo.uri.toString());
        Bitmap bitmap = cache.get(photo.uri.toString()); image.setImageBitmap(bitmap);
        if (bitmap == null && pendingThumbnails.putIfAbsent(photo.uri.toString(), Boolean.TRUE) == null) {
            image.setImageDrawable(null);
            loader.execute(() -> {
                Bitmap b = null;
                boolean acquired = false;
                try { thumbnailSlots.acquire(); acquired = true; b = decode(photo, 360); if (b != null) cache.put(photo.uri.toString(), b); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                finally { if (acquired) thumbnailSlots.release(); pendingThumbnails.remove(photo.uri.toString()); }
                Bitmap result = b;
                runOnUiThread(() -> {
                    if (result == null || grid == null) return;
                    int first = grid.getFirstVisiblePosition(); View child = grid.getChildAt(position - first);
                    if (child instanceof FrameLayout && photo.uri.toString().equals(child.getTag())) { FrameLayout targetCell = (FrameLayout) child; ((ImageView)targetCell.getChildAt(0)).setImageBitmap(result); if (displayMode == 2) ((TextView)targetCell.getChildAt(2)).setText(imageMetadata(photo)); }
                });
            });
        }
            return cell;
        }
    }

    private Bitmap decode(Photo photo, int target) {
        try (InputStream in = getContentResolver().openInputStream(photo.uri)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeStream(in, null, bounds);
            int sample = 1; while (bounds.outWidth / sample > target * 2 || bounds.outHeight / sample > target * 2) sample *= 2;
            try (InputStream image = getContentResolver().openInputStream(photo.uri)) { BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample; return BitmapFactory.decodeStream(image, null, opts); }
        } catch (Exception ignored) { return null; }
    }

    private String imageMetadata(Photo photo) {
        Bitmap bitmap = cache.get(photo.uri.toString());
        String dimensions = bitmap == null ? "Dimensions unavailable" : bitmap.getWidth() + " × " + bitmap.getHeight();
        String format = photo.name.contains(".") ? photo.name.substring(photo.name.lastIndexOf('.') + 1).toUpperCase(java.util.Locale.ROOT) : "IMAGE";
        String date = photo.modified > 0 ? android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", photo.modified).toString() : "Time unavailable";
        return dimensions + "  •  " + date + "  •  " + format;
    }

    void openPhoto() {
        if (photos.isEmpty()) return;
        enterScreen(Screen.VIEWER);
        root.removeAllViews();
        fullImage = new ImageView(this); fullImage.setBackgroundColor(0xff111315); fullImage.setScaleType(ImageView.ScaleType.FIT_CENTER); fullImage.setFocusable(true); fullImage.setFocusableInTouchMode(true); root.addView(fullImage, new FrameLayout.LayoutParams(-1, -1));
        photoTransition++;
        fullImage.setAlpha(1f);
        displayPhoto(); fullImage.requestFocus();
        viewerController.onEnter();
    }

    void displayPhoto() {
        if (fullImage == null || photos.isEmpty()) return;
        Photo photo = photos.get(selected);
        int transition = ++photoTransition;
        ImageView previousImage = fullImage;
        if (photoExitRunnable != null) handler.removeCallbacks(photoExitRunnable);
        photoExitRunnable = () -> {
            if (current != Screen.VIEWER || transition != photoTransition) return;
            fadeOutImage(previousImage);
        };
        handler.postDelayed(photoExitRunnable, PHOTO_EXIT_DELAY_MS);
        loader.execute(() -> {
            Bitmap bitmap = decode(photo, 2400);
            runOnUiThread(() -> {
                if (current != Screen.VIEWER || transition != photoTransition || fullImage == null) return;
                if (bitmap == null) return;
                ImageView incoming = new ImageView(MainActivity.this);
                incoming.setScaleType(ImageView.ScaleType.FIT_CENTER);
                incoming.setBackgroundColor(0xff111315);
                incoming.setImageBitmap(bitmap);
                incoming.setAlpha(0f);
                root.addView(incoming, new FrameLayout.LayoutParams(-1, -1));
                incomingImage = incoming;
                incoming.animate().alpha(1f).setDuration(PHOTO_FADE_MS).withEndAction(() -> {
                    if (current != Screen.VIEWER || transition != photoTransition || incomingImage != incoming) {
                        fadeOutImage(incoming);
                        return;
                    }
                    fullImage = incoming;
                    incomingImage = null;
                    fullImage.requestFocus();
                    if (photos.get(selected) != photo) fadeOutImage(incoming);
                }).start();
            });
        });
    }

    private void fadeOutImage(ImageView image) {
        if (image == null) return;
        image.animate().cancel();
        image.animate().alpha(0f).setDuration(PHOTO_FADE_MS).withEndAction(() -> {
            if (image.getParent() == root) root.removeView(image);
            if (incomingImage == image) incomingImage = null;
        }).start();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        interaction.track(event);
        if (slideshowOverlayVisible) {
            if (event.getAction() == KeyEvent.ACTION_UP && (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B || event.getKeyCode() == KeyEvent.KEYCODE_BACK)) { closeSlideshowControls(); return true; }
            return super.dispatchKeyEvent(event);
        }
        if (deleteOverlay != null) return handleDeleteOverlayKey(event);
        return controllerFor(current).onKey(event) || super.dispatchKeyEvent(event);
    }

    private ScreenController controllerFor(Screen screen) {
        switch (screen) {
            case SETTINGS: return settingsController;
            case VIEWER: return viewerController;
            case SHARE: return shareController;
            default: return galleryController;
        }
    }

    /** Single screen transition: reset shared input state, then switch. */
    private void enterScreen(Screen screen) {
        interaction.reset();
        current = screen;
    }

    private boolean handleDeleteOverlayKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP) {
            // Releases of keys that were already held when the confirm opened are swallowed once.
            if (interaction.consumeSwallowed(key)) return true;
            if (key == KeyEvent.KEYCODE_BUTTON_A) { closeDeleteConfirm(); deleteCurrentPhoto(); }
            else if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) closeDeleteConfirm();
        }
        return true;
    }

    void performFaceButtonShortPress(int key) {
        if (key == KeyEvent.KEYCODE_BUTTON_A) {
            if (photos.isEmpty()) { pickFolder(); return; }
            openSelectedPhoto();
        } else if (key == KeyEvent.KEYCODE_BUTTON_X) {
            sort = (sort + 1) % SORTS.length;
            preferences.edit().putInt("sort", sort).apply();
            sortPhotos();
            selected = Math.min(selected, Math.max(0, photos.size() - 1));
            showGrid();
        }
    }

    void openSelectedPhoto() {
        if (grid == null || photos.isEmpty()) { pickFolder(); return; }
        int focused = grid.getSelectedItemPosition();
        if (focused >= 0) selected = focused;
        openPhoto();
    }

    void moveGridSelection(int key) {
        if (grid == null || photos.isEmpty()) return;
        grid.requestFocus();
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) selected = Math.max(0, selected - 1);
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) selected = Math.min(photos.size() - 1, selected + 1);
        else if (key == KeyEvent.KEYCODE_DPAD_UP) selected = Math.max(0, selected - gridColumns);
        else selected = Math.min(photos.size() - 1, selected + gridColumns);
        int target = selected;
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            grid.setSelection(target);
            if (displayMode == 2) {
                grid.post(() -> {
                    int first = grid.getFirstVisiblePosition();
                    View focused = grid.getChildAt(target - first);
                    if (focused != null) {
                        int desiredTop = Math.max(grid.getPaddingTop(), (grid.getHeight() - focused.getHeight()) / 2);
                        grid.smoothScrollBy(focused.getTop() - desiredTop, 140);
                    } else grid.smoothScrollToPosition(target);
                    grid.post(this::updateGridHighlight);
                });
            } else grid.smoothScrollToPosition(target);
        } else {
            grid.setSelection(target);
            grid.smoothScrollToPosition(target);
        }
        grid.post(this::updateGridHighlight);
        updateGridHighlight();
    }

    void scrollGridToEdge(boolean top, boolean animate) {
        if (grid == null || photos.isEmpty()) return;
        selected = top ? 0 : photos.size() - 1;
        grid.requestFocus();
        if (animate) grid.smoothScrollToPosition(selected);
        else {
            grid.setSelection(selected);
            grid.post(() -> { if (grid != null) grid.setSelection(selected); });
        }
        updateGridHighlight();
    }

    boolean hasFolder() { return folder != null; }

    void applySortFromWheel() {
        String selectedName = photos.isEmpty() ? null : photos.get(Math.min(selected, photos.size() - 1)).name;
        sort = (sort + 1) % SORTS.length;
        preferences.edit().putInt("sort", sort).apply();
        sortPhotos();
        if (selectedName != null) {
            for (int i = 0; i < photos.size(); i++) if (selectedName.equals(photos.get(i).name)) { selected = i; break; }
        }
        if (grid != null) {
            ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
            grid.setSelection(selected);
            refreshSummary();
        }
    }

    void cycleDisplayModeFromWheel() {
        displayMode = (displayMode + 1) % DISPLAY_MODES.length;
        preferences.edit().putInt("display_mode", displayMode).apply();
        refreshDisplayModeInGrid();
    }

    void refreshDisplayModeInGrid() {
        if (grid == null) return;
        gridColumns = Math.max(1, getResources().getDisplayMetrics().widthPixels / dp(displayMode == 2 ? 300 : 210));
        grid.setNumColumns(gridColumns);
        grid.setColumnWidth(dp(displayMode == 2 ? 280 : 190));
        ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        grid.setSelection(selected);
        // notifyDataSetChanged/setNumColumns can rebind or relayout children after this
        // method returns. Apply the custom selection border once those children exist.
        grid.post(() -> {
            if (grid == null || current != Screen.GALLERY) return;
            grid.setSelection(selected);
            updateGridHighlight();
        });
    }

    String imageSizeLabel() { return DISPLAY_MODES[displayMode]; }

    /** Cancel any in-progress long press before changing UI state or presenting a modal UI. */
    void resetButtonHoldForUiChange() { interaction.cancelHoldForUiChange(); }
    private void showPlaceholder(String message) { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show(); }
    void showShareScreen() {
        if (photos.isEmpty()) return;
        resetButtonHoldForUiChange();
        stopShareServer();
        final String token = randomToken();
        final Photo photo = photos.get(selected);
        try {
            ServerSocket server = openShareServer();
            server.setSoTimeout(0);
            shareServer = server;
            String address = localAddress();
            if (address == null) throw new java.io.IOException("No LAN address");
            shareUrl = "http://" + address + ":" + server.getLocalPort();
            showShareUi(photo, shareUrl);
            shareThread = new Thread(() -> serveShare(server, token, photo), "gallery-share");
            shareThread.start();
        } catch (Exception e) {
            showPlaceholder("Unable to start local sharing");
            showGrid();
        }
    }
    private void shareListenerStopped() { if (current == Screen.SHARE) closeShareScreen(); shareServer = null; }
    private void serveShare(ServerSocket server, String token, Photo photo) {
        while (!server.isClosed()) {
            try (Socket client = server.accept()) {
                client.setSoTimeout(10000);
                BufferedInputStream in = new BufferedInputStream(client.getInputStream());
                ByteArrayOutputStream request = new ByteArrayOutputStream();
                int b, matched = 0;
                while ((b = in.read()) != -1) {
                    request.write(b);
                    if (b == '\n') { if (++matched >= 2) break; } else if (b != '\r') matched = 0;
                    if (request.size() > 4096) break;
                }
                String first = request.toString("US-ASCII").split("\\r?\\n", 2)[0];
                String expected = "GET / ";
                OutputStream out = client.getOutputStream();
                if (!first.startsWith(expected)) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
                } else {
                    String mime = getContentResolver().getType(photo.uri);
                    if (mime == null || !mime.startsWith("image/")) mime = "application/octet-stream";
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nContent-Disposition: attachment; filename=\"" + safeFilename(photo.name) + "\"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                    try (InputStream image = getContentResolver().openInputStream(photo.uri)) {
                        if (image != null) { byte[] buffer = new byte[32768]; int count; while ((count = image.read(buffer)) != -1 && !server.isClosed()) out.write(buffer, 0, count); }
                    }
                    out.flush();
                }
            } catch (java.net.SocketException e) { break; }
            catch (Exception ignored) { }
        }
        runOnUiThread(this::shareListenerStopped);
    }
    private ServerSocket openShareServer() throws java.io.IOException {
        java.net.InetAddress bindAddress = java.net.InetAddress.getByName("0.0.0.0");
        java.io.IOException lastError = null;
        for (int offset = 0; offset <= SHARE_FALLBACK_PORTS; offset++) {
            try { return new ServerSocket(SHARE_PORT + offset, 1, bindAddress); }
            catch (java.io.IOException e) { lastError = e; }
        }
        throw lastError == null ? new java.io.IOException("No available sharing port") : lastError;
    }
    private String safeFilename(String name) { return (name == null ? "image" : name).replaceAll("[^A-Za-z0-9._-]", "_"); }
    private String randomToken() { byte[] bytes = new byte[16]; new SecureRandom().nextBytes(bytes); StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255)); return s.toString(); }
    private String localAddress() {
        try { Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces(); while (interfaces.hasMoreElements()) { NetworkInterface ni = interfaces.nextElement(); if (!ni.isUp() || ni.isLoopback()) continue; Enumeration<java.net.InetAddress> addresses = ni.getInetAddresses(); while (addresses.hasMoreElements()) { java.net.InetAddress a = addresses.nextElement(); if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) return a.getHostAddress(); } } } catch (Exception ignored) { }
        return null;
    }
    private Bitmap gaussianBlur(Bitmap source) {
        int width = Math.max(1, Math.round(source.getWidth() * 0.7f));
        int height = Math.max(1, Math.round(source.getHeight() * 0.7f));
        Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
        if (scaled != source) source.recycle();
        int[] pixels = new int[width * height];
        int[] temp = new int[pixels.length];
        scaled.getPixels(pixels, 0, width, 0, 0, width, height);
        final int radius = 5;
        final float sigma = 2.2f;
        float[] weights = new float[radius * 2 + 1];
        float sum = 0;
        for (int i = -radius; i <= radius; i++) { float w = (float)Math.exp(-(i * i) / (2f * sigma * sigma)); weights[i + radius] = w; sum += w; }
        for (int i = 0; i < weights.length; i++) weights[i] /= sum;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            float a=0,r=0,g=0,b=0;
            for (int k=-radius;k<=radius;k++) { int c=pixels[y*width+Math.max(0,Math.min(width-1,x+k))]; float w=weights[k+radius]; a+=((c>>>24)&255)*w; r+=((c>>>16)&255)*w; g+=((c>>>8)&255)*w; b+=(c&255)*w; }
            temp[y*width+x]=((int)a<<24)|((int)r<<16)|((int)g<<8)|(int)b;
        }
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            float a=0,r=0,g=0,b=0;
            for (int k=-radius;k<=radius;k++) { int c=temp[Math.max(0,Math.min(height-1,y+k))*width+x]; float w=weights[k+radius]; a+=((c>>>24)&255)*w; r+=((c>>>16)&255)*w; g+=((c>>>8)&255)*w; b+=(c&255)*w; }
            pixels[y*width+x]=((int)a<<24)|((int)r<<16)|((int)g<<8)|(int)b;
        }
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, width, 0, 0, width, height);
        scaled.recycle();
        return result;
    }
    private void showShareUi(Photo photo, String url) {
        enterScreen(Screen.SHARE);
        root.removeAllViews();
        root.setBackgroundColor(0xff181a1d);
        ImageView background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        background.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{0.71f,0,0,0,0, 0,0.71f,0,0,0, 0,0,0.71f,0,0, 0,0,0,1,0})));
        root.addView(background, new FrameLayout.LayoutParams(-1, -1));
        loader.execute(() -> {
            Bitmap source = decode(photo, 960);
            Bitmap backdrop = source == null ? null : gaussianBlur(source);
            if (backdrop != null) runOnUiThread(() -> { if (current == Screen.SHARE && background.getParent() == root) background.setImageBitmap(backdrop); });
        });
        View scrim = new View(this);
        scrim.setBackgroundColor(0x33000000);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setGravity(Gravity.CENTER); content.setPadding(dp(24), dp(10), dp(24), dp(148));
        content.setBackgroundColor(0x22000000);
        TextView filename = label(photo.name, 18); filename.setSingleLine(true); content.addView(filename);
        try { BitMatrix matrix = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 240, 240); Bitmap qr = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565); for (int y = 0; y < 240; y++) for (int x = 0; x < 240; x++) qr.setPixel(x, y, matrix.get(x, y) ? 0xff111315 : 0xffffffff); ImageView qrView = new ImageView(this); qrView.setImageBitmap(qr); qrView.setScaleType(ImageView.ScaleType.FIT_CENTER); LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(190), dp(190)); qrParams.topMargin = dp(6); qrParams.bottomMargin = dp(6); content.addView(qrView, qrParams); } catch (Exception ignored) { }
        TextView path = label(url, 16); path.setTextIsSelectable(true); path.setSingleLine(true); path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); path.setPadding(dp(12), dp(6), dp(12), dp(6)); content.addView(path);
        TextView hint = label("Open on a device on the same Wi-Fi network", 15); content.addView(hint);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        shareController.onEnter();
    }
    void closeShareScreen() { stopShareServer(); showGrid(); }
    private void stopShareServer() { if (shareServer != null) { try { shareServer.close(); } catch (Exception ignored) { } shareServer = null; } shareUrl = null; }
    void showDeleteConfirmation() {
        if (photos.isEmpty() || deleteOverlay != null) return;
        // The key that opened this confirm is usually still held; its release must not confirm.
        interaction.clearSwallowed();
        interaction.swallowAllPressed();
        resetButtonHoldForUiChange();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(0xff272b30);
        panelBackground.setCornerRadius(dp(16));
        panel.setBackground(panelBackground);
        TextView message = label("Delete this image?", 20);
        message.setPadding(dp(24), dp(24), dp(24), dp(20));
        panel.addView(message, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(this);
        divider.setBackgroundColor(0xff3a3f46);
        panel.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View confirm = dialogButton("switch_a_96dpi", "Delete");
        View cancel = dialogButton("switch_b_96dpi", "Cancel");
        row.addView(confirm, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x99000000);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> closeDeleteConfirm());
        overlay.addView(panel, new FrameLayout.LayoutParams(dp(400), -2, Gravity.CENTER));
        confirm.setOnClickListener(v -> { closeDeleteConfirm(); deleteCurrentPhoto(); });
        cancel.setOnClickListener(v -> closeDeleteConfirm());
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        deleteOverlay = overlay;
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(OVERLAY_FADE_MS).start();
    }
    private void closeDeleteConfirm() {
        View closing = deleteOverlay;
        deleteOverlay = null;
        interaction.clearSwallowed();
        if (closing != null && closing.getParent() == root) {
            closing.animate().alpha(0f).setDuration(OVERLAY_FADE_MS).withEndAction(() -> root.removeView(closing)).start();
        }
        controllerFor(current).refreshHints();
    }
    /** Dialog action button: controller icon + caption, never focusable so the d-pad cannot select it. */
    private View dialogButton(String iconName, String text) {
        LinearLayout button = new LinearLayout(this);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), dp(18), dp(16), dp(18));
        button.setFocusable(false);
        button.setFocusableInTouchMode(false);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconParams.rightMargin = dp(8);
        button.addView(icon(iconName), iconParams);
        TextView caption = label(text, 17);
        caption.setPadding(0, 0, 0, 0);
        button.addView(caption);
        return button;
    }
    private void deleteCurrentPhoto() { try { DocumentsContract.deleteDocument(getContentResolver(), photos.get(selected).uri); photos.remove(selected); sortPhotos(); selected = Math.max(0, Math.min(selected, photos.size() - 1)); showGrid(); } catch (Exception e) { showPlaceholder("Unable to delete image"); controllerFor(current).refreshHints(); } }

    @Override protected void onDestroy() { if (interaction != null) interaction.reset(); if (directoryCountPoll != null) handler.removeCallbacks(directoryCountPoll); stopShareServer(); super.onDestroy(); loader.shutdownNow(); }
}
