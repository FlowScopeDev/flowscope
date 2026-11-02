package dev.flowscope.host;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * Screenshots, decoded once and re-encoded small enough to travel inside a report.
 *
 * <p>A run's evidence is written as full-resolution device PNGs, which is right for the files on
 * disk and wrong for an artifact someone sends to a colleague: `report.html` linked those files
 * relatively, so the page went blank the moment it was shared without its folder. Both the HTML and
 * the PDF now carry the pixels, and both get them from here so a PNG is decoded once for the two of
 * them.
 *
 * <p>Two sizes per capture. The thumbnail is what the grid shows and is always embedded, so a
 * shared page always shows every capture. The plate is the full look — the overlay on the page and
 * the plate in the PDF — and is embedded in the page only while [plateBudget] lasts, because a
 * regression suite can carry fifty captures and a browser should not be handed a hundred-megabyte
 * document.
 *
 * <p>Nothing here can fail a run. A capture that will not decode simply keeps its relative link,
 * exactly as a hierarchy that will not dump keeps `details.hierarchyError`.
 */
final class ReportImages {
    /** The grid never needs more than this, and at this size every capture can be embedded. */
    private static final int THUMBNAIL_EDGE = 420;
    /** Legible for a 1080x2400 device screenshot, on screen and printed at about 3.5 inches wide. */
    private static final int PLATE_EDGE = 1200;
    /** How many encoded plate bytes one page may carry before the rest fall back to their files. */
    private static final long PLATE_BUDGET = 32L * 1024 * 1024;

    private static final String PNG_URI = "data:image/png;base64,";

    static {
        // ImageIO and the scaling below are headless-safe, but a report must never be the thing
        // that wakes a window server: say so rather than depending on the default.
        System.setProperty("java.awt.headless", "true");
    }

    /** One capture, re-encoded. [platePng] is PNG bytes again so the cache stays small. */
    record Capture(byte[] thumbnailPng, byte[] platePng, int plateWidth, int plateHeight) {}

    private final Path root;
    private final Map<String, Optional<Capture>> captures = new HashMap<>();
    private long plateBytesSpent;

    ReportImages(Path root) {
        this.root = root;
    }

    /** A thumbnail `data:` URI for the grid, or empty when the file is missing or unreadable. */
    Optional<String> thumbnail(String reference) {
        return capture(reference).map(capture -> PNG_URI + encode(capture.thumbnailPng()));
    }

    /**
     * A full-size `data:` URI for the overlay, or empty once the budget is spent. The caller falls
     * back to the relative file, which still works for a reader who has the run folder.
     */
    Optional<String> plate(String reference) {
        Optional<Capture> capture = capture(reference);
        if (capture.isEmpty()) return Optional.empty();
        String encoded = encode(capture.get().platePng());
        if (plateBytesSpent + encoded.length() > PLATE_BUDGET) return Optional.empty();
        plateBytesSpent += encoded.length();
        return Optional.of(PNG_URI + encoded);
    }

    /**
     * Every shot with its sources embedded, in gallery order, so when the plate budget runs out it
     * is the last captures that fall back rather than an arbitrary scattering of them.
     */
    List<ReportHtml.Shot> embed(List<ReportHtml.Shot> shots) {
        List<ReportHtml.Shot> out = new ArrayList<>(shots.size());
        for (ReportHtml.Shot shot : shots) {
            out.add(shot.embedded(thumbnail(shot.path()).orElse(null), plate(shot.path()).orElse(null)));
        }
        return out;
    }

    /**
     * Thumbnails only, for a strip beside a table row. The strip opens the gallery's own overlay, so
     * asking for a plate here would spend the budget twice on one capture.
     */
    List<ReportHtml.Shot> embedThumbnails(List<ReportHtml.Shot> shots) {
        List<ReportHtml.Shot> out = new ArrayList<>(shots.size());
        for (ReportHtml.Shot shot : shots) {
            out.add(shot.embedded(thumbnail(shot.path()).orElse(null), null));
        }
        return out;
    }

    /** True once a plate has been dropped, so the section can say the page is not quite complete. */
    boolean plateBudgetSpent() {
        return plateBytesSpent >= PLATE_BUDGET;
    }

    /**
     * The plate as raw RGB for the PDF: three bytes a pixel, top row first, which is what a PDF
     * image XObject wants. Decoded from the cached PNG rather than kept in memory — fifty plates of
     * loose pixels is a hundred megabytes, fifty plates of PNG is a handful.
     */
    Optional<Rgb> rgb(String reference) {
        return capture(reference).flatMap(capture -> {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(capture.platePng()));
                if (image == null) return Optional.empty();
                int width = image.getWidth();
                int height = image.getHeight();
                byte[] out = new byte[width * height * 3];
                int at = 0;
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int pixel = image.getRGB(x, y);
                        out[at++] = (byte) (pixel >> 16);
                        out[at++] = (byte) (pixel >> 8);
                        out[at++] = (byte) pixel;
                    }
                }
                return Optional.of(new Rgb(width, height, out));
            } catch (Exception unreadable) {
                return Optional.empty();
            }
        });
    }

    /** Loose pixels for one plate, ready for a PDF image XObject. */
    record Rgb(int width, int height, byte[] bytes) {}

    private Optional<Capture> capture(String reference) {
        return captures.computeIfAbsent(reference, this::read);
    }

    private Optional<Capture> read(String reference) {
        try {
            Path file = RunArtifacts.containedFile(root, reference);
            BufferedImage source = ImageIO.read(file.toFile());
            if (source == null) return Optional.empty();
            BufferedImage plate = scaled(source, PLATE_EDGE);
            return Optional.of(new Capture(png(scaled(source, THUMBNAIL_EDGE)), png(plate),
                plate.getWidth(), plate.getHeight()));
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Down to [longest] on its longer side, never up: a capture smaller than the box is already as
     * good as it gets, and stretching it would only cost bytes.
     */
    private static BufferedImage scaled(BufferedImage source, int longest) {
        int width = source.getWidth();
        int height = source.getHeight();
        double factor = Math.min(1.0, (double) longest / Math.max(width, height));
        int target = Math.max(1, (int) Math.round(width * factor));
        int targetHeight = Math.max(1, (int) Math.round(height * factor));
        // TYPE_INT_RGB, not ARGB: a screencap is opaque, and an opaque canvas keeps a PDF image
        // one plain DeviceRGB stream instead of needing a soft mask.
        BufferedImage out = new BufferedImage(target, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = out.createGraphics();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            canvas.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            canvas.drawImage(source, 0, 0, target, targetHeight, null);
        } finally {
            canvas.dispose();
        }
        return out;
    }

    private static byte[] png(BufferedImage image) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
