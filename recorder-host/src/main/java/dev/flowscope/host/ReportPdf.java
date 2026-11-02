package dev.flowscope.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `report.pdf` and `suite-report.pdf`: the run as a document someone can send.
 *
 * <p>The HTML report carries its screenshots too, but a PDF is what gets attached to a ticket, read
 * on a phone and printed, and it paginates — so this is not a picture of the page. The steps are set
 * as block rows rather than squeezed into the page's seven columns, and the screenshots get plates
 * of their own, two to a page for the portrait captures a phone produces.
 *
 * <p>The caller hands over strings it has already redacted; nothing here reads a runtime value. A
 * failure writing a PDF must never change a run's verdict — see the callers, which catch and warn.
 */
final class ReportPdf {
    private ReportPdf() {}

    private static final double MARGIN = 48;
    private static final double CONTENT = Pdf.PAGE_WIDTH - 2 * MARGIN;
    private static final double TOP = Pdf.PAGE_HEIGHT - MARGIN;

    private static final Pdf.Colour INK = Pdf.Colour.of(0x1b1a17);
    private static final Pdf.Colour INK_2 = Pdf.Colour.of(0x4a463f);
    private static final Pdf.Colour INK_3 = Pdf.Colour.of(0x6f6a61);
    private static final Pdf.Colour HAIR = Pdf.Colour.of(0xddd8cf);
    private static final Pdf.Colour PASS = Pdf.Colour.of(0x1f6b4a);
    private static final Pdf.Colour FAIL = Pdf.Colour.of(0xa8323a);
    private static final Pdf.Colour WARN = Pdf.Colour.of(0x8a5a12);
    private static final Pdf.Colour INFO = Pdf.Colour.of(0x2d5699);

    /** A label and its value in the run's fact list. */
    record Fact(String label, String value) {}

    /** One figure in the counts ledger. [tone] is a status word or "" for the neutral figure. */
    record Count(String label, String value, String tone) {}

    /**
     * One row of the steps or tests table. [lead] is the phase word or the test's reference,
     * [screenshot] the evidence path whose plate this row should link to, if any.
     */
    record Row(String number, String lead, String name, String status, String duration,
               String details, String error, String screenshot) {}

    /** One screenshot plate: the evidence path plus the provenance the gallery shows. */
    record Plate(String path, String title, String where, String what, String status) {}

    /** One line of the performance table. */
    record Measure(String number, String name, String duration, String detail, double share) {}

    /** Everything a report PDF sets, already redacted and already formatted. */
    record Doc(String name, String kind, String status, String verdict, List<String> cleanup,
               List<Count> counts, List<Fact> facts,
               String rowsTitle, String rowsNote, List<Row> rows,
               List<Plate> plates,
               String performanceNote, List<Measure> slowest,
               String extraTitle, String extraNote, List<Row> extra) {}

    static byte[] render(Doc report, ReportImages images) {
        Pdf.Doc doc = new Pdf.Doc();
        doc.title = report.name();
        Cursor cursor = new Cursor(doc);

        // The tables come first: a reader wants the verdict, not the pictures, on page one. So the
        // page a screenshot lands on is not known while its step row is being set. The rows record
        // where they were drawn, the plates record where they went, and the links are added last.
        List<Band> bands = new ArrayList<>();
        Map<String, Integer> plateAt = new LinkedHashMap<>();

        masthead(cursor, report);
        counts(cursor, report.counts());
        facts(cursor, report.facts());
        cleanup(cursor, report.cleanup());
        rows(cursor, report, bands);
        performance(cursor, report);
        extra(cursor, report);
        plates(cursor, report.plates(), images, plateAt);
        for (Band band : bands) {
            Integer page = plateAt.get(band.screenshot());
            if (page == null) continue;
            doc.pages.get(band.page()).add(new Pdf.Link(MARGIN, band.bottom(), CONTENT,
                band.height(), page));
        }
        return Pdf.render(doc);
    }

