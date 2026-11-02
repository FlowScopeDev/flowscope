package dev.flowscope.host;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared chrome for `report.html` and `suite-report.html`.
 *
 * <p>Both reports are opened straight off disk, often from a CI artifact with no network, and both
 * are sent to people as a single file. So the page carries its own stylesheet, no script, no font or
 * image host, and its screenshots as `data:` URIs rather than links into a folder that will not
 * travel with it.
 *
 * <p>The page is light only. There is no script to remember a theme choice across reports, a report
 * is a document rather than an application surface, and light is the one theme a printed or pasted
 * screenshot looks right in. The palette here is the report's own paper palette, documented in the
 * stamp below; it is deliberately no longer kept in lockstep with the IDE's `Theme.kt`.
 *
 * <p>Report content is still subject to the redaction rules: this class only escapes and lays out
 * strings its callers have already decided are safe to persist.
 */
final class ReportHtml {
    private ReportHtml() {}

    /**
     * A screenshot in a gallery: the evidence file, what captured it, and that step's verdict.
     *
     * <p>[where] and [what] are the capture's provenance kept apart rather than joined into one
     * string, so the page can set the step apart from the action instead of running them together
     * with a separator. [link] is where a thumbnail points — the file itself, or a gallery overlay's
     * anchor. [thumbnail] and [plate] are embedded `data:` sources; either falling back to null
     * means that size could not be embedded and the relative file is used instead.
     */
    record Shot(String path, String title, String where, String what, String status, String link,
                String thumbnail, String plate) {
        Shot(String path, String title, String where, String what, String status) {
            this(path, title, where, what, status, path, null, null);
        }
        Shot linkedTo(String target) {
            return new Shot(path, title, where, what, status, target, thumbnail, plate);
        }
        Shot embedded(String thumbnailUri, String plateUri) {
            return new Shot(path, title, where, what, status, link, thumbnailUri, plateUri);
        }
        String thumbnailSrc() { return thumbnail == null ? path : thumbnail; }
        String plateSrc() { return plate == null ? path : plate; }
    }

    /** One entry in the counts ledger. [tone] is a status word or "" for the neutral figure. */
    record Stat(String value, String label, String tone) {}

    /** A card the rail links to. Callers know which cards they emit, so they name them. */
    record Section(String id, String label) {}

    /** Wall time, readable: the exact instant stays in `report.json` for anything that parses it. */
    private static final DateTimeFormatter WALL_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    static String time(Instant at) {
        return at == null ? "" : WALL_TIME.format(at) + " UTC";
    }

    private static final String CHEVRON_LEFT =
        "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M15 4.5 7.5 12 15 19.5\"/></svg>";
    private static final String CHEVRON_RIGHT =
        "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M9 4.5 16.5 12 9 19.5\"/></svg>";

