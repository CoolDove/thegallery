package com.dove.thegallery;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
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
    private TextView photoCountSummary;
    private TextView selectionSummary;
    private android.widget.Toast currentToast;
    private Uri folder;
    int selected, sort;
    private final Set<String> selectedPhotoUris = new HashSet<>();
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
    private Runnable shareBackdropCycle;
    private int shareBackdropIndex;
    private long shareBackdropGeneration;
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
    Theme theme;

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
        theme = Theme.of(this);
        root = new FrameLayout(this);
        root.setBackground(theme.backdrop());
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
        else empty("No images here.", true);
    }

    private void empty(String text, boolean allowPicker) {
        root.removeAllViews();
        root.setBackground(theme.backdrop());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(40), dp(20), dp(40), dp(90));
        box.addView(brand(), new LinearLayout.LayoutParams(-2, -2));
        TextView title = heading(text, 26);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.topMargin = dp(18);
        box.addView(title, titleParams);
        if (allowPicker) {
            View choose = actionPill("switch_plus_96dpi", "Choose folder");
            choose.setOnClickListener(v -> pickFolder());
            LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(-2, -2);
            chooseParams.topMargin = dp(22);
            box.addView(choose, chooseParams);
            choose.requestFocus();
        }
        root.addView(box, new FrameLayout.LayoutParams(-1, -1));
        status = title;
    }

    TextView label(String text, int size) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(theme == null ? 0xffffffff : theme.textPrimary);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setGravity(Gravity.CENTER);
        v.setPadding(20, 18, 20, 18);
        return v;
    }

    TextView heading(String text, int size) {
        TextView v = label(text, size);
        v.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        v.setLetterSpacing(0.01f);
        v.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        return v;
    }

    /** "The" in ink plus "Gallery" in the iiSU-style warm-to-pink gradient. */
    private View brand() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView article = label("The", 17);
        article.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        article.setLetterSpacing(0.05f);
        article.setPadding(0, 0, dp(6), 0);
        TextView name = label("Gallery", 17);
        name.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        name.setLetterSpacing(0.05f);
        name.setPadding(0, 0, 0, 0);
        row.addView(article);
        row.addView(name);
        name.post(() -> {
            if (name.getWidth() == 0) return;
            name.getPaint().setShader(new LinearGradient(0, 0, name.getWidth(), 0,
                    new int[]{theme.accentWarm, theme.accent}, null, Shader.TileMode.CLAMP));
            name.invalidate();
        });
        return row;
    }

    private LinearLayout surfacePill() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(16), dp(8));
        row.setBackground(theme.bordered(theme.surfaceGlass, 1000f, 1f, theme.outline));
        row.setElevation(dp(4));
        row.setClipToOutline(true);
        return row;
    }

    /** Focusable glass pill used for touch and controller actions. */
    private View actionPill(String iconName, String caption) {
        LinearLayout row = surfacePill();
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setOnFocusChangeListener((v, focused) -> v.setBackground(focused
                ? theme.selection(1000f, theme.surface, 2f)
                : theme.bordered(theme.surfaceGlass, 1000f, 1f, theme.outline)));
        ImageView glyph = icon(iconName);
        LinearLayout.LayoutParams glyphParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        glyphParams.rightMargin = dp(8);
        row.addView(glyph, glyphParams);
        TextView text = label(caption, 15);
        text.setPadding(0, 0, 0, 0);
        row.addView(text);
        return row;
    }

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
                cache.evictAll();
                HashSet<String> availableUris = new HashSet<>();
                for (Photo photo : photos) availableUris.add(photo.uri.toString());
                selectedPhotoUris.retainAll(availableUris);
                sortPhotos();
                selected = 0;
                if (selectedUri != null) for (int i = 0; i < photos.size(); i++) if (selectedUri.equals(photos.get(i).uri.toString())) { selected = i; break; }
                if (selectedPhotoUris.size() == 1) {
                    String selectedPhotoUri = selectedPhotoUris.iterator().next();
                    for (int i = 0; i < photos.size(); i++) if (selectedPhotoUri.equals(photos.get(i).uri.toString())) { selected = i; break; }
                }
                scanInProgress = false;
                boolean refreshAgain = sourcesDirty;
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
                if (refreshAgain && current == Screen.GALLERY) scan();
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
        root.setBackground(theme.backdrop());
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(48), dp(20), dp(48), dp(96));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = heading("Settings", 30);
        title.setPadding(0, 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(brand());
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = label("Choose the folders The Gallery reads images from.", 14);
        subtitle.setGravity(Gravity.LEFT);
        subtitle.setTextColor(theme.textSecondary);
        subtitle.setPadding(0, dp(2), 0, 0);
        page.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        TextView category = label("SOURCES", 11);
        category.setGravity(Gravity.LEFT);
        category.setTextColor(theme.textMuted);
        category.setLetterSpacing(0.16f);
        category.setPadding(0, dp(16), 0, dp(4));
        page.addView(category, new LinearLayout.LayoutParams(-1, -2));

        for (int i = 0; i < sources.size(); i++) {
            final Uri source = sources.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(18), dp(12), dp(12), dp(12));
            card.setBackground(theme.bordered(theme.surface, 16f, 1f, theme.outline));
            card.setElevation(dp(2));
            View dot = new View(this);
            dot.setBackground(theme.dot());
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(10), dp(10));
            dotParams.rightMargin = dp(14);
            card.addView(dot, dotParams);
            TextView name = label(source.getLastPathSegment() == null ? source.toString() : source.getLastPathSegment(), 16);
            name.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            card.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView remove = label("Remove", 14);
            remove.setTextColor(theme.textPrimary);
            remove.setPadding(dp(16), dp(7), dp(16), dp(7));
            remove.setBackground(theme.pill(theme.surfaceAlt));
            remove.setFocusable(true);
            remove.setFocusableInTouchMode(true);
            remove.setOnFocusChangeListener((v, focused) -> {
                v.setBackground(focused ? theme.selection(1000f, theme.surface, 2f) : theme.pill(theme.surfaceAlt));
                v.setElevation(dp(focused ? 4 : 0));
            });
            settingsActions.add(remove);
            remove.setOnClickListener(v -> {
                sources.remove(source); sourcesDirty = true;
                persistSources();
                if (sources.isEmpty()) { photos.clear(); showGrid(); }
                else folder = sources.get(0);
                showSettings();
            });
            card.addView(remove);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.topMargin = dp(8);
            page.addView(card, cardParams);
        }

        LinearLayout add = new LinearLayout(this);
        add.setOrientation(LinearLayout.HORIZONTAL);
        add.setGravity(Gravity.CENTER_VERTICAL);
        add.setPadding(dp(18), dp(14), dp(18), dp(14));
        add.setBackground(theme.bordered(theme.surface, 16f, 1f, theme.outline));
        add.setElevation(dp(2));
        add.setFocusable(true);
        add.setFocusableInTouchMode(true);
        add.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(focused ? theme.selection(16f, theme.surface, 2f)
                    : theme.bordered(theme.surface, 16f, 1f, theme.outline));
            v.setElevation(dp(focused ? 7 : 2));
        });
        ImageView plus = icon("ic_add");
        plus.setColorFilter(theme.textPrimary);
        LinearLayout.LayoutParams plusParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        plusParams.rightMargin = dp(12);
        add.addView(plus, plusParams);
        TextView addText = label("Add source", 16);
        addText.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        addText.setPadding(0, 0, 0, 0);
        add.addView(addText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView addHint = label("Folder picker", 13);
        addHint.setTextColor(theme.textSecondary);
        addHint.setPadding(0, 0, 0, 0);
        add.addView(addHint);
        settingsActions.add(add);
        add.setOnClickListener(v -> pickSource());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, -2);
        addParams.topMargin = dp(8);
        page.addView(add, addParams);

        TextView footer = label("Each folder is scanned one level deep.", 13);
        footer.setGravity(Gravity.LEFT);
        footer.setTextColor(theme.textMuted);
        footer.setPadding(0, dp(14), 0, 0);
        page.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.addView(page, new android.widget.FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        settingsFocus = 0;
        focusSettingsAction();
        settingsController.onEnter();
    }

    void focusSettingsAction() {
        if (settingsActions.isEmpty()) return;
        settingsFocus = Math.max(0, Math.min(settingsFocus, settingsActions.size() - 1));
        settingsActions.get(settingsFocus).requestFocus();
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
        root.setBackground(theme.backdrop());
        querySourceFileCounts(false);
        startDirectoryCountPolling();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        FrameLayout bar = new FrameLayout(this);
        bar.setPadding(dp(22), dp(10), dp(22), dp(2));

        LinearLayout leftGroup = new LinearLayout(this);
        leftGroup.setOrientation(LinearLayout.HORIZONTAL);
        leftGroup.setGravity(Gravity.CENTER_VERTICAL);
        View settingsButton = actionPill("switch_plus_96dpi", "Settings");
        settingsButton.setOnClickListener(v -> showSettings());
        leftGroup.addView(settingsButton);
        TextView sortButton = label(SORTS[sort], 14);
        sortButton.setTextColor(theme.textSecondary);
        sortButton.setPadding(dp(12), dp(7), dp(12), dp(7));
        sortButton.setBackground(theme.bordered(theme.surfaceGlass, 1000f, 1f, theme.outline));
        sortButton.setElevation(dp(4));
        sortButton.setFocusable(true);
        sortButton.setOnFocusChangeListener((view, focused) -> view.setBackground(focused
                ? theme.selection(1000f, theme.surface, 2f)
                : theme.bordered(theme.surfaceGlass, 1000f, 1f, theme.outline)));
        sortButton.setOnClickListener(view -> {
            applySortFromWheel();
            sortButton.setText(SORTS[sort]);
        });
        LinearLayout.LayoutParams sortParams = new LinearLayout.LayoutParams(-2, -2);
        sortParams.leftMargin = dp(8);
        leftGroup.addView(sortButton, sortParams);
        status = null;
        refreshIndicator = label("Refreshing…", 12);
        refreshIndicator.setTextColor(theme.textSecondary);
        refreshIndicator.setBackground(theme.pill(theme.surfaceAlt));
        refreshIndicator.setPadding(dp(12), dp(5), dp(12), dp(5));
        refreshIndicator.setVisibility(View.GONE);
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(-2, -2);
        refreshParams.leftMargin = dp(8);
        leftGroup.addView(refreshIndicator, refreshParams);
        bar.addView(leftGroup, new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        bar.addView(brand(), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        LinearLayout summaryPill = new LinearLayout(this);
        summaryPill.setGravity(Gravity.CENTER_VERTICAL);
        summaryPill.setBackground(theme.bordered(theme.surfaceGlass, 1000f, 1f, theme.outline));
        summaryPill.setPadding(dp(16), dp(8), dp(16), dp(8));
        summaryPill.setElevation(dp(4));
        summaryPill.setClipToOutline(true);
        photoCountSummary = label("", 14);
        photoCountSummary.setTextColor(theme.textSecondary);
        photoCountSummary.setPadding(0, 0, 0, 0);
        selectionSummary = label("", 14);
        selectionSummary.setGravity(Gravity.CENTER_VERTICAL);
        selectionSummary.setPadding(0, 0, 0, 0);
        summaryPill.addView(photoCountSummary);
        summaryPill.addView(selectionSummary);
        summary = photoCountSummary;
        bar.addView(summaryPill, new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        refreshSummary();
        layout.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        grid = new GridView(this);
        gridColumns = gridColumnsFor(displayMode);
        grid.setNumColumns(gridColumns);
        grid.setColumnWidth(dp(displayMode == 2 ? 280 : 190));
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(12));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(dp(22), dp(8), dp(22), dp(18));
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setChoiceMode(GridView.CHOICE_MODE_SINGLE);
        grid.setSelector(new android.graphics.drawable.StateListDrawable());
        grid.setDrawSelectorOnTop(false);
        grid.setAdapter(new PhotoAdapter());
        grid.setOnItemClickListener((parent, view, position, id) -> { selected = position; openPhoto(); });
        grid.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { selected = position; updateGridHighlight(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        layout.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1)); root.addView(layout);
        if (photos.isEmpty()) { empty("No images here.", true); return; }
        selected = Math.min(selected, photos.size() - 1);
        grid.setFocusable(true); grid.setFocusableInTouchMode(true);
        grid.setSelection(selected); grid.requestFocus(); grid.post(() -> { if (grid != null) { grid.requestFocus(); updateGridHighlight(); } });
        galleryController.onEnter();
    }

    private int gridColumnsFor(int mode) {
        int available = getResources().getDisplayMetrics().widthPixels - 2 * dp(22);
        return Math.max(1, available / dp(mode == 2 ? 300 : 210));
    }

    private void refreshSummary() {
        if (summary == null) return;
        String count = photos.size() + (photos.size() == 1 ? " image" : " images");
        photoCountSummary.setText(count);
        selectionSummary.setText(selectedPhotoUris.isEmpty() ? "" : "  ·  " + selectedPhotoUris.size() + " selected");
        android.text.SpannableString styledSelection = new android.text.SpannableString(selectionSummary.getText());
        if (!selectedPhotoUris.isEmpty()) styledSelection.setSpan(new android.text.style.ForegroundColorSpan(theme.accent), 0, styledSelection.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        selectionSummary.setText(styledSelection);
    }

    private void updateRefreshIndicator(boolean refreshing) {
        if (refreshIndicator != null) refreshIndicator.setVisibility(refreshing ? View.VISIBLE : View.GONE);
    }

    void updateGridHighlight() {
        if (grid == null) return;
        int first = grid.getFirstVisiblePosition();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            boolean active = first + i == selected;
            String uri = first + i >= 0 && first + i < photos.size() ? photos.get(first + i).uri.toString() : null;
            boolean preview = galleryController != null && galleryController.isPreviewSelected(uri);
            applyTileStyle(child, active, first + i, preview);
        }
    }

    void applyTileStyle(View cell, boolean active) {
        applyTileStyle(cell, active, -1, false);
    }

    private void applyTileStyle(View cell, boolean active, int position) {
        applyTileStyle(cell, active, position, false);
    }

    private void applyTileStyle(View cell, boolean active, int position, boolean preview) {
        cell.setBackground(theme.tile(active));
        cell.setAlpha(1f);
        cell.setClipToOutline(true);
        cell.setElevation(dp(active ? 9 : 2));
        if (cell instanceof FrameLayout && ((FrameLayout) cell).getChildCount() > 4) {
            View previewFrame = ((FrameLayout) cell).getChildAt(4);
            previewFrame.setVisibility(preview ? View.VISIBLE : View.GONE);
            android.graphics.drawable.GradientDrawable outline = new android.graphics.drawable.GradientDrawable();
            outline.setColor(android.graphics.Color.TRANSPARENT);
            outline.setCornerRadius(dp((int) Theme.TILE_RADIUS_DP));
            outline.setStroke(dp(2), (theme.accent & 0x00ffffff) | 0x77000000);
            previewFrame.setBackground(outline);
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            cell.setOutlineSpotShadowColor(active && !preview ? theme.glow : 0x33000000);
            cell.setOutlineAmbientShadowColor(active && !preview ? theme.glow : 0x20000000);
        }
        if (cell instanceof FrameLayout && ((FrameLayout) cell).getChildCount() > 3) {
            TextView marker = (TextView) ((FrameLayout) cell).getChildAt(3);
            boolean chosen = position >= 0 && position < photos.size()
                    && selectedPhotoUris.contains(photos.get(position).uri.toString());
            marker.setVisibility(chosen ? View.VISIBLE : View.GONE);
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
                image.setClipToOutline(true);
                cell.addView(image, new FrameLayout.LayoutParams(-1, dp(143), Gravity.TOP));
                TextView name = label("", 13);
                name.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
                name.setPadding(dp(12), dp(1), dp(12), dp(3));
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                cell.addView(name, new FrameLayout.LayoutParams(-1, dp(40), Gravity.BOTTOM));
                TextView metadata = label("", 11);
                metadata.setGravity(Gravity.BOTTOM | Gravity.LEFT);
                metadata.setPadding(dp(12), 0, dp(12), dp(5));
                cell.addView(metadata, new FrameLayout.LayoutParams(-1, dp(22), Gravity.BOTTOM));
                TextView marker = label("✔", 15);
                marker.setTextColor(0xffffffff);
                marker.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
                marker.setGravity(Gravity.CENTER);
                marker.setPadding(0, 0, 0, dp(1));
                marker.setBackground(theme.dot());
                marker.setElevation(dp(4));
                FrameLayout.LayoutParams markerParams = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.RIGHT | Gravity.TOP);
                markerParams.setMargins(0, dp(9), dp(9), 0);
                cell.addView(marker, markerParams);
            }
            image = (ImageView) cell.getChildAt(0); TextView name = (TextView) cell.getChildAt(1); TextView metadata = (TextView) cell.getChildAt(2); Photo photo = photos.get(position); name.setText(photo.name);
            name.setVisibility(displayMode == 0 ? View.GONE : View.VISIBLE);
            metadata.setVisibility(displayMode == 2 ? View.VISIBLE : View.GONE);
            if (displayMode == 2) metadata.setText(imageMetadata(photo));
            name.setTextColor(theme.textPrimary);
            metadata.setTextColor(theme.textSecondary);
            boolean preview = galleryController != null && galleryController.isPreviewSelected(photo.uri.toString());
            if (cell.getChildCount() == 4) {
                View previewFrame = new View(MainActivity.this);
                previewFrame.setClickable(false);
                cell.addView(previewFrame, new FrameLayout.LayoutParams(-1, -1));
            }
            applyTileStyle(cell, position == selected, position, preview);
            int rowHeight = displayMode == 2 ? 276 : displayMode == 0 ? 160 : 190;
            cell.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(rowHeight)));
            image.setBackground(theme.solid(theme.surfaceAlt, Theme.PHOTO_RADIUS_DP));
            FrameLayout.LayoutParams imageParams = (FrameLayout.LayoutParams) image.getLayoutParams();
            imageParams.setMargins(dp(7), dp(7), dp(7), dp(7));
            imageParams.height = dp(displayMode == 0 ? 146 : displayMode == 2 ? 204 : 143);
            image.setLayoutParams(imageParams);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams nameParams = (FrameLayout.LayoutParams) name.getLayoutParams();
            nameParams.height = dp(displayMode == 2 ? 36 : 40);
            nameParams.bottomMargin = displayMode == 2 ? dp(26) : 0;
            name.setLayoutParams(nameParams);
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
                    if (grid == null) return;
                    if (result == null) {
                        int visibleFirst = grid.getFirstVisiblePosition();
                        View visibleChild = grid.getChildAt(position - visibleFirst);
                        if (visibleChild instanceof FrameLayout && photo.uri.toString().equals(visibleChild.getTag())) {
                            ImageView failedImage = (ImageView) ((FrameLayout) visibleChild).getChildAt(0);
                            failedImage.setImageResource(android.R.drawable.ic_menu_report_image);
                            failedImage.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                        }
                        return;
                    }
                    int first = grid.getFirstVisiblePosition(); View child = grid.getChildAt(position - first);
                    if (child instanceof FrameLayout && photo.uri.toString().equals(child.getTag())) { FrameLayout targetCell = (FrameLayout) child; ((ImageView)targetCell.getChildAt(0)).setImageBitmap(result); if (displayMode == 2) ((TextView)targetCell.getChildAt(2)).setText(imageMetadata(photo)); }
                });
            });
        }
            return cell;
        }
    }

    private Bitmap decode(Photo photo, int target) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(photo.uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (Exception ignored) { return null; }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / sample > target * 2 || bounds.outHeight / sample > target * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        try (InputStream image = getContentResolver().openInputStream(photo.uri)) {
            return image == null ? null : BitmapFactory.decodeStream(image, null, options);
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
        root.setBackgroundColor(theme.viewerBackground);
        fullImage = new ImageView(this); fullImage.setBackgroundColor(theme.viewerBackground); fullImage.setScaleType(ImageView.ScaleType.FIT_CENTER); fullImage.setFocusable(true); fullImage.setFocusableInTouchMode(true); root.addView(fullImage, new FrameLayout.LayoutParams(-1, -1));
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
                incoming.setBackgroundColor(theme.viewerBackground);
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
        if (selectedPhotoUris.size() > 1) { showCannotViewMultipleHint(); return; }
        if (selectedPhotoUris.size() == 1) {
            String selectedUri = selectedPhotoUris.iterator().next();
            for (int i = 0; i < photos.size(); i++) if (selectedUri.equals(photos.get(i).uri.toString())) { selected = i; break; }
        }
        openPhoto();
    }

    String photoUriAt(int position) { return position >= 0 && position < photos.size() ? photos.get(position).uri.toString() : null; }
    boolean isPhotoSelected(int position) { String uri = photoUriAt(position); return uri != null && selectedPhotoUris.contains(uri); }
    boolean selectedPhotoUrisEmpty() { return selectedPhotoUris.isEmpty(); }
    boolean hasPhotoSelection() { return !selectedPhotoUris.isEmpty(); }
    boolean hasMultiplePhotoSelection() { return selectedPhotoUris.size() > 1; }
    void applyRangeSelection(Set<String> uris, boolean remove) {
        if (remove) selectedPhotoUris.removeAll(uris); else selectedPhotoUris.addAll(uris);
        refreshSummary(); updateGridHighlight();
    }
    void clearPhotoSelection() { selectedPhotoUris.clear(); refreshSummary(); updateGridHighlight(); }

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
        refreshSortButtonLabel();
    }

    private void refreshSortButtonLabel() {
        if (grid == null || grid.getParent() == null) return;
        ViewGroup layout = (ViewGroup) grid.getParent();
        if (layout.getChildCount() == 0 || !(layout.getChildAt(0) instanceof FrameLayout)) return;
        FrameLayout bar = (FrameLayout) layout.getChildAt(0);
        if (bar.getChildCount() == 0 || !(bar.getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout controls = (LinearLayout) bar.getChildAt(0);
        if (controls.getChildCount() > 1 && controls.getChildAt(1) instanceof TextView) {
            ((TextView) controls.getChildAt(1)).setText(SORTS[sort]);
        }
    }

    void cycleDisplayModeFromWheel() {
        displayMode = (displayMode + 1) % DISPLAY_MODES.length;
        preferences.edit().putInt("display_mode", displayMode).apply();
        refreshDisplayModeInGrid();
    }

    void refreshDisplayModeInGrid() {
        if (grid == null) return;
        gridColumns = gridColumnsFor(displayMode);
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
    private void showPlaceholder(String message) {
        if (currentToast != null) currentToast.cancel();
        currentToast = android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT);
        currentToast.show();
    }
    void showCannotViewMultipleHint() { showPlaceholder("Cannot view multiple images at once"); }
    void showShareScreen() {
        if (photos.isEmpty()) return;
        resetButtonHoldForUiChange();
        stopShareServer();
        final String token = randomToken();
        final ArrayList<Photo> sharePhotos = getActionPhotos();
        if (sharePhotos.isEmpty()) return;
        final Photo photo = sharePhotos.get(0);
        try {
            ServerSocket server = openShareServer();
            server.setSoTimeout(0);
            shareServer = server;
            String address = localAddress();
            if (address == null) throw new java.io.IOException("No LAN address");
            shareUrl = "http://" + address + ":" + server.getLocalPort();
            showShareUi(photo, shareUrl, sharePhotos);
            shareThread = new Thread(() -> serveShare(server, token, sharePhotos), "gallery-share");
            shareThread.start();
        } catch (Exception e) {
            showPlaceholder("Unable to start local sharing");
            showGrid();
        }
    }
    private void shareListenerStopped() { if (current == Screen.SHARE) closeShareScreen(); shareServer = null; }
    private void serveShare(ServerSocket server, String token, ArrayList<Photo> sharePhotos) {
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
                    if (sharePhotos.size() == 1) {
                        Photo photo = sharePhotos.get(0);
                        String mime = getContentResolver().getType(photo.uri);
                        if (mime == null || !mime.startsWith("image/")) mime = "application/octet-stream";
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nContent-Disposition: attachment; filename=\"" + safeFilename(photo.name) + "\"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                        try (InputStream image = getContentResolver().openInputStream(photo.uri)) {
                            if (image != null) { byte[] buffer = new byte[32768]; int count; while ((count = image.read(buffer)) != -1 && !server.isClosed()) out.write(buffer, 0, count); }
                        }
                    } else {
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Disposition: attachment; filename=\"gallery-images.zip\"\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
                        java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out);
                        byte[] buffer = new byte[32768];
                        for (Photo photo : sharePhotos) {
                            if (server.isClosed()) break;
                            zip.putNextEntry(new java.util.zip.ZipEntry(safeFilename(photo.name)));
                            try (InputStream image = getContentResolver().openInputStream(photo.uri)) {
                                if (image != null) { int count; while ((count = image.read(buffer)) != -1 && !server.isClosed()) zip.write(buffer, 0, count); }
                            } catch (Exception ignored) { }
                            zip.closeEntry();
                        }
                        zip.finish();
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
    private void showShareUi(Photo photo, String url) { showShareUi(photo, url, Collections.singletonList(photo)); }
    private void showShareUi(Photo photo, String url, java.util.List<Photo> sharePhotos) {
        int count = sharePhotos.size();
        enterScreen(Screen.SHARE);
        root.removeAllViews();
        root.setBackground(theme.backdrop());
        ImageView background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        float dim = theme.night ? 0.55f : 0.78f;
        background.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                dim, 0, 0, 0, 0,
                0, dim, 0, 0, 0,
                0, 0, dim, 0, 0,
                0, 0, 0, 1, 0})));
        root.addView(background, new FrameLayout.LayoutParams(-1, -1));
        long generation = ++shareBackdropGeneration;
        shareBackdropIndex = 0;
        setShareBackdrop(background, sharePhotos, generation, 0);
        if (count > 1) {
            shareBackdropCycle = new Runnable() {
                @Override public void run() {
                    if (current != Screen.SHARE || generation != shareBackdropGeneration || background.getParent() != root) return;
                    int next = (shareBackdropIndex + 1) % sharePhotos.size();
                    setShareBackdrop(background, sharePhotos, generation, next);
                    handler.postDelayed(this, 2000);
                }
            };
            handler.postDelayed(shareBackdropCycle, 2000);
        }
        View scrim = new View(this);
        scrim.setBackgroundColor(theme.night ? 0xB3101014 : 0xB8F6F7FB);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(26), dp(20), dp(26), dp(20));
        card.setBackground(theme.bordered(theme.surfaceGlass, 24f, 1f, theme.outline));
        card.setElevation(dp(18));
        card.setClipToOutline(true);
        TextView filename = label(count > 1 ? "Share " + count + " images" : photo.name, 17);
        filename.setSingleLine(true);
        filename.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(filename, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = label(count > 1 ? "Scan to download " + count + " images as a ZIP" : "Scan to open this image", 13);
        subtitle.setTextColor(theme.textSecondary);
        subtitle.setPadding(0, 0, 0, 0);
        card.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout qrWell = new LinearLayout(this);
        qrWell.setGravity(Gravity.CENTER);
        qrWell.setPadding(dp(12), dp(12), dp(12), dp(12));
        qrWell.setBackground(theme.solid(0xffffffff, 18f));
        qrWell.setElevation(dp(3));
        qrWell.setClipToOutline(true);
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 240, 240);
            Bitmap qr = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
            for (int y = 0; y < 240; y++) for (int x = 0; x < 240; x++) qr.setPixel(x, y, matrix.get(x, y) ? 0xff13151b : 0xffffffff);
            ImageView qrView = new ImageView(this);
            qrView.setImageBitmap(qr);
            qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            qrWell.addView(qrView, new LinearLayout.LayoutParams(dp(168), dp(168)));
        } catch (Exception ignored) { }
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(-2, -2);
        qrParams.topMargin = dp(14);
        card.addView(qrWell, qrParams);
        TextView path = label(url, 13);
        path.setTextIsSelectable(true);
        path.setSingleLine(true);
        path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        path.setTextColor(theme.textSecondary);
        path.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(-1, -2);
        pathParams.topMargin = dp(12);
        card.addView(path, pathParams);
        TextView hint = label("Open on a device on the same Wi-Fi network", 12);
        hint.setTextColor(theme.textMuted);
        hint.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(2);
        card.addView(hint, hintParams);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(0, dp(8), 0, dp(86));
        container.addView(card, new FrameLayout.LayoutParams(dp(380), -2, Gravity.CENTER));
        root.addView(container, new FrameLayout.LayoutParams(-1, -1));
        shareController.onEnter();
    }
    private void setShareBackdrop(ImageView target, java.util.List<Photo> sharePhotos, long generation, int index) {
        if (sharePhotos.isEmpty()) return;
        Photo photo = sharePhotos.get(index);
        loader.execute(() -> {
            Bitmap source = decode(photo, 960);
            Bitmap backdrop = source == null ? null : gaussianBlur(source);
            if (backdrop != null) runOnUiThread(() -> {
                if (current != Screen.SHARE || generation != shareBackdropGeneration || target.getParent() != root) {
                    backdrop.recycle();
                    return;
                }
                shareBackdropIndex = index;
                target.setImageBitmap(backdrop);
                target.setAlpha(0f);
                target.animate().alpha(1f).setDuration(650).start();
            });
        });
    }
    void closeShareScreen() { stopShareServer(); showGrid(); }
    private void stopShareServer() {
        if (shareBackdropCycle != null) { handler.removeCallbacks(shareBackdropCycle); shareBackdropCycle = null; }
        shareBackdropGeneration++;
        if (shareServer != null) { try { shareServer.close(); } catch (Exception ignored) { } shareServer = null; }
        shareUrl = null;
    }
    void showDeleteConfirmation() {
        if (photos.isEmpty() || deleteOverlay != null) return;
        // The key that opened this confirm is usually still held; its release must not confirm.
        interaction.clearSwallowed();
        interaction.swallowAllPressed();
        resetButtonHoldForUiChange();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(theme.bordered(theme.surface, 22f, 1f, theme.outline));
        panel.setElevation(dp(24));
        panel.setClipToOutline(true);
        int count = getActionPhotos().size();
        TextView message = label(count > 1 ? "Delete these " + count + " images?" : "Delete this image?", 20);
        message.setPadding(dp(26), dp(24), dp(26), dp(20));
        panel.addView(message, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(this);
        divider.setBackgroundColor(theme.outline);
        panel.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View confirm = dialogButton("switch_a_96dpi", "Delete", theme.danger);
        View cancel = dialogButton("switch_b_96dpi", "Cancel", theme.textPrimary);
        row.addView(confirm, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(theme.scrim);
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
    private View dialogButton(String iconName, String text, int textColor) {
        LinearLayout button = new LinearLayout(this);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), dp(18), dp(16), dp(18));
        button.setFocusable(false);
        button.setFocusableInTouchMode(false);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconParams.rightMargin = dp(8);
        button.addView(icon(iconName), iconParams);
        TextView caption = label(text, 17);
        caption.setTextColor(textColor);
        caption.setPadding(0, 0, 0, 0);
        button.addView(caption);
        return button;
    }
    private ArrayList<Photo> getActionPhotos() {
        ArrayList<Photo> result = new ArrayList<>();
        if (!selectedPhotoUris.isEmpty()) {
            for (Photo photo : photos) if (selectedPhotoUris.contains(photo.uri.toString())) result.add(photo);
        } else if (!photos.isEmpty()) result.add(photos.get(Math.max(0, Math.min(selected, photos.size() - 1))));
        return result;
    }
    private void deleteCurrentPhoto() {
        ArrayList<Photo> targets = getActionPhotos();
        if (targets.isEmpty()) return;
        int deleted = 0;
        HashSet<String> deletedUris = new HashSet<>();
        for (Photo photo : targets) {
            try { if (DocumentsContract.deleteDocument(getContentResolver(), photo.uri)) { deleted++; deletedUris.add(photo.uri.toString()); selectedPhotoUris.remove(photo.uri.toString()); } }
            catch (Exception ignored) { }
        }
        photos.removeIf(photo -> deletedUris.contains(photo.uri.toString()));
        selected = Math.max(0, Math.min(selected, photos.size() - 1));
        showGrid();
        if (deleted < targets.size()) showPlaceholder("Deleted " + deleted + " of " + targets.size() + " images");
    }

    /** System light/dark switch: rebuild the visible screen with the other palette, preserving state. */
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        Theme updated = Theme.of(this);
        if (updated.night == theme.night) { theme = updated; return; }
        theme = updated;
        if (deleteOverlay != null) {
            View overlay = deleteOverlay;
            deleteOverlay = null;
            if (overlay.getParent() == root) root.removeView(overlay);
        }
        switch (current) {
            case SETTINGS:
                showSettings();
                break;
            case VIEWER:
                rerenderViewer();
                break;
            case SHARE:
                if (!photos.isEmpty() && shareUrl != null) showShareUi(photos.get(selected), shareUrl);
                else showGrid();
                break;
            default:
                if (sources.isEmpty() && folder == null) empty("Choose a folder to browse images", true);
                else showGrid();
                break;
        }
    }

    private void rerenderViewer() {
        if (fullImage == null) { openPhoto(); return; }
        root.setBackgroundColor(theme.viewerBackground);
        fullImage.setBackgroundColor(theme.viewerBackground);
        if (incomingImage != null) incomingImage.setBackgroundColor(theme.viewerBackground);
        viewerController.refreshHints();
    }

    @Override protected void onDestroy() { if (interaction != null) interaction.reset(); if (directoryCountPoll != null) handler.removeCallbacks(directoryCountPoll); stopShareServer(); super.onDestroy(); loader.shutdownNow(); }
}