    private static void masthead(Cursor cursor, Doc report) {
        cursor.rule(statusColour(report.status()), 3);
        cursor.gap(14);
        for (String line : Pdf.wrap(report.name(), Pdf.Font.BOLD, 20, CONTENT)) {
            cursor.line(line, Pdf.Font.BOLD, 20, INK, 25);
        }
        cursor.gap(4);
        cursor.line(report.kind() + " " + ReportHtml.sentenceCase(report.status()),
            Pdf.Font.BOLD, 11, statusColour(report.status()), 16);
        if (report.verdict() != null && !report.verdict().isBlank()) {
            cursor.gap(4);
            for (String line : Pdf.wrap(report.verdict(), Pdf.Font.REGULAR, 10, CONTENT)) {
                cursor.line(line, Pdf.Font.REGULAR, 10, FAIL, 14);
            }
        }
        cursor.gap(18);
    }

    private static void counts(Cursor cursor, List<Count> counts) {
        if (counts.isEmpty()) return;
        double column = CONTENT / counts.size();
        cursor.need(42);
        double top = cursor.y;
        for (int index = 0; index < counts.size(); index++) {
            Count count = counts.get(index);
            double x = MARGIN + index * column;
            cursor.at(count.value(), x, top - 16, Pdf.Font.BOLD, 17, tone(count.tone()));
            cursor.at(count.label(), x, top - 30, Pdf.Font.REGULAR, 9, INK_3);
        }
        cursor.y = top - 30;
        cursor.gap(16);
        cursor.rule(HAIR, 0.75);
        cursor.gap(16);
    }

    private static void facts(Cursor cursor, List<Fact> all) {
        // A fact with no value is not a fact: the page drops those too, rather than printing a
        // label over nothing (a suite run outside a project has no project).
        List<Fact> facts = new ArrayList<>();
        for (Fact fact : all) if (fact.value() != null && !fact.value().isBlank()) facts.add(fact);
        if (facts.isEmpty()) return;
        // Two columns: a run's facts are short, and a single column of them wastes most of a page.
        double column = CONTENT / 2;
        for (int index = 0; index < facts.size(); index += 2) {
            cursor.need(26);
            double top = cursor.y;
            for (int side = 0; side < 2 && index + side < facts.size(); side++) {
                Fact fact = facts.get(index + side);
                double x = MARGIN + side * column;
                cursor.at(fact.label(), x, top - 9, Pdf.Font.REGULAR, 8, INK_3);
                cursor.at(Pdf.ellipsise(fact.value(), Pdf.Font.MONO, 8.5, column - 12),
                    x, top - 21, Pdf.Font.MONO, 8.5, INK_2);
            }
            cursor.y = top - 21;
            cursor.gap(9);
        }
        cursor.gap(8);
    }

    private static void cleanup(Cursor cursor, List<String> cleanup) {
        if (cleanup.isEmpty()) return;
        cursor.gap(4);
        cursor.line("Cleanup failures: the device or its fixtures may be dirty",
            Pdf.Font.BOLD, 10, FAIL, 15);
        for (String failure : cleanup) {
            for (String line : Pdf.wrap(failure, Pdf.Font.REGULAR, 9.5, CONTENT - 14)) {
                cursor.at(line, MARGIN + 14, cursor.y - 11, Pdf.Font.REGULAR, 9.5, FAIL);
                cursor.y -= 12;
            }
        }
        cursor.gap(12);
    }

