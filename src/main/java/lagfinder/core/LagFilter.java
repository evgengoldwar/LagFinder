package lagfinder.core;

import java.util.Iterator;

/**
 * What a tick scan leaves out of its report: everything that took less than so many milliseconds a tick, on average
 * or at its worst tick. It goes down to the kinds of things: a kind kept keeps all of its things, so they can all be
 * seen as in a scan with no filter. The categories stay, even when nothing in them is left.
 */
public final class LagFilter {

    public enum By {
        AVERAGE,
        PEAK
    }

    public final By by;
    /** The least time kept (ms). */
    public final double minMs;

    public LagFilter(By by, double minMs) {
        this.by = by;
        this.minMs = minMs;
    }

    /** Takes out of the report what took less than the least time. */
    public void apply(Report report) {
        for (ReportEntry category : report.categories) {
            prune(report, category);
        }
    }

    private void prune(Report report, ReportEntry entry) {
        if (entry.level >= 2) {
            return;
        }
        for (Iterator<ReportEntry> it = entry.children.iterator(); it.hasNext();) {
            ReportEntry child = it.next();
            if (!keeps(report, child)) {
                // What is under it took no more than it did.
                it.remove();
            } else {
                prune(report, child);
            }
        }
    }

    private boolean keeps(Report report, ReportEntry entry) {
        double ms = by == By.PEAK ? entry.maxMs : report.perPeriod(entry);
        return ms >= minMs;
    }
}
