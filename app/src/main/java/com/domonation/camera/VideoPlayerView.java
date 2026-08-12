package com.domonation.camera;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

/** A texture-backed video player that does not suffer from SurfaceView z-order issues. */
final class VideoPlayerView extends FrameLayout implements TextureView.SurfaceTextureListener {
    private final TextureView textureView;
    private MediaPlayer player;
    private Uri source;
    private boolean looping;
    private boolean startWhenReady = true;
    private int videoWidth;
    private int videoHeight;

    VideoPlayerView(Context context) { this(context, null); }

    VideoPlayerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(android.graphics.Color.BLACK);
        textureView = new TextureView(context);
        textureView.setSurfaceTextureListener(this);
        addView(textureView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setOnClickListener(v -> togglePlayback());
    }

    void setVideo(Uri uri, boolean shouldLoop) {
        source = uri;
        looping = shouldLoop;
        startWhenReady = true;
        if (textureView.isAvailable()) prepare(textureView.getSurfaceTexture());
    }

    void release() {
        if (player != null) {
            player.reset();
            player.release();
            player = null;
        }
    }

    private void prepare(SurfaceTexture texture) {
        release();
        if (source == null || texture == null) return;
        try {
            MediaPlayer next = new MediaPlayer();
            player = next;
            next.setDataSource(getContext(), source);
            Surface surface = new Surface(texture);
            next.setSurface(surface);
            surface.release();
            next.setLooping(looping);
            next.setOnVideoSizeChangedListener((mp, width, height) -> {
                videoWidth = width;
                videoHeight = height;
                updateTransform();
            });
            next.setOnPreparedListener(mp -> {
                if (player == mp && startWhenReady) mp.start();
            });
            next.setOnErrorListener((mp, what, extra) -> true);
            next.prepareAsync();
        } catch (Exception error) {
            release();
        }
    }

    private void togglePlayback() {
        if (player == null) return;
        if (player.isPlaying()) {
            startWhenReady = false;
            player.pause();
        } else {
            startWhenReady = true;
            player.start();
        }
    }

    private void updateTransform() {
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (viewWidth == 0 || viewHeight == 0 || videoWidth == 0 || videoHeight == 0) return;
        float scale = Math.min((float) viewWidth / videoWidth, (float) viewHeight / videoHeight);
        float scaledWidth = videoWidth * scale;
        float scaledHeight = videoHeight * scale;
        Matrix matrix = new Matrix();
        matrix.setScale(scaledWidth / viewWidth, scaledHeight / viewHeight,
                viewWidth / 2f, viewHeight / 2f);
        textureView.setTransform(matrix);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateTransform();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        prepare(surface);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        updateTransform();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        release();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }

    @Override protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }
}
