package com.domonation.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class MediaStorage {
    static final class SaveResult {
        final boolean saved;
        final boolean usedDefaultFallback;

        SaveResult(boolean saved, boolean usedDefaultFallback) {
            this.saved = saved;
            this.usedDefaultFallback = usedDefaultFallback;
        }
    }

    private final Context context;
    private final ContentResolver resolver;

    MediaStorage(Context context) {
        this.context = context;
        resolver = context.getContentResolver();
    }

    SaveResult save(File source, String mime, Uri selectedTree) {
        if (selectedTree != null && saveToSelectedFolder(source, mime, selectedTree)) {
            return new SaveResult(true, false);
        }
        return new SaveResult(saveToMediaStore(source, mime), selectedTree != null);
    }

    private boolean saveToSelectedFolder(File source, String mime, Uri treeUri) {
        DocumentFile created = null;
        try {
            DocumentFile folder = DocumentFile.fromTreeUri(context, treeUri);
            created = folder == null ? null : folder.createFile(mime, source.getName());
            if (created == null) return false;
            long copied = 0;
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(created.getUri(), "rwt");
            if (descriptor == null) throw new IOException("No writable file descriptor");
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                copied = copy(input, output);
            }
            if (copied != source.length() || copied == 0) throw new IOException("Incomplete copy");
            return true;
        } catch (Exception error) {
            Log.e("DomonationCamera", "Selected-folder save failed", error);
            if (created != null) created.delete();
            return false;
        }
    }

    private boolean saveToMediaStore(File source, String mime) {
        Uri destination = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, source.getName());
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    mime.startsWith("video") ? "Movies/PaperCamera" : "Pictures/PaperCamera");
            if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = mime.startsWith("video") ?
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            destination = resolver.insert(collection, values);
            if (destination == null) return false;
            long copied;
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = resolver.openOutputStream(destination)) {
                if (output == null) throw new IOException("No MediaStore output stream");
                copied = copy(input, output);
            }
            if (copied != source.length() || copied == 0) throw new IOException("Incomplete copy");
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues publish = new ContentValues();
                publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(destination, publish, null, null);
            }
            return true;
        } catch (Exception error) {
            Log.e("DomonationCamera", "MediaStore save failed", error);
            if (destination != null) resolver.delete(destination, null, null);
            return false;
        }
    }

    private long copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long copied = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            copied += count;
        }
        output.flush();
        return copied;
    }
}
