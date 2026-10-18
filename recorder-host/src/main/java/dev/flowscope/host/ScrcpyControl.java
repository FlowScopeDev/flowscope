package dev.flowscope.host;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Encoder for scrcpy's control protocol.
 *
 * <p>Injecting through the control socket costs roughly 0.5ms, against ~85ms for
 * {@code adb shell input} which has to start a process on the device for every event. It is also
 * the only way to produce a real continuous drag or to type text outside the ASCII subset
 * {@code input text} accepts.
 *
 * <p><b>Version sensitivity.</b> The message ordinals and field layouts below were read from the
 * scrcpy {@value #SUPPORTED_MAJOR}.x server and verified against a device. scrcpy versions its
 * client and server together and offers no cross-version compatibility guarantee, so
 * {@link #supports(String)} gates use of this protocol and callers fall back to {@code adb shell
 * input} for anything else.
 */
public final class ScrcpyControl {
    public static final int SUPPORTED_MAJOR = 4;

    // Verified against scrcpy 4.1's ControlMessage constants.
    private static final byte TYPE_INJECT_KEYCODE = 0;
    private static final byte TYPE_INJECT_TEXT = 1;
    private static final byte TYPE_INJECT_TOUCH_EVENT = 2;
    private static final byte TYPE_SET_CLIPBOARD = 9;

    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;

    /** scrcpy reads pressure as a 16-bit fixed-point fraction; 0xFFFF is full pressure. */
    private static final int PRESSURE_MAX = 0xFFFF;
    /** AMOTION_EVENT_BUTTON_PRIMARY. */
    private static final int BUTTON_PRIMARY = 1;
    /** A stable non-mouse pointer id for synthesised touches. */
    private static final long POINTER_ID = -42L;

    private final OutputStream out;

    public ScrcpyControl(OutputStream out) {
        this.out = out;
    }

    /** Whether this protocol encoder matches the resolved scrcpy version. */
    public static boolean supports(String version) {
        if (version == null || version.isBlank()) return false;
        int dot = version.indexOf('.');
        String major = dot < 0 ? version : version.substring(0, dot);
        try {
            return Integer.parseInt(major.strip()) == SUPPORTED_MAJOR;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /**
     * One touch event. {@code width}/{@code height} describe the coordinate space {@code x} and
     * {@code y} are expressed in — the server scales them onto the real display, so passing the
     * dimensions of the frame the user clicked keeps taps accurate at any stream resolution.
     */
    public static byte[] touch(int action, int x, int y, int width, int height) {
        return ByteBuffer.allocate(32)
            .put(TYPE_INJECT_TOUCH_EVENT)
            .put((byte) action)
            .putLong(POINTER_ID)
            .putInt(x).putInt(y)
            .putShort((short) width).putShort((short) height)
            .putShort((short) (action == ACTION_UP ? 0 : PRESSURE_MAX))
            .putInt(action == ACTION_DOWN ? BUTTON_PRIMARY : 0)
            .putInt(action == ACTION_UP ? 0 : BUTTON_PRIMARY)
            .array();
    }

    public static byte[] keycode(int action, int keycode) {
        return ByteBuffer.allocate(14)
            .put(TYPE_INJECT_KEYCODE)
            .put((byte) action)
            .putInt(keycode)
            .putInt(0)   // repeat
            .putInt(0)   // metaState
            .array();
    }

    /** Types arbitrary UTF-8 — unlike {@code input text}, which only accepts a narrow subset. */
    public static byte[] text(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(5 + utf8.length)
            .put(TYPE_INJECT_TEXT)
            .putInt(utf8.length)
            .put(utf8)
            .array();
    }

    public static byte[] setClipboard(String value, boolean paste) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(14 + utf8.length)
            .put(TYPE_SET_CLIPBOARD)
            .putLong(0L)                      // sequence: 0 = no acknowledgement wanted
            .put((byte) (paste ? 1 : 0))
            .putInt(utf8.length)
            .put(utf8)
            .array();
    }

    public synchronized void send(byte[] message) throws IOException {
        out.write(message);
        out.flush();
    }

    /**
     * Android discards a touch that goes down and up in the same instant, so a tap has to dwell
     * the way a real finger does. scrcpy itself never hits this because its DOWN and UP come from
     * separate mouse events, which are naturally tens of milliseconds apart.
     */
    public static final int TAP_DWELL_MILLIS = 60;

    public void tap(int x, int y, int width, int height) throws IOException, InterruptedException {
        send(touch(ACTION_DOWN, x, y, width, height));
        Thread.sleep(TAP_DWELL_MILLIS);
        send(touch(ACTION_UP, x, y, width, height));
    }

    /**
     * A real continuous drag: press, interpolated moves, release. {@code adb shell input swipe}
     * can only synthesise a fixed-duration gesture and cannot express a held drag.
     */
    public void swipe(int startX, int startY, int endX, int endY, int durationMs,
        int width, int height) throws IOException, InterruptedException {
        int steps = Math.max(2, Math.min(60, durationMs / 8));
        send(touch(ACTION_DOWN, startX, startY, width, height));
        for (int step = 1; step < steps; step++) {
            float t = (float) step / steps;
            int x = Math.round(startX + (endX - startX) * t);
            int y = Math.round(startY + (endY - startY) * t);
            send(touch(ACTION_MOVE, x, y, width, height));
            Thread.sleep(Math.max(1, durationMs / steps));
        }
        send(touch(ACTION_UP, endX, endY, width, height));
    }
}
