package dev.flowscope.host;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class UiHierarchy {
    private static final Pattern BOUNDS = Pattern.compile("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]");

    private UiHierarchy() {}

    public static List<UiNode> parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        NodeList nodes = factory.newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml)).getElementsByTagName("node");
        List<UiNode> result = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (!(node instanceof Element element)) continue;
            Matcher bounds = BOUNDS.matcher(element.getAttribute("bounds"));
            if (!bounds.matches()) continue;
            result.add(new UiNode(
                element.getAttribute("resource-id"),
                element.getAttribute("text"),
                element.getAttribute("content-desc"),
                element.getAttribute("class"),
                Integer.parseInt(bounds.group(1)), Integer.parseInt(bounds.group(2)),
                Integer.parseInt(bounds.group(3)), Integer.parseInt(bounds.group(4)),
                element.getAttribute("enabled").equals("true"),
                element.getAttribute("checked").equals("true"),
                element.getAttribute("focused").equals("true"),
                element.getAttribute("clickable").equals("true"),
                element.getAttribute("selected").equals("true")));
        }
        return result;
    }

    /** Selector keys that choose elements; at least one is required by selector actions. */
    public static final java.util.Set<String> SELECTOR_KEYS =
        java.util.Set.of("id", "text", "text-contains", "desc", "class");
    /** Boolean accessibility-state filters, matched against the node's attribute. */
    public static final java.util.Set<String> STATE_KEYS =
        java.util.Set.of("enabled", "checked", "focused", "clickable", "selected");

    /** The first match in document order, or the zero-based {@code index}th when one is given. */
    static UiNode find(List<UiNode> nodes, Map<String, String> arguments) {
        int index = arguments.containsKey("index") ? Integer.parseInt(arguments.get("index").strip()) : 0;
        return nodes.stream().filter(node -> node.matches(arguments)).skip(index).findFirst().orElse(null);
    }

    public record UiNode(String id, String text, String description, String className,
                         int left, int top, int right, int bottom,
                         boolean enabled, boolean checked, boolean focused,
                         boolean clickable, boolean selected) {
        /** A node with no accessibility state, for callers that only care about identity and bounds. */
        public UiNode(String id, String text, String description, String className,
                      int left, int top, int right, int bottom) {
            this(id, text, description, className, left, top, right, bottom,
                false, false, false, false, false);
        }

        boolean matches(Map<String, String> arguments) {
            return matches(arguments, "id", id)
                && matches(arguments, "text", text)
                && contains(arguments, "text-contains", text)
                && matches(arguments, "desc", description)
                && matches(arguments, "class", className)
                && state(arguments, "enabled", enabled)
                && state(arguments, "checked", checked)
                && state(arguments, "focused", focused)
                && state(arguments, "clickable", clickable)
                && state(arguments, "selected", selected);
        }

        private boolean state(Map<String, String> arguments, String key, boolean actual) {
            String expected = arguments.get(key);
            return expected == null || expected.strip().equals(String.valueOf(actual));
        }

        private boolean matches(Map<String, String> arguments, String key, String actual) {
            String expected = arguments.get(key);
            return expected == null || expected.equals(actual);
        }

        private boolean contains(Map<String, String> arguments, String key, String actual) {
            String expected = arguments.get(key);
            return expected == null || actual.contains(expected);
        }

        /**
         * Returns the horizontal tap target for a selector. A text-contains selector points at the
         * requested inline substring instead of the centre of the containing accessibility node.
         * This keeps durable tests coordinate-free while supporting clickable spans merged into a
         * single-line node by UIAutomator.
         */
        int tapX(Map<String, String> arguments) {
            String substring = arguments.get("text-contains");
            if (substring == null || substring.isEmpty() || text.isEmpty()) return centerX();
            int start = text.indexOf(substring);
            if (start < 0) return centerX();
            double characterCentre = start + substring.length() / 2.0;
            double fraction = characterCentre / text.length();
            return left + (int) Math.round((right - left) * fraction);
        }

        public int centerX() { return left + (right - left) / 2; }
        public int centerY() { return top + (bottom - top) / 2; }
        public boolean contains(int x, int y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
        public long area() { return (long) (right - left) * (bottom - top); }
    }
}
