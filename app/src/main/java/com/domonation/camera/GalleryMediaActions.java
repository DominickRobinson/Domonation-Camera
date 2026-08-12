package com.domonation.camera;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;

final class GalleryMediaActions {
    private final Context context;
    private final ContentResolver resolver;

    GalleryMediaActions(Context context) {
        this.context = context;
        resolver = context.getContentResolver();
    }

    Intent shareIntent(List<GalleryMediaItem> items) {
        ArrayList<Uri> uris = new ArrayList<>();
        boolean image = false;
        boolean video = false;
        for (GalleryMediaItem item : items) {
            uris.add(item.uri);
            if (item.isVideo()) video = true; else image = true;
        }
        Intent intent = new Intent(items.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        intent.setType(image && video ? "*/*" : video ? "video/*" : "image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (items.size() == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        ClipData clip = ClipData.newUri(resolver, items.get(0).name, items.get(0).uri);
        for (int i = 1; i < items.size(); i++) clip.addItem(new ClipData.Item(items.get(i).uri));
        intent.setClipData(clip);
        return intent;
    }

    int deleteDirectly(List<GalleryMediaItem> items) {
        int removed = 0;
        for (GalleryMediaItem item : items) if (deleteDirectly(item)) removed++;
        return removed;
    }

    boolean deleteDirectly(GalleryMediaItem item) {
        if (DocumentsContract.isDocumentUri(context, item.uri)) {
            try {
                if (DocumentsContract.deleteDocument(resolver, item.uri)) return true;
            } catch (Exception error) {
                Log.e("DomonationCamera", "Document-provider delete failed for " + item.uri, error);
            }
        }
        try {
            if (resolver.delete(item.uri, null, null) > 0) return true;
            DocumentFile file = DocumentFile.fromSingleUri(context, item.uri);
            return file != null && file.delete();
        } catch (RuntimeException error) {
            Log.e("DomonationCamera", "Resolver delete failed for " + item.uri, error);
            return false;
        }
    }
}
