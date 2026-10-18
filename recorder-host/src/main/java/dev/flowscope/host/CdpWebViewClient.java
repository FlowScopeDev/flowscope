package dev.flowscope.host;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Package-scoped Chrome DevTools Protocol bridge for debuggable Android WebViews. */
final class CdpWebViewClient implements FlowWebViewClient {
    private static final long POLL_MILLIS = 200;
    private final AdbClient adb;
    private final Device device;
    private final String packageName;
    private final boolean includeAgent;
    private final HttpClient http;
    private final AtomicLong sequence = new AtomicLong();
    private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private WebSocket socket;
    private Integer forwardedPort;
    private String targetUrl = "";
    private String targetTitle = "";
    private String connectedPageMatch;

    CdpWebViewClient(AdbClient adb, Device device, String packageName) {
        this(adb, device, packageName, false);
    }

    CdpWebViewClient(AdbClient adb, Device device, String packageName, boolean includeAgent) {
        this(adb, device, packageName, includeAgent, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build());
    }

    CdpWebViewClient(AdbClient adb, Device device, String packageName, HttpClient http) {
        this(adb, device, packageName, false, http);
    }

    CdpWebViewClient(AdbClient adb, Device device, String packageName, boolean includeAgent, HttpClient http) {
        this.adb = adb;
        this.device = device;
        this.packageName = packageName;
        this.includeAgent = includeAgent;
        this.http = http;
    }

    @Override public void waitVisible(Map<String, String> arguments, long timeoutMillis) throws Exception {
        await(arguments, timeoutMillis, () -> booleanValue(evaluate(visibleExpression(arguments.get("css")))));
    }

    @Override public void tap(Map<String, String> arguments, long timeoutMillis) throws Exception {
        waitVisible(arguments, timeoutMillis);
        String expression = "(()=>{const e=document.querySelector(" + Json.quote(arguments.get("css"))
            + ");if(!e)return false;e.scrollIntoView({block:'center',inline:'center'});e.click();return true})()";
        if (!booleanValue(evaluate(expression))) throw new IllegalStateException("Web element disappeared before tap");
    }

    @Override public void type(Map<String, String> arguments, long timeoutMillis) throws Exception {
        waitVisible(arguments, timeoutMillis);
        String expression = "(()=>{const e=document.querySelector(" + Json.quote(arguments.get("css"))
            + ");if(!e)return false;e.focus();e.value=" + Json.quote(arguments.get("value"))
            + ";e.dispatchEvent(new Event('input',{bubbles:true}));"
            + "e.dispatchEvent(new Event('change',{bubbles:true}));return true})()";
        if (!booleanValue(evaluate(expression))) throw new IllegalStateException("Web element disappeared before type");
    }

    @Override public String text(Map<String, String> arguments, long timeoutMillis) throws Exception {
        waitVisible(arguments, timeoutMillis);
        String expression = "(()=>{const e=document.querySelector(" + Json.quote(arguments.get("css"))
            + ");return e?(e.innerText||e.textContent||'').trim():null})()";
        Object value = evaluate(expression);
        return value == null ? "" : value.toString();
    }

    @Override public String url(Map<String, String> arguments, long timeoutMillis) throws Exception {
        ensureConnected(arguments, timeoutMillis);
        Object value = evaluate("location.href");
        return value == null ? "" : value.toString();
    }

    @Override public byte[] screenshot(Map<String, String> arguments, long timeoutMillis) throws Exception {
        ensureConnected(arguments, timeoutMillis);
        Map<String, Object> result = command("Page.captureScreenshot", "{\"format\":\"png\"}", timeoutMillis);
        Object data = path(result, "result", "data");
        if (!(data instanceof String encoded)) throw new IOException("WebView did not return screenshot data");
        return Base64.getDecoder().decode(encoded);
    }

