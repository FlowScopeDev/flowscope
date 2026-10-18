package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class UiHierarchyTest {
    private static final UiHierarchy.UiNode TERMS_ROW = new UiHierarchy.UiNode(
        "", "I have read and accept the  Terms & Policies", "",
        "android.widget.TextView", 76, 631, 503, 648);

    @Test
    void textContainsMatchesInlineText() {
        assertTrue(TERMS_ROW.matches(Map.of("text-contains", "Terms & Policies")));
        assertFalse(TERMS_ROW.matches(Map.of("text-contains", "Privacy Policy")));
    }

    @Test
    void textContainsTargetsInlineSubstringInsteadOfWholeNodeCentre() {
        assertEquals(425, TERMS_ROW.tapX(Map.of("text-contains", "Terms & Policies")));
        assertEquals(289, TERMS_ROW.centerX());
    }

    @Test
    void exactTextKeepsWholeNodeCentre() {
        assertEquals(TERMS_ROW.centerX(), TERMS_ROW.tapX(Map.of("text", TERMS_ROW.text())));
    }
}
