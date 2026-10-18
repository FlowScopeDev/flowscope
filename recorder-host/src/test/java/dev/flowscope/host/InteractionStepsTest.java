package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InteractionStepsTest {
    @Test
    void convertsDominantSwipeAxisToScrollDirection() {
        assertEquals("step scroll direction=up", InteractionSteps.scrollStep(500, 1800, 520, 400));
        assertEquals("step scroll direction=down", InteractionSteps.scrollStep(500, 400, 480, 1800));
        assertEquals("step scroll direction=left", InteractionSteps.scrollStep(900, 800, 100, 820));
        assertEquals("step scroll direction=right", InteractionSteps.scrollStep(100, 800, 900, 780));
    }
}
