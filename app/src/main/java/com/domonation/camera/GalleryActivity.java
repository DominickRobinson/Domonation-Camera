package com.domonation.camera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GalleryActivity extends ComponentActivity {
    private static final String PREFS = "domonation_camera";
    private static final String KEY_TREE = "save_tree";
    private static final String KEY_GALLERY_ROWS = "gallery_rows";
    private static final String KEY_GALLERY_COLUMNS = "gallery_columns";

    private final ArrayList<GalleryMediaItem> items = new ArrayList<>();
    private final Set<Uri> selected = new LinkedHashSet<>();
    private final Map<Uri, FrameLayout> visibleCells = new HashMap<>();
    private final ExecutorService loader = Executors.newFixedThreadPool(2);
    private GalleryMediaRepository mediaRepository;
    private GalleryMediaActions mediaActions;
    private LinearLayout root;
    private LinearLayout header;
    private GridLayout grid;
    private TextView title;
    private TextView pageLabel;
    private ImageButton shareButton;
    private ImageButton deleteButton;
    private ImageButton backButton;
    private TextView previousButton;
    private TextView nextButton;
    private int page;
    private GalleryMediaItem openItem;
    private VideoPlayerView openVideo;
    private boolean loading;
    private ArrayList<GalleryMediaItem> pendingSystemDelete;

    private final ActivityResultLauncher<String[]> mediaPermission =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> loadItems());

    private final ActivityResultLauncher<IntentSenderRequest> deletePermission =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                ArrayList<GalleryMediaItem> pending = pendingSystemDelete;
                pendingSystemDelete = null;
                if (result.getResultCode() == Activity.RESULT_OK) {
                    selected.clear();
                    openItem = null;
                    showGrid();
                    loadItems();
                }
            });

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(AppTheme.wrap(base));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppTheme.applySystemBars(this);
        getWindow().setWindowAnimations(0);
        mediaRepository = new GalleryMediaRepository(this);
        mediaActions = new GalleryMediaActions(this);
        buildGallery();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (openItem != null) { showGrid(); return; }
                if (!selected.isEmpty()) { clearSelection(); return; }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
        requestAccessOrLoad();
    }

    private void buildGallery() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(paper());
        setContentView(root);

        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundResource(R.drawable.top_rule);
        backButton = iconButton(R.drawable.ic_back, "Back");
        backButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        header.addView(backButton, new LinearLayout.LayoutParams(dp(52), dp(52)));
        title = new TextView(this);
        title.setText("Gallery");
        title.setTextColor(ink());
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        shareButton = iconButton(R.drawable.ic_share, "Share selected");
        shareButton.setOnClickListener(v -> shareSelected());
        deleteButton = iconButton(R.drawable.ic_delete, "Delete selected");
        deleteButton.setOnClickListener(v -> confirmDelete(currentSelection()));
        header.addView(shareButton, new LinearLayout.LayoutParams(dp(52), dp(52)));
        header.addView(deleteButton, new LinearLayout.LayoutParams(dp(52), dp(52)));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        grid = new GridLayout(this);
        grid.setColumnCount(gridColumns());
        grid.setRowCount(gridRows());
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setBackgroundColor(paper());
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout pager = new LinearLayout(this);
        pager.setGravity(Gravity.CENTER);
        pager.setBackgroundResource(R.drawable.top_rule);
        previousButton = textButton("‹", "Previous page");
        previousButton.setOnClickListener(v -> { if (page > 0) { page--; renderPage(); } });
        pageLabel = new TextView(this);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setTextColor(ink());
        pageLabel.setTextSize(16);
        nextButton = textButton("›", "Next page");
        nextButton.setOnClickListener(v -> { if ((page + 1) * pageSize() < items.size()) { page++; renderPage(); } });
        pager.addView(previousButton, new LinearLayout.LayoutParams(dp(88), dp(60)));
        pager.addView(pageLabel, new LinearLayout.LayoutParams(0, dp(60), 1));
        pager.addView(nextButton, new LinearLayout.LayoutParams(dp(88), dp(60)));
        root.addView(pager, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
        updateActions();
    }

    private void requestAccessOrLoad() {
        if (prefs().getString(KEY_TREE, null) != null || Build.VERSION.SDK_INT < 23) { loadItems(); return; }
        ArrayList<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.READ_MEDIA_IMAGES);
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (missing.isEmpty()) loadItems(); else mediaPermission.launch(missing.toArray(new String[0]));
    }

    private void loadItems() {
        if (loading) return;
        loading = true;
        title.setText("Loading…");
        loader.execute(() -> {
            String tree = prefs().getString(KEY_TREE, null);
            ArrayList<GalleryMediaItem> found = mediaRepository.load(
                    tree == null ? null : Uri.parse(tree));
            runOnUiThread(() -> {
                items.clear();
                items.addAll(found);
                page = 0;
                loading = false;
                renderPage();
            });
        });
    }

    private void renderPage() {
        openItem = null;
        visibleCells.clear();
        grid.removeAllViews();
        title.setText(selected.isEmpty() ? "Gallery" : selected.size() + " selected");
        int pageSize = pageSize();
        int pages = Math.max(1, (items.size() + pageSize - 1) / pageSize);
        if (page >= pages) page = pages - 1;
        pageLabel.setText(items.isEmpty() ? "No photos or videos" : (page + 1) + " / " + pages);
        previousButton.setVisibility(page > 0 ? View.VISIBLE : View.INVISIBLE);
        nextButton.setVisibility(page < pages - 1 ? View.VISIBLE : View.INVISIBLE);
        int start = page * pageSize;
        int end = Math.min(items.size(), start + pageSize);
        for (int i = start; i < end; i++) grid.addView(mediaCell(items.get(i)));
        for (int i = end; i < start + pageSize; i++) grid.addView(emptyCell());
        updateActions();
    }

    private View emptyCell() {
        View spacer = new View(this);
        spacer.setBackgroundColor(paper());
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = 0;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.setMargins(dp(1), dp(1), dp(1), dp(1));
        spacer.setLayoutParams(lp);
        return spacer;
    }

    private View mediaCell(GalleryMediaItem item) {
        FrameLayout cell = new FrameLayout(this);
        cell.setBackgroundColor(ink());
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = 0;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.setMargins(dp(1), dp(1), dp(1), dp(1));
        cell.setLayoutParams(lp);
        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundColor(Color.LTGRAY);
        cell.addView(thumbnail, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (item.isVideo()) {
            ImageView play = new ImageView(this);
            play.setImageResource(R.drawable.ic_play);
            play.setColorFilter(Color.WHITE);
            play.setBackgroundColor(0x66000000);
            play.setPadding(dp(9), dp(9), dp(9), dp(9));
            FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER);
            cell.addView(play, playLp);
        }
        visibleCells.put(item.uri, cell);
        updateCellSelection(item, cell);
        cell.setContentDescription(item.name + (selected.contains(item.uri) ? ", selected" : ""));
        cell.setOnClickListener(v -> {
            if (selected.isEmpty()) showViewer(item); else toggleSelection(item);
        });
        cell.setOnLongClickListener(v -> { toggleSelection(item); return true; });
        loadThumbnail(item, thumbnail);
        return cell;
    }

    private void loadThumbnail(GalleryMediaItem item, ImageView target) {
        loader.execute(() -> {
            Bitmap ready = mediaRepository.loadThumbnail(item);
            runOnUiThread(() -> { if (ready != null && target.isAttachedToWindow()) target.setImageBitmap(ready); });
        });
    }

    private void toggleSelection(GalleryMediaItem item) {
        if (!selected.add(item.uri)) selected.remove(item.uri);
        FrameLayout cell = visibleCells.get(item.uri);
        if (cell != null) updateCellSelection(item, cell);
        title.setText(selected.isEmpty() ? "Gallery" : selected.size() + " selected");
        updateActions();
    }

    private void clearSelection() {
        selected.clear();
        for (GalleryMediaItem item : items) {
            FrameLayout cell = visibleCells.get(item.uri);
            if (cell != null) updateCellSelection(item, cell);
        }
        title.setText("Gallery");
        updateActions();
    }

    private void updateCellSelection(GalleryMediaItem item, FrameLayout cell) {
        for (int i = cell.getChildCount() - 1; i >= 0; i--) {
            if ("selection".equals(cell.getChildAt(i).getTag())) cell.removeViewAt(i);
        }
        boolean active = selected.contains(item.uri);
        if (active) {
            TextView check = new TextView(this);
            check.setTag("selection");
            check.setText("✓");
            check.setTextSize(22);
            check.setTextColor(paper());
            check.setGravity(Gravity.CENTER);
            check.setBackgroundColor(ink());
            cell.addView(check, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP | Gravity.END));
        }
        cell.setContentDescription(item.name + (active ? ", selected" : ""));
    }

    private void showViewer(GalleryMediaItem item) {
        if (openVideo != null) openVideo.release();
        openVideo = null;
        openItem = item;
        root.removeAllViews();
        LinearLayout viewerHeader = new LinearLayout(this);
        viewerHeader.setGravity(Gravity.CENTER_VERTICAL);
        viewerHeader.setBackgroundResource(R.drawable.top_rule);
        ImageButton close = iconButton(R.drawable.ic_back, "Back to gallery");
        close.setOnClickListener(v -> showGrid());
        TextView detail = new TextView(this);
        detail.setText(formatDate(item.modified));
        detail.setTextColor(ink());
        detail.setTextSize(18);
        detail.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton share = iconButton(R.drawable.ic_share, "Share");
        share.setOnClickListener(v -> share(Collections.singletonList(item)));
        ImageButton delete = iconButton(R.drawable.ic_delete, "Delete");
        delete.setOnClickListener(v -> confirmDelete(Collections.singletonList(item)));
        viewerHeader.addView(close, new LinearLayout.LayoutParams(dp(52), dp(60)));
        viewerHeader.addView(detail, new LinearLayout.LayoutParams(0, dp(60), 1));
        viewerHeader.addView(share, new LinearLayout.LayoutParams(dp(52), dp(60)));
        viewerHeader.addView(delete, new LinearLayout.LayoutParams(dp(52), dp(60)));
        root.addView(viewerHeader, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        if (item.isVideo()) {
            VideoPlayerView video = new VideoPlayerView(this);
            openVideo = video;
            video.setVideo(item.uri, false);
            root.addView(video, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            ZoomImageView image = new ZoomImageView(this);
            root.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            loader.execute(() -> {
                Bitmap ready = mediaRepository.loadImage(item.uri);
                runOnUiThread(() -> { if (openItem == item && ready != null) image.setImageBitmap(ready); });
            });
        }
        addViewerNavigation(item);
    }

    private void addViewerNavigation(GalleryMediaItem item) {
        int index = items.indexOf(item);
        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER);
        navigation.setBackgroundResource(R.drawable.top_rule);
        TextView previous = textButton("‹", "Previous item");
        TextView position = new TextView(this);
        position.setText((index + 1) + " / " + items.size());
        position.setTextColor(ink());
        position.setTextSize(15);
        position.setGravity(Gravity.CENTER);
        TextView next = textButton("›", "Next item");
        previous.setVisibility(index > 0 ? View.VISIBLE : View.INVISIBLE);
        next.setVisibility(index + 1 < items.size() ? View.VISIBLE : View.INVISIBLE);
        previous.setOnClickListener(v -> { if (index > 0) showViewer(items.get(index - 1)); });
        next.setOnClickListener(v -> { if (index + 1 < items.size()) showViewer(items.get(index + 1)); });
        navigation.addView(previous, new LinearLayout.LayoutParams(dp(88), dp(52)));
        navigation.addView(position, new LinearLayout.LayoutParams(0, dp(52), 1));
        navigation.addView(next, new LinearLayout.LayoutParams(dp(88), dp(52)));
        root.addView(navigation, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
    }

    private void showGrid() {
        if (openVideo != null) openVideo.release();
        openVideo = null;
        openItem = null;
        root.removeAllViews();
        buildGallery();
        renderPage();
    }

    private List<GalleryMediaItem> currentSelection() {
        ArrayList<GalleryMediaItem> result = new ArrayList<>();
        for (GalleryMediaItem item : items) if (selected.contains(item.uri)) result.add(item);
        return result;
    }

    private void shareSelected() { share(currentSelection()); }

    private void share(List<GalleryMediaItem> chosen) {
        if (chosen.isEmpty()) return;
        startActivity(Intent.createChooser(mediaActions.shareIntent(chosen), "Share media"));
    }

    private void confirmDelete(List<GalleryMediaItem> chosen) {
        if (chosen.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(chosen.size() == 1 ? "Delete this item?" : "Delete " + chosen.size() + " items?")
                .setMessage("This cannot be undone.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE", (dialog, which) -> delete(chosen))
                .show();
    }

    private void delete(List<GalleryMediaItem> chosen) {
        if (Build.VERSION.SDK_INT >= 30) {
            ArrayList<GalleryMediaItem> mediaItems = new ArrayList<>();
            ArrayList<GalleryMediaItem> directItems = new ArrayList<>();
            for (GalleryMediaItem item : chosen) {
                if ("media".equals(item.uri.getAuthority())) mediaItems.add(item);
                else directItems.add(item);
            }
            int directlyRemoved = mediaActions.deleteDirectly(directItems);
            if (!mediaItems.isEmpty()) {
                ArrayList<Uri> uris = new ArrayList<>();
                for (GalleryMediaItem item : mediaItems) uris.add(item.uri);
                try {
                    PendingIntent request = MediaStore.createDeleteRequest(getContentResolver(), uris);
                    pendingSystemDelete = mediaItems;
                    deletePermission.launch(new IntentSenderRequest.Builder(request.getIntentSender()).build());
                    return;
                } catch (RuntimeException error) {
                    // Leave the item in place when Android cannot present its delete request.
                }
            }
            finishDirectDelete(chosen.size(), directlyRemoved);
            return;
        }
        finishDirectDelete(chosen.size(), mediaActions.deleteDirectly(chosen));
    }

    private void finishDirectDelete(int requested, int removed) {
        selected.clear();
        openItem = null;
        showGrid();
        loadItems();
    }

    private void updateActions() {
        boolean active = !selected.isEmpty();
        shareButton.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
        deleteButton.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
    }

    private ImageButton iconButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setContentDescription(description);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setColorFilter(ink());
        button.setPadding(dp(14), dp(14), dp(14), dp(14));
        button.setStateListAnimator(null);
        return button;
    }

    private TextView textButton(String text, String description) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(40);
        button.setTextColor(ink());
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setTranslationY(-dp(2));
        button.setContentDescription(description);
        return button;
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    private int gridRows() { return clampGrid(prefs().getInt(KEY_GALLERY_ROWS, 3)); }
    private int gridColumns() { return clampGrid(prefs().getInt(KEY_GALLERY_COLUMNS, 3)); }
    private int pageSize() { return gridRows() * gridColumns(); }
    private int clampGrid(int value) { return Math.max(2, Math.min(4, value)); }
    private int paper() { return ContextCompat.getColor(this, R.color.paper); }
    private int ink() { return ContextCompat.getColor(this, R.color.ink); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String formatDate(long time) {
        if (time <= 0) return "Media";
        return new SimpleDateFormat("dd MMM yyyy\nHH:mm", Locale.getDefault()).format(new Date(time));
    }

    @Override protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

}
