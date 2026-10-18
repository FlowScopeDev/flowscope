package dev.flowscope.host;

/** Sanitized selectable DevTools target. Page titles and query values are deliberately omitted. */
public record WebViewTarget(String pageMatch, String url) {}
