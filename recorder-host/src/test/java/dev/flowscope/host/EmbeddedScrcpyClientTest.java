package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddedScrcpyClientTest {
    @Test
    void extractsCompleteJpegFramesAndIgnoresPrefixBytes() throws Exception {
        byte[] first = {(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9};
        byte[] second = {(byte) 0xff, (byte) 0xd8, 3, 4, (byte) 0xff, (byte) 0xd9};
        byte[] stream = {9, 8, (byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9,
            7, (byte) 0xff, (byte) 0xd8, 3, 4, (byte) 0xff, (byte) 0xd9};
        List<byte[]> frames = new ArrayList<>();

        EmbeddedScrcpyClient.readJpegFrames(new ByteArrayInputStream(stream), frames::add);

        assertEquals(2, frames.size());
        assertArrayEquals(first, frames.get(0));
        assertArrayEquals(second, frames.get(1));
    }

    @Test
    void encodesSafeAdbTextAndRejectsShellSyntax() {
        assertEquals("hello%sworld@example.com", EmbeddedScrcpyClient.encodeInputText("hello world@example.com"));
        assertThrows(IllegalArgumentException.class,
            () -> EmbeddedScrcpyClient.encodeInputText("hello; reboot"));
    }
}