    /**
     * The steps, as blocks rather than columns. Seven columns fit a wide screen and not a page; a
     * block keeps the long strings — the action, its details, its error — at a readable measure and
     * lets a row take the height it needs.
     */
    private static void rows(Cursor cursor, Doc report, List<Band> bands) {
        heading(cursor, report.rowsTitle(), report.rowsNote());
        if (report.rows().isEmpty()) {
            cursor.line("Nothing ran.", Pdf.Font.REGULAR, 9.5, INK_3, 14);
            cursor.gap(10);
            return;
        }
        double indent = 26;
        for (Row row : report.rows()) {
            cursor.need(34);
            double top = cursor.y;
            int startedOn = cursor.pageNumber();
            cursor.at(row.number(), MARGIN, top - 11, Pdf.Font.MONO, 8.5, INK_3);
            String status = ReportHtml.sentenceCase(row.status());
            double statusWidth = Pdf.Font.BOLD.width(status, 8.5);
            double durationWidth = Pdf.Font.MONO.width(row.duration(), 8.5);
            double tail = statusWidth + durationWidth + 16;
            double nameWidth = CONTENT - indent - tail;
            List<String> name = Pdf.wrap(row.name(), Pdf.Font.BOLD, 10, nameWidth);
            cursor.at(status, MARGIN + CONTENT - tail + 8, top - 11, Pdf.Font.BOLD, 8.5,
                tone(row.status()));
            cursor.at(row.duration(), MARGIN + CONTENT - durationWidth, top - 11, Pdf.Font.MONO,
                8.5, INK_2);
            double y = top;
            for (String line : name) {
                cursor.at(line, MARGIN + indent, y - 11, Pdf.Font.BOLD, 10, INK);
                y -= 13;
            }
            if (row.lead() != null && !row.lead().isBlank()) {
                cursor.at(row.lead(), MARGIN + indent, y - 9, Pdf.Font.REGULAR, 8, INK_3);
                y -= 11;
            }
            if (row.details() != null && !row.details().isBlank()) {
                for (String line : Pdf.wrap(row.details(), Pdf.Font.MONO, 8, CONTENT - indent)) {
                    cursor.at(line, MARGIN + indent, y - 9, Pdf.Font.MONO, 8, INK_2);
                    y -= 10;
                }
            }
            if (row.error() != null && !row.error().isBlank()) {
                Pdf.Colour ink = "failed".equals(row.status()) ? FAIL : tone(row.status());
                for (String line : Pdf.wrap(row.error(), Pdf.Font.REGULAR, 9, CONTENT - indent)) {
                    cursor.at(line, MARGIN + indent, y - 10, Pdf.Font.REGULAR, 9, ink);
                    y -= 11;
                }
            }
            // Only a row that did not straddle a page break can carry one rectangle.
            if (row.screenshot() != null && cursor.pageNumber() == startedOn) {
                bands.add(new Band(row.screenshot(), startedOn, y, top - y));
            }
            cursor.y = y;
            cursor.gap(6);
            cursor.rule(HAIR, 0.5);
            cursor.gap(6);
        }
        cursor.gap(8);
    }

    private static void performance(Cursor cursor, Doc report) {
        if (report.performanceNote() == null) return;
        heading(cursor, "Performance", report.performanceNote());
        for (Measure measure : report.slowest()) {
            cursor.need(24);
            double top = cursor.y;
            cursor.at(measure.number(), MARGIN, top - 10, Pdf.Font.MONO, 8.5, INK_3);
            cursor.at(Pdf.ellipsise(measure.name(), Pdf.Font.REGULAR, 9.5, CONTENT - 200),
                MARGIN + 26, top - 10, Pdf.Font.REGULAR, 9.5, INK);
            cursor.at(measure.duration(), MARGIN + CONTENT - 150, top - 10, Pdf.Font.MONO, 8.5, INK_2);
            cursor.at(measure.detail(), MARGIN + CONTENT - 70, top - 10, Pdf.Font.MONO, 8.5, INK_3);
            double width = Math.max(1, 80 * Math.min(1, Math.max(0, measure.share())));
            cursor.box(MARGIN + CONTENT - 150, top - 16, width, 2.5, INFO);
            cursor.y = top - 20;
            cursor.gap(4);
        }
        cursor.gap(10);
    }

