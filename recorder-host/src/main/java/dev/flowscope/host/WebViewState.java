package dev.flowscope.host;

import java.util.ArrayList;
import java.util.List;

/** Internal WebView state. Cookie/storage values must never leave this package-private type. */
record WebViewState(
    String targetUrl,
    List<WebViewSnapshot.Frame> frames,
    List<Cookie> cookies,
    List<Storage> storage,
    List<WebViewSnapshot.Bridge> bridges,
    WebViewSnapshot.Security security,
    List<String> diagnostics,
    List<String> warnings
) {
    record Cookie(
        String name, String value, String domain, String path, boolean secure, boolean httpOnly,
        String sameSite, boolean session, double expires, boolean partitioned, int size
    ) {}
    record Storage(String area, String origin, String key, String value, int size) {}

    WebViewSnapshot sanitized() {
        List<WebViewSnapshot.Cookie> safeCookies = cookies.stream().map(cookie ->
            new WebViewSnapshot.Cookie(cookie.name(), cookie.domain(), cookie.path(), cookie.secure(),
                cookie.httpOnly(), cookie.sameSite(), cookie.session(), expiry(cookie),
                cookie.partitioned(), cookie.size())).toList();
        List<WebViewSnapshot.StorageEntry> safeStorage = storage.stream().map(entry ->
            new WebViewSnapshot.StorageEntry(entry.area(), entry.origin(), entry.key(), entry.size())).toList();
        return new WebViewSnapshot(safeUrl(targetUrl), frames, safeCookies, safeStorage, bridges,
            security, diagnostics, warnings);
    }

    private static String expiry(Cookie cookie) {
        if (cookie.session() || cookie.expires() <= 0) return "session";
        try { return java.time.Instant.ofEpochMilli((long) (cookie.expires() * 1000)).toString(); }
        catch (Exception ignored) { return "persistent"; }
    }

    static String safeUrl(String raw) {
        try {
            java.net.URI uri = java.net.URI.create(raw);
            String authority = uri.getRawAuthority();
            if (authority == null) {
                String schemeSpecific = uri.getRawSchemeSpecificPart();
                int query = schemeSpecific == null ? -1 : schemeSpecific.indexOf('?');
                if (query >= 0) schemeSpecific = schemeSpecific.substring(0, query);
                return uri.getScheme() + ":" + (schemeSpecific == null ? "" : schemeSpecific);
            }
            return uri.getScheme() + "://" + (authority == null ? "" : authority)
                + (uri.getRawPath() == null ? "" : uri.getRawPath());
        } catch (Exception ignored) { return ""; }
    }

    static List<String> diagnostics(List<Cookie> cookies, WebViewSnapshot.Security security) {
        List<String> result = new ArrayList<>();
        if (!security.secureContext()) result.add("Page is not a secure context");
        if (security.mixedContent()) result.add("Secure page loaded insecure resources");
        if (Boolean.TRUE.equals(security.allowFileAccess())) result.add("WebView file access is enabled");
        for (Cookie cookie : cookies) {
            String lower = cookie.name().toLowerCase(java.util.Locale.ROOT);
            if (!(lower.contains("session") || lower.contains("auth") || lower.contains("token"))) continue;
            List<String> missing = new ArrayList<>();
            if (!cookie.secure()) missing.add("Secure");
            if (!cookie.httpOnly()) missing.add("HttpOnly");
            if (cookie.sameSite().isBlank()) missing.add("SameSite");
            if (!missing.isEmpty()) result.add("Cookie " + cookie.name() + " is missing " + String.join(", ", missing));
        }
        return List.copyOf(result);
    }
}
