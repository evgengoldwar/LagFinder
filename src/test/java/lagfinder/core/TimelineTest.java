package lagfinder.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TimelineTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    public void spanSumsTheSecondsInIt() {
        Timeline timeline = new Timeline(10);
        for (int s = 0; s < 3; s++) {
            for (int i = 0; i < 20 - s; i++) {
                timeline.add(s * SECOND + i * 50_000_000L, 10 + s);
            }
            timeline.cover(s * SECOND, s * SECOND + SECOND - 1);
        }
        Timeline.Span span = timeline.span(0, 2);
        assertEquals(3, span.seconds);
        assertEquals(57, span.count);
        assertEquals(19, span.perSecond(), 1e-9);
        assertEquals(18, span.fewest);
        assertEquals(20, span.most);
        assertEquals(10, span.low, 1e-9);
        assertEquals(12, span.high, 1e-9);
    }

    @Test
    public void aSecondRunThroughWithoutValuesCountsAsNone() {
        Timeline timeline = new Timeline(10);
        // One tick of three seconds: the server ran, but finished a single tick.
        timeline.add(3 * SECOND, 3000);
        timeline.cover(0, 3 * SECOND);
        Timeline.Span span = timeline.span(0, 3);
        assertEquals(4, span.seconds);
        assertEquals(0, span.fewest);
        assertEquals(0.25, span.perSecond(), 1e-9);
    }

    @Test
    public void secondsNotRunThroughAreLeftOut() {
        Timeline timeline = new Timeline(10);
        timeline.add(SECOND, 5);
        assertTrue(
            timeline.span(0, 0)
                .isEmpty());
        assertEquals(1, timeline.span(0, 5).seconds);
    }

    @Test
    public void oldSecondsAreForgotten() {
        Timeline timeline = new Timeline(10);
        timeline.add(SECOND, 5);
        timeline.add(11 * SECOND, 7);
        Timeline.Span span = timeline.span(0, 11);
        assertEquals(1, span.seconds);
        assertEquals(7, span.high, 1e-9);
        assertFalse(
            timeline.span(1, 1)
                .hasValues());
    }

    @Test
    public void clearForgetsAll() {
        Timeline timeline = new Timeline(10);
        timeline.add(SECOND, 5);
        timeline.clear();
        assertTrue(
            timeline.span(0, 5)
                .isEmpty());
    }
}
