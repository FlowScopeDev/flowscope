package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddedScrcpyFrameTest {

    @Test
    void fitsTheLargerDimensionToTheCap() {
        // 720x1600 capped at 1080 -> scale 0.675 -> 486x1080.
        assertArrayEquals(new int[] {486, 1080}, EmbeddedScrcpyClient.fitToMaxSize(720, 1600, 1080));
    }

    @Test
    void leavesSmallDisplaysAlone() {
        assertArrayEquals(new int[] {720, 1024}, EmbeddedScrcpyClient.fitToMaxSize(720, 1024, 1080));
    }

    @Test
    void roundsDownToEvenForTheEncoder() {
        int[] size = EmbeddedScrcpyClient.fitToMaxSize(1079, 1079, 0);
        assertEquals(0, size[0] % 2);
        assertEquals(0, size[1] % 2);
    }

    @Test
    void handlesLandscape() {
        assertArrayEquals(new int[] {1080, 486}, EmbeddedScrcpyClient.fitToMaxSize(1600, 720, 1080));
    }

    @Test
    void readsWholeFramesAndStopsOnAShortTail() {
        int w = 4, h = 2, bytes = w * h * 4;
        byte[] stream = new byte[bytes * 2 + 3];   // two whole frames plus a truncated one
        for (int i = 0; i < stream.length; i++) stream[i] = (byte) i;
        List<EmbeddedScrcpyClient.Frame> frames = new ArrayList<>();

        assertDoesNotThrowIO(() -> EmbeddedScrcpyClient.readRawFrames(
            new ByteArrayInputStream(stream), w, h, frames::add));

        assertEquals(2, frames.size(), "the partial trailing frame must be discarded");
        for (EmbeddedScrcpyClient.Frame frame : frames) {
            assertEquals(bytes, frame.pixels().length);
            assertEquals(w, frame.width());
            assertEquals(h, frame.height());
        }
        assertEquals(0, frames.get(0).pixels()[0]);
        assertEquals((byte) bytes, frames.get(1).pixels()[0], "frames must not overlap");
    }

    private static void assertDoesNotThrowIO(IoRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private interface IoRunnable {
        void run() throws Exception;
    }

    @Test
    void recycledBuffersAreReusedInsteadOfReallocated() throws Exception {
        int w = 4, h = 2, bytes = w * h * 4;
        byte[] stream = new byte[bytes * 3];
        List<byte[]> seen = new ArrayList<>();

        EmbeddedScrcpyClient.readRawFrames(new ByteArrayInputStream(stream), w, h, frame -> {
            seen.add(frame.pixels());
            frame.recycle();   // what the UI does once the pixels are copied into an image
        });

        assertEquals(3, seen.size());
        assertSame(seen.get(0), seen.get(1), "a recycled buffer is handed straight back out");
        assertSame(seen.get(1), seen.get(2));
    }

    @Test
    void withoutRecyclingEveryFrameGetsItsOwnBuffer() throws Exception {
        int w = 4, h = 2;
        List<byte[]> seen = new ArrayList<>();
        EmbeddedScrcpyClient.readRawFrames(
            new ByteArrayInputStream(new byte[w * h * 4 * 2]), w, h, frame -> seen.add(frame.pixels()));
        assertNotSame(seen.get(0), seen.get(1), "frames still in use must never be overwritten");
    }

    @Test
    void thePoolIsBoundedAndRejectsForeignBuffers() {
        EmbeddedScrcpyClient.FramePool pool = new EmbeddedScrcpyClient.FramePool(16, 2);
        pool.offer(new byte[16]);
        pool.offer(new byte[16]);
        pool.offer(new byte[16]);
        assertEquals(2, pool.available(), "bounded");
        EmbeddedScrcpyClient.FramePool other = new EmbeddedScrcpyClient.FramePool(16, 2);
        other.offer(new byte[8]);
        assertEquals(0, other.available(), "a wrong-sized buffer is not pooled");
    }
}
