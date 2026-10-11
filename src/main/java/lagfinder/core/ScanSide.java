package lagfinder.core;

/** Where a scan runs: on the server (its ticks), on this client (its frames), or on both at once, a report each. */
public enum ScanSide {

    SERVER,
    CLIENT,
    BOTH;

    public boolean server() {
        return this != CLIENT;
    }

    public boolean client() {
        return this != SERVER;
    }

    /** The side a scan of the given mode runs on. */
    public static ScanSide of(ScanMode mode) {
        return mode == ScanMode.TPS ? SERVER : CLIENT;
    }
}
