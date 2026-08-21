package com.greenbuddy.faceswapstudio.video;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.greenbuddy.faceswapstudio.engine.FaceSwapException;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Encodes timestamped ARGB bitmaps into a video-only H.264 MP4. */
public final class AvcBitmapEncoder implements AutoCloseable {
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final long CODEC_TIMEOUT_US = 20_000L;
    private static final int MAX_DRAIN_RETRIES = 250;

    private final int width;
    private final int height;
    private final int yuvSize;
    private final int colorFormat;
    private final int[] argbPixels;
    private final MediaCodec encoder;
    private final MediaMuxer muxer;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    private boolean encoderStarted;
    private boolean muxerStarted;
    private boolean finished;
    private int muxerVideoTrack = -1;

    public AvcBitmapEncoder(File output, int width, int height, int frameRate)
        throws FaceSwapException {
        if ((width & 1) != 0 || (height & 1) != 0 || width < 2 || height < 2) {
            throw new FaceSwapException("Die Videoauflösung muss aus geraden Pixelwerten bestehen.");
        }
        this.width = width;
        this.height = height;
        this.yuvSize = width * height * 3 / 2;
        this.argbPixels = new int[width * height];

        MediaCodec createdEncoder = null;
        MediaMuxer createdMuxer = null;
        int selectedColorFormat;
        try {
            createdEncoder = MediaCodec.createEncoderByType(MIME);
            selectedColorFormat = selectColorFormat(createdEncoder.getCodecInfo());
            MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, selectedColorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate(width, height, frameRate));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            createdEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            createdEncoder.start();
            encoderStarted = true;
            createdMuxer = new MediaMuxer(
                output.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            );
        } catch (IOException | RuntimeException error) {
            if (createdEncoder != null) {
                try {
                    if (encoderStarted) {
                        createdEncoder.stop();
                    }
                } catch (RuntimeException ignored) {
                    // Constructor failure is the useful error.
                }
                createdEncoder.release();
            }
            if (createdMuxer != null) {
                createdMuxer.release();
            }
            throw new FaceSwapException(
                "Android konnte den H.264-Videoencoder nicht starten.",
                error
            );
        }
        encoder = createdEncoder;
        muxer = createdMuxer;
        colorFormat = selectedColorFormat;
    }

    public void encode(Bitmap frame, long presentationTimeUs) throws FaceSwapException {
        if (finished) {
            throw new FaceSwapException("Der Videoencoder wurde bereits beendet.");
        }
        if (frame.getWidth() != width || frame.getHeight() != height) {
            throw new FaceSwapException(
                "Ein Videobild besitzt eine unerwartete Auflösung: "
                    + frame.getWidth() + "x" + frame.getHeight()
            );
        }

        int attempts = 0;
        while (attempts++ < MAX_DRAIN_RETRIES) {
            drain(false);
            int inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
            if (inputIndex < 0) {
                continue;
            }
            writeFrame(inputIndex, frame);
            encoder.queueInputBuffer(inputIndex, 0, yuvSize, presentationTimeUs, 0);
            return;
        }
        throw new FaceSwapException("Der Videoencoder nimmt keine weiteren Bilder an.");
    }

    public void finish(long presentationTimeUs) throws FaceSwapException {
        if (finished) {
            return;
        }
        int attempts = 0;
        while (attempts++ < MAX_DRAIN_RETRIES) {
            drain(false);
            int inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    presentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                );
                drain(true);
                finished = true;
                return;
            }
        }
        throw new FaceSwapException("Der Videoencoder konnte nicht sauber abgeschlossen werden.");
    }

    private void writeFrame(int inputIndex, Bitmap frame) throws FaceSwapException {
        Image image = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            ? encoder.getInputImage(inputIndex)
            : null;
        if (image != null) {
            try {
                writeFlexibleYuv(frame, image, argbPixels);
            } finally {
                image.close();
            }
            return;
        }

        ByteBuffer buffer = encoder.getInputBuffer(inputIndex);
        if (buffer == null || buffer.capacity() < yuvSize) {
            throw new FaceSwapException("Der Videoencoder stellte keinen gültigen Eingabepuffer bereit.");
        }
        buffer.clear();
        writeLinearYuv(frame, buffer, isSemiPlanar(colorFormat), argbPixels);
    }

    private void drain(boolean waitForEnd) throws FaceSwapException {
        int retries = 0;
        while (true) {
            int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!waitForEnd) {
                    return;
                }
                if (++retries >= MAX_DRAIN_RETRIES) {
                    throw new FaceSwapException("Der Videoencoder lieferte kein Abschluss-Signal.");
                }
                continue;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw new FaceSwapException("Der Videoencoder änderte sein Format unerwartet mehrfach.");
                }
                muxerVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            }
            if (outputIndex < 0) {
                continue;
            }

            ByteBuffer encoded = encoder.getOutputBuffer(outputIndex);
            if (encoded == null) {
                encoder.releaseOutputBuffer(outputIndex, false);
                throw new FaceSwapException("Der Videoencoder lieferte einen leeren Ausgabepuffer.");
            }
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                bufferInfo.size = 0;
            }
            if (bufferInfo.size > 0) {
                if (!muxerStarted || muxerVideoTrack < 0) {
                    encoder.releaseOutputBuffer(outputIndex, false);
                    throw new FaceSwapException("Der MP4-Container wurde nicht rechtzeitig gestartet.");
                }
                encoded.position(bufferInfo.offset);
                encoded.limit(bufferInfo.offset + bufferInfo.size);
                muxer.writeSampleData(muxerVideoTrack, encoded, bufferInfo);
            }
            boolean end = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            encoder.releaseOutputBuffer(outputIndex, false);
            if (end) {
                return;
            }
        }
    }

    private static void writeFlexibleYuv(Bitmap bitmap, Image image, int[] pixels)
        throws FaceSwapException {
        if (image.getFormat() != android.graphics.ImageFormat.YUV_420_888) {
            throw new FaceSwapException("Der Videoencoder verwendet kein flexibles YUV-Format.");
        }
        Image.Plane[] planes = image.getPlanes();
        if (planes.length != 3) {
            throw new FaceSwapException("Der Videoencoder lieferte keine drei YUV-Ebenen.");
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int yBase = yBuffer.position();
        int uBase = uBuffer.position();
        int vBase = vBuffer.position();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = pixels[y * width + x];
                int luma = y(Color.red(color), Color.green(color), Color.blue(color));
                int index = yBase + y * planes[0].getRowStride() + x * planes[0].getPixelStride();
                yBuffer.put(index, (byte) luma);
            }
        }
        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                int color = pixels[y * width + x];
                int red = Color.red(color);
                int green = Color.green(color);
                int blue = Color.blue(color);
                int chromaX = x / 2;
                int chromaY = y / 2;
                int uIndex = uBase + chromaY * planes[1].getRowStride()
                    + chromaX * planes[1].getPixelStride();
                int vIndex = vBase + chromaY * planes[2].getRowStride()
                    + chromaX * planes[2].getPixelStride();
                uBuffer.put(uIndex, (byte) u(red, green, blue));
                vBuffer.put(vIndex, (byte) v(red, green, blue));
            }
        }
    }

    private static void writeLinearYuv(
        Bitmap bitmap,
        ByteBuffer output,
        boolean semiPlanar,
        int[] pixels
    ) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int color : pixels) {
            output.put((byte) y(Color.red(color), Color.green(color), Color.blue(color)));
        }
        if (semiPlanar) {
            for (int row = 0; row < height; row += 2) {
                for (int column = 0; column < width; column += 2) {
                    int color = pixels[row * width + column];
                    int red = Color.red(color);
                    int green = Color.green(color);
                    int blue = Color.blue(color);
                    output.put((byte) u(red, green, blue));
                    output.put((byte) v(red, green, blue));
                }
            }
            return;
        }
        for (int row = 0; row < height; row += 2) {
            for (int column = 0; column < width; column += 2) {
                int color = pixels[row * width + column];
                output.put((byte) u(Color.red(color), Color.green(color), Color.blue(color)));
            }
        }
        for (int row = 0; row < height; row += 2) {
            for (int column = 0; column < width; column += 2) {
                int color = pixels[row * width + column];
                output.put((byte) v(Color.red(color), Color.green(color), Color.blue(color)));
            }
        }
    }

    private static int selectColorFormat(MediaCodecInfo codecInfo) throws FaceSwapException {
        int planar = -1;
        int semiPlanar = -1;
        for (int format : codecInfo.getCapabilitiesForType(MIME).colorFormats) {
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                return format;
            }
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                || format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar) {
                planar = format;
            }
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                || format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar) {
                semiPlanar = format;
            }
        }
        if (planar != -1) {
            return planar;
        }
        if (semiPlanar != -1) {
            return semiPlanar;
        }
        throw new FaceSwapException("Dieses Gerät besitzt keinen kompatiblen H.264-YUV-Encoder.");
    }

    private static boolean isSemiPlanar(int format) {
        return format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            || format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar;
    }

    private static int bitrate(int width, int height, int frameRate) {
        long calculated = Math.round(width * (double) height * frameRate * 0.22d);
        return (int) Math.max(800_000L, Math.min(8_000_000L, calculated));
    }

    private static int y(int red, int green, int blue) {
        return clamp(((66 * red + 129 * green + 25 * blue + 128) >> 8) + 16);
    }

    private static int u(int red, int green, int blue) {
        return clamp(((-38 * red - 74 * green + 112 * blue + 128) >> 8) + 128);
    }

    private static int v(int red, int green, int blue) {
        return clamp(((112 * red - 94 * green - 18 * blue + 128) >> 8) + 128);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    @Override
    public void close() {
        if (encoderStarted) {
            try {
                encoder.stop();
            } catch (RuntimeException ignored) {
                // Release below is still required.
            }
            encoderStarted = false;
        }
        encoder.release();
        if (muxerStarted) {
            try {
                muxer.stop();
            } catch (RuntimeException ignored) {
                // An incomplete file is rejected by the caller's validation.
            }
            muxerStarted = false;
        }
        muxer.release();
    }
}
