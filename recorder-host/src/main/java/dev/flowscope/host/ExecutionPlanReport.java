package dev.flowscope.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** Aggregate assignment metadata only. Child reports own detailed, redacted failures/evidence. */
final class ExecutionPlanReport {
    private ExecutionPlanReport() {}
    static void write(Path root, String name, String id, Instant started, Instant ended,
                      List<ExecutionPlanRunner.JobResult> jobs, boolean passed) throws IOException {
        long failed = jobs.stream().filter(job -> job.status().equals("failed")).count();
        long skipped = jobs.stream().filter(job -> job.status().equals("skipped")).count();
        long cancelled = jobs.stream().filter(job -> job.status().equals("cancelled")).count();
        long successful = jobs.stream().filter(job -> job.status().equals("passed")).count();
        var jsonJobs = new StringJoiner(",\n", "[\n", "\n]");
        var htmlRows = new StringBuilder();
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"")
            .append(escape(name)).append("\" tests=\"").append(jobs.size()).append("\" failures=\"")
            .append(failed).append("\" skipped=\"").append(skipped + cancelled).append("\">\n");
        var pdfRows = new ArrayList<ReportPdf.Row>();
        int number = 0;
        for (var job : jobs) {
            var modes = new StringJoiner(",", "[", "]");
            job.modes().forEach(mode -> modes.add(Json.quote(mode)));
            jsonJobs.add("  {\"project\":" + Json.quote(job.project()) + ",\"projectRoot\":" + Json.quote(job.projectRoot()) + ",\"deviceRole\":" + Json.quote(job.role())
                + ",\"deviceSerial\":" + Json.quote(job.serial()) + ",\"reference\":" + Json.quote(job.reference())
                + ",\"kind\":" + Json.quote(job.kind()) + ",\"status\":" + Json.quote(job.status())
                + ",\"failureKind\":" + Json.quote(job.failureKind()) + ",\"report\":" + Json.quote(job.report())
                + ",\"modes\":" + modes + "}");
            String link = job.report() == null ? "" : "<a href=\"" + escape(Path.of(job.report()).resolveSibling(
                job.kind().equals("suite") ? "suite-report.html" : "report.html").toUri().toASCIIString()) + "\">Open report</a>";
            htmlRows.append("<tr><td>").append(escape(job.project())).append("</td><td>")
                .append(escape(job.role())).append("</td><td>").append(escape(job.serial())).append("</td><td>")
                .append(escape(job.reference())).append("</td><td>").append(ReportHtml.pill(job.status()))
                .append("</td><td>").append(escape(job.failureKind())).append("</td><td>").append(link).append("</td></tr>");
            xml.append("<testcase classname=\"").append(escape(job.project() + "." + job.role()))
                .append("\" name=\"").append(++number).append(" ").append(escape(job.reference())).append("\">");
            if (job.status().equals("failed")) xml.append("<failure message=\"").append(escape(job.failureKind()))
                .append("\">See child report: ").append(escape(job.report())).append("</failure>");
            if (job.status().equals("skipped") || job.status().equals("cancelled")) xml.append("<skipped/>");
            xml.append("</testcase>\n");
            pdfRows.add(new ReportPdf.Row(Integer.toString(number), job.project() + " / " + job.role(),
                job.reference(), job.status(), "", "Device: " + job.serial(), job.failureKind(), null));
        }
        String status = passed ? "passed" : "failed";
        String json = "{\n\"schemaVersion\":1,\"kind\":\"execution\",\"id\":" + Json.quote(id)
            + ",\"name\":" + Json.quote(name) + ",\"startedAt\":" + Json.quote(started.toString())
            + ",\"endedAt\":" + Json.quote(ended.toString()) + ",\"status\":" + Json.quote(status)
            + ",\"totalJobs\":" + jobs.size() + ",\"passed\":" + successful + ",\"failed\":" + failed
            + ",\"skipped\":" + skipped + ",\"cancelled\":" + cancelled + ",\"jobs\":" + jsonJobs + "\n}\n";
        Files.writeString(root.resolve("execution-report.json"), json);
        Files.writeString(root.resolve("junit.xml"), xml.append("</testsuite>\n"));
        String note = "Counts describe assigned jobs; a suite is one job. Child reports retain test and cleanup details. Report links refer to local project outputs.";
        String html = ReportHtml.document(name, ReportHtml.head(name, status, "Execution " + status),
            ReportHtml.rail(List.of(new ReportHtml.Stat(Long.toString(successful), "Passed jobs", "passed"),
                new ReportHtml.Stat(Long.toString(failed), "Failed jobs", "failed"),
                new ReportHtml.Stat(Long.toString(skipped), "Skipped jobs", "skipped"),
                new ReportHtml.Stat(Long.toString(cancelled), "Cancelled jobs", "cancelled")),
                List.of("Started=" + ReportHtml.time(started), "Finished=" + ReportHtml.time(ended)),
                List.of(new ReportHtml.Section("jobs", "Assignments"))),
            ReportHtml.card("jobs", "Assignments", note, "", ReportHtml.table(
                "<th>Project</th><th>Device role</th><th>Serial</th><th>Job</th><th>Status</th><th>Failure</th><th>Report</th>", htmlRows.toString())));
        Files.writeString(root.resolve("execution-report.html"), html);
        try {
            var document = new ReportPdf.Doc(name, "Execution", status, "Execution " + status, List.of(),
                List.of(new ReportPdf.Count("Passed jobs", Long.toString(successful), "passed"),
                    new ReportPdf.Count("Failed jobs", Long.toString(failed), "failed"),
                    new ReportPdf.Count("Skipped jobs", Long.toString(skipped), "skipped"),
                    new ReportPdf.Count("Cancelled jobs", Long.toString(cancelled), "cancelled")),
                List.of(new ReportPdf.Fact("Started", ReportHtml.time(started)), new ReportPdf.Fact("Finished", ReportHtml.time(ended))),
                "Assignments", note, pdfRows, List.of(), "", List.of(), "", "", List.of());
            Files.write(root.resolve("execution-report.pdf"), ReportPdf.render(document, new ReportImages(root)));
        } catch (Exception failure) { System.err.println("WARN could not write execution-report.pdf; other reports are available"); }
    }
    private static String escape(String text) { return ReportHtml.escape(text); }
}
