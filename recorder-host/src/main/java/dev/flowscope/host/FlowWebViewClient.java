package dev.flowscope.host;

import java.util.Map;

/** Restricted WebView surface used by the declarative runner; callers cannot execute JavaScript. */
interface FlowWebViewClient extends AutoCloseable {
    void waitVisible(Map<String, String> arguments, long timeoutMillis) throws Exception;
    void tap(Map<String, String> arguments, long timeoutMillis) throws Exception;
    void type(Map<String, String> arguments, long timeoutMillis) throws Exception;
    String text(Map<String, String> arguments, long timeoutMillis) throws Exception;
    String url(Map<String, String> arguments, long timeoutMillis) throws Exception;
    byte[] screenshot(Map<String, String> arguments, long timeoutMillis) throws Exception;
    WebViewState inspect(Map<String, String> arguments, long timeoutMillis) throws Exception;
    boolean interfaceExists(Map<String, String> arguments, String name, String method,
        long timeoutMillis) throws Exception;
    void clearCookies(Map<String, String> arguments, long timeoutMillis) throws Exception;
    void clearStorage(Map<String, String> arguments, long timeoutMillis) throws Exception;
    Map<String, String> metadata();
    @Override void close();
}
