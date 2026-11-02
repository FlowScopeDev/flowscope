package dev.flowscope.host;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

/**
 * Reads the text back out of a rendered PDF.
 *
 * <p>The redaction rules apply to every artifact a run writes, and a PDF hides its strings inside
 * deflated content streams where `Files.readString` cannot see them. So the tests inflate the real
 * bytes rather than inspecting what {@link ReportPdf} composed: a leak that only shows up in the
 * file is exactly the kind this has to catch.
 */
final class PdfProbe {
    private PdfProbe() {}

    /** Every string the document draws, in page order, with the PDF escapes undone. */
    static String text(byte[] pdf) {
        StringBuilder out = new StringBuilder();
        for (byte[] stream : streams(pdf)) {
            String content = new String(stream, StandardCharsets.ISO_8859_1);
            if (!content.contains("Tj")) continue;
            int at = 0;
            while (true) {
                int open = content.indexOf('(', at);
                if (open < 0) break;
                int close = open + 1;
                int depth = 1;
                while (close < content.length() && depth > 0) {
                    char character = content.charAt(close);
                    if (character == '\\') close++;
                    else if (character == '(') depth++;
                    else if (character == ')') depth--;
                    if (depth > 0) close++;
                }
                if (close >= content.length()) break;
                out.append(unescape(content.substring(open + 1, close))).append('\n');
                at = close + 1;
            }
        }
        return out.toString();
    }

    /** How many image XObjects the file carries, which is how many plates actually made it in. */
    static int images(byte[] pdf) {
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        int count = 0;
        int at = 0;
        while ((at = raw.indexOf("/Subtype /Image", at)) >= 0) {
            count++;
            at += 15;
        }
        return count;
    }

    private static String unescape(String value) {
        return value.replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\");
    }

    private static List<byte[]> streams(byte[] pdf) {
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        List<byte[]> out = new ArrayList<>();
        int at = 0;
        while (true) {
            int start = raw.indexOf("stream\n", at);
            if (start < 0) break;
            int from = start + "stream\n".length();
            int end = raw.indexOf("\nendstream", from);
            if (end < 0) break;
            out.add(inflate(java.util.Arrays.copyOfRange(pdf, from, end)));
            // Past the whole token: "endstream" ends in "stream", so advancing by one would match
            // it again and inflate garbage spanning two objects.
            at = end + "\nendstream".length();
        }
        return out;
    }

    private static byte[] inflate(byte[] bytes) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(bytes);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read == 0) break;
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Exception notDeflated) {
            return new byte[0];
        } finally {
            inflater.end();
        }
    }
}