    @Override public WebViewState inspect(Map<String, String> arguments, long timeoutMillis) throws Exception {
        ensureConnected(arguments, timeoutMillis);
        List<String> warnings = new ArrayList<>();
        List<WebViewState.Cookie> cookies = cookies(timeoutMillis, warnings);
        Map<String, Object> runtime = runtimeSnapshot(timeoutMillis);
        List<WebViewState.Storage> storage = storage(runtime);
        List<WebViewSnapshot.Bridge> bridges = heuristicBridges(runtime);
        WebViewSnapshot.Security security = security(runtime, null);
        AgentState agent = includeAgent ? agentState(warnings) : AgentState.EMPTY;
        if (!agent.bridges().isEmpty()) {
            Map<String, WebViewSnapshot.Bridge> merged = new LinkedHashMap<>();
            for (WebViewSnapshot.Bridge bridge : bridges) merged.put(bridge.name(), bridge);
            for (WebViewSnapshot.Bridge bridge : agent.bridges()) merged.put(bridge.name(), bridge);
            bridges = merged.values().stream().sorted(Comparator.comparing(WebViewSnapshot.Bridge::name)).toList();
        }
        if (agent.security() != null) security = security(runtime, agent.security());
        List<WebViewSnapshot.Frame> frames = frames(timeoutMillis, warnings);
        String effectiveUrl = targetUrl;
        if ((effectiveUrl == null || effectiveUrl.isBlank() || effectiveUrl.startsWith("about:"))
            && !frames.isEmpty() && !frames.getFirst().url().isBlank()) {
            effectiveUrl = frames.getFirst().url();
        }
        return new WebViewState(effectiveUrl, frames, cookies, storage, bridges,
            security, WebViewState.diagnostics(cookies, security), List.copyOf(warnings));
    }

    @Override public boolean interfaceExists(Map<String, String> arguments, String name, String method,
        long timeoutMillis) throws Exception {
        ensureConnected(arguments, timeoutMillis);
        String expression = method == null
            ? "(()=>{try{return typeof window[" + Json.quote(name) + "]!=='undefined'}catch(e){return false}})()"
            : "(()=>{try{const v=window[" + Json.quote(name) + "];return !!v&&typeof v["
                + Json.quote(method) + "]==='function'}catch(e){return false}})()";
        return booleanValue(evaluate(expression));
    }

    @Override public void clearCookies(Map<String, String> arguments, long timeoutMillis) throws Exception {
        ensureConnected(arguments, timeoutMillis);
        List<String> warnings = new ArrayList<>();
        List<WebViewState.Cookie> matches = cookies(timeoutMillis, warnings).stream()
            .filter(cookie -> domainMatches(cookie.domain(), arguments.get("domain")))
            .filter(cookie -> !arguments.containsKey("name") || cookie.name().equals(arguments.get("name")))
            .filter(cookie -> !arguments.containsKey("path") || cookie.path().equals(arguments.get("path")))
            .toList();
        if (!warnings.isEmpty()) throw new IllegalStateException(warnings.getFirst());
        for (WebViewState.Cookie cookie : matches) {
            command("Network.deleteCookies", "{\"name\":" + Json.quote(cookie.name())
                + ",\"domain\":" + Json.quote(cookie.domain())
                + ",\"path\":" + Json.quote(cookie.path()) + "}", timeoutMillis);
        }
    }

    @Override public void clearStorage(Map<String, String> arguments, long timeoutMillis) throws Exception {
        ensureConnected(arguments, timeoutMillis);
        String origin = arguments.get("origin");
        String types = java.util.Arrays.stream(arguments.get("types").split(","))
            .map(String::strip).map(type -> switch (type) {
                case "local" -> "local_storage";
                case "session" -> "session_storage";
                case "cache" -> "cache_storage";
                default -> type;
            }).distinct().collect(java.util.stream.Collectors.joining(","));
        command("Storage.clearDataForOrigin", "{\"origin\":" + Json.quote(origin)
            + ",\"storageTypes\":" + Json.quote(types) + "}", timeoutMillis);
    }

    @Override public Map<String, String> metadata() {
        return Map.of("webTargetUrl", safeUrl(targetUrl));
    }

