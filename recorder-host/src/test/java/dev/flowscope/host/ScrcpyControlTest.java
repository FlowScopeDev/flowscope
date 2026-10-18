package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ScrcpyControlTest {

    @Test
    void touchMessageMatchesTheServerLayout() {
        // type, action, pointerId(8), x(4), y(4), w(2), h(2), pressure(2), actionButton(4), buttons(4)
        byte[] message = ScrcpyControl.touch(ScrcpyControl.ACTION_DOWN, 360, 800, 720, 1600);
        assertEquals(32, message.length);
        ByteBuffer b = ByteBuffer.wrap(message);
        assertEquals(2, b.get(), "INJECT_TOUCH_EVENT");
        assertEquals(0, b.get(), "ACTION_DOWN");
        b.getLong();
        assertEquals(360, b.getInt());
        assertEquals(800, b.getInt());
        assertEquals(720, b.getShort() & 0xFFFF);
        assertEquals(1600, b.getShort() & 0xFFFF);
        assertEquals(0xFFFF, b.getShort() & 0xFFFF, "full pressure while pressed");
        assertEquals(1, b.getInt(), "actionButton primary on press");
        assertEquals(1, b.getInt(), "buttons primary while held");
    }

    @Test
    void releaseCarriesNoPressureOrButtons() {
        ByteBuffer b = ByteBuffer.wrap(
            ScrcpyControl.touch(ScrcpyControl.ACTION_UP, 10, 20, 720, 1600));
        b.position(22);   // type1 + action1 + pointerId8 + x4 + y4 + w2 + h2
        assertEquals(0, b.getShort() & 0xFFFF, "pressure released");
        assertEquals(0, b.getInt());
        assertEquals(0, b.getInt());
    }

    @Test
    void keycodeMessageMatchesTheServerLayout() {
        byte[] message = ScrcpyControl.keycode(ScrcpyControl.ACTION_DOWN, 3);
        assertEquals(14, message.length);
        ByteBuffer b = ByteBuffer.wrap(message);
        assertEquals(0, b.get(), "INJECT_KEYCODE");
        assertEquals(0, b.get());
        assertEquals(3, b.getInt(), "KEYCODE_HOME");
        assertEquals(0, b.getInt());
        assertEquals(0, b.getInt());
    }

    @Test
    void textIsLengthPrefixedUtf8() {
        byte[] message = ScrcpyControl.text("hé");
        byte[] utf8 = "hé".getBytes(StandardCharsets.UTF_8);
        assertEquals(1, message[0]);
        assertEquals(utf8.length, ByteBuffer.wrap(message, 1, 4).getInt());
        byte[] tail = new byte[utf8.length];
        System.arraycopy(message, 5, tail, 0, utf8.length);
        assertArrayEquals(utf8, tail, "non-ASCII survives, unlike `input text`");
    }

    @Test
    void clipboardCarriesSequenceAndPasteFlag() {
        byte[] message = ScrcpyControl.setClipboard("hi", true);
        ByteBuffer b = ByteBuffer.wrap(message);
        assertEquals(9, b.get(), "SET_CLIPBOARD");
        assertEquals(0L, b.getLong(), "sequence 0 = no ack");
        assertEquals(1, b.get(), "paste flag");
        assertEquals(2, b.getInt());
    }

    @Test
    void versionGateAcceptsOnlyTheVerifiedMajor() {
        assertTrue(ScrcpyControl.supports("4.1"));
        assertTrue(ScrcpyControl.supports("4.0.2"));
        assertFalse(ScrcpyControl.supports("3.9"));
        assertFalse(ScrcpyControl.supports("5.0"));
        assertFalse(ScrcpyControl.supports(""));
        assertFalse(ScrcpyControl.supports(null));
        assertFalse(ScrcpyControl.supports("not-a-version"));
    }
}
