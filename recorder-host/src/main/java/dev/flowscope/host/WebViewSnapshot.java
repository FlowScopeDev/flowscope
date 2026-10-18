package dev.flowscope.host;

import java.util.List;

/** Sanitized, value-free WebView state safe for CLI output, the IDE, evidence, and reports. */
public record WebViewSnapshot(
    String targetUrl,
    List<Frame> frames,
    List<Cookie> cookies,
    List<StorageEntry> storage,
    List<Bridge> bridges,
    Security security,
    List<String> diagnostics,
    List<String> warnings
) {
    public WebViewSnapshot {
        targetUrl = targetUrl == null ? "" : targetUrl;
        frames = List.copyOf(frames);
        cookies = List.copyOf(cookies);
        storage = List.copyOf(storage);
        bridges = List.copyOf(bridges);
        diagnostics = List.copyOf(diagnostics);
        warnings = List.copyOf(warnings);
    }

    public record Frame(String id, String parentId, String url, String name) {}
    public record Cookie(
        String name, String domain, String path, boolean secure, boolean httpOnly,
        String sameSite, boolean session, String expires, boolean partitioned, int size
    ) {}
    public record StorageEntry(String area, String origin, String key, int size) {}
    public record Bridge(String name, List<String> methods, String source, String confidence) {
        public Bridge { methods = List.copyOf(methods); }
    }
    public record Security(
        boolean secureContext, boolean mixedContent, String userAgent,
        Boolean javaScriptEnabled, Boolean domStorageEnabled, Boolean allowFileAccess,
        Boolean allowContentAccess, String mixedContentMode, Boolean safeBrowsingEnabled
    ) {}
}