    List<WebViewTarget> targets() throws Exception {
        return discoverTargets().stream().map(target ->
            new WebViewTarget(target.id(), WebViewState.safeUrl(target.url())))
            .distinct().toList();
    }

    private void await(Map<String, String> arguments, long timeoutMillis, CheckedBoolean condition)
        throws Exception {
        ensureConnected(arguments, timeoutMillis);
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos();
        Exception last = null;
        do {
            try {
                if (condition.get()) return;
            } catch (Exception failure) {
                last = failure;
            }
            Thread.sleep(POLL_MILLIS);
        } while (System.nanoTime() < deadline);
        if (last != null) throw last;
        throw new IllegalStateException("Web element did not become visible: css=" + arguments.get("css"));
    }

    private void ensureConnected(Map<String, String> arguments, long timeoutMillis) throws Exception {
        String pageMatch = arguments.get("page");
        if (socket != null && java.util.Objects.equals(pageMatch, connectedPageMatch)) return;
        if (socket != null) disconnectSocket();
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos();
        Exception last = null;
        do {
            List<Target> candidates = discoverTargets();
            List<Target> metadataMatches = pageMatch == null ? candidates : candidates.stream()
                .filter(target -> targetMatches(target, pageMatch)).toList();
            Target candidate = null;
            if (pageMatch == null && candidates.size() == 1) candidate = candidates.getFirst();
            else if (pageMatch != null && metadataMatches.size() == 1) candidate = metadataMatches.getFirst();
            if (candidate != null) {
                try {
                    connectTarget(candidate);
                    if (pageMatch == null || targetMatches(candidate, pageMatch)
                        || runtimePageMatchScore(pageMatch) > 0) {
                        connectedPageMatch = pageMatch;
                        return;
                    }
                    last = new IllegalStateException("No WebView page matched: " + pageMatch);
                } catch (Exception failure) {
                    last = failure;
                }
                disconnectSocket();
            } else if (pageMatch != null && metadataMatches.isEmpty() && !candidates.isEmpty()) {
                Target runtimeMatch = null;
                int bestScore = 0;
                boolean ambiguous = false;
                for (Target possible : candidates) {
                    try {
                        connectTarget(possible);
                        int score = runtimePageMatchScore(pageMatch);
                        if (score > bestScore) {
                            runtimeMatch = possible;
                            bestScore = score;
                            ambiguous = false;
                        } else if (score > 0 && score == bestScore) {
                            ambiguous = true;
                        }
                    } catch (Exception failure) {
                        last = failure;
                    } finally {
                        disconnectSocket();
                    }
                }
                if (runtimeMatch != null && !ambiguous) {
                    connectTarget(runtimeMatch);
                    runtimePageMatchScore(pageMatch);
                    connectedPageMatch = pageMatch;
                    return;
                }
                last = new IllegalStateException(ambiguous
                    ? "Multiple WebView pages matched: " + pageMatch
                    : "No WebView page matched: " + pageMatch);
            } else if (candidates.isEmpty()) {
                last = new IllegalStateException("No debuggable WebView page is available");
            } else if (pageMatch == null || metadataMatches.size() > 1) {
                last = new IllegalStateException(pageMatch == null
                    ? "Multiple WebView pages are available; add page= to select one"
                    : "Multiple WebView pages matched: " + pageMatch);
            } else {
                last = new IllegalStateException("No WebView page matched: " + pageMatch);
            }
            Thread.sleep(POLL_MILLIS);
        } while (System.nanoTime() < deadline);
        if (last != null) throw last;
        throw new IllegalStateException("No debuggable WebView page is available");
    }

    private void connectTarget(Target target) throws Exception {
        targetUrl = target.url();
        targetTitle = target.title();
        socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
            .buildAsync(URI.create(target.webSocketUrl()), new Listener(messages)).join();
        command("Runtime.enable", "{}", 5_000);
        command("Page.enable", "{}", 5_000);
        command("Network.enable", "{}", 5_000);
    }

