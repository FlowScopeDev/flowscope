package dev.flowscope.host;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The shared chrome for `report.html` and `suite-report.html`.
 *
 * <p>Both reports are opened straight off disk, often from a CI artifact with no network, so the
 * page carries its own stylesheet and no script, font or image host. The palette is the IDE's
 * (`Theme.kt`), light and dark, so a report read next to the app looks like the same product.
 *
 * <p>Report content is still subject to the redaction rules: this class only escapes and lays out
 * strings its callers have already decided are safe to persist.
 */
final class ReportHtml {
    private ReportHtml() {}

    /** A screenshot in a gallery: the evidence file, what captured it, and that step's verdict. */
    record Shot(String path, String title, String note, String status) {}

    /** One figure in the summary strip. [tone] is a status word or "" for the neutral card. */
    record Stat(String value, String label, String tone) {}

    /** Wall time, readable: the exact instant stays in `report.json` for anything that parses it. */
    private static final DateTimeFormatter WALL_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    static String time(Instant at) {
        return at == null ? "" : WALL_TIME.format(at) + " UTC";
    }

    private static final String STYLE = """
        *{box-sizing:border-box}
        :root{color-scheme:light dark;
        --bg:#f8f9fb;--panel:#fff;--panel2:#f4f6f8;--line:#dae0e8;--line-soft:#eef1f5;
        --text:#171b22;--mid:#46505e;--low:#687382;
        --pass:#147a50;--pass-bg:#e7f3ed;--fail:#c9323c;--fail-bg:#fde8ea;
        --warn:#9a5b00;--warn-bg:#fff0d1;--info:#315fb5;--info-bg:#e6ecf8;
        --shadow:0 1px 2px rgba(23,27,34,.05),0 10px 30px -12px rgba(23,27,34,.18)}
        @media (prefers-color-scheme:dark){:root{
        --bg:#06070a;--panel:#0b0d11;--panel2:#11141a;--line:#262c36;--line-soft:#171b22;
        --text:#edf0f4;--mid:#99a1ad;--low:#5e6673;
        --pass:#3ecf8e;--pass-bg:#0d241a;--fail:#ff4f56;--fail-bg:#2a1012;
        --warn:#ffb224;--warn-bg:#2a1f08;--info:#9dbcff;--info-bg:#121a2b;
        --shadow:0 1px 2px rgba(0,0,0,.5),0 12px 32px -14px rgba(0,0,0,.7)}}
        html{-webkit-text-size-adjust:100%}
        body{margin:0;background:var(--bg);color:var(--text);
        font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
        font-feature-settings:"tnum" 1}
        .wrap{max-width:1140px;margin:0 auto;padding:40px 24px 72px}
        a{color:var(--info);text-decoration:none}
        a:hover{text-decoration:underline}
        .mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12.5px}

        .hero{position:relative;overflow:hidden;background:var(--panel);border:1px solid var(--line);
        border-radius:14px;box-shadow:var(--shadow);padding:26px 28px 22px 32px}
        .hero::before{content:"";position:absolute;left:0;top:0;bottom:0;width:5px;background:var(--low)}
        .hero.passed::before{background:var(--pass)}
        .hero.failed::before{background:var(--fail)}
        .hero.cancelled::before{background:var(--warn)}
        .eyebrow{margin:0;font-size:11px;letter-spacing:.14em;text-transform:uppercase;color:var(--low)}
        .hero h1{margin:6px 0 0;font-size:27px;line-height:1.2;letter-spacing:-.015em}
        .hero .verdict{margin-top:14px;display:flex;align-items:center;gap:10px;flex-wrap:wrap}
        .hero .why{color:var(--fail);font-size:13.5px;line-height:1.45;max-width:78ch}
        .meta{margin:16px 0 0;padding:0;list-style:none;display:flex;flex-wrap:wrap;gap:6px}
        .meta li{display:flex;gap:6px;align-items:baseline;background:var(--panel2);
        border:1px solid var(--line-soft);border-radius:7px;padding:4px 9px;font-size:12px;color:var(--mid)}
        .meta b{font-weight:500;color:var(--low);font-size:10px;letter-spacing:.08em;text-transform:uppercase}

        .pill{display:inline-flex;align-items:center;gap:6px;border-radius:999px;padding:3px 10px;
        font-size:11px;font-weight:600;letter-spacing:.06em;text-transform:uppercase;
        background:var(--panel2);color:var(--low);border:1px solid var(--line)}
        .pill::before{content:"";width:6px;height:6px;border-radius:50%;background:currentColor}
        .pill.passed{background:var(--pass-bg);color:var(--pass);border-color:transparent}
        .pill.failed{background:var(--fail-bg);color:var(--fail);border-color:transparent}
        .pill.cancelled,.pill.running,.pill.interrupted{background:var(--warn-bg);color:var(--warn);border-color:transparent}
        .pill.big{font-size:13px;padding:6px 14px;letter-spacing:.08em}

        .stats{display:grid;gap:12px;margin:16px 0 0;
        grid-template-columns:repeat(auto-fit,minmax(132px,1fr))}
        .stat{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:14px 16px}
        .stat b{display:block;font-size:26px;font-weight:600;letter-spacing:-.02em;line-height:1.1}
        .stat span{display:block;margin-top:3px;font-size:11px;letter-spacing:.1em;
        text-transform:uppercase;color:var(--low)}
        .stat.passed b{color:var(--pass)}
        .stat.failed b{color:var(--fail)}
        .stat.skipped b,.stat.cancelled b{color:var(--warn)}

        .card{margin-top:28px;background:var(--panel);border:1px solid var(--line);border-radius:14px;
        box-shadow:var(--shadow);overflow:hidden}
        .card>header{display:flex;align-items:baseline;gap:10px;flex-wrap:wrap;
        padding:16px 20px;border-bottom:1px solid var(--line-soft)}
        .card>header h2{margin:0;font-size:15px;letter-spacing:-.01em}
        .card>header p{margin:0;font-size:12.5px;color:var(--low);flex:1 1 220px}
        .card>header .tag{font-size:11px;font-weight:600;letter-spacing:.08em;text-transform:uppercase;
        color:var(--low);background:var(--panel2);border:1px solid var(--line-soft);
        border-radius:999px;padding:2px 9px}
        .body{padding:16px 20px}
        .body p{margin:0;color:var(--mid);font-size:13.5px}
        .body p+p{margin-top:8px}

        .scroll{overflow-x:auto}
        table{border-collapse:collapse;width:100%;font-size:13.5px}
        th{position:sticky;top:0;background:var(--panel2);color:var(--low);text-align:left;
        font-size:10.5px;font-weight:600;letter-spacing:.1em;text-transform:uppercase;
        padding:9px 14px;white-space:nowrap;border-bottom:1px solid var(--line)}
        td{padding:11px 14px;border-bottom:1px solid var(--line-soft);vertical-align:top}
        tbody tr:last-child td{border-bottom:0}
        tbody tr:hover td{background:var(--panel2)}
        td.num{color:var(--low);font-variant-numeric:tabular-nums;white-space:nowrap;width:1%}
        td.dur{font-variant-numeric:tabular-nums;white-space:nowrap;color:var(--mid)}
        td.name{font-weight:500;min-width:190px}
        td.sub{color:var(--low)}
        td.err{color:var(--fail);max-width:320px}
        .phase{display:inline-block;font-size:10px;font-weight:600;letter-spacing:.09em;
        color:var(--low);background:var(--panel2);border:1px solid var(--line-soft);
        border-radius:5px;padding:2px 6px}
        .detail{display:flex;flex-wrap:wrap;gap:4px}
        .detail code{background:var(--panel2);border:1px solid var(--line-soft);border-radius:5px;
        padding:1px 6px;color:var(--mid);
        font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:11.5px}
        .detail a code{color:var(--info);border-color:var(--info-bg);background:var(--info-bg)}
        .bar{height:4px;border-radius:999px;background:var(--line-soft);overflow:hidden;max-width:180px;margin-top:6px}
        .bar i{display:block;height:100%;background:var(--info)}

        .note{margin:0;padding:14px 20px;border-bottom:1px solid var(--line-soft);
        background:var(--fail-bg);color:var(--fail);font-size:13.5px;font-weight:500}
        .note.warn{background:var(--warn-bg);color:var(--warn)}
        .note ul{margin:6px 0 0;padding-left:20px;font-weight:400}

        .gallery{display:grid;gap:14px;padding:18px 20px;
        grid-template-columns:repeat(auto-fill,minmax(168px,1fr))}
        .shot{margin:0;display:flex;flex-direction:column;gap:8px}
        .shot .frame{display:block;background:var(--panel2);border:1px solid var(--line);
        border-radius:10px;padding:6px;line-height:0;transition:border-color .12s,transform .12s}
        .shot .frame:hover{border-color:var(--info);transform:translateY(-1px)}
        .shot img{width:100%;height:232px;object-fit:contain;object-position:top;border-radius:6px;
        background:var(--panel)}
        .shot figcaption{font-size:12px;line-height:1.35}
        .shot figcaption b{display:block;font-weight:600;overflow-wrap:anywhere}
        .shot figcaption .where{display:block;color:var(--low);margin-top:2px;overflow-wrap:anywhere}
        .shot figcaption .pill{margin-top:6px}
        td.screenshots{padding-top:0}
        .gallery.strip{padding:0 0 4px;gap:10px;grid-template-columns:repeat(auto-fill,minmax(104px,124px))}
        .gallery.strip img{height:148px}
        .gallery.strip figcaption{font-size:11px;color:var(--low)}
        .gallery.strip figcaption b{font-weight:500}

        .lb{display:none}
        .lb:target{display:flex;position:fixed;inset:0;z-index:20;align-items:center;
        justify-content:center;padding:28px;background:rgba(6,7,10,.86)}
        .lb .close{position:absolute;inset:0;cursor:zoom-out}
        .lb figure{position:relative;margin:0;display:flex;flex-direction:column;gap:10px;
        max-height:100%;align-items:center}
        .lb img{max-width:min(94vw,560px);max-height:78vh;object-fit:contain;border-radius:10px;
        background:#fff;box-shadow:0 24px 60px -20px rgba(0,0,0,.8)}
        .lb figcaption{color:#edf0f4;font-size:13px;text-align:center}
        .lb figcaption a{color:#9dbcff}

        .empty{padding:18px 20px;color:var(--low);font-size:13.5px}
        footer{margin-top:32px;color:var(--low);font-size:12px;text-align:center}

        @media (max-width:640px){.wrap{padding:24px 14px 48px}.hero{padding:20px 18px 18px 22px}
        .hero h1{font-size:22px}.shot img{height:180px}}
        @media print{body{background:#fff}.card,.hero,.stat{box-shadow:none}.lb{display:none!important}
        .shot .frame:hover{transform:none}}
        """;

