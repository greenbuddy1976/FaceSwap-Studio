package com.greenbuddy.faceswapstudio.engine;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public final class MappedAsset implements AutoCloseable {
    private final AssetFileDescriptor descriptor;
    private final FileInputStream stream;
    private final FileChannel channel;
    private final MappedByteBuffer buffer;

    private MappedAsset(
        AssetFileDescriptor descriptor,
        FileInputStream stream,
        FileChannel channel,
        MappedByteBuffer buffer
    ) {
        this.descriptor = descriptor;
        this.stream = stream;
        this.channel = channel;
        this.buffer = buffer;
    }

    public static MappedAsset open(AssetManager assets, String path) throws FaceSwapException {
        AssetFileDescriptor descriptor = null;
        FileInputStream stream = null;
        FileChannel channel = null;
        try {
            descriptor = assets.openFd(path);
            long length = descriptor.getDeclaredLength();
            if (length <= 0) {
                throw new IOException("Asset length is unavailable.");
            }
            stream = new FileInputStream(descriptor.getFileDescriptor());
            channel = stream.getChannel();
            MappedByteBuffer mapped = channel.map(
                FileChannel.MapMode.READ_ONLY,
                descriptor.getStartOffset(),
                length
            );
            return new MappedAsset(descriptor, stream, channel, mapped);
        } catch (IOException error) {
            closeQuietly(channel);
            closeQuietly(stream);
            closeQuietly(descriptor);
            throw new FaceSwapException(
                "Das eingebaute KI-Modell " + path + " ist nicht direkt lesbar. Die APK ist beschädigt.",
                error
            );
        }
    }

    public MappedByteBuffer getBuffer() {
        buffer.position(0);
        return buffer;
    }

    @Override
    public void close() {
        closeQuietly(channel);
        closeQuietly(stream);
        closeQuietly(descriptor);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // The inference result is more important than a best-effort descriptor close.
        }
    }
}