    private int runtimePageMatchScore(String pageMatch) throws Exception {
        Object location = evaluate("location.href");
        if (location != null && location.toString().contains(pageMatch)) {
            targetUrl = location.toString();
            return 2;
        }
        Map<String, Object> tree = command("Page.getFrameTree", "{}", 5_000);
        List<WebViewSnapshot.Frame> runtimeFrames = new ArrayList<>();
        collectFrames(path(tree, "result", "frameTree"), "", runtimeFrames);
        return runtimeFrames.stream().anyMatch(frame -> frame.url().contains(pageMatch)) ? 1 : 0;
    }

    private static boolean targetMatches(Target target, String pageMatch) {
        return target.url().contains(pageMatch) || target.title().contains(pageMatch)
            || target.id().equals(pageMatch);
    }

    private void disconnectSocket() {
        if (socket != null) runQuietly(() -> socket.sendClose(WebSocket.NORMAL_CLOSURE, "switch").join());
        socket = null;
        connectedPageMatch = null;
        messages.clear();
    }

    private List<Target> discoverTargets() throws Exception {
        if (forwardedPort != null) return fetchTargets();
        String pid = adb.pidOf(device.serial(), packageName);
        if (pid == null) throw new IllegalStateException("Target app is not running; launch it before using WebView actions");
        var unix = adb.run(List.of("-s", device.serial(), "shell", "cat", "/proc/net/unix"));
        if (unix.exitCode() != 0) throw new IOException("Could not inspect WebView debugging sockets");
        String socketName = findSocket(unix.stdout(), pid);
        if (socketName == null) {
            throw new IllegalStateException("No debuggable WebView found for " + packageName
                + ". Enable WebView debugging in the debug build or use accessibility selectors");
        }
        var forward = adb.run(List.of("-s", device.serial(), "forward", "tcp:0", "localabstract:" + socketName));
        if (forward.exitCode() != 0) throw new IOException("Could not forward the WebView debugging socket");
        forwardedPort = Integer.parseInt(forward.stdout().strip());
        return fetchTargets();
    }