    /** The page shell. Self-contained by design: no external stylesheet, script or font. */
    static String document(String title, String body) {
        return "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>" + escape(title) + " · FlowScope</title>"
            + "<style>" + STYLE + "</style></head><body><main class=\"wrap\">" + body
            + "<footer>" + escape(signature()) + "</footer>"
            + "</main></body></html>\n";
    }

    /** A plain-javac build has no generated version resource; the page says so by saying less. */
    private static String signature() {
        String version = FlowScopeVersion.current();
        return FlowScopeVersion.UNKNOWN.equals(version) ? "FlowScope" : "FlowScope " + version;
    }

    /**
     * The verdict banner. The status word leads, so a reader never has to find it in a table, and
     * [verdict] carries the phase-aware failure message when there is one.
     */
    static String hero(String eyebrow, String name, String status, String verdict, List<String> meta) {
        StringBuilder out = new StringBuilder("<section class=\"hero ").append(escape(status))
            .append("\"><p class=\"eyebrow\">").append(escape(eyebrow)).append("</p><h1>")
            .append(escape(name)).append("</h1><div class=\"verdict\">")
            .append(pill(status, true));
        if (verdict != null && !verdict.isBlank()) {
            out.append("<span class=\"why\">").append(escape(verdict)).append("</span>");
        }
        out.append("</div>");
        if (!meta.isEmpty()) {
            out.append("<ul class=\"meta\">");
            for (String entry : meta) {
                int split = entry.indexOf('=');
                String label = split < 0 ? "" : entry.substring(0, split);
                String value = split < 0 ? entry : entry.substring(split + 1);
                out.append("<li>");
                if (!label.isEmpty()) out.append("<b>").append(escape(label)).append("</b>");
                out.append("<span>").append(escape(value)).append("</span></li>");
            }
            out.append("</ul>");
        }
        return out.append("</section>").toString();
    }

