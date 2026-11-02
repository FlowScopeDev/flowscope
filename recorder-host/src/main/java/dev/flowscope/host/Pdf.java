package dev.flowscope.host;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * A very small PDF writer: enough to set a report, and nothing more.
 *
 * <p>The host CLI runs from a plain JDK with no dependencies — the same reason `flowscope.json` is
 * read by {@link Json} rather than a library — so a report that has to be a PDF has to be written
 * here. This is deliberately not a layout engine and not an HTML renderer: it draws text, rules,
 * filled rectangles and images at coordinates that {@link ReportPdf} works out.
 *
 * <p>Only the base-14 fonts are used, so nothing is embedded and no font licence is involved. That
 * costs the page its typeface — a PDF of this report is Helvetica, not the system stack the HTML
 * asks for — and buys a file that any reader on any platform opens. The standard widths are carried
 * below so text can be measured, which is what makes wrapping and right-alignment possible.
 *
 * <p>Composition is kept apart from serialisation on purpose. {@link Doc} is an ordinary list of
 * pages of ops that a test can read directly; {@link #render} turns one into bytes. Asserting on
 * composed text beats inflating content streams in a test.
 */
final class Pdf {
    private Pdf() {}

    /** Points. A4 is the paper the rest of the world prints a report on. */
    static final double PAGE_WIDTH = 595.28;
    static final double PAGE_HEIGHT = 841.89;

    enum Font {
        REGULAR("Helvetica", Widths.HELVETICA),
        BOLD("Helvetica-Bold", Widths.HELVETICA_BOLD),
        MONO("Courier", null);

        final String base;
        private final int[] widths;

        Font(String base, int[] widths) {
            this.base = base;
            this.widths = widths;
        }

        /** Width of [text] at [size], in points. Courier is monospaced at 600/1000. */
        double width(String text, double size) {
            double total = 0;
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (widths == null) {
                    total += 600;
                } else {
                    int code = character & 0xff;
                    total += code >= 32 && code - 32 < widths.length ? widths[code - 32] : 500;
                }
            }
            return total * size / 1000.0;
        }
    }

    /** A colour, 0..1 per channel, written straight into the content stream. */
    record Colour(double red, double green, double blue) {
        static Colour of(int rgb) {
            return new Colour(((rgb >> 16) & 0xff) / 255.0, ((rgb >> 8) & 0xff) / 255.0,
                (rgb & 0xff) / 255.0);
        }
    }

    /** One drawing instruction. Coordinates are PDF user space: origin bottom-left, y upwards. */
    sealed interface Op permits Text, Rule, Box, Picture, Link {}

    record Text(String value, double x, double y, Font font, double size, Colour colour) implements Op {}
    record Rule(double x, double y, double width, double thickness, Colour colour) implements Op {}
    record Box(double x, double y, double width, double height, Colour colour) implements Op {}
    /** [image] is a key into {@link Doc#images}; several pages may draw the same one. */
    record Picture(String image, double x, double y, double width, double height) implements Op {}
    /** A tap target over [x,y,width,height] that jumps to the top of page [page], 0-based. */
    record Link(double x, double y, double width, double height, int page) implements Op {}

    /** One image, as loose RGB pixels; {@link #render} compresses and writes it once. */
    record Image(int width, int height, byte[] rgb) {}

    /** A page is its ops; a document is its pages plus the images they refer to. */
    static final class Doc {
        final List<List<Op>> pages = new ArrayList<>();
        final Map<String, Image> images = new LinkedHashMap<>();
        String title = "";

        List<Op> newPage() {
            List<Op> page = new ArrayList<>();
            pages.add(page);
            return page;
        }

        /** Every string the document draws, in order. What a test reads instead of the bytes. */
        List<String> text() {
            List<String> out = new ArrayList<>();
            for (List<Op> page : pages) {
                for (Op op : page) if (op instanceof Text text) out.add(text.value());
            }
            return out;
        }
    }

    /**
     * Greedy wrap of [text] to [width], breaking on spaces and hard-breaking a single word that is
     * wider than the line on its own, which a stack trace or a long resource id always is.
     */
    static List<String> wrap(String text, Font font, double size, double width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) return lines;
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.width(candidate, size) <= width) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            while (font.width(word, size) > width && word.length() > 1) {
                int fits = 1;
                while (fits < word.length() && font.width(word.substring(0, fits + 1), size) <= width) {
                    fits++;
                }
                lines.add(word.substring(0, fits));
                word = word.substring(fits);
            }
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    /** [text] cut to [width], with an ellipsis when something had to go. */
    static String ellipsise(String text, Font font, double size, double width) {
        if (text == null) return "";
        if (font.width(text, size) <= width) return text;
        String suffix = "...";
        int fits = 0;
        while (fits < text.length()
            && font.width(text.substring(0, fits + 1) + suffix, size) <= width) {
            fits++;
        }
        return text.substring(0, fits) + suffix;
    }

    /**
     * WinAnsi, which is what the base-14 fonts are asked to use below. Anything outside it becomes
     * an ASCII stand-in rather than a wrong glyph: a report may carry a step label in any language,
     * and a readable approximation beats a page of question marks.
     */
    static String winAnsi(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '‘', '’' -> out.append('\'');
                case '“', '”' -> out.append('"');
                case '–' -> out.append('-');
                case '—' -> out.append("--");
                case '…' -> out.append("...");
                case ' ' -> out.append(' ');
                case '\t' -> out.append("    ");
                default -> {
                    if (character == '\n' || character == '\r') out.append(' ');
                    else if (character < 32) out.append(' ');
                    else if (character < 127 || isWinAnsiHigh(character)) out.append(character);
                    else out.append('?');
                }
            }
        }
        return out.toString();
    }

    /** The Latin-1 range WinAnsi shares with Unicode. The 0x80-0x9f block is not worth mapping. */
    private static boolean isWinAnsiHigh(char character) {
        return character >= 0xa1 && character <= 0xff;
    }

    static byte[] render(Doc doc) {
        return new Writer(doc).write();
    }

    /** Assembles the objects, records their offsets, and writes the xref table over them. */
    private static final class Writer {
        private final Doc doc;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final List<Integer> offsets = new ArrayList<>();
        private int objects;

        Writer(Doc doc) { this.doc = doc; }

        byte[] write() {
            ascii("%PDF-1.7\n");
            // A binary comment marks the file as binary for anything that still sniffs it.
            out.writeBytes(new byte[] {'%', (byte) 0xe2, (byte) 0xe3, (byte) 0xcf, (byte) 0xd3, '\n'});

            int fonts = reserve(Font.values().length);
            int imageBase = reserve(doc.images.size());
            int pageCount = Math.max(1, doc.pages.size());
            int pagesObject = reserve(1);
            int pageBase = reserve(pageCount);
            int contentBase = reserve(pageCount);
            int catalog = reserve(1);
            int info = reserve(1);

            for (int index = 0; index < Font.values().length; index++) {
                Font font = Font.values()[index];
                object(fonts + index, "<< /Type /Font /Subtype /Type1 /BaseFont /" + font.base
                    + " /Encoding /WinAnsiEncoding >>");
            }

            List<String> imageKeys = List.copyOf(doc.images.keySet());
            for (int index = 0; index < imageKeys.size(); index++) {
                Image image = doc.images.get(imageKeys.get(index));
                byte[] compressed = deflate(image.rgb());
                stream(imageBase + index, "/Type /XObject /Subtype /Image"
                    + " /Width " + image.width() + " /Height " + image.height()
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode",
                    compressed);
            }

            StringBuilder kids = new StringBuilder();
            for (int index = 0; index < pageCount; index++) {
                kids.append(pageBase + index).append(" 0 R ");
            }
            object(pagesObject, "<< /Type /Pages /Count " + pageCount + " /Kids [" + kids + "] >>");

            for (int index = 0; index < pageCount; index++) {
                List<Op> page = index < doc.pages.size() ? doc.pages.get(index) : List.of();
                StringBuilder resources = new StringBuilder("<< /Font <<");
                for (int f = 0; f < Font.values().length; f++) {
                    resources.append(" /F").append(f).append(' ').append(fonts + f).append(" 0 R");
                }
                resources.append(" >>");
                if (!imageKeys.isEmpty()) {
                    resources.append(" /XObject <<");
                    for (int i = 0; i < imageKeys.size(); i++) {
                        resources.append(" /I").append(i).append(' ').append(imageBase + i).append(" 0 R");
                    }
                    resources.append(" >>");
                }
                resources.append(" >>");
                object(pageBase + index, "<< /Type /Page /Parent " + pagesObject + " 0 R"
                    + " /MediaBox [0 0 " + number(PAGE_WIDTH) + " " + number(PAGE_HEIGHT) + "]"
                    + " /Resources " + resources
                    + " /Contents " + (contentBase + index) + " 0 R"
                    + annotations(page, pageBase) + " >>");
            }

            for (int index = 0; index < pageCount; index++) {
                List<Op> page = index < doc.pages.size() ? doc.pages.get(index) : List.of();
                byte[] content = deflate(content(page, imageKeys));
                stream(contentBase + index, "/Filter /FlateDecode", content);
            }

            object(catalog, "<< /Type /Catalog /Pages " + pagesObject + " 0 R >>");
            object(info, "<< /Title (" + escape(winAnsi(doc.title)) + ") /Producer (FlowScope) >>");

            int xref = out.size();
            ascii("xref\n0 " + (objects + 1) + "\n0000000000 65535 f \n");
            for (int index = 1; index <= objects; index++) {
                ascii(String.format("%010d 00000 n \n", offsets.get(index - 1)));
            }
            ascii("trailer\n<< /Size " + (objects + 1) + " /Root " + catalog + " 0 R /Info "
                + info + " 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
            return out.toByteArray();
        }

        /** Link annotations jump to the top of their target page. */
        private String annotations(List<Op> page, int pageBase) {
            StringBuilder out = new StringBuilder();
            for (Op op : page) {
                if (!(op instanceof Link link)) continue;
                out.append("<< /Type /Annot /Subtype /Link /Border [0 0 0] /Rect [")
                    .append(number(link.x())).append(' ').append(number(link.y())).append(' ')
                    .append(number(link.x() + link.width())).append(' ')
                    .append(number(link.y() + link.height()))
                    .append("] /Dest [").append(pageBase + link.page())
                    .append(" 0 R /XYZ null null null] >> ");
            }
            return out.isEmpty() ? "" : " /Annots [" + out + "]";
        }

        private byte[] content(List<Op> page, List<String> imageKeys) {
            StringBuilder out = new StringBuilder();
            for (Op op : page) {
                if (op instanceof Text text) {
                    out.append("BT /F").append(text.font().ordinal()).append(' ')
                        .append(number(text.size())).append(" Tf ")
                        .append(fill(text.colour()))
                        .append(number(text.x())).append(' ').append(number(text.y()))
                        .append(" Td (").append(escape(winAnsi(text.value()))).append(") Tj ET\n");
                } else if (op instanceof Rule rule) {
                    out.append(fill(rule.colour())).append(number(rule.x())).append(' ')
                        .append(number(rule.y())).append(' ').append(number(rule.width()))
                        .append(' ').append(number(rule.thickness())).append(" re f\n");
                } else if (op instanceof Box box) {
                    out.append(fill(box.colour())).append(number(box.x())).append(' ')
                        .append(number(box.y())).append(' ').append(number(box.width()))
                        .append(' ').append(number(box.height())).append(" re f\n");
                } else if (op instanceof Picture picture) {
                    int index = imageKeys.indexOf(picture.image());
                    if (index < 0) continue;
                    out.append("q ").append(number(picture.width())).append(" 0 0 ")
                        .append(number(picture.height())).append(' ').append(number(picture.x()))
                        .append(' ').append(number(picture.y())).append(" cm /I").append(index)
                        .append(" Do Q\n");
                }
            }
            return out.toString().getBytes(StandardCharsets.ISO_8859_1);
        }

        private static String fill(Colour colour) {
            return number(colour.red()) + " " + number(colour.green()) + " " + number(colour.blue())
                + " rg ";
        }

        private int reserve(int count) {
            int first = objects + 1;
            for (int index = 0; index < count; index++) {
                objects++;
                offsets.add(0);
            }
            return first;
        }

        private void object(int number, String body) {
            offsets.set(number - 1, out.size());
            ascii(number + " 0 obj\n" + body + "\nendobj\n");
        }

        private void stream(int number, String dictionary, byte[] bytes) {
            offsets.set(number - 1, out.size());
            ascii(number + " 0 obj\n<< " + dictionary + " /Length " + bytes.length + " >>\nstream\n");
            out.writeBytes(bytes);
            ascii("\nendstream\nendobj\n");
        }

        private void ascii(String value) {
            out.writeBytes(value.getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    private static byte[] deflate(byte[] bytes) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(bytes);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, bytes.length / 4));
            byte[] buffer = new byte[16 * 1024];
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    /** Short, locale-independent decimals: a content stream is not the place for scientific notation. */
    static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e9) return Long.toString((long) value);
        return java.math.BigDecimal.valueOf(value).setScale(3, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString();
    }

    /** The standard Adobe widths for the two faces this writer measures, codes 32 to 255. */
    private static final class Widths {
        private static final int[] HELVETICA = {
            278,278,355,556,556,889,667,191,333,333,389,584,278,333,278,278,
            556,556,556,556,556,556,556,556,556,556,278,278,584,584,584,556,
            1015,667,667,722,722,667,611,778,722,278,500,667,556,833,722,778,
            667,778,722,667,611,722,667,944,667,667,611,278,278,278,469,556,
            333,556,556,500,556,556,278,556,556,222,222,500,222,833,556,556,
            556,556,333,500,278,556,500,722,500,500,500,334,260,334,584,350,
            556,350,222,556,333,1000,556,556,333,1000,667,333,1000,350,611,350,
            350,222,222,333,333,350,556,1000,333,1000,500,333,944,350,500,667,
            278,333,556,556,556,556,260,556,333,737,370,556,584,333,737,552,
            400,549,333,333,333,576,537,278,333,333,365,556,834,834,834,611,
            667,667,667,667,667,667,1000,722,667,667,667,667,278,278,278,278,
            722,722,778,778,778,778,778,584,778,722,722,722,722,667,667,611,
            556,556,556,556,556,556,889,500,556,556,556,556,278,278,278,278,
            556,556,556,556,556,556,556,549,611,556,556,556,556,500,556,500,
        };
        private static final int[] HELVETICA_BOLD = {
            278,333,474,556,556,889,722,238,333,333,389,584,278,333,278,278,
            556,556,556,556,556,556,556,556,556,556,333,333,584,584,584,611,
            975,722,722,722,722,667,611,778,722,278,556,722,611,833,722,778,
            667,778,722,667,611,722,667,944,667,667,611,333,278,333,584,556,
            333,556,611,556,611,556,333,611,611,278,278,556,278,889,611,611,
            611,611,389,556,333,611,556,778,556,556,500,389,280,389,584,350,
            556,350,278,556,500,1000,556,556,333,1000,667,333,1000,350,611,350,
            350,278,278,500,500,350,556,1000,333,1000,556,333,944,350,500,667,
            278,333,556,556,556,556,280,556,333,737,370,556,584,333,737,552,
            400,549,333,333,333,576,556,278,333,333,365,556,834,834,834,611,
            722,722,722,722,722,722,1000,722,667,667,667,667,278,278,278,278,
            722,722,778,778,778,778,778,584,778,722,722,722,722,667,667,611,
            556,556,556,556,556,556,889,556,556,556,556,556,278,278,278,278,
            611,611,611,611,611,611,611,549,611,611,611,611,611,556,611,556,
        };
    }
}