    private static final String STYLE = """
        /* FlowScope report. Paper: warm off-white #fbfaf8 on ink #1b1a17, status inks desaturated
           toward print rather than UI. Type: one system stack, 16px body, 12px floor, labels in
           sentence case and untracked. Spacing: 4px scale, tokens only. Layout: masthead band over
           a sticky facts rail and a wide data column; the shell and the tables use the viewport,
           prose stays at a 65ch measure, which is how a data document reads that rule. Waivers, on
           record: the display face is the system stack because the page may not fetch a font, and
           there is no script, which is what costs a remembered theme and is why there is one. */
        *{box-sizing:border-box}
        :root{color-scheme:light;
        --paper:#fbfaf8;--panel:#fffefc;--sunk:#f4f2ed;--line:#ddd8cf;--hair:#ebe7df;
        --ink:#1b1a17;--ink-2:#4a463f;--ink-3:#6f6a61;
        --pass:#1f6b4a;--pass-bg:#e8f1ea;--fail:#a8323a;--fail-bg:#f7e9e8;
        --warn:#8a5a12;--warn-bg:#f7eedd;--info:#2d5699;--info-bg:#e8edf6;
        --scrim:rgba(27,26,23,.88);--on-scrim:#f6f4f0;--on-scrim-2:#bdb8ae;--on-scrim-link:#b9ccec;
        --control:rgba(246,244,240,.14);--control-line:rgba(246,244,240,.28);
        --s1:4px;--s2:8px;--s3:12px;--s4:16px;--s5:20px;--s6:24px;--s7:32px;--s8:40px;--s9:48px;
        --t-xs:12px;--t-s:14px;--t-m:16px;--t-l:20px;--t-xl:28px;--t-xxl:34px;
        --radius:10px;--radius-s:6px;
        --shadow:0 1px 2px rgba(27,26,23,.05),0 10px 30px -14px rgba(27,26,23,.16)}

        html{-webkit-text-size-adjust:100%}
        html,body{overflow-x:clip}
        body{margin:0;background:var(--paper);color:var(--ink);
        font:var(--t-m)/1.55 system-ui,-apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
        font-feature-settings:"tnum" 1}
        a{color:var(--info);text-decoration:underline;text-decoration-thickness:1px;
        text-underline-offset:2px}
        a:hover{text-decoration-thickness:2px}
        :focus-visible{outline:2px solid var(--info);outline-offset:2px;border-radius:var(--radius-s)}
        .mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:var(--t-xs)}

        .wrap{max-width:1560px;margin:0 auto;padding:var(--s8) var(--s6) var(--s9);
        display:grid;gap:var(--s6) var(--s7);
        grid-template-columns:264px minmax(0,1fr);
        grid-template-areas:"head head" "rail flow"}
        .head{grid-area:head}
        .rail{grid-area:rail}
        .flow{grid-area:flow;min-width:0}

        .head{position:relative;padding-left:var(--s5);border-left:5px solid var(--ink-3);
        display:flex;align-items:baseline;gap:var(--s4) var(--s5);flex-wrap:wrap}
        .head.passed{border-left-color:var(--pass)}
        .head.failed{border-left-color:var(--fail)}
        .head.cancelled{border-left-color:var(--warn)}
        .head h1{margin:0;font-size:var(--t-xxl);line-height:1.15;letter-spacing:-.02em;
        overflow-wrap:break-word;min-width:0;max-width:30ch}
        .head .verdict{display:flex;align-items:baseline;gap:var(--s4);flex-wrap:wrap;
        flex:1 1 320px;min-width:0}
        .head .why{color:var(--fail);font-size:var(--t-s);line-height:1.5;max-width:65ch}

        .pill{display:inline-flex;align-items:center;border-radius:999px;
        padding:var(--s1) var(--s3);font-size:var(--t-xs);font-weight:600;
        background:var(--sunk);color:var(--ink-2);border:1px solid var(--line)}
        .pill.passed{background:var(--pass-bg);color:var(--pass);border-color:transparent}
        .pill.failed{background:var(--fail-bg);color:var(--fail);border-color:transparent}
        .pill.skipped,.pill.cancelled,.pill.running,.pill.interrupted{background:var(--warn-bg);
        color:var(--warn);border-color:transparent}
        .pill.big{font-size:var(--t-s);padding:var(--s2) var(--s4)}

        .rail{align-self:start;position:sticky;top:var(--s6);display:flex;flex-direction:column;
        gap:var(--s6)}
        .ledger,.facts{margin:0;display:grid;gap:0;align-content:start}
        .ledger>div,.facts>div{display:flex;align-items:baseline;justify-content:space-between;
        gap:var(--s4);padding:var(--s2) 0;border-bottom:1px solid var(--hair)}
        .ledger>div:last-child,.facts>div:last-child{border-bottom:0}
        .ledger dt,.facts dt{color:var(--ink-3);font-size:var(--t-s)}
        .ledger dd{margin:0;font-size:var(--t-l);font-weight:600;letter-spacing:-.02em;
        font-variant-numeric:tabular-nums}
        .ledger dd.passed{color:var(--pass)}
        .ledger dd.failed{color:var(--fail)}
        .ledger dd.skipped,.ledger dd.cancelled{color:var(--warn)}
        .facts{font-size:var(--t-s)}
        .facts>div{flex-direction:column;align-items:stretch;gap:var(--s1)}
        .facts dd{margin:0;color:var(--ink-2);overflow-wrap:anywhere;
        font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:var(--t-xs)}
        .rail h2{margin:0 0 var(--s2);font-size:var(--t-s);font-weight:600;color:var(--ink-3)}
        .jump{margin:0;padding:0;list-style:none;display:flex;flex-direction:column;gap:var(--s1)}
        .jump a{display:block;padding:var(--s1) 0;font-size:var(--t-s);text-decoration:none}
        .jump a:hover{text-decoration:underline}

        .card{background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);
        box-shadow:var(--shadow);overflow:hidden}
        .card+.card{margin-top:var(--s6)}
        .card>header{display:flex;align-items:baseline;gap:var(--s3);flex-wrap:wrap;
        padding:var(--s4) var(--s5);border-bottom:1px solid var(--hair)}
        .card>header h2{margin:0;font-size:var(--t-m);letter-spacing:-.01em}
        .card>header p{margin:0;font-size:var(--t-s);color:var(--ink-3);flex:1 1 240px;max-width:65ch}
        .card>header .tag{font-size:var(--t-xs);color:var(--ink-3);background:var(--sunk);
        border:1px solid var(--hair);border-radius:999px;padding:var(--s1) var(--s3)}
        .body{padding:var(--s4) var(--s5)}
        .body p{margin:0;color:var(--ink-2);font-size:var(--t-s);max-width:65ch}
        .body p+p{margin-top:var(--s2)}

        .scroll{overflow-x:auto}
        table{border-collapse:collapse;width:100%;font-size:var(--t-s)}
        th{position:sticky;top:0;background:var(--sunk);color:var(--ink-3);text-align:left;
        font-size:var(--t-xs);font-weight:600;padding:var(--s2) var(--s4);white-space:nowrap;
        border-bottom:1px solid var(--line)}
        td{padding:var(--s3) var(--s4);border-bottom:1px solid var(--hair);vertical-align:top}
        tbody tr:last-child td{border-bottom:0}
        tbody tr:hover td{background:var(--sunk)}
        td.num{color:var(--ink-3);font-variant-numeric:tabular-nums;white-space:nowrap;width:1%}
        td.dur{font-variant-numeric:tabular-nums;white-space:nowrap;color:var(--ink-2)}
        td.name{font-weight:500;min-width:220px}
        td.sub{color:var(--ink-3)}
        td.err{color:var(--fail);max-width:44ch}
        .phase{display:inline-block;font-size:var(--t-xs);color:var(--ink-3);background:var(--sunk);
        border:1px solid var(--hair);border-radius:var(--radius-s);padding:0 var(--s2)}
        .detail{display:flex;flex-wrap:wrap;gap:var(--s1)}
        .detail code{background:var(--sunk);border:1px solid var(--hair);border-radius:var(--radius-s);
        padding:0 var(--s2);color:var(--ink-2);
        font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:var(--t-xs)}
        .detail a{text-decoration:none}
        .detail a code{color:var(--info);border-color:var(--info-bg);background:var(--info-bg);
        text-decoration:underline;text-underline-offset:2px}
        .bar{height:4px;border-radius:999px;background:var(--hair);overflow:hidden;max-width:180px;
        margin-top:var(--s1)}
        .bar i{display:block;height:100%;background:var(--info)}

        .note{margin:0;padding:var(--s4) var(--s5);border-bottom:1px solid var(--hair);
        background:var(--fail-bg);color:var(--fail);font-size:var(--t-s);font-weight:500}
        .note>p{margin:0}
        .note.warn{background:var(--warn-bg);color:var(--warn)}
        .note ul{margin:var(--s2) 0 0;padding-left:var(--s5);font-weight:400}

        .gallery{display:grid;gap:var(--s5);padding:var(--s5);
        grid-template-columns:repeat(auto-fill,minmax(200px,1fr))}
        .shot{margin:0;display:flex;flex-direction:column;gap:var(--s2);min-width:0}
        .shot .frame{display:block;line-height:0;border-radius:var(--radius-s)}
        .shot img{width:100%;height:280px;object-fit:contain;object-position:top;
        border:1px solid var(--line);border-radius:var(--radius-s);background:var(--sunk)}
        .shot .frame:hover img{border-color:var(--info)}
        .shot figcaption{font-size:var(--t-s);line-height:1.4}
        .shot figcaption b{display:block;font-weight:600;overflow-wrap:anywhere}
        .shot figcaption .where{display:block;color:var(--ink-3);margin-top:var(--s1);
        font-size:var(--t-xs);overflow-wrap:anywhere}
        .shot figcaption .where b{display:inline;font-weight:600;color:var(--ink-2)}
        .shot figcaption .pill{margin-top:var(--s2)}
        td.screenshots{padding-top:0}
        .gallery.strip{padding:0 0 var(--s1);gap:var(--s3);
        grid-template-columns:repeat(auto-fill,minmax(104px,124px))}
        .gallery.strip img{height:148px}
        .gallery.strip figcaption{font-size:var(--t-xs);color:var(--ink-3)}
        .gallery.strip figcaption b{font-weight:500}

        .lb{display:none}
        .lb:target{display:flex;position:fixed;inset:0;z-index:20;align-items:center;
        justify-content:center;padding:var(--s7);background:var(--scrim)}
        .lb .close{position:absolute;inset:0;cursor:zoom-out}
        .lb figure{position:relative;margin:0;display:flex;flex-direction:column;gap:var(--s3);
        max-height:100%;align-items:center}
        .lb img{max-width:min(94vw,560px);max-height:78vh;object-fit:contain;
        border-radius:var(--radius);background:var(--panel)}
        .lb figcaption{color:var(--on-scrim);font-size:var(--t-s);text-align:center;max-width:65ch}
        .lb figcaption .count{display:block;color:var(--on-scrim-2);font-size:var(--t-xs);
        font-variant-numeric:tabular-nums}
        .lb figcaption .file{display:block;color:var(--on-scrim-2);font-size:var(--t-xs);
        font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;overflow-wrap:anywhere}
        .lb .nav{position:absolute;top:50%;transform:translateY(-50%);z-index:2;display:flex;
        align-items:center;justify-content:center;width:44px;height:44px;border-radius:50%;
        color:var(--on-scrim);background:var(--control);border:1px solid var(--control-line)}
        .lb .nav svg{width:22px;height:22px;fill:none;stroke:currentColor;stroke-width:1.8;
        stroke-linecap:round;stroke-linejoin:round}
        .lb .nav:hover{background:var(--control-line)}
        .lb .nav.off{opacity:.3}
        .lb .prev{left:var(--s5)}
        .lb .next{right:var(--s5)}

        .empty{padding:var(--s5);color:var(--ink-3);font-size:var(--t-s);max-width:65ch}
        footer{grid-column:1/-1;margin-top:var(--s2);padding-top:var(--s4);
        border-top:1px solid var(--hair);color:var(--ink-3);font-size:var(--t-s)}

        @media (max-width:1039px){
        .wrap{grid-template-columns:minmax(0,1fr);grid-template-areas:"head" "rail" "flow"}
        .rail{position:static;flex-direction:row;flex-wrap:wrap;gap:var(--s5) var(--s6)}
        .rail>*{flex:1 1 200px;min-width:0}}
        @media (max-width:640px){.wrap{padding:var(--s6) var(--s4) var(--s8);gap:var(--s5)}
        .rail{flex-direction:column;gap:var(--s5)}
        .rail>*{flex:0 0 auto}
        .head{padding-left:var(--s4)}.head h1{font-size:var(--t-xl);max-width:none}
        .head .verdict{flex:1 1 100%}
        .gallery{padding:var(--s4);gap:var(--s4);
        grid-template-columns:repeat(auto-fill,minmax(136px,1fr))}
        .shot img{height:200px}
        .lb:target{padding:var(--s4)}.lb .nav{width:36px;height:36px}
        .lb .prev{left:var(--s1)}.lb .next{right:var(--s1)}}
        @media (prefers-reduced-motion:reduce){*{transition:none!important;animation:none!important}}
        @media print{body{background:var(--panel)}
        .wrap{display:block;max-width:none;padding:0}
        .card,.head{box-shadow:none;break-inside:avoid}
        .rail{position:static;display:block;margin-bottom:var(--s6)}
        .jump{display:none}
        .lb{display:none!important}
        .shot img{height:auto}}
        """;