    static String stats(List<Stat> values) {
        if (values.isEmpty()) return "";
        StringBuilder out = new StringBuilder("<section class=\"stats\">");
        for (Stat stat : values) {
            out.append("<div class=\"stat ").append(escape(stat.tone())).append("\"><b>")
                .append(escape(stat.value())).append("</b><span>").append(escape(stat.label()))
                .append("</span></div>");
        }
        return out.append("</section>").toString();
    }

    /** A titled panel. [note] explains the section; [tag] is the count or mode shown beside it. */
    static String card(String id, String title, String note, String tag, String body) {
        StringBuilder out = new StringBuilder("<section class=\"card\"");
        if (id != null && !id.isEmpty()) out.append(" id=\"").append(escape(id)).append('"');
        out.append("><header><h2>").append(escape(title)).append("</h2>");
        if (tag != null && !tag.isBlank()) out.append("<span class=\"tag\">").append(escape(tag)).append("</span>");
        if (note != null && !note.isBlank()) out.append("<p>").append(escape(note)).append("</p>");
        return out.append("</header>").append(body).append("</section>").toString();
    }

    static String table(String headers, String rows) {
        return "<div class=\"scroll\"><table><thead><tr>" + headers + "</tr></thead><tbody>"
            + rows + "</tbody></table></div>";
    }

