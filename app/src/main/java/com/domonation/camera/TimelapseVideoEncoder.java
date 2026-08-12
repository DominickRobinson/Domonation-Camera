package com.domonation.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

final class TimelapseVideoEncoder {
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final long TIMEOUT_US = 10_000;

    private TimelapseVideoEncoder() {}

    static void encode(List<File> frames, File output, int fps) throws IOException {
        if (frames.isEmpty()) throw new IOException("No timelapse frames");
        Bitmap first = decodeOriented(frames.get(0));
        if (first == null) throw new IOException("Could not decode first frame");

        EncoderChoice choice = findEncoder();
        int[] dimensions = chooseDimensions(first.getWidth(), first.getHeight(), choice.info);
        int width = dimensions[0];
        int height = dimensions[1];
        first.recycle();

        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, choice.colorFormat);
        format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(1_000_000, width * height * fps / 8));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2);

        MediaCodec codec = MediaCodec.createByCodecName(choice.info.getName());
        MediaMuxer muxer = null;
        DrainState state = new DrainState();
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int encodedFrameCount = Math.max(2, frames.size());
            for (int i = 0; i < encodedFrameCount; i++) {
                Bitmap decoded = decodeOriented(frames.get(Math.min(i, frames.size() - 1)));
                if (decoded == null) throw new IOException("Could not decode frame " + i);
                Bitmap scaled = decoded.getWidth() == width && decoded.getHeight() == height ?
                        decoded : Bitmap.createScaledBitmap(decoded, width, height, true);
                if (scaled != decoded) decoded.recycle();
                byte[] yuv = bitmapToYuv420(scaled, choice.semiPlanar);
                scaled.recycle();
                queueInput(codec, yuv, i * 1_000_000L / fps, 0);
                drain(codec, muxer, state, false);
            }

            queueInput(codec, new byte[0], encodedFrameCount * 1_000_000L / fps,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(codec, muxer, state, true);
            if (!state.started) throw new IOException("Encoder produced no video track");
        } finally {
            try { codec.stop(); } catch (Exception ignored) {}
            codec.release();
            if (muxer != null) {
                try { if (state.started) muxer.stop(); } catch (Exception ignored) {}
                muxer.release();
            }
        }
    }

    private static EncoderChoice findEncoder() throws IOException {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            boolean avc = false;
            for (String type : info.getSupportedTypes()) if (MIME.equalsIgnoreCase(type)) avc = true;
            if (!avc) continue;
            MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(MIME);
            int planar = 0;
            int flexible = 0;
            for (int color : capabilities.colorFormats) {
                if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                    return new EncoderChoice(info, color, true);
                }
                if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) planar = color;
                if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) flexible = color;
            }
            if (planar != 0) return new EncoderChoice(info, planar, false);
            if (flexible != 0) return new EncoderChoice(info, flexible, false);
        }
        throw new IOException("No compatible AVC encoder");
    }

    private static int[] chooseDimensions(int sourceWidth, int sourceHeight, MediaCodecInfo info)
            throws IOException {
        MediaCodecInfo.VideoCapabilities video = info.getCapabilitiesForType(MIME).getVideoCapabilities();
        double scale = Math.min(1.0, 1280.0 / Math.max(sourceWidth, sourceHeight));
        int widthAlignment = Math.max(2, video.getWidthAlignment());
        int heightAlignment = Math.max(2, video.getHeightAlignment());
        for (int attempt = 0; attempt < 8; attempt++) {
            int width = alignDown((int) Math.round(sourceWidth * scale), widthAlignment);
            int height = alignDown((int) Math.round(sourceHeight * scale), heightAlignment);
            width = Math.max(widthAlignment, width);
            height = Math.max(heightAlignment, height);
            try {
                if (video.isSizeSupported(width, height)) return new int[]{width, height};
            } catch (IllegalArgumentException ignored) {}
            scale *= 0.75;
        }
        throw new IOException("Camera frame size is not supported by the video encoder");
    }

    private static int alignDown(int value, int alignment) {
        return value - value % alignment;
    }

    private static Bitmap decodeOriented(File file) throws IOException {
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null) return null;
        int rotation = new ExifInterface(file).getRotationDegrees();
        if (rotation == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }

    private static byte[] bitmapToYuv420(Bitmap bitmap, boolean semiPlanar) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int frameSize = width * height;
        byte[] output = new byte[frameSize * 3 / 2];
        int[] pixels = new int[frameSize];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int yIndex = 0;
        int uvIndex = 0;
        int uStart = frameSize;
        int vStart = frameSize + frameSize / 4;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = pixels[y * width + x];
                int red = (color >> 16) & 0xff;
                int green = (color >> 8) & 0xff;
                int blue = color & 0xff;
                int luma = ((66 * red + 129 * green + 25 * blue + 128) >> 8) + 16;
                output[yIndex++] = (byte) clamp(luma);
                if ((y & 1) == 0 && (x & 1) == 0) {
                    int u = ((-38 * red - 74 * green + 112 * blue + 128) >> 8) + 128;
                    int v = ((112 * red - 94 * green - 18 * blue + 128) >> 8) + 128;
                    if (semiPlanar) {
                        output[frameSize + uvIndex++] = (byte) clamp(u);
                        output[frameSize + uvIndex++] = (byte) clamp(v);
                    } else {
                        int chromaIndex = uvIndex++;
                        output[uStart + chromaIndex] = (byte) clamp(u);
                        output[vStart + chromaIndex] = (byte) clamp(v);
                    }
                }
            }
        }
        return output;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static void queueInput(MediaCodec codec, byte[] data, long presentationTimeUs, int flags)
            throws IOException {
        for (int attempts = 0; attempts < 200; attempts++) {
            int index = codec.dequeueInputBuffer(TIMEOUT_US);
            if (index < 0) continue;
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null) throw new IOException("Encoder input buffer unavailable");
            buffer.clear();
            if (buffer.remaining() < data.length) throw new IOException("Encoder input buffer too small");
            buffer.put(data);
            codec.queueInputBuffer(index, 0, data.length, presentationTimeUs, flags);
            return;
        }
        throw new IOException("Encoder stopped accepting frames");
    }

    private static void drain(MediaCodec codec, MediaMuxer muxer, DrainState state, boolean end)
            throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int idle = 0;
        while (true) {
            int index = codec.dequeueOutputBuffer(info, TIMEOUT_US);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end) return;
                if (++idle > 500) throw new IOException("Timed out finishing timelapse video");
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (state.started) throw new IOException("Video format changed twice");
                state.track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                state.started = true;
                continue;
            }
            if (index < 0) continue;
            ByteBuffer buffer = codec.getOutputBuffer(index);
            if (buffer == null) throw new IOException("Encoder output buffer unavailable");
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
            if (info.size > 0) {
                if (!state.started) throw new IOException("Video track was not initialized");
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                muxer.writeSampleData(state.track, buffer, info);
            }
            boolean finished = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(index, false);
            if (finished) return;
        }
    }

    private static final class EncoderChoice {
        final MediaCodecInfo info;
        final int colorFormat;
        final boolean semiPlanar;

        EncoderChoice(MediaCodecInfo info, int colorFormat, boolean semiPlanar) {
            this.info = info;
            this.colorFormat = colorFormat;
            this.semiPlanar = semiPlanar;
        }
    }

    private static final class DrainState {
        int track = -1;
        boolean started;
    }
}

