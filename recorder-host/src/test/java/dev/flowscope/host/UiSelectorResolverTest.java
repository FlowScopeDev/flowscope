package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UiSelectorResolverTest {
    @Test
    void choosesSmallestNodeAndPrefersUniqueResourceId() throws Exception {
        byte[] xml = hierarchy("""
            <node resource-id="" text="" content-desc="" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]"/>
            <node resource-id="com.example:id/continue" text="Continue" content-desc="" class="android.widget.Button" bounds="[100,200][500,400]"/>
            """);

        var result = UiSelectorResolver.resolve(xml, 250, 300);

        assertNotNull(result);
        assertEquals(Map.of("id", "com.example:id/continue"), result.selector());
        assertEquals("high", result.stability());
        assertEquals("step tap id=\"com.example:id/continue\"", result.flowStep());
        assertEquals(100, result.left());
        assertEquals(400, result.bottom());
    }

    @Test
    void combinesDuplicateResourceIdWithText() throws Exception {
        byte[] xml = hierarchy("""
            <node resource-id="com.example:id/action" text="Pay" content-desc="" class="android.widget.Button" bounds="[0,0][300,200]"/>
            <node resource-id="com.example:id/action" text="Renew" content-desc="" class="android.widget.Button" bounds="[400,0][700,200]"/>
            """);

        var result = UiSelectorResolver.resolve(xml, 500, 100);

        assertNotNull(result);
        assertEquals(Map.of("id", "com.example:id/action", "text", "Renew"), result.selector());
        assertEquals("high", result.stability());
        assertEquals("step tap id=\"com.example:id/action\" text=\"Renew\"", result.flowStep());
    }

    private static byte[] hierarchy(String nodes) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><hierarchy>" + nodes + "</hierarchy>")
            .getBytes(StandardCharsets.UTF_8);
    }
}