    /**
     * The page shell. Self-contained by design: no external stylesheet, script, font or image, and
     * the screenshots travel inside the page as `data:` URIs.
     */
    static String document(String title, String head, String rail, String body) {
        return "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>" + escape(title) + " FlowScope report</title>"
            + "<style>" + STYLE + "</style></head><body><main class=\"wrap\">"
            + head + rail + "<div class=\"flow\">" + body + "</div>"
            + "<footer>" + escape(signature()) + "</footer>"
            + "</main></body></html>\n";
    }

    /** A plain-javac build has no generated version resource; the page says so by saying less. */
    private static String signature() {
        String version = FlowScopeVersion.current();
        return FlowScopeVersion.UNKNOWN.equals(version) ? "FlowScope" : "FlowScope " + version;
    }

    /**
     * The masthead. The name leads and the status word sits under it, so a reader never has to find
     * the verdict in a table; [verdict] carries the phase-aware failure message when there is one.
     * There is no kicker above the name — the kind of run is a fact in the rail, not a label.
     */
    static String head(String name, String status, String verdict) {
        StringBuilder out = new StringBuilder("<section class=\"head ").append(escape(status))
            .append("\"><h1>").append(escape(name)).append("</h1><div class=\"verdict\">")
            .append(pill(status, true));
        if (verdict != null && !verdict.isBlank()) {
            out.append("<span class=\"why\">").append(escape(verdict)).append("</span>");
        }
        return out.append("</div></section>").toString();
    }

