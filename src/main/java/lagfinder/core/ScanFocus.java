package lagfinder.core;

/**
 * What a scan is for, on top of the side it runs on ({@link ScanMode}): the lag of the things in the world, the
 * methods the thread spends its time in (sampled, like Spark), the objects in the Java heap counted by class, or the
 * objects being created and the classes creating them.
 */
public enum ScanFocus {

    LAG,
    METHODS,
    OBJECTS,
    ALLOCATIONS,
    STEADY;

    public static ScanFocus of(int ordinal) {
        return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : LAG;
    }
}
