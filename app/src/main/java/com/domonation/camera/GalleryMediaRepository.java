package com.domonation.camera;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Size;

import androidx.documentfile.provider.DocumentFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class GalleryMediaRepository {
    private final Context context;
    private final ContentResolver resolver;

    GalleryMediaRepository(Context context) {
        this.context = context.getApplicationContext();
        resolver = context.getContentResolver();
    }

    ArrayList<GalleryMediaItem> load(Uri selectedTree) {
        ArrayList<GalleryMediaItem> result = new ArrayList<>();
        if (selectedTree == null) loadDefaultMedia(result); else loadTreeMedia(result, selectedTree);
        result.sort(Comparator.comparingLong((GalleryMediaItem item) -> item.modified).reversed());
        return result;
    }

    Bitmap loadThumbnail(GalleryMediaItem item) {
        if (item.isVideo()) {
            Bitmap frame = loadVideoFrame(item.uri);
            if (frame != null) return frame;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return resolver.loadThumbnail(item.uri, new Size(360, 360), null);
            }
            return loadImage(item.uri);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Bitmap loadVideoFrame(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            Bitmap frame;
            if (Build.VERSION.SDK_INT >= 27) {
                frame = retriever.getScaledFrameAtTime(1_000_000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360);
            } else {
                frame = retriever.getFrameAtTime(1_000_000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) frame = retriever.getFrameAtTime(-1);
            return frame;
        } catch (Exception ignored) {
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    Bitmap loadImage(Uri uri) {
        try (InputStream input = resolver.openInputStream(uri)) {
            return BitmapFactory.decodeStream(input);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void loadTreeMedia(List<GalleryMediaItem> result, Uri treeUri) {
        DocumentFile folder = DocumentFile.fromTreeUri(context, treeUri);
        if (folder == null || !folder.canRead()) return;
        for (DocumentFile file : folder.listFiles()) {
            String mime = file.getType();
            if (file.isFile() && mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                result.add(new GalleryMediaItem(file.getUri(), file.getName(), mime, file.lastModified()));
            }
        }
    }

    private void loadDefaultMedia(List<GalleryMediaItem> result) {
        queryCollection(result, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media.RELATIVE_PATH + "=?", new String[]{"Pictures/DomonationCamera/"});
        queryCollection(result, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.RELATIVE_PATH + "=?", new String[]{"Movies/DomonationCamera/"});
    }

    private void queryCollection(List<GalleryMediaItem> result, Uri collection,
                                 String selection, String[] args) {
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED};
        try (Cursor cursor = resolver.query(collection, projection, selection, args, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                result.add(new GalleryMediaItem(
                        ContentUris.withAppendedId(collection, cursor.getLong(0)),
                        cursor.getString(1), cursor.getString(2), cursor.getLong(3) * 1000L));
            }
        } catch (RuntimeException ignored) { }
    }
}
