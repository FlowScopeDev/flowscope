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
        NodeList nodes = document(xml).getElementsByTagName("node");
        List<UiNode> result = new ArrayList<>();
        Map<Node, UiNode> parsed = new java.util.IdentityHashMap<>();
        Map<Node, Integer> windows = new java.util.IdentityHashMap<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (!(node instanceof Element element)) continue;
            Matcher bounds = BOUNDS.matcher(element.getAttribute("bounds"));
            if (!bounds.matches()) continue;
            UiNode parent = null;
            int window = 0;
            for (Node ancestor = element.getParentNode(); ancestor != null; ancestor = ancestor.getParentNode()) {
                if (parent == null) parent = parsed.get(ancestor);
                if (ancestor.getNodeName().equals("window")) {
                    window = windows.computeIfAbsent(ancestor, key -> windows.size() + 1);
                    break;
                }
            }
            UiNode item = new UiNode(
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
                element.getAttribute("selected").equals("true"), parent,
                integerAttribute(element, "drawing-order"), window, index,
                element.getAttribute("scrollable").equals("true"));
            result.add(item);
            parsed.put(element, item);
        }
        return result;
    }

    private static org.w3c.dom.Document document(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    /** Redact decoded attributes, then escape them again so secrets containing XML syntax stay hidden. */
    static byte[] redact(byte[] xml, java.util.function.UnaryOperator<String> redactor) throws Exception {
        var document = document(xml);
        NodeList elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            var attributes = elements.item(i).getAttributes();
            for (int j = 0; j < attributes.getLength(); j++) {
                Node attribute = attributes.item(j);
                attribute.setNodeValue(redactor.apply(attribute.getNodeValue()));
            }
        }
        var factory = javax.xml.transform.TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var output = new java.io.ByteArrayOutputStream();
        factory.newTransformer().transform(new javax.xml.transform.dom.DOMSource(document),
            new javax.xml.transform.stream.StreamResult(output));
        return output.toByteArray();
    }

    private static int integerAttribute(Element element, String name) {
        try { return Integer.parseInt(element.getAttribute(name)); }
        catch (NumberFormatException absent) { return -1; }
    }

    /** Selector keys that choose elements; at least one is required by selector actions. */
    public static final java.util.Set<String> SELECTOR_KEYS =
        java.util.Set.of("id", "text", "text-contains", "desc", "class");
    /** Boolean accessibility-state filters, matched against the node's attribute. */
    public static final java.util.Set<String> STATE_KEYS =
        java.util.Set.of("enabled", "checked", "focused", "clickable", "selected");

    /**
     * The first match in document order, or the zero-based {@code index}th when one is given. Nodes
     * with no area are skipped: Compose reports a node scrolled out of its viewport as [0,0][0,0].
     */
    static UiNode find(List<UiNode> nodes, Map<String, String> arguments) {
        int index = arguments.containsKey("index") ? Integer.parseInt(arguments.get("index").strip()) : 0;
        return nodes.stream().filter(node -> node.hasArea() && node.matches(arguments)).skip(index).findFirst().orElse(null);
    }

    /** Only unrelated branches can cover a target; a label inside its button is not an overlay. */
    static UiNode covering(List<UiNode> nodes, UiNode target, int x, int y) {
        UiNode cover = null;
        for (UiNode node : nodes) {
            if (node == target || !node.hasArea() || !node.contains(x, y)
                || descendant(node, target) || descendant(target, node)) continue;
            if (drawnAfter(node, target) && (cover == null || drawnAfter(node, cover))) cover = node;
        }
        return cover;
    }

    static boolean descendant(UiNode node, UiNode ancestor) {
        for (UiNode parent = node.parent; parent != null; parent = parent.parent) {
            if (parent == ancestor) return true;
        }
        return false;
    }

    private static boolean drawnAfter(UiNode a, UiNode b) {
        if (a.window != b.window) return a.window > b.window;
        List<UiNode> left = branch(a), right = branch(b);
        int i = 0;
        while (i < Math.min(left.size(), right.size()) && left.get(i) == right.get(i)) i++;
        if (i == Math.min(left.size(), right.size())) return false;
        UiNode x = left.get(i), y = right.get(i);
        // Missing drawing metadata cannot establish coverage. Equal orders use sibling order.
        return x.drawingOrder >= 0 && y.drawingOrder >= 0
            && (x.drawingOrder > y.drawingOrder
                || (x.drawingOrder == y.drawingOrder && x.documentOrder > y.documentOrder));
    }

    private static List<UiNode> branch(UiNode node) {
        List<UiNode> path = new ArrayList<>();
        for (; node != null; node = node.parent) path.addFirst(node);
        return path;
    }

    /** Stable content identity inside one scroll container; unrelated clocks are excluded. */
    static String contentSignature(List<UiNode> nodes, UiNode container) {
        StringBuilder signature = new StringBuilder();
        for (UiNode node : nodes) {
            if (!node.hasArea() || (container != null && !descendant(node, container))) continue;
            signature.append(List.of(node.id, node.text, node.description, node.className,
                node.left, node.top, node.right, node.bottom)).append('\n');
        }
        return signature.toString();
    }

    public record UiNode(String id, String text, String description, String className,
                         int left, int top, int right, int bottom,
                         boolean enabled, boolean checked, boolean focused,
                         boolean clickable, boolean selected, UiNode parent, int drawingOrder,
                         int window, int documentOrder, boolean scrollable) {
        public UiNode(String id, String text, String description, String className,
                      int left, int top, int right, int bottom,
                      boolean enabled, boolean checked, boolean focused, boolean clickable, boolean selected) {
            this(id, text, description, className, left, top, right, bottom,
                enabled, checked, focused, clickable, selected, null, -1, 0, -1, false);
        }
        /** A node with no accessibility state, for callers that only care about identity and bounds. */
        public UiNode(String id, String text, String description, String className,
                      int left, int top, int right, int bottom) {
            this(id, text, description, className, left, top, right, bottom,
                false, false, false, false, false);
        }

        boolean hasArea() {
            return right > left && bottom > top;
        }

        boolean matches(Map<String, String> arguments) {
            return within(arguments.get("within-id"))
                && matches(arguments, "id", id)
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

        private boolean within(String id) {
            if (id == null) return true;
            for (UiNode ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
                if (ancestor.id.equals(id)) return true;
            }
            return false;
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
