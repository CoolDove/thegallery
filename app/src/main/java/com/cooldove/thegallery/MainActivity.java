package com.cooldove.thegallery;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final long PHOTO_LOAD_TIMEOUT_MS = 500;
    private static final long PHOTO_FADE_MS = 110;
    private static final int PICK_FOLDER = 1;
    private static final String PREFS = "gallery";
    private static final String[] SORTS = {"Newest", "Oldest", "Name A–Z", "Name Z–A"};
    private final ArrayList<Photo> photos = new ArrayList<>();
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
    private Uri folder;
    private int selected, sort;
    private boolean viewing;
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
    private boolean transitionWaiting;
    private boolean transitionStarted;
    private ProgressBar exitProgress;
    private TextView exitHint;
    private Runnable exitRunnable;
    private Runnable exitHintRunnable;

    private static final class Photo {
        final Uri uri; final String name; final long modified;
        Photo(Uri uri, String name, long modified) { this.uri = uri; this.name = name; this.modified = modified; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this);
        setContentView(root);
        String savedText = getPreferences(0).getString("folder", "");
        if (!savedText.isEmpty()) { folder = Uri.parse(savedText); scan(); }
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
        if (request == PICK_FOLDER && result == RESULT_OK && data != null && data.getData() != null) {
            folder = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(folder, flags); } catch (SecurityException ignored) { }
            getPreferences(0).edit().putString("folder", folder.toString()).apply();
            scan();
        } else if (request == PICK_FOLDER && folder == null) empty("No folder selected", true);
    }

    private void scan() {
        root.removeAllViews(); photos.clear(); selected = 0;
        empty("Loading images…", false);
        loader.execute(() -> {
            ArrayList<Photo> found = new ArrayList<>();
            try {
                String id = DocumentsContract.getTreeDocumentId(folder);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, id);
                try (android.database.Cursor c = getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                    if (c != null) while (c.moveToNext()) {
                        String mime = c.getString(2), name = c.getString(1);
                        if (mime != null && mime.startsWith("image/")) found.add(new Photo(DocumentsContract.buildDocumentUriUsingTree(folder, c.getString(0)), name == null ? "" : name, c.getLong(3)));
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> empty("Folder unavailable. Choose another folder.", true)); return;
            }
            photos.addAll(found);
            runOnUiThread(() -> { sortPhotos(); showGrid(); });
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

    private void showGrid() {
        viewing = false; root.removeAllViews();
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setBackgroundColor(0xff101116);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        View folderButton = prompt("switch_plus_96dpi", "Folder", 18); folderButton.setOnClickListener(v -> pickFolder()); bar.addView(folderButton);
        status = label(SORTS[sort] + "  ·  " + photos.size() + " images", 16); bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        layout.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        grid = new GridView(this); gridColumns = Math.max(1, getResources().getDisplayMetrics().widthPixels / dp(210)); grid.setNumColumns(gridColumns); grid.setColumnWidth(dp(190)); grid.setHorizontalSpacing(dp(10)); grid.setVerticalSpacing(dp(10)); grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH); grid.setPadding(dp(16), dp(12), dp(16), dp(12)); grid.setClipToPadding(false); grid.setChoiceMode(GridView.CHOICE_MODE_SINGLE); grid.setSelector(new android.graphics.drawable.StateListDrawable()); grid.setDrawSelectorOnTop(false); grid.setAdapter(new PhotoAdapter());
        grid.setOnItemClickListener((parent, view, position, id) -> { selected = position; openPhoto(); });
        grid.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { selected = position; updateGridHighlight(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        layout.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1)); root.addView(layout);
        LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER); controls.setBackgroundColor(0xff191b22);
        controls.addView(prompt("switch_a_96dpi", "View", 15));
        controls.addView(prompt("switch_x_96dpi", "Sort", 15));
        controls.addView(prompt("switch_b_96dpi", "Back · hold to exit", 15));
        layout.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        if (photos.isEmpty()) { empty("No images here. Choose another folder.", true); return; }
        selected = Math.min(selected, photos.size() - 1);
        grid.setFocusable(true); grid.setFocusableInTouchMode(true);
        grid.setSelection(selected); grid.requestFocus(); grid.post(() -> { if (grid != null) { grid.requestFocus(); updateGridHighlight(); } });
    }

    private void updateGridHighlight() {
        if (grid == null) return;
        int first = grid.getFirstVisiblePosition();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            boolean active = first + i == selected;
            child.setBackgroundColor(active ? 0xff59c8ff : 0xff262832);
            child.setPadding(active ? dp(4) : 0, active ? dp(4) : 0, active ? dp(4) : 0, active ? dp(4) : 0);
            child.setElevation(active ? dp(6) : 0);
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
            if (cell.getChildCount() == 0) { image = new ImageView(MainActivity.this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); cell.addView(image, new FrameLayout.LayoutParams(-1, -1)); TextView name = label("", 13); name.setBackgroundColor(0x99000000); FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); cell.addView(name, lp); }
            image = (ImageView) cell.getChildAt(0); TextView name = (TextView) cell.getChildAt(1); Photo photo = photos.get(position); name.setText(photo.name);
            cell.setBackgroundColor(0xff262832); cell.setPadding(0, 0, 0, 0); cell.setElevation(0);
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
                    if (child instanceof FrameLayout && photo.uri.toString().equals(child.getTag())) ((ImageView)((FrameLayout)child).getChildAt(0)).setImageBitmap(result);
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

    private void openPhoto() {
        if (photos.isEmpty()) return;
        viewing = true; root.removeAllViews();
        fullImage = new ImageView(this); fullImage.setBackgroundColor(0xff08090c); fullImage.setScaleType(ImageView.ScaleType.FIT_CENTER); fullImage.setFocusable(true); fullImage.setFocusableInTouchMode(true); root.addView(fullImage, new FrameLayout.LayoutParams(-1, -1));
        photoTransition++;
        fullImage.setAlpha(0f);
        displayPhoto(); fullImage.requestFocus();
    }

    private void displayPhoto() {
        if (fullImage == null || photos.isEmpty()) return;
        Photo photo = photos.get(selected);
        int transition = ++photoTransition;
        transitionWaiting = true;
        transitionStarted = false;
        handler.removeCallbacks(photoLoadTimeout);
        handler.postDelayed(photoLoadTimeout, PHOTO_LOAD_TIMEOUT_MS);
        loader.execute(() -> {
            Bitmap bitmap = decode(photo, 2400);
            runOnUiThread(() -> {
                if (!viewing || transition != photoTransition || fullImage == null) return;
                handler.removeCallbacks(photoLoadTimeout);
                transitionWaiting = false;
                if (bitmap == null) { beginPhotoFadeOut(transition); return; }
                if (transitionStarted) {
                    fullImage.setImageBitmap(bitmap);
                    fullImage.setAlpha(0f);
                    fullImage.animate().alpha(1f).setDuration(PHOTO_FADE_MS).start();
                    transitionStarted = true;
                } else {
                    if (fullImage.getDrawable() == null) {
                        fullImage.setImageBitmap(bitmap);
                        fullImage.setAlpha(0f);
                        fullImage.animate().alpha(1f).setDuration(PHOTO_FADE_MS).start();
                    } else {
                        if (incomingImage != null) root.removeView(incomingImage);
                        incomingImage = new ImageView(MainActivity.this);
                        incomingImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        incomingImage.setBackgroundColor(0xff08090c);
                        incomingImage.setImageBitmap(bitmap);
                        incomingImage.setAlpha(0f);
                        root.addView(incomingImage, new FrameLayout.LayoutParams(-1, -1));
                        incomingImage.animate().alpha(1f).setDuration(PHOTO_FADE_MS).withEndAction(() -> {
                            if (transition != photoTransition || incomingImage == null) return;
                            fullImage.setImageBitmap(bitmap);
                            root.removeView(incomingImage);
                            incomingImage = null;
                        }).start();
                    }
                }
            });
        });
    }

    private final Runnable photoLoadTimeout = () -> {
        if (!viewing || !transitionWaiting || fullImage == null) return;
        transitionWaiting = false;
        beginPhotoFadeOut(photoTransition);
    };

    private void beginPhotoFadeOut(int transition) {
        if (transition != photoTransition || fullImage == null) return;
        if (incomingImage != null) { root.removeView(incomingImage); incomingImage = null; }
        transitionStarted = true;
        fullImage.setImageDrawable(null);
        fullImage.animate().cancel();
        fullImage.animate().alpha(0f).setDuration(PHOTO_FADE_MS).withEndAction(() -> {
            if (!viewing || transition != photoTransition || fullImage == null) return;
        }).start();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (!viewing && key == KeyEvent.KEYCODE_BUTTON_START) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && grid != null) grid.requestFocus();
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) beginExitHold();
            if (event.getAction() == KeyEvent.ACTION_UP) { cancelExitHold(); if (viewing) showGrid(); else if (folder == null) pickFolder(); }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && !viewing && photos.isEmpty() && (key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_DPAD_CENTER)) { pickFolder(); return true; }
        if (event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_BUTTON_X || key == KeyEvent.KEYCODE_X)) { sort = (sort + 1) % SORTS.length; sortPhotos(); selected = Math.min(selected, Math.max(0, photos.size() - 1)); showGrid(); return true; }
        if (event.getAction() == KeyEvent.ACTION_DOWN && viewing && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) { selected = Math.floorMod(selected + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1), photos.size()); displayPhoto(); return true; }
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

    private void beginExitHold() {
        if (exitRunnable != null) return;
        exitProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); exitProgress.setMax(1500); exitProgress.setProgress(0);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, dp(8), Gravity.BOTTOM); root.addView(exitProgress, lp);
        exitHint = label("长按B退出应用", 16);
        exitHint.setBackgroundColor(0xaa191b22);
        exitHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        hintParams.bottomMargin = dp(8);
        root.addView(exitHint, hintParams);
        exitHintRunnable = () -> { if (exitHint != null) exitHint.setVisibility(View.VISIBLE); };
        handler.postDelayed(exitHintRunnable, 300);
        long started = android.os.SystemClock.uptimeMillis();
        exitRunnable = new Runnable() { @Override public void run() { int progress = (int)(android.os.SystemClock.uptimeMillis() - started); if (exitProgress != null) exitProgress.setProgress(progress); if (progress >= 1500) finish(); else handler.postDelayed(this, 30); } };
        handler.post(exitRunnable);
    }
    private void cancelExitHold() { if (exitRunnable != null) handler.removeCallbacks(exitRunnable); if (exitHintRunnable != null) handler.removeCallbacks(exitHintRunnable); exitRunnable = null; exitHintRunnable = null; if (exitProgress != null) root.removeView(exitProgress); if (exitHint != null) root.removeView(exitHint); exitProgress = null; exitHint = null; }
    @Override protected void onDestroy() { super.onDestroy(); handler.removeCallbacks(dpadMove); loader.shutdownNow(); }
}