    /** The trailing table: the agent's events for a test, the lifecycle hooks for a suite. */
    private static void extra(Cursor cursor, Doc report) {
        if (report.extraTitle() == null) return;
        heading(cursor, report.extraTitle(), report.extraNote());
        for (Row row : report.extra()) {
            cursor.need(18);
            double top = cursor.y;
            cursor.at(row.number(), MARGIN, top - 10, Pdf.Font.MONO, 8, INK_3);
            cursor.at(Pdf.ellipsise(row.name(), Pdf.Font.BOLD, 8.5, 150), MARGIN + 60, top - 10,
                Pdf.Font.BOLD, 8.5, INK);
            cursor.at(Pdf.ellipsise(row.details(), Pdf.Font.REGULAR, 8.5, CONTENT - 290),
                MARGIN + 216, top - 10, Pdf.Font.REGULAR, 8.5, INK_2);
            if (row.status() != null && !row.status().isBlank()) {
                cursor.at(ReportHtml.sentenceCase(row.status()), MARGIN + CONTENT - 56, top - 10,
                    Pdf.Font.BOLD, 8, tone(row.status()));
            }
            double y = top - 13;
            if (row.error() != null && !row.error().isBlank()) {
                for (String line : Pdf.wrap(row.error(), Pdf.Font.REGULAR, 8.5, CONTENT - 60)) {
                    cursor.at(line, MARGIN + 60, y - 9, Pdf.Font.REGULAR, 8.5, FAIL);
                    y -= 10;
                }
            }
            cursor.y = y;
        }
        cursor.gap(10);
    }

    /**
     * A plate per capture: portrait screenshots two to a page, anything squarer on its own. A phone
     * screenshot set one to a page wastes two thirds of the paper, and set four to a page is
     * unreadable.
     */
    private static void plates(Cursor cursor, List<Plate> plates, ReportImages images,
                               Map<String, Integer> plateAt) {
        if (plates.isEmpty()) return;
        List<Placed> placed = new ArrayList<>();
        for (Plate plate : plates) {
            images.rgb(plate.path()).ifPresent(rgb -> placed.add(new Placed(plate, rgb)));
        }
        if (placed.isEmpty()) return;

        int index = 0;
        while (index < placed.size()) {
            Placed first = placed.get(index);
            boolean portrait = first.rgb.height() > first.rgb.width();
            Placed second = portrait && index + 1 < placed.size()
                && placed.get(index + 1).rgb.height() > placed.get(index + 1).rgb.width()
                ? placed.get(index + 1) : null;

            cursor.page();
            cursor.line("Screenshots", Pdf.Font.BOLD, 11, INK_3, 18);
            cursor.gap(4);
            double top = cursor.y;
            double column = second == null ? CONTENT : (CONTENT - 16) / 2;
            double box = top - MARGIN - 54;
            draw(cursor, first, MARGIN, top, column, box);
            plateAt.put(first.plate.path(), cursor.pageNumber());
            index++;
            if (second != null) {
                draw(cursor, second, MARGIN + column + 16, top, column, box);
                plateAt.put(second.plate.path(), cursor.pageNumber());
                index++;
            }
            cursor.y = MARGIN;
        }
    }

