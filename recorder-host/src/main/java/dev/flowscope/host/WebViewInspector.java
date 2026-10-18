package dev.flowscope.host;

import java.util.List;
import java.util.Map;

/** One-shot, package-scoped WebView inspection facade used by the CLI and desktop IDE. */
public final class WebViewInspector {
    private WebViewInspector() {}

    public static WebViewSnapshot inspect(
        AdbClient adb, Device device, String packageName, String page, long timeoutMillis
    ) throws Exception {
        return inspect(adb, device, packageName, page, CaptureMode.ZERO_CODE, timeoutMillis);
    }

    public static WebViewSnapshot inspect(
        AdbClient adb, Device device, String packageName, String page, CaptureMode mode,
        long timeoutMillis
    ) throws Exception {
        Map<String, String> arguments = page == null || page.isBlank()
            ? Map.of() : Map.of("page", page);
        try (CdpWebViewClient client = new CdpWebViewClient(adb, device, packageName,
            mode == CaptureMode.INSTRUMENTED)) {
            return client.inspect(arguments, timeoutMillis).sanitized();
        }
    }

    public static List<WebViewTarget> targets(
        AdbClient adb, Device device, String packageName
    ) throws Exception {
        try (CdpWebViewClient client = new CdpWebViewClient(adb, device, packageName)) {
            return client.targets();
        }
    }

    public static String json(WebViewSnapshot snapshot) {
        StringBuilder out = new StringBuilder("{");
        field(out, "targetUrl", snapshot.targetUrl());
        out.append(",\"frames\":[");
        for (int i = 0; i < snapshot.frames().size(); i++) {
            if (i > 0) out.append(',');
            WebViewSnapshot.Frame frame = snapshot.frames().get(i);
            out.append('{'); field(out, "id", frame.id()); field(out, "parentId", frame.parentId(), true);
            field(out, "url", frame.url(), true); field(out, "name", frame.name(), true); out.append('}');
        }
        out.append("],\"cookies\":[");
        for (int i = 0; i < snapshot.cookies().size(); i++) {
            if (i > 0) out.append(',');
            WebViewSnapshot.Cookie cookie = snapshot.cookies().get(i);
            out.append('{'); field(out, "name", cookie.name()); field(out, "domain", cookie.domain(), true);
            field(out, "path", cookie.path(), true); bool(out, "secure", cookie.secure());
            bool(out, "httpOnly", cookie.httpOnly()); field(out, "sameSite", cookie.sameSite(), true);
            bool(out, "session", cookie.session()); field(out, "expires", cookie.expires(), true);
            bool(out, "partitioned", cookie.partitioned()); number(out, "size", cookie.size()); out.append('}');
        }
        out.append("],\"storage\":[");
        for (int i = 0; i < snapshot.storage().size(); i++) {
            if (i > 0) out.append(',');
            WebViewSnapshot.StorageEntry entry = snapshot.storage().get(i);
            out.append('{'); field(out, "area", entry.area()); field(out, "origin", entry.origin(), true);
            field(out, "key", entry.key(), true); number(out, "size", entry.size()); out.append('}');
        }
        out.append("],\"bridges\":[");
        for (int i = 0; i < snapshot.bridges().size(); i++) {
            if (i > 0) out.append(',');
            WebViewSnapshot.Bridge bridge = snapshot.bridges().get(i);
            out.append('{'); field(out, "name", bridge.name());
            out.append(",\"methods\":[");
            for (int j = 0; j < bridge.methods().size(); j++) {
                if (j > 0) out.append(','); out.append(Json.quote(bridge.methods().get(j)));
            }
            out.append(']'); field(out, "source", bridge.source(), true);
            field(out, "confidence", bridge.confidence(), true); out.append('}');
        }
        WebViewSnapshot.Security security = snapshot.security();
        out.append("],\"security\":{");
        firstBool(out, "secureContext", security.secureContext());
        bool(out, "mixedContent", security.mixedContent()); field(out, "userAgent", security.userAgent(), true);
        nullable(out, "javaScriptEnabled", security.javaScriptEnabled());
        nullable(out, "domStorageEnabled", security.domStorageEnabled());
        nullable(out, "allowFileAccess", security.allowFileAccess());
        nullable(out, "allowContentAccess", security.allowContentAccess());
        field(out, "mixedContentMode", security.mixedContentMode(), true);
        nullable(out, "safeBrowsingEnabled", security.safeBrowsingEnabled());
        out.append("},\"diagnostics\":"); strings(out, snapshot.diagnostics());
        out.append(",\"warnings\":"); strings(out, snapshot.warnings());
        return out.append('}').toString();
    }

    public static String text(WebViewSnapshot snapshot) {
        StringBuilder out = new StringBuilder();
        out.append("target       ").append(snapshot.targetUrl()).append('\n');
        out.append("frames       ").append(snapshot.frames().size()).append('\n');
        out.append("cookies      ").append(snapshot.cookies().size()).append(" (values hidden)\n");
        for (WebViewSnapshot.Cookie cookie : snapshot.cookies()) {
            out.append("  ").append(cookie.name()).append("  ").append(cookie.domain())
                .append(cookie.path()).append("  ")
                .append(cookie.secure() ? "Secure " : "")
                .append(cookie.httpOnly() ? "HttpOnly " : "")
                .append(cookie.sameSite().isBlank() ? "" : "SameSite=" + cookie.sameSite()).append('\n');
        }
        out.append("storage      ").append(snapshot.storage().size()).append(" (values hidden)\n");
        for (WebViewSnapshot.StorageEntry entry : snapshot.storage()) {
            out.append("  ").append(entry.area()).append("  ").append(entry.key())
                .append("  ").append(entry.size()).append(" chars\n");
        }
        out.append("bridges      ").append(snapshot.bridges().size()).append('\n');
        for (WebViewSnapshot.Bridge bridge : snapshot.bridges()) {
            out.append("  ").append(bridge.name()).append("  ").append(String.join(",", bridge.methods()))
                .append("  ").append(bridge.source()).append('/').append(bridge.confidence()).append('\n');
        }
        out.append("secure       ").append(snapshot.security().secureContext()).append('\n');
        for (String diagnostic : snapshot.diagnostics()) out.append("diagnostic   ").append(diagnostic).append('\n');
        for (String warning : snapshot.warnings()) out.append("warning      ").append(warning).append('\n');
        return out.toString();
    }

    private static void field(StringBuilder out, String name, String value) {
        out.append(Json.quote(name)).append(':').append(Json.quote(value));
    }
    private static void field(StringBuilder out, String name, String value, boolean comma) {
        if (comma) out.append(','); field(out, name, value);
    }
    private static void firstBool(StringBuilder out, String name, boolean value) {
        out.append(Json.quote(name)).append(':').append(value);
    }
    private static void bool(StringBuilder out, String name, boolean value) {
        out.append(',').append(Json.quote(name)).append(':').append(value);
    }
    private static void number(StringBuilder out, String name, int value) {
        out.append(',').append(Json.quote(name)).append(':').append(value);
    }
    private static void nullable(StringBuilder out, String name, Boolean value) {
        out.append(',').append(Json.quote(name)).append(':').append(value == null ? "null" : value);
    }
    private static void strings(StringBuilder out, List<String> values) {
        out.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(','); out.append(Json.quote(values.get(i)));
        }
        out.append(']');
    }
}
