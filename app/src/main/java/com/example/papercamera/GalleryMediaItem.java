package com.domonation.camera;

import android.net.Uri;

final class GalleryMediaItem {
    final Uri uri;
    final String name;
    final String mime;
    final long modified;

    GalleryMediaItem(Uri uri, String name, String mime, long modified) {
        this.uri = uri;
        this.name = name == null ? "Media" : name;
        this.mime = mime == null ? "application/octet-stream" : mime;
        this.modified = modified;
    }

    boolean isVideo() {
        return mime.startsWith("video/");
    }
}
