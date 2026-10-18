package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentEventCaptureTest {

    @Test
    void summarizesSemanticActionsWithTheirScreen() {
        Map<String, Object> payload = Map.of("action", "submit", "componentId", "sign_in", "screen", "login-xml");
        assertEquals("submit sign_in @login-xml", AgentEventCapture.summarize("semantic.action", payload));
    }

    @Test
    void summarizesNetworkEventsFromRouteTemplatesOnly() {
        Map<String, Object> request = Map.of("method", "GET", "routeTemplate", "/v1/profile/{id}",
            "requestId", "secret-correlation-id");
        assertEquals("GET /v1/profile/{id}", AgentEventCapture.summarize("network.request", request));

        Map<String, Object> response = new HashMap<>(request);
        response.put("statusCode", 200L);
        response.put("durationMillis", 803L);
        String summary = AgentEventCapture.summarize("network.response", response);
        assertEquals("GET /v1/profile/{id} 200 803 ms", summary);
        assertFalse(summary.contains("secret-correlation-id"));
    }

    @Test
    void summarizesBackgroundWorkAndHello() {
        assertEquals("profile-sync finished", AgentEventCapture.summarize("background.work",
            Map.of("workName", "profile-sync", "state", "finished")));
        assertEquals("agent 0.1.0", AgentEventCapture.summarize("agent.hello", Map.of("agentVersion", "0.1.0")));
    }

    @Test
    void retainsNothingForUnlistedKinds() {
        assertEquals("", AgentEventCapture.summarize("ui.tap", Map.of("resourceName", "x", "text", "private")));
    }
}
