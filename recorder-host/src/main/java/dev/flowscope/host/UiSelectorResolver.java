package dev.flowscope.host;

import java.util.LinkedHashMap;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Resolves a device coordinate to the smallest semantic UIAutomator node. */
public final class UiSelectorResolver {
    private UiSelectorResolver() {}

    public static Resolution resolve(byte[] hierarchyXml, int x, int y) throws Exception {
        List<UiHierarchy.UiNode> nodes = UiHierarchy.parse(hierarchyXml);
        UiHierarchy.UiNode target = nodes.stream()
            .filter(node -> node.contains(x, y))
            .min(java.util.Comparator.comparingLong(UiHierarchy.UiNode::area)
                .thenComparing(node -> semanticFieldCount(node) == 0 ? 1 : 0))
            .orElse(null);
        if (target == null) return null;

        SelectorChoice choice = chooseSelector(nodes, target);
        return new Resolution(choice.selector(), choice.stability(), target.className(),
            target.left(), target.top(), target.right(), target.bottom());
    }

    /**
     * Resolves the node under a coordinate without an active recording session. Used by the IDE to
     * preview a selector before recording starts. Returns null when the device cannot be dumped.
     *
     * <p>While a session is recording, use {@link SessionRecorder#resolveTap} instead — it pauses
     * the accessibility event stream around the dump, which this method deliberately does not touch.
     */
    public static Resolution resolveOnDevice(AdbClient adb, String serial, int x, int y)
        throws IOException, InterruptedException {
        String remote = "/data/local/tmp/flowscope-inspect-window.xml";
        AdbClient.CommandResult dump = adb.run(
            List.of("-s", serial, "shell", "uiautomator", "dump", remote));
        if (dump.exitCode() != 0) return null;
        try {
            return resolve(adb.execOut(serial, "cat", remote), x, y);
        } catch (Exception parseFailure) {
            return null;
        }
    }

    private static SelectorChoice chooseSelector(List<UiHierarchy.UiNode> nodes,
        UiHierarchy.UiNode target) {
        Map<String, String> selector = new LinkedHashMap<>();
        if (!target.id().isBlank()) {
            selector.put("id", target.id());
            if (unique(nodes, selector)) return new SelectorChoice(selector, "high");
            if (!target.description().isBlank()) {
                selector.put("desc", target.description());
                if (unique(nodes, selector)) return new SelectorChoice(selector, "high");
                selector.remove("desc");
            }
            if (!target.text().isBlank()) {
                selector.put("text", target.text());
                if (unique(nodes, selector)) return new SelectorChoice(selector, "high");
                selector.remove("text");
            }
        }

        selector.clear();
        if (!target.description().isBlank()) {
            selector.put("desc", target.description());
            if (unique(nodes, selector)) return new SelectorChoice(selector, "medium");
            selector.put("class", target.className());
            if (unique(nodes, selector)) return new SelectorChoice(selector, "medium");
        }

        selector.clear();
        if (!target.text().isBlank()) {
            selector.put("text", target.text());
            if (unique(nodes, selector)) return new SelectorChoice(selector, "medium");
            selector.put("class", target.className());
            if (unique(nodes, selector)) return new SelectorChoice(selector, "low");
        }

        selector.clear();
        if (!target.className().isBlank()) {
            selector.put("class", target.className());
            if (unique(nodes, selector)) return new SelectorChoice(selector, "low");
        }
        return new SelectorChoice(Map.of(), "unresolved");
    }

    private static boolean unique(List<UiHierarchy.UiNode> nodes, Map<String, String> selector) {
        return nodes.stream().filter(node -> node.matches(selector)).limit(2).count() == 1;
    }

    private static int semanticFieldCount(UiHierarchy.UiNode node) {
        int count = 0;
        if (!node.id().isBlank()) count++;
        if (!node.description().isBlank()) count++;
        if (!node.text().isBlank()) count++;
        return count;
    }

    public record Resolution(Map<String, String> selector, String stability, String className,
                             int left, int top, int right, int bottom) {
        public Resolution {
            selector = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(selector));
        }

        String toJson() {
            StringBuilder json = new StringBuilder("{\"selector\":{");
            int index = 0;
            for (Map.Entry<String, String> entry : selector.entrySet()) {
                if (index++ > 0) json.append(',');
                json.append(Json.quote(entry.getKey())).append(':').append(Json.quote(entry.getValue()));
            }
            return json.append("},\"stability\":").append(Json.quote(stability))
                .append(",\"class\":").append(Json.quote(className))
                .append(",\"bounds\":{\"left\":").append(left)
                .append(",\"top\":").append(top)
                .append(",\"right\":").append(right)
                .append(",\"bottom\":").append(bottom).append("}}")
                .toString();
        }

        public String flowStep() {
            if (selector.isEmpty()) return null;
            StringBuilder step = new StringBuilder("step tap");
            selector.forEach((key, value) -> step.append(' ').append(key).append("=\"")
                .append(value.replace("\\", "\\\\").replace("\"", "\\\""))
                .append('"'));
            return step.toString();
        }
    }

    private record SelectorChoice(Map<String, String> selector, String stability) {}
}
