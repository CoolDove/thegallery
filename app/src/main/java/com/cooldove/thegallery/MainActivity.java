package com.cooldove.thegallery;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
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
import java.util.Set;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final long PHOTO_EXIT_DELAY_MS = 200;
    private static final long PHOTO_FADE_MS = 110;
    private static final int EXIT_HOLD_DURATION_MS = 1000;
    private static final int LONG_PRESS_MS = 550;
    private static final int HOLD_HINT_DELAY_MS = 150;
    private static final int PICK_FOLDER = 1;
    private static final int PICK_SOURCE = 2;
    private static final String PREFS = "gallery";
    private static final String[] SORTS = {"Newest", "Oldest", "Name A-Z", "Name Z-A"};
    private static final String[] DISPLAY_MODES = {"Simple", "Normal", "Detailed"};
    /** Shared geometry so the XYAB prompt panel and the D-pad wheel line up exactly. */
    private static final int CLUSTER_BUTTON_DP = 26;
    private static final int CLUSTER_INTERVAL_DP = 30;
    private static final int CLUSTER_WIDTH_DP = 250;
    private static final int CLUSTER_HEIGHT_DP = 124;
    private static final int CLUSTER_CENTER_X_DP = 107;
    private static final long OVERLAY_FADE_MS = 140;
    private final ArrayList<Photo> photos = new ArrayList<>();
    private final ArrayList<Uri> sources = new ArrayList<>();
    private final Handler handler = new Handler();
    private final ExecutorService loader = Executors.newFixedThreadPool(3);
    private final ConcurrentHashMap<String, Boolean> pendingThumbnails = new ConcurrentHashMap<>();
    private final Semaphore thumbnailSlots = new Semaphore(2);
    private final android.util.LruCache<String, Bitmap> cache = new android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private FrameLayout root;
    private GridView grid;
    private TextView status;
    private TextView summary;
    private Uri folder;
    private int selected, sort;
    private boolean viewing;
    private boolean settings;
    private final ArrayList<View> settingsActions = new ArrayList<>();
    private int settingsFocus;
    private int gridColumns = 1;
    private long lastDpadMove;
    private boolean dpadMovePending;
    private final Runnable dpadMove = () -> {
        dpadMovePending = false;
        if (grid == null || viewing || photos.isEmpty()) return;
        int candidate = grid.getSelectedItemPosition();
        if (candidate >= 0) selected = candidate;
        updateGridHighlight();
        grid.setSelection(selected);
    };
    private ImageView fullImage;
    private ImageView incomingImage;
    private int photoTransition;
    private Runnable photoExitRunnable;
    private Runnable exitRunnable;
    private View promptOverlay;
    private View radialOverlay;
    private View deleteOverlay;
    private final Set<Integer> pressedKeys = new HashSet<>();
    private final Set<Integer> swallowedKeyUps = new HashSet<>();
    private HoldRing holdRing;
    private Runnable holdRingTick;
    private long holdRingDuration = LONG_PRESS_MS;
    private int heldButton = KeyEvent.KEYCODE_UNKNOWN;
    private long heldButtonAt;
    private boolean longPressTriggered;
    private Runnable longPressRunnable;
    private Runnable holdHintRunnable;
    private int sortDirection;
    private int displayMode = 1;
    private int[] activeWheelActions;
    private int[] wheelToggleActions;
    private boolean wheelUsesFaceButton;

    private static final class Photo {
        final Uri uri; final String name; final long modified;
        Photo(Uri uri, String name, long modified) { this.uri = uri; this.name = name; this.modified = modified; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this);
        setContentView(root);
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

    private TextView label(String text, int size) { TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(0xffffffff); v.setGravity(Gravity.CENTER); v.setPadding(20, 18, 20, 18); return v; }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        Uri current = folder;
        if (current != null && android.os.Build.VERSION.SDK_INT >= 26) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, current);
        startActivityForResult(intent, PICK_FOLDER);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request == PICK_FOLDER || request == PICK_SOURCE) && result == RESULT_OK && data != null && data.getData() != null) {
            folder = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(folder, flags); } catch (SecurityException ignored) { }
            if (request == PICK_SOURCE) {
                if (!sources.contains(folder)) sources.add(folder);
                persistSources();
                if (settings) showSettings();
            } else {
                sources.clear(); sources.add(folder); persistSources(); scan();
            }
        } else if (request == PICK_FOLDER && folder == null) empty("No folder selected", true);
    }

    private void scan() {
        root.removeAllViews(); photos.clear(); selected = 0;
        empty("Loading images…", false);
        loader.execute(() -> {
            ArrayList<Photo> found = new ArrayList<>();
            try {
                for (Uri source : new ArrayList<>(sources)) {
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
                runOnUiThread(() -> empty("Folder unavailable. Choose another folder.", true)); return;
            }
            photos.addAll(found);
            runOnUiThread(() -> { sortPhotos(); if (settings) showSettings(); else showGrid(); });
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

    private void showSettings() {
        settings = true;
        settingsActions.clear();
        root.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(32), dp(12), dp(32), dp(12));
        page.setBackgroundColor(0xff181a1d);
        TextView heading = label("Settings", 28);
        page.addView(heading, new LinearLayout.LayoutParams(-1, -2));
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
                sources.remove(source);
                persistSources();
                if (sources.isEmpty()) { photos.clear(); showGrid(); }
                else { folder = sources.get(0); scan(); }
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
        FrameLayout backCluster = new FrameLayout(this);
        addClusterButton(backCluster, "switch_b_96dpi", "Back", dp(CLUSTER_CENTER_X_DP), dp(CLUSTER_HEIGHT_DP) / 2 + dp(CLUSTER_INTERVAL_DP), dp(CLUSTER_BUTTON_DP), Gravity.RIGHT, 0);
        backCluster.setOnClickListener(v -> closeSettings());
        root.addView(backCluster, clusterParams(Gravity.RIGHT));
        settingsFocus = Math.max(0, settingsActions.size() - 1);
        focusSettingsAction();
    }

    private void focusSettingsAction() {
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
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        if (folder != null && android.os.Build.VERSION.SDK_INT >= 26) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder);
        startActivityForResult(intent, PICK_SOURCE);
    }

    private void closeSettings() {
        settings = false;
        if (photos.isEmpty()) scan(); else showGrid();
    }

    private void showGrid() {
        viewing = false; closeWheel(); removePromptOverlay(); deleteOverlay = null; swallowedKeyUps.clear(); root.removeAllViews();
        cancelButtonHold();
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setBackgroundColor(0xff181a1d);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        View settingsButton = prompt("switch_plus_96dpi", "Settings", 18); settingsButton.setFocusable(true); settingsButton.setOnClickListener(v -> showSettings()); bar.addView(settingsButton);
        status = null;
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
        addButtonPrompts(false);
        if (photos.isEmpty()) { empty("No images here. Choose another folder.", true); return; }
        selected = Math.min(selected, photos.size() - 1);
        grid.setFocusable(true); grid.setFocusableInTouchMode(true);
        grid.setSelection(selected); grid.requestFocus(); grid.post(() -> { if (grid != null) { grid.requestFocus(); updateGridHighlight(); } });
    }

    private void refreshSummary() {
        if (summary == null) return;
        summary.setText(SORTS[sort] + " | " + photos.size() + " images");
    }

    private void updateGridHighlight() {
        if (grid == null) return;
        int first = grid.getFirstVisiblePosition();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            boolean active = first + i == selected;
            GradientDrawable outline = new GradientDrawable();
            outline.setColor(0xff35383d);
            if (active) outline.setStroke(dp(3), 0xffaeb3b8);
            child.setBackground(outline);
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private ImageView icon(String iconName) {
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
                image.setPadding(dp(8), dp(8), dp(8), dp(8));
                FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(-1, dp(displayMode == 2 ? 210 : 150), Gravity.TOP);
                imageParams.setMargins(dp(4), dp(4), dp(4), 0);
                cell.addView(image, imageParams);
                TextView name = label("", 13);
                name.setGravity(Gravity.TOP | Gravity.LEFT);
                name.setPadding(dp(10), dp(2), dp(10), dp(2));
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, dp(42), Gravity.BOTTOM);
                cell.addView(name, lp);
                TextView metadata = label("", 12);
                metadata.setGravity(Gravity.BOTTOM | Gravity.LEFT);
                metadata.setPadding(dp(10), dp(2), dp(10), dp(4));
                metadata.setBackgroundColor(0xb8111315);
                cell.addView(metadata, new FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM));
            }
            image = (ImageView) cell.getChildAt(0); TextView name = (TextView) cell.getChildAt(1); TextView metadata = (TextView) cell.getChildAt(2); Photo photo = photos.get(position); name.setText(photo.name);
            name.setVisibility(displayMode == 0 ? View.GONE : View.VISIBLE);
            metadata.setVisibility(displayMode == 2 ? View.VISIBLE : View.GONE);
            if (displayMode == 2) metadata.setText(imageMetadata(photo));
            GradientDrawable cellBackground = new GradientDrawable();
            cellBackground.setColor(0xff35383d);
            cellBackground.setCornerRadius(dp(6));
            cell.setBackground(cellBackground);
            cell.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(displayMode == 2 ? 270 : displayMode == 0 ? 166 : 208)));
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

    private void openPhoto() {
        if (photos.isEmpty()) return;
        viewing = true; root.removeAllViews();
        fullImage = new ImageView(this); fullImage.setBackgroundColor(0xff111315); fullImage.setScaleType(ImageView.ScaleType.FIT_CENTER); fullImage.setFocusable(true); fullImage.setFocusableInTouchMode(true); root.addView(fullImage, new FrameLayout.LayoutParams(-1, -1));
        photoTransition++;
        fullImage.setAlpha(1f);
        displayPhoto(); fullImage.requestFocus();
        addButtonPrompts(false);
    }

    private void displayPhoto() {
        if (fullImage == null || photos.isEmpty()) return;
        Photo photo = photos.get(selected);
        int transition = ++photoTransition;
        ImageView previousImage = fullImage;
        if (photoExitRunnable != null) handler.removeCallbacks(photoExitRunnable);
        photoExitRunnable = () -> {
            if (!viewing || transition != photoTransition) return;
            fadeOutImage(previousImage);
        };
        handler.postDelayed(photoExitRunnable, PHOTO_EXIT_DELAY_MS);
        loader.execute(() -> {
            Bitmap bitmap = decode(photo, 2400);
            runOnUiThread(() -> {
                if (!viewing || transition != photoTransition || fullImage == null) return;
                if (bitmap == null) return;
                ImageView incoming = new ImageView(MainActivity.this);
                incoming.setScaleType(ImageView.ScaleType.FIT_CENTER);
                incoming.setBackgroundColor(0xff111315);
                incoming.setImageBitmap(bitmap);
                incoming.setAlpha(0f);
                root.addView(incoming, new FrameLayout.LayoutParams(-1, -1));
                incomingImage = incoming;
                incoming.animate().alpha(1f).setDuration(PHOTO_FADE_MS).withEndAction(() -> {
                    if (!viewing || transition != photoTransition || incomingImage != incoming) {
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
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) pressedKeys.add(key);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            pressedKeys.remove(key);
        }
        if (deleteOverlay != null) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                // Releases of keys that were already held when the confirm opened are swallowed once.
                if (swallowedKeyUps.remove(key)) return true;
                if (key == KeyEvent.KEYCODE_BUTTON_A) { closeDeleteConfirm(); deleteCurrentPhoto(); }
                else if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) closeDeleteConfirm();
            }
            return true;
        }
        if (radialOverlay != null) {
            return handleWheelKey(event);
        }
        if (settings) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    settingsFocus = (settingsFocus + 1) % settingsActions.size(); focusSettingsAction(); return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    settingsFocus = Math.floorMod(settingsFocus - 1, settingsActions.size()); focusSettingsAction(); return true;
                }
                if (key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                    if (!settingsActions.isEmpty()) settingsActions.get(settingsFocus).performClick(); return true;
                }
                if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_BUTTON_START || key == KeyEvent.KEYCODE_MENU) { closeSettings(); return true; }
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && heldButton == KeyEvent.KEYCODE_BUTTON_B && radialOverlay == null) {
                boolean wasLongPress = longPressTriggered;
                boolean passedHint = holdPassedHint();
                cancelButtonHold();
                if (!wasLongPress && !passedHint && viewing) showGrid();
                else if (!wasLongPress && !passedHint && folder == null) pickFolder();
                else if (!viewing) addButtonPrompts(false);
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && heldButton == key && radialOverlay != null) { closeWheel(); boolean wasLongPress = longPressTriggered; heldButton = KeyEvent.KEYCODE_UNKNOWN; longPressTriggered = false; if (!wasLongPress && viewing) showGrid(); else addButtonPrompts(false); return true; }
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && !settings) startButtonHold(key);
            if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean passedHint = holdPassedHint();
                if (heldButton == key) { boolean held = longPressTriggered; cancelButtonHold(); if (!held && !passedHint) { if (settings) closeSettings(); else if (viewing) showGrid(); else if (folder == null) pickFolder(); else addButtonPrompts(false); } else if (!viewing) addButtonPrompts(false); }
                else { cancelExitHold(); if (settings) closeSettings(); else if (viewing) showGrid(); else if (folder == null) pickFolder(); else addButtonPrompts(false); }
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && !viewing && (key == KeyEvent.KEYCODE_BUTTON_START || key == KeyEvent.KEYCODE_MENU)) { if (settings) closeSettings(); else showSettings(); return true; }
        if (key == KeyEvent.KEYCODE_BUTTON_Y) return true;
        if (key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_BUTTON_X) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) startButtonHold(key);
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && heldButton == key) {
                boolean wasLongPress = longPressTriggered;
                boolean passedHint = holdPassedHint();
                cancelButtonHold();
                if (!wasLongPress && !passedHint) performFaceButtonShortPress(key);
                if (!viewing) addButtonPrompts(false);
                return true;
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && viewing && !photos.isEmpty() && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) { selected = Math.floorMod(selected + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1), photos.size()); displayPhoto(); return true; }
        if (event.getAction() == KeyEvent.ACTION_DOWN && !viewing && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_BUTTON_A)) { if (grid == null || photos.isEmpty()) { pickFolder(); return true; } int focused = grid.getSelectedItemPosition(); if (focused >= 0) selected = focused; openPhoto(); return true; }
        if (event.getAction() == KeyEvent.ACTION_DOWN && !viewing && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (grid == null || photos.isEmpty()) return true;
            grid.requestFocus();
            if (grid.getSelectedItemPosition() >= 0) selected = grid.getSelectedItemPosition();
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) selected = Math.max(0, selected - 1);
            else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) selected = Math.min(photos.size() - 1, selected + 1);
            else if (key == KeyEvent.KEYCODE_DPAD_UP) selected = Math.max(0, selected - gridColumns);
            else selected = Math.min(photos.size() - 1, selected + gridColumns);
            grid.setSelection(selected);
            grid.smoothScrollToPosition(selected);
            updateGridHighlight();
            lastDpadMove = android.os.SystemClock.uptimeMillis();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void performFaceButtonShortPress(int key) {
        if (key == KeyEvent.KEYCODE_BUTTON_A && !viewing) {
            if (photos.isEmpty()) { pickFolder(); return; }
            int focused = grid == null ? -1 : grid.getSelectedItemPosition();
            if (focused >= 0) selected = focused;
            openPhoto();
        } else if (key == KeyEvent.KEYCODE_BUTTON_X && !viewing) {
            sort = (sort + 1) % SORTS.length;
            sortPhotos();
            selected = Math.min(selected, Math.max(0, photos.size() - 1));
            showGrid();
        }
    }

    private void applySortFromWheel() {
        String selectedName = photos.isEmpty() ? null : photos.get(Math.min(selected, photos.size() - 1)).name;
        sort = (sort + 1) % SORTS.length;
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

    private void startButtonHold(int key) {
        if (heldButton != KeyEvent.KEYCODE_UNKNOWN) return;
        heldButton = key; heldButtonAt = android.os.SystemClock.uptimeMillis(); longPressTriggered = false;
        boolean holdAction = key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_BUTTON_X || (key == KeyEvent.KEYCODE_BUTTON_A && !viewing);
        if (holdAction) {
            holdHintRunnable = () -> { if (heldButton != key) return; addButtonPrompts(true); };
            handler.postDelayed(holdHintRunnable, HOLD_HINT_DELAY_MS);
        }
        if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) {
            exitRunnable = new Runnable() { @Override public void run() { if (android.os.SystemClock.uptimeMillis() - heldButtonAt >= EXIT_HOLD_DURATION_MS) finish(); else handler.postDelayed(this, 30); } };
            handler.post(exitRunnable);
        } else {
            longPressRunnable = () -> { if (heldButton != key) return; longPressTriggered = true; if (key == KeyEvent.KEYCODE_BUTTON_A && !viewing) showWheel(new String[]{"Delete", "Share", "", ""}, new int[]{1, 2, 0, 0}); else if (key == KeyEvent.KEYCODE_BUTTON_X && !viewing) showWheel(new String[]{SORTS[sort], imageSizeLabel(), "", ""}, new int[]{0, 0, 0, 0}, new int[]{1, 2, 0, 0}); else if (key == KeyEvent.KEYCODE_BUTTON_X && viewing) showDeleteConfirmation(); };
            handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
        }
    }

    /** True once the press has lasted long enough that short-press behavior must be suppressed. */
    private boolean holdPassedHint() {
        return heldButton != KeyEvent.KEYCODE_UNKNOWN && android.os.SystemClock.uptimeMillis() - heldButtonAt >= HOLD_HINT_DELAY_MS;
    }

    private void cancelButtonHold() { if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable); longPressRunnable = null; if (holdHintRunnable != null) handler.removeCallbacks(holdHintRunnable); holdHintRunnable = null; heldButton = KeyEvent.KEYCODE_UNKNOWN; cancelExitHold(); removePromptOverlay(); }
    private String imageSizeLabel() { return DISPLAY_MODES[displayMode]; }
    private void addButtonPrompts(boolean holding) {
        removePromptOverlay();
        if (root == null || viewing && !holding) return;
        FrameLayout cluster = new FrameLayout(this);
        // cluster.setBackgroundColor(0x99000000);
        String a = heldButton == KeyEvent.KEYCODE_BUTTON_A && holding ? "Actions" : (viewing ? "" : "Open");
        String x = heldButton == KeyEvent.KEYCODE_BUTTON_X && holding ? (viewing ? "Delete" : "View options") : (viewing ? "" : "Sort");
        String y = viewing ? "" : "Select";
        String b = heldButton == KeyEvent.KEYCODE_BUTTON_B && holding ? "Exit" : (viewing ? "Back" : "Hold to exit");
        int diameter = dp(CLUSTER_BUTTON_DP);
        int interval = CLUSTER_INTERVAL_DP;
        int centerX = dp(CLUSTER_CENTER_X_DP), centerY = dp(CLUSTER_HEIGHT_DP) / 2;
        boolean holdA = holding && heldButton == KeyEvent.KEYCODE_BUTTON_A && !viewing;
        boolean holdX = holding && heldButton == KeyEvent.KEYCODE_BUTTON_X;
        boolean holdB = holding && (heldButton == KeyEvent.KEYCODE_BUTTON_B || heldButton == KeyEvent.KEYCODE_BACK);
        addClusterButton(cluster, "switch_x_96dpi", x, centerX, centerY - dp(interval), diameter, Gravity.RIGHT, holdX ? LONG_PRESS_MS : 0);
        addClusterButton(cluster, "switch_y", y, centerX - dp(interval), centerY, diameter, Gravity.LEFT, 0);
        addClusterButton(cluster, "switch_a_96dpi", a, centerX + dp(interval), centerY, diameter, Gravity.RIGHT, holdA ? LONG_PRESS_MS : 0);
        addClusterButton(cluster, "switch_b_96dpi", b, centerX, centerY + dp(interval), diameter, Gravity.RIGHT, holdB ? EXIT_HOLD_DURATION_MS : 0);
        root.addView(cluster, clusterParams(Gravity.RIGHT));
        promptOverlay = cluster;
    }

    private FrameLayout.LayoutParams clusterParams(int horizontalGravity) {
        return new FrameLayout.LayoutParams(dp(CLUSTER_WIDTH_DP), dp(CLUSTER_HEIGHT_DP), horizontalGravity | Gravity.BOTTOM);
    }
    /** captionSide marks the side of the button icon the caption is placed on: TOP = above, BOTTOM = below, LEFT = start, RIGHT = end. holdRingDuration > 0 draws a hold ring around this same icon. */
    private void addClusterButton(FrameLayout cluster, String iconName, String caption, int cx, int cy, int diameter, int captionSide, long holdRingDuration) {
        addClusterButton(cluster, icon(iconName), caption, cx, cy, diameter, captionSide, holdRingDuration);
    }
    private void addClusterButton(FrameLayout cluster, View buttonIcon, String caption, int cx, int cy, int diameter, int captionSide, long holdRingDuration) {
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(diameter, diameter, Gravity.TOP | Gravity.LEFT);
        iconParams.leftMargin = cx - diameter / 2;
        iconParams.topMargin = cy - diameter / 2;
        cluster.addView(buttonIcon, iconParams);
        if (holdRingDuration > 0) addHoldRing(cluster, cx, cy, diameter + dp(10), holdRingDuration);
        if (caption.isEmpty()) return;

        TextView text = label(caption, 12);
        text.setPadding(dp(4), dp(2), dp(4), dp(2));
        text.setBackgroundColor(0xcc111315);
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        text.measure(unspecified, unspecified);
        int textWidth = text.getMeasuredWidth();
        int textHeight = text.getMeasuredHeight();
        int gap = dp(3);
        int radius = diameter / 2 + gap;
        int textCenterX = cx;
        int textCenterY = cy;
        if (captionSide == Gravity.TOP) textCenterY = cy - radius - textHeight / 2;
        else if (captionSide == Gravity.BOTTOM) textCenterY = cy + radius + textHeight / 2;
        else if (captionSide == Gravity.LEFT) textCenterX = cx - radius - textWidth / 2;
        else if (captionSide == Gravity.RIGHT) textCenterX = cx + radius + textWidth / 2;
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(textWidth, textHeight, Gravity.TOP | Gravity.LEFT);
        textParams.leftMargin = textCenterX - textWidth / 2;
        textParams.topMargin = textCenterY - textHeight / 2;
        cluster.addView(text, textParams);
    }
    private void addHoldRing(FrameLayout cluster, int cx, int cy, int size, long duration) {
        HoldRing ring = new HoldRing();
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT);
        params.leftMargin = cx - size / 2;
        params.topMargin = cy - size / 2;
        cluster.addView(ring, params);
        stopHoldRingTicker();
        holdRing = ring;
        holdRingDuration = duration;
        holdRingTick = new Runnable() {
            @Override public void run() {
                if (holdRing == null) { holdRingTick = null; return; }
                long elapsed = android.os.SystemClock.uptimeMillis() - heldButtonAt;
                holdRing.setProgress(Math.min(1f, elapsed / (float) holdRingDuration));
                if (elapsed < holdRingDuration) handler.postDelayed(this, 16);
            }
        };
        handler.post(holdRingTick);
    }
    private void stopHoldRingTicker() {
        if (holdRingTick != null) handler.removeCallbacks(holdRingTick);
        holdRingTick = null;
        holdRing = null;
    }
    private final class HoldRing extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;
        HoldRing() {
            super(MainActivity.this);
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeWidth(dp(3));
            trackPaint.setColor(0x40ffffff);
            progressPaint.setStyle(Paint.Style.STROKE);
            progressPaint.setStrokeWidth(dp(3));
            progressPaint.setStrokeCap(Paint.Cap.ROUND);
            progressPaint.setColor(0xffffffff);
        }
        void setProgress(float value) {
            if (progress == value) return;
            progress = value;
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float radius = (Math.min(getWidth(), getHeight()) - dp(3)) / 2f;
            canvas.drawCircle(cx, cy, radius, trackPaint);
            if (progress > 0f) canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90f, 360f * progress, false, progressPaint);
        }
    }
    private void removePromptOverlay() { stopHoldRingTicker(); if (promptOverlay != null && promptOverlay.getParent() == root) root.removeView(promptOverlay); promptOverlay = null; }

    private void showWheel(String[] labels, int[] actions) {
        showWheel(labels, actions, new int[4]);
    }
    private void showWheel(String[] labels, int[] actions, int[] toggles) {
        boolean appearing = radialOverlay == null;
        removeWheelOverlay();
        activeWheelActions = actions;
        wheelToggleActions = toggles;
        wheelUsesFaceButton = heldButton == KeyEvent.KEYCODE_BUTTON_A || heldButton == KeyEvent.KEYCODE_BUTTON_X || heldButton == KeyEvent.KEYCODE_BUTTON_Y;
        FrameLayout cluster = new FrameLayout(this);
        int diameter = dp(CLUSTER_BUTTON_DP);
        int interval = CLUSTER_INTERVAL_DP;
        int centerX = dp(CLUSTER_CENTER_X_DP), centerY = dp(CLUSTER_HEIGHT_DP) / 2;
        addClusterButton(cluster, new DpadIcon(DPAD_UP), labels[0], centerX, centerY - dp(interval), diameter, Gravity.RIGHT, 0);
        addClusterButton(cluster, new DpadIcon(DPAD_DOWN), labels[1], centerX, centerY + dp(interval), diameter, Gravity.RIGHT, 0);
        addClusterButton(cluster, new DpadIcon(DPAD_LEFT), labels[2], centerX - dp(interval), centerY, diameter, Gravity.LEFT, 0);
        addClusterButton(cluster, new DpadIcon(DPAD_RIGHT), labels[3], centerX + dp(interval), centerY, diameter, Gravity.RIGHT, 0);
        FrameLayout overlay = new FrameLayout(this);
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x59000000, 0x99000000}));
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1, dp(CLUSTER_HEIGHT_DP), Gravity.BOTTOM));
        overlay.addView(cluster, clusterParams(Gravity.LEFT));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        radialOverlay = overlay;
        if (appearing) {
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(OVERLAY_FADE_MS).start();
        }
    }
    private boolean handleWheelKey(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP && wheelUsesFaceButton && e.getKeyCode() == heldButton) { closeWheel(); heldButton = KeyEvent.KEYCODE_UNKNOWN; longPressTriggered = false; addButtonPrompts(false); return true; }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int direction = e.getKeyCode(); if (direction == KeyEvent.KEYCODE_BUTTON_B || direction == KeyEvent.KEYCODE_BACK) { closeWheel(); heldButton = KeyEvent.KEYCODE_UNKNOWN; longPressTriggered = false; addButtonPrompts(false); return true; }
        if (direction == heldButton || e.getRepeatCount() > 0) return true;
        int index = direction == KeyEvent.KEYCODE_DPAD_UP ? 0 : direction == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : direction == KeyEvent.KEYCODE_DPAD_LEFT ? 2 : direction == KeyEvent.KEYCODE_DPAD_RIGHT ? 3 : -1;
        if (index < 0) return true;
        int action = activeWheelActions == null ? 0 : activeWheelActions[index];
        int toggle = wheelToggleActions == null ? 0 : wheelToggleActions[index];
        if (toggle == 1) applySortFromWheel();
        else if (toggle == 2) displayMode = (displayMode + 1) % DISPLAY_MODES.length;
        if (toggle != 0) {
            if (toggle == 2) refreshDisplayModeInGrid();
            refreshWheelToggleLabel();
        } else {
            closeWheel();
            if (action == 1) showDeleteConfirmation(); else if (action == 2) showPlaceholder("Sharing will be available later");
        }
        return true;
    }

    private void refreshDisplayModeInGrid() {
        if (grid == null) return;
        gridColumns = Math.max(1, getResources().getDisplayMetrics().widthPixels / dp(displayMode == 2 ? 300 : 210));
        grid.setNumColumns(gridColumns);
        grid.setColumnWidth(dp(displayMode == 2 ? 280 : 190));
        ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        grid.setSelection(selected);
        updateGridHighlight();
    }

    private void refreshWheelToggleLabel() {
        if (radialOverlay == null) return;
        updateWheelLabel(radialOverlay, imageSizeLabel());
    }

    private void updateWheelLabel(View view, String value) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getText().toString().matches("Simple|Normal|Detailed|Small|Big|List")) text.setText(value);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) updateWheelLabel(group.getChildAt(i), value);
        }
    }
    private static final int DPAD_UP = 0, DPAD_DOWN = 1, DPAD_LEFT = 2, DPAD_RIGHT = 3;

    private final class DpadIcon extends View {
        private final int direction;
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint armPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeArmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        DpadIcon(int direction) {
            super(MainActivity.this);
            this.direction = direction;
            circlePaint.setStyle(Paint.Style.FILL);
            circlePaint.setColor(0xff384162);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(2));
            ringPaint.setColor(0xffd7dde5);
            armPaint.setColor(0x59d3e1e7);
            activeArmPaint.setColor(0xffd3e1e7);
        }
        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) / 2f - dp(1);
            canvas.drawCircle(cx, cy, radius, circlePaint);
            canvas.drawCircle(cx, cy, radius - dp(1), ringPaint);
            float halfThickness = radius * 0.19f;
            float corner = halfThickness;
            for (int side = DPAD_UP; side <= DPAD_RIGHT; side++) {
                boolean active = side == direction;
                Paint paint = active ? activeArmPaint : armPaint;
                float length = radius * (active ? 0.78f : 0.58f);
                if (side == DPAD_UP) canvas.drawRoundRect(cx - halfThickness, cy - length, cx + halfThickness, cy, corner, corner, paint);
                else if (side == DPAD_DOWN) canvas.drawRoundRect(cx - halfThickness, cy, cx + halfThickness, cy + length, corner, corner, paint);
                else if (side == DPAD_LEFT) canvas.drawRoundRect(cx - length, cy - halfThickness, cx, cy + halfThickness, corner, corner, paint);
                else canvas.drawRoundRect(cx, cy - halfThickness, cx + length, cy + halfThickness, corner, corner, paint);
            }
        }
    }

    private void closeWheel() {
        View closing = radialOverlay;
        radialOverlay = null;
        activeWheelActions = null;
        wheelToggleActions = null;
        wheelUsesFaceButton = false;
        if (closing != null && closing.getParent() == root) {
            closing.animate().alpha(0f).setDuration(OVERLAY_FADE_MS).withEndAction(() -> root.removeView(closing)).start();
        }
    }
    private void removeWheelOverlay() {
        if (radialOverlay != null && radialOverlay.getParent() == root) root.removeView(radialOverlay);
        radialOverlay = null;
    }
    private void showPlaceholder(String message) { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show(); }
    private void showDeleteConfirmation() {
        if (photos.isEmpty() || deleteOverlay != null) return;
        // The key that opened this confirm is usually still held; its release must not confirm.
        cancelButtonHold();
        swallowedKeyUps.clear();
        swallowedKeyUps.addAll(pressedKeys);
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
        swallowedKeyUps.clear();
        if (closing != null && closing.getParent() == root) {
            closing.animate().alpha(0f).setDuration(OVERLAY_FADE_MS).withEndAction(() -> root.removeView(closing)).start();
        }
        if (!viewing) addButtonPrompts(false);
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
    private void deleteCurrentPhoto() { try { DocumentsContract.deleteDocument(getContentResolver(), photos.get(selected).uri); photos.remove(selected); sortPhotos(); selected = Math.max(0, Math.min(selected, photos.size() - 1)); showGrid(); } catch (Exception e) { showPlaceholder("Unable to delete image"); } }

    private void cancelExitHold() { if (exitRunnable != null) handler.removeCallbacks(exitRunnable); exitRunnable = null; }
    @Override protected void onDestroy() { super.onDestroy(); handler.removeCallbacks(dpadMove); loader.shutdownNow(); }
}