    /**
     * The rail: the counts, the run's facts, and a way into each section. A ledger rather than a row
     * of equal tiles — five identical figures in five identical boxes is a dashboard, and this is a
     * document whose counts are read once.
     */
    static String rail(List<Stat> counts, List<String> facts, List<Section> sections) {
        StringBuilder out = new StringBuilder("<aside class=\"rail\">");
        if (!counts.isEmpty()) {
            out.append("<dl class=\"ledger\">");
            for (Stat stat : counts) {
                out.append("<div><dt>").append(escape(stat.label())).append("</dt><dd class=\"")
                    .append(escape(stat.tone())).append("\">").append(escape(stat.value()))
                    .append("</dd></div>");
            }
            out.append("</dl>");
        }
        if (!facts.isEmpty()) {
            out.append("<dl class=\"facts\">");
            for (String entry : facts) {
                int split = entry.indexOf('=');
                String label = split < 0 ? "" : entry.substring(0, split);
                String value = split < 0 ? entry : entry.substring(split + 1);
                out.append("<div><dt>").append(escape(label)).append("</dt><dd>")
                    .append(escape(value)).append("</dd></div>");
            }
            out.append("</dl>");
        }
        if (!sections.isEmpty()) {
            out.append("<nav><h2>Sections</h2><ul class=\"jump\">");
            for (Section section : sections) {
                out.append("<li><a href=\"#").append(escape(section.id())).append("\">")
                    .append(escape(section.label())).append("</a></li>");
            }
            out.append("</ul></nav>");
        }
        return out.append("</aside>").toString();
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

    /** Sentence case, untracked, and no decorative dot: the word is the signal. */
    static String pill(String status, boolean big) {
        String word = status == null || status.isBlank() ? "unknown" : status;
        return "<span class=\"pill " + escape(word) + (big ? " big" : "") + "\">"
            + escape(sentenceCase(word)) + "</span>";
    }

    static String sentenceCase(String word) {
        if (word == null || word.isEmpty()) return "";
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    static String note(String tone, String body) {
        return "<div class=\"note " + escape(tone) + "\">" + body + "</div>";
    }

    static String empty(String message) {
        return "<p class=\"empty\">" + escape(message) + "</p>";
    }

    /**
     * The screenshot gallery: a labelled thumbnail per capture plus a CSS-only overlay for each, so
     * a full-size look — and stepping through the run one capture at a time — costs a click and
     * never a script. [returnId] is the section an overlay closes back to.
     *
     * <p>The overlay names the evidence file rather than linking to it. The page carries the pixels,
     * so it works when it has been sent on its own; a link into a folder that is not there would be
     * a control the page cannot honour.
     */
    static String gallery(String returnId, List<Shot> shots) {
        if (shots.isEmpty()) return empty("This run captured no screenshots.");
        StringBuilder grid = new StringBuilder("<div class=\"gallery\">");
        StringBuilder overlays = new StringBuilder();
        for (int index = 0; index < shots.size(); index++) {
            Shot shot = shots.get(index);
            String anchor = anchor(returnId, index);
            String alt = caption(shot);
            grid.append("<figure class=\"shot\"><a class=\"frame\" href=\"#").append(escape(anchor))
                .append("\"><img src=\"").append(escape(shot.thumbnailSrc())).append("\" alt=\"")
                .append(escape(alt)).append("\" loading=\"lazy\"></a><figcaption><b>")
                .append(escape(shot.title())).append("</b>")
                .append(provenance(shot));
            if (shot.status() != null && !shot.status().isBlank()) grid.append(pill(shot.status()));
            grid.append("</figcaption></figure>");
            overlays.append("<div class=\"lb\" id=\"").append(escape(anchor))
                .append("\"><a class=\"close\" href=\"#").append(escape(returnId))
                .append("\" aria-label=\"Close\"></a><figure><img src=\"")
                .append(escape(shot.plateSrc())).append("\" alt=\"").append(escape(alt))
                .append("\" loading=\"lazy\"><figcaption><span class=\"count\">")
                .append(index + 1).append(" / ").append(shots.size())
                .append("</span>").append(escape(alt))
                .append("<span class=\"file\">").append(escape(shot.path()))
                .append("</span></figcaption></figure>")
                .append(step("prev", CHEVRON_LEFT, "Previous screenshot",
                    index > 0 ? anchor(returnId, index - 1) : null))
                .append(step("next", CHEVRON_RIGHT, "Next screenshot",
                    index + 1 < shots.size() ? anchor(returnId, index + 1) : null))
                .append("</div>");
        }
        return grid.append("</div>").append(overlays).toString();
    }

    /** Which step took the capture, with the step set apart from what it did. */
    private static String provenance(Shot shot) {
        boolean hasWhere = shot.where() != null && !shot.where().isBlank();
        boolean hasWhat = shot.what() != null && !shot.what().isBlank();
        if (!hasWhere && !hasWhat) return "";
        StringBuilder out = new StringBuilder("<span class=\"where\">");
        if (hasWhere) out.append("<b>").append(escape(shot.where())).append("</b>");
        if (hasWhere && hasWhat) out.append(' ');
        if (hasWhat) out.append(escape(shot.what()));
        return out.append("</span>").toString();
    }

    /**
     * One step through the gallery. At either end the control stays in place, visibly inert, rather
     * than disappearing: a vanishing arrow reads as a rendering fault, not as "this is the last one".
     */
    private static String step(String direction, String glyph, String label, String target) {
        if (target == null) {
            return "<span class=\"nav " + direction + " off\" aria-hidden=\"true\">" + glyph + "</span>";
        }
        return "<a class=\"nav " + direction + "\" href=\"#" + escape(target) + "\" aria-label=\""
            + escape(label) + "\">" + glyph + "</a>";
    }

    static String anchor(String returnId, int index) { return returnId + "-" + (index + 1); }

    private static String caption(Shot shot) {
        StringBuilder out = new StringBuilder(shot.title() == null ? "" : shot.title());
        String where = shot.where() == null || shot.where().isBlank() ? null : shot.where();
        String what = shot.what() == null || shot.what().isBlank() ? null : shot.what();
        if (where != null || what != null) {
            out.append(" — ");
            if (where != null) out.append(where);
            if (where != null && what != null) out.append(", ");
            if (what != null) out.append(what);
        }
        return out.toString();
    }

    /**
     * A thumbnail strip for one row of a table. Each thumbnail follows its [Shot.link], so a strip
     * beside a suite's test row opens the same overlay the gallery does instead of dropping the
     * reader onto a bare image file with no way back.
     */
    static String strip(List<Shot> shots) {
        StringBuilder out = new StringBuilder("<div class=\"gallery strip\">");
        for (Shot shot : shots) {
            out.append("<figure class=\"shot\"><a class=\"frame\" href=\"").append(escape(shot.link()))
                .append("\"><img src=\"").append(escape(shot.thumbnailSrc())).append("\" alt=\"")
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