    private static void draw(Cursor cursor, Placed placed, double x, double top, double column,
                             double box) {
        double scale = Math.min(column / placed.rgb.width(), box / placed.rgb.height());
        double width = placed.rgb.width() * scale;
        double height = placed.rgb.height() * scale;
        double left = x + (column - width) / 2;
        double bottom = top - height;
        cursor.picture(placed.plate.path(), placed.rgb, left, bottom, width, height);
        double y = bottom - 14;
        cursor.at(Pdf.ellipsise(placed.plate.title(), Pdf.Font.BOLD, 9.5, column), x, y,
            Pdf.Font.BOLD, 9.5, INK);
        y -= 12;
        double indent = 0;
        if (placed.plate.where() != null && !placed.plate.where().isBlank()) {
            String where = Pdf.ellipsise(placed.plate.where(), Pdf.Font.BOLD, 8.5, column);
            cursor.at(where, x, y, Pdf.Font.BOLD, 8.5, INK_2);
            indent = Pdf.Font.BOLD.width(where, 8.5) + 4;
        }
        if (placed.plate.what() != null && !placed.plate.what().isBlank()) {
            List<String> lines = Pdf.wrap(placed.plate.what(), Pdf.Font.REGULAR, 8.5,
                column - indent);
            for (int line = 0; line < lines.size(); line++) {
                cursor.at(lines.get(line), x + (line == 0 ? indent : 0), y, Pdf.Font.REGULAR, 8.5,
                    INK_3);
                y -= 10;
            }
        } else if (indent > 0) {
            y -= 10;
        }
        if (placed.plate.status() != null && !placed.plate.status().isBlank()) {
            cursor.at(ReportHtml.sentenceCase(placed.plate.status()), x, y, Pdf.Font.BOLD, 8.5,
                tone(placed.plate.status()));
        }
    }

    private static void heading(Cursor cursor, String title, String note) {
        cursor.need(44);
        cursor.gap(6);
        cursor.line(title, Pdf.Font.BOLD, 13, INK, 18);
        if (note != null && !note.isBlank()) {
            for (String line : Pdf.wrap(note, Pdf.Font.REGULAR, 9, CONTENT)) {
                cursor.line(line, Pdf.Font.REGULAR, 9, INK_3, 12);
            }
        }
        cursor.gap(6);
        cursor.rule(HAIR, 0.75);
        cursor.gap(10);
    }

    private static Pdf.Colour statusColour(String status) {
        return tone(status);
    }

    private static Pdf.Colour tone(String status) {
        if (status == null) return INK;
        return switch (status) {
            case "passed" -> PASS;
            case "failed" -> FAIL;
            case "skipped", "cancelled", "running", "interrupted" -> WARN;
            default -> INK;
        };
    }

    private record Placed(Plate plate, ReportImages.Rgb rgb) {}

    /** Where a step row was drawn, so a link to its plate can be laid over it afterwards. */
    private record Band(String screenshot, int page, double bottom, double height) {}

    /** A page cursor: writes down the page and opens a new one when the next block will not fit. */
    private static final class Cursor {
        private final Pdf.Doc doc;
        private List<Pdf.Op> page;
        private double y = TOP;

        Cursor(Pdf.Doc doc) {
            this.doc = doc;
            this.page = doc.newPage();
        }

        int pageNumber() { return doc.pages.size() - 1; }

        void page() {
            page = doc.newPage();
            y = TOP;
        }

        void need(double height) {
            if (y - height < MARGIN) page();
        }

        void gap(double height) { y -= height; }

        void line(String value, Pdf.Font font, double size, Pdf.Colour colour, double advance) {
            need(advance);
            page.add(new Pdf.Text(value, MARGIN, y - size, font, size, colour));
            y -= advance;
        }

        void at(String value, double x, double baseline, Pdf.Font font, double size,
                Pdf.Colour colour) {
            page.add(new Pdf.Text(value, x, baseline, font, size, colour));
        }

        void rule(Pdf.Colour colour, double thickness) {
            page.add(new Pdf.Rule(MARGIN, y, CONTENT, thickness, colour));
            y -= thickness;
        }

        void box(double x, double bottom, double width, double height, Pdf.Colour colour) {
            page.add(new Pdf.Box(x, bottom, width, height, colour));
        }

        void picture(String key, ReportImages.Rgb rgb, double x, double bottom, double width,
                     double height) {
            doc.images.putIfAbsent(key, new Pdf.Image(rgb.width(), rgb.height(), rgb.bytes()));
            page.add(new Pdf.Picture(key, x, bottom, width, height));
        }
    }
}
