package lagfinder.core;

/** What a scan measures: the server's ticks or the client's frames. */
public enum ScanMode {

    TPS,
    FPS;

    /** The best rate there is: 20 ticks a second; frames have no such limit. */
    public float targetRate() {
        return this == TPS ? 20f : 60f;
    }

    /** The time one period may take before the rate drops (ms). */
    public float budgetMs() {
        return this == TPS ? 50f : 1000f / 60f;
    }
}