    static String pill(String status) { return pill(status, false); }

    static String pill(String status, boolean big) {
        String word = status == null || status.isBlank() ? "unknown" : status;
        return "<span class=\"pill " + escape(word) + (big ? " big" : "") + "\">"
            + escape(word.toUpperCase(Locale.ROOT)) + "</span>";
    }

    static String note(String tone, String body) {
        return "<p class=\"note " + escape(tone) + "\">" + body + "</p>";
    }

    static String empty(String message) {
        return "<p class=\"empty\">" + escape(message) + "</p>";
    }

    /**
     * The screenshot gallery: a labelled thumbnail per capture plus a CSS-only overlay for each, so
     * a full-size look costs a click and never a script. [returnId] is the section the overlay
     * closes back to.
     */
    static String gallery(String returnId, List<Shot> shots) {
        if (shots.isEmpty()) return empty("This run captured no screenshots.");
        StringBuilder grid = new StringBuilder("<div class=\"gallery\">");
        StringBuilder overlays = new StringBuilder();
        for (int index = 0; index < shots.size(); index++) {
            Shot shot = shots.get(index);
            String anchor = returnId + "-" + (index + 1);
            String alt = shot.title() + (shot.note() == null || shot.note().isBlank() ? "" : " — " + shot.note());
            grid.append("<figure class=\"shot\"><a class=\"frame\" href=\"#").append(escape(anchor))
                .append("\"><img src=\"").append(escape(shot.path())).append("\" alt=\"")
                .append(escape(alt)).append("\" loading=\"lazy\"></a><figcaption><b>")
                .append(escape(shot.title())).append("</b>");
            if (shot.note() != null && !shot.note().isBlank()) {
                grid.append("<span class=\"where\">").append(escape(shot.note())).append("</span>");
            }
            if (shot.status() != null && !shot.status().isBlank()) grid.append(pill(shot.status()));
            grid.append("</figcaption></figure>");
            overlays.append("<div class=\"lb\" id=\"").append(escape(anchor)).append("\"><a class=\"close\" href=\"#")
                .append(escape(returnId)).append("\" aria-label=\"Close\"></a><figure><img src=\"")
                .append(escape(shot.path())).append("\" alt=\"").append(escape(alt))
                .append("\" loading=\"lazy\"><figcaption>").append(escape(alt))
                .append(" · <a href=\"").append(escape(shot.path())).append("\">Open file</a>")
                .append("</figcaption></figure></div>");
        }
        return grid.append("</div>").append(overlays).toString();
    }

    /** A thumbnail strip for one row of a table, with no overlay of its own. */
    static String strip(List<Shot> shots) {
        StringBuilder out = new StringBuilder("<div class=\"gallery strip\">");
        for (Shot shot : shots) {
            out.append("<figure class=\"shot\"><a class=\"frame\" href=\"").append(escape(shot.path()))
                .append("\"><img src=\"").append(escape(shot.path())).append("\" alt=\"")
                .append(escape(shot.title())).append("\" loading=\"lazy\"></a><figcaption><b>")
                .append(escape(shot.title())).append("</b></figcaption></figure>");
        }
        return out.append("</div>").toString();
    }

    /** A bare bar for a share of a total; the caller supplies the label beside it. */
    static String bar(long value, long total) {
        long percent = total <= 0 ? 0 : Math.round(100.0 * value / total);
        return "<div class=\"bar\"><i style=\"width:" + Math.max(0, Math.min(100, percent)) + "%\"></i></div>";
    }

    static List<String> meta(String... entries) {
        List<String> out = new ArrayList<>();
        for (String entry : entries) if (entry != null && !entry.isBlank()) out.add(entry);
        return out;
    }

    static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
