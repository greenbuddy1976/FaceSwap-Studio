package com.greenbuddy.faceswapstudio.video;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.greenbuddy.faceswapstudio.engine.FaceSwapException;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Combines the newly encoded H.264 track with the untouched source audio track. */
public final class Mp4AudioMuxer {
    private static final int DEFAULT_SAMPLE_BUFFER = 2 * 1024 * 1024;

    private Mp4AudioMuxer() {
    }

    public static boolean mux(File videoOnly, File original, File output) throws FaceSwapException {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            videoExtractor.setDataSource(videoOnly.getAbsolutePath());
            audioExtractor.setDataSource(original.getAbsolutePath());
            int videoSourceTrack = findTrack(videoExtractor, "video/");
            if (videoSourceTrack < 0) {
                throw new FaceSwapException("Das neu berechnete MP4 enthält keine Videospur.");
            }
            int audioSourceTrack = findTrack(audioExtractor, "audio/");

            muxer = new MediaMuxer(
                output.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            );
            int videoDestinationTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoSourceTrack));
            int audioDestinationTrack = -1;
            if (audioSourceTrack >= 0) {
                try {
                    audioDestinationTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioSourceTrack));
                } catch (RuntimeException error) {
                    throw new FaceSwapException(
                        "Die Originaltonspur ist nicht mit einem MP4-Ausgabevideo kompatibel.",
                        error
                    );
                }
            }
            muxer.start();
            started = true;

            copyTrack(videoExtractor, videoSourceTrack, muxer, videoDestinationTrack);
            if (audioSourceTrack >= 0) {
                copyTrack(audioExtractor, audioSourceTrack, muxer, audioDestinationTrack);
            }
            muxer.stop();
            started = false;
            if (!output.isFile() || output.length() < 10_000L) {
                throw new FaceSwapException("Das erzeugte MP4 ist unvollständig.");
            }
            return audioSourceTrack >= 0;
        } catch (FaceSwapException error) {
            output.delete();
            throw error;
        } catch (IOException | RuntimeException error) {
            output.delete();
            throw new FaceSwapException("Video und Originalton konnten nicht zusammengeführt werden.", error);
        } finally {
            videoExtractor.release();
            audioExtractor.release();
            if (muxer != null) {
                if (started) {
                    try {
                        muxer.stop();
                    } catch (RuntimeException ignored) {
                        // The incomplete output was already rejected.
                    }
                }
                muxer.release();
            }
        }
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                return index;
            }
        }
        return -1;
    }

    private static void copyTrack(
        MediaExtractor extractor,
        int sourceTrack,
        MediaMuxer muxer,
        int destinationTrack
    ) throws FaceSwapException {
        extractor.selectTrack(sourceTrack);
        MediaFormat format = extractor.getTrackFormat(sourceTrack);
        int capacity = DEFAULT_SAMPLE_BUFFER;
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            capacity = Math.max(capacity, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long previousPresentationTimeUs = -1L;

        while (true) {
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                break;
            }
            if (size > buffer.capacity()) {
                buffer = ByteBuffer.allocateDirect(size);
                continue;
            }
            long presentationTimeUs = extractor.getSampleTime();
            if (presentationTimeUs < previousPresentationTimeUs) {
                throw new FaceSwapException("Die Zeitstempel einer Mediendatei sind nicht sortiert.");
            }
            previousPresentationTimeUs = presentationTimeUs;
            info.set(0, size, presentationTimeUs, toCodecFlags(extractor.getSampleFlags()));
            buffer.position(0);
            buffer.limit(size);
            muxer.writeSampleData(destinationTrack, buffer, info);
            if (!extractor.advance()) {
                break;
            }
        }
        extractor.unselectTrack(sourceTrack);
    }

    private static int toCodecFlags(int extractorFlags) throws FaceSwapException {
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
            throw new FaceSwapException("Verschlüsselte Video- oder Tonspuren werden nicht unterstützt.");
        }
        int codecFlags = 0;
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            codecFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
        }
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            codecFlags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
        }
        return codecFlags;
    }
}
