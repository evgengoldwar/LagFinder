package lagfinder.client.gui.ui;

/**
 * A number that eases toward where it should be, frame by frame, at the same pace whatever the frame rate: scroll
 * positions, hover highlights, switches. It keeps its own clock, so it only needs to be updated where it is drawn.
 */
public final class Smooth {

    private double value;
    private long lastUpdate;

    public Smooth(double value) {
        this.value = value;
    }

    /**
     * Moves toward {@code target}, about {@code speed} times the distance left a second (higher is faster), and
     * returns where it got to. Close enough, it lands on the target.
     */
    public double update(double target, double speed) {
        long now = System.nanoTime();
        double seconds = lastUpdate == 0 ? 0 : Math.min(0.1, (now - lastUpdate) / 1e9);
        lastUpdate = now;
        value += (target - value) * (1 - Math.exp(-seconds * speed));
        if (Math.abs(target - value) < 0.002 * Math.max(1, Math.abs(target))) {
            value = target;
        }
        return value;
    }

    /** Jumps to the value, without easing. */
    public void set(double value) {
        this.value = value;
    }

    public double get() {
        return value;
    }
}