    private List<Target> fetchTargets() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + forwardedPort + "/json/list"))
            .timeout(Duration.ofSeconds(5)).GET().build();
        String body = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        return candidateTargets(CdpJson.parse(body));
    }

    static String findSocket(String procNetUnix, String pid) {
        if (procNetUnix == null) return null;
        String exact = "webview_devtools_remote_" + pid;
        for (String line : procNetUnix.split("\\R")) {
            int at = line.indexOf('@');
            if (at >= 0 && line.substring(at + 1).strip().equals(exact)) return exact;
        }
        return null;
    }

    static Target selectTarget(Object parsed, String pageMatch) {
        return selectTarget(candidateTargets(parsed), pageMatch);
    }

    private static Target selectTarget(List<Target> candidates, String pageMatch) {
        if (pageMatch != null) {
            List<Target> matches = candidates.stream().filter(target -> targetMatches(target, pageMatch)).toList();
            if (matches.size() == 1) return matches.getFirst();
            if (matches.size() > 1) throw new IllegalStateException("Multiple WebView pages matched: " + pageMatch);
            throw new IllegalStateException("No WebView page matched: " + pageMatch);
        }
        if (candidates.size() == 1) return candidates.getFirst();
        if (candidates.isEmpty()) throw new IllegalStateException("No debuggable WebView page is available");
        throw new IllegalStateException("Multiple WebView pages are available; add page= to select one");
    }

    private static List<Target> candidateTargets(Object parsed) {
        if (!(parsed instanceof List<?> targets)) throw new IllegalStateException("Invalid WebView target list");
        java.util.ArrayList<Target> candidates = new java.util.ArrayList<>();
        for (Object raw : targets) {
            if (!(raw instanceof Map<?, ?> map)) continue;
            String type = string(map.get("type"));
            String id = string(map.get("id"));
            String url = string(map.get("url"));
            String title = string(map.get("title"));
            String ws = string(map.get("webSocketDebuggerUrl"));
            if ((type.equals("page") || type.equals("iframe") || type.isBlank()) && !ws.isBlank()) {
                candidates.add(new Target(id, title, url, ws));
            }
        }
        return List.copyOf(candidates);
    }

    private Object evaluate(String expression) throws Exception {
        Map<String, Object> response = command("Runtime.evaluate",
            "{\"expression\":" + Json.quote(expression) + ",\"returnByValue\":true,\"awaitPromise\":true}", 5_000);
        Object exception = path(response, "result", "exceptionDetails");
        if (exception != null) throw new IllegalStateException("WebView expression failed");
        return path(response, "result", "result", "value");
    }

    private Map<String, Object> command(String method, String params, long timeoutMillis) throws Exception {
        long id = sequence.incrementAndGet();
        socket.sendText("{\"id\":" + id + ",\"method\":" + Json.quote(method)
            + ",\"params\":" + params + "}", true).join();
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos();
        while (System.nanoTime() < deadline) {
            long remaining = Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toMillis());
            String message = messages.poll(remaining, TimeUnit.MILLISECONDS);
            if (message == null) break;
            if (!hasResponseId(message, id)) continue;
            Map<String, Object> object = CdpJson.parseObject(message);
            if (object.get("id") instanceof Number responseId && responseId.longValue() == id) {
                if (object.containsKey("error")) throw new IllegalStateException("WebView command failed: " + method);
                return object;
            }
        }
        throw new IOException("Timed out waiting for WebView command: " + method);
    }

    private static boolean hasResponseId(String message, long id) {
        return message.matches("(?s).*\\\"id\\\"\\s*:\\s*" + id + "(?:\\s*[,}]).*");
    }

    private List<WebViewState.Cookie> cookies(long timeoutMillis, List<String> warnings) {
        try {
            Map<String, Object> response = command("Network.getAllCookies", "{}", timeoutMillis);
            Object raw = path(response, "result", "cookies");
            if (!(raw instanceof List<?> list)) throw new IllegalStateException("Cookie metadata is unavailable");
            List<WebViewState.Cookie> result = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> cookie)) continue;
                String value = string(cookie.get("value"));
                int size = number(cookie.get("size")).intValue();
                if (size <= 0) size = string(cookie.get("name")).length() + value.length();
                result.add(new WebViewState.Cookie(
                    string(cookie.get("name")), value, string(cookie.get("domain")),
                    string(cookie.get("path")), bool(cookie.get("secure")), bool(cookie.get("httpOnly")),
                    string(cookie.get("sameSite")), bool(cookie.get("session")),
                    number(cookie.get("expires")).doubleValue(), cookie.get("partitionKey") != null,
                    size));
            }
            return result.stream().sorted(Comparator.comparing(WebViewState.Cookie::domain)
                .thenComparing(WebViewState.Cookie::path).thenComparing(WebViewState.Cookie::name)).toList();
        } catch (Exception unsupported) {
            warnings.add("Cookie inspection is unsupported by this WebView");
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runtimeSnapshot(long timeoutMillis) throws Exception {
        Object raw = evaluate("(()=>{const read=(a)=>{try{const s=a==='local'?window.localStorage:window.sessionStorage;"
            + "return Object.keys(s).slice(0,256).map(k=>{const v=s.getItem(k)||'';return{area:a,key:k,value:v,size:k.length+v.length}})}catch(e){return[]}};"
            + "let base=new Set(),f=null;try{f=document.createElement('iframe');f.style.display='none';"
            + "document.documentElement.appendChild(f);base=new Set(Object.getOwnPropertyNames(f.contentWindow))}catch(e){}"
            + "const bridges=[];for(const n of Object.getOwnPropertyNames(window).filter(n=>!base.has(n)&&/^[A-Za-z_$][A-Za-z0-9_$]{0,127}$/.test(n)).slice(0,256)){"
            + "try{const wd=Object.getOwnPropertyDescriptor(window,n);const v=wd&&'value'in wd?wd.value:null;if(v&&typeof v==='object'){"
            + "const p=Object.getPrototypeOf(v),od=Object.getOwnPropertyDescriptors(v),pd=p?Object.getOwnPropertyDescriptors(p):{};"
            + "const ms=[...new Set(Object.keys(od).concat(Object.keys(pd)))].filter(m=>m!=='constructor'&&"
            + "typeof((od[m]||pd[m]||{}).value)==='function').slice(0,128);"
            + "if(v!==window&&ms.length&&ms.length<=32)bridges.push({name:n,methods:ms})}}catch(e){}}if(f)f.remove();"
            + "const mixed=location.protocol==='https:'&&performance.getEntriesByType('resource').some(e=>String(e.name).startsWith('http:'));"
            + "return{origin:location.origin,secureContext:!!window.isSecureContext,mixedContent:mixed,userAgent:navigator.userAgent||'',"
            + "local:read('local'),session:read('session'),bridges:bridges}})()");
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalStateException("WebView inspection returned invalid state");
        return (Map<String, Object>) map;
    }

    private List<WebViewState.Storage> storage(Map<String, Object> runtime) {
        List<WebViewState.Storage> result = new ArrayList<>();
        String origin = string(runtime.get("origin"));
        for (String bucket : List.of("local", "session")) {
            if (!(runtime.get(bucket) instanceof List<?> entries)) continue;
            for (Object item : entries) {
                if (!(item instanceof Map<?, ?> entry)) continue;
                result.add(new WebViewState.Storage(bucket, origin, string(entry.get("key")),
                    string(entry.get("value")), number(entry.get("size")).intValue()));
            }
        }
        return result.stream().sorted(Comparator.comparing(WebViewState.Storage::area)
            .thenComparing(WebViewState.Storage::key)).toList();
    }

    private List<WebViewSnapshot.Bridge> heuristicBridges(Map<String, Object> runtime) {
        if (!(runtime.get("bridges") instanceof List<?> list)) return List.of();
        List<WebViewSnapshot.Bridge> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> bridge)) continue;
            List<String> methods = strings(bridge.get("methods"));
            result.add(new WebViewSnapshot.Bridge(string(bridge.get("name")), methods,
                "runtime", "HEURISTIC"));
        }
        return result.stream().filter(bridge -> !bridge.name().isBlank())
            .sorted(Comparator.comparing(WebViewSnapshot.Bridge::name)).toList();
    }

    private List<WebViewSnapshot.Frame> frames(long timeoutMillis, List<String> warnings) {
        try {
            Map<String, Object> result = command("Page.getFrameTree", "{}", timeoutMillis);
            List<WebViewSnapshot.Frame> frames = new ArrayList<>();
            collectFrames(path(result, "result", "frameTree"), "", frames);
            return List.copyOf(frames);
        } catch (Exception unsupported) {
            warnings.add("Frame inspection is unsupported by this WebView");
            return List.of();
        }
    }

    private static void collectFrames(Object raw, String parent, List<WebViewSnapshot.Frame> out) {
        if (!(raw instanceof Map<?, ?> tree) || !(tree.get("frame") instanceof Map<?, ?> frame)) return;
        String id = string(frame.get("id"));
        out.add(new WebViewSnapshot.Frame(id, parent, WebViewState.safeUrl(string(frame.get("url"))),
            string(frame.get("name"))));
        if (tree.get("childFrames") instanceof List<?> children) {
            for (Object child : children) collectFrames(child, id, out);
        }
    }

    private WebViewSnapshot.Security security(Map<String, Object> runtime, WebViewSnapshot.Security agent) {
        return new WebViewSnapshot.Security(bool(runtime.get("secureContext")),
            bool(runtime.get("mixedContent")), string(runtime.get("userAgent")),
            agent == null ? null : agent.javaScriptEnabled(),
            agent == null ? null : agent.domStorageEnabled(),
            agent == null ? null : agent.allowFileAccess(),
            agent == null ? null : agent.allowContentAccess(),
            agent == null ? "unknown" : agent.mixedContentMode(),
            agent == null ? null : agent.safeBrowsingEnabled());
    }

    private AgentState agentState(List<String> warnings) {
        try {
            byte[] bytes = adb.execOut(device.serial(), "run-as", packageName, "cat",
                "files/flowscope-webview-registry.json");
            if (bytes.length == 0) return AgentState.EMPTY;
            Object parsed = CdpJson.parse(new String(bytes, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> root)) return AgentState.EMPTY;
            List<WebViewSnapshot.Bridge> bridges = new ArrayList<>();
            if (root.get("bridges") instanceof List<?> rawBridges) {
                for (Object raw : rawBridges) {
                    if (!(raw instanceof Map<?, ?> bridge)) continue;
                    bridges.add(new WebViewSnapshot.Bridge(string(bridge.get("name")),
                        strings(bridge.get("methods")), "agent", "EXACT"));
                }
            }
            WebViewSnapshot.Security security = null;
            if (root.get("webViews") instanceof List<?> webViews && webViews.size() == 1
                && webViews.getFirst() instanceof Map<?, ?> webView
                && webView.get("settings") instanceof Map<?, ?> settings) {
                security = new WebViewSnapshot.Security(false, false, "",
                    nullableBool(settings.get("javaScriptEnabled")),
                    nullableBool(settings.get("domStorageEnabled")),
                    nullableBool(settings.get("allowFileAccess")),
                    nullableBool(settings.get("allowContentAccess")),
                    string(settings.get("mixedContentMode")),
                    nullableBool(settings.get("safeBrowsingEnabled")));
            } else if (root.get("webViews") instanceof List<?> webViews && webViews.size() > 1) {
                warnings.add("Multiple agent-observed WebViews are available; settings were not merged");
            }
            return new AgentState(List.copyOf(bridges), security);
        } catch (Exception unavailable) {
            return AgentState.EMPTY;
        }
    }

    private static boolean domainMatches(String actual, String expected) {
        String a = actual.startsWith(".") ? actual.substring(1) : actual;
        String e = expected.startsWith(".") ? expected.substring(1) : expected;
        return a.equalsIgnoreCase(e) || a.toLowerCase().endsWith("." + e.toLowerCase());
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(CdpWebViewClient::string).filter(value -> !value.isBlank())
            .distinct().sorted().toList();
    }

    private static Number number(Object value) { return value instanceof Number n ? n : 0L; }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value); }
    private static Boolean nullableBool(Object value) { return value instanceof Boolean flag ? flag : null; }

    private static String visibleExpression(String css) {
        return "(()=>{const e=document.querySelector(" + Json.quote(css)
            + ");if(!e)return false;const r=e.getBoundingClientRect();const s=getComputedStyle(e);"
            + "return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'})()";
    }

    private static boolean booleanValue(Object value) { return Boolean.TRUE.equals(value); }
    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static Object path(Object root, String... parts) {
        Object current = root;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }
    private static String safeUrl(String raw) { return WebViewState.safeUrl(raw); }

    @Override public void close() {
        disconnectSocket();
        if (forwardedPort != null) runQuietly(() -> adb.run(List.of("-s", device.serial(),
            "forward", "--remove", "tcp:" + forwardedPort)));
        forwardedPort = null;
    }

    private static void runQuietly(CheckedRunnable action) { try { action.run(); } catch (Exception ignored) {} }
    record Target(String id, String title, String url, String webSocketUrl) {}
    private record AgentState(List<WebViewSnapshot.Bridge> bridges, WebViewSnapshot.Security security) {
        private static final AgentState EMPTY = new AgentState(List.of(), null);
    }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }

    private static final class Listener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages;
        private final StringBuilder partial = new StringBuilder();
        Listener(LinkedBlockingQueue<String> messages) { this.messages = messages; }
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) { messages.offer(partial.toString()); partial.setLength(0); }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
