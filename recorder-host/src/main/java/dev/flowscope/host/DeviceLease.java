package dev.flowscope.host;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Cross-process reservation. Lock files are permanent; deleting one could split ownership. */
public final class DeviceLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private DeviceLease(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static DeviceLease acquire(String serial) throws IOException {
        return acquire(Path.of(System.getProperty("user.home"), ".flowscope", "device-locks"), serial);
    }

    static DeviceLease acquire(Path directory, String serial) throws IOException {
        if (serial == null || serial.isBlank()) throw new IllegalArgumentException("Device serial is required");
        Files.createDirectories(directory);
        String name;
        try {
            name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(serial.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        FileChannel channel = FileChannel.open(directory.resolve(name + ".lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { lock = null; }
            if (lock == null) throw new IllegalStateException("Device is busy in another FlowScope operation: " + serial);
            return new DeviceLease(channel, lock);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    @Override public void close() throws IOException {
        try { if (lock.isValid()) lock.release(); }
        finally { channel.close(); }
    }
}
