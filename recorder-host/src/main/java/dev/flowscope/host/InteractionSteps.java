package dev.flowscope.host;

/** Converts raw host gestures into durable FlowScope steps. */
public final class InteractionSteps {
    private InteractionSteps() {}

    public static String swipeDirection(int startX, int startY, int endX, int endY) {
        int deltaX = endX - startX;
        int deltaY = endY - startY;
        if (Math.abs(deltaY) >= Math.abs(deltaX)) return deltaY < 0 ? "up" : "down";
        return deltaX < 0 ? "left" : "right";
    }

    public static String scrollStep(int startX, int startY, int endX, int endY) {
        return "step scroll direction=" + swipeDirection(startX, startY, endX, endY);
    }
}
