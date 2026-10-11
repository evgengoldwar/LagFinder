package lagfinder.core;

import java.util.ArrayList;
import java.util.List;

/**
 * One method in the call tree of a sampled thread (or, in the list of hot methods, one method over all the places it
 * was called from): the time spent in it with what it called ({@link #totalMs}), the time spent in its own code
 * ({@link #selfMs}), and both split by the mod the time is put down to. Times are milliseconds over the whole scan.
 */
public final class CallNode {

    private static final int[] NO_MODS = new int[0];
    private static final float[] NO_TIMES = new float[0];

    public String className = "";
    public String method = "";
    /** The mod the method's class comes from (index into {@link Profile#mods}). */
    public int mod;
    public double totalMs, selfMs;
    public int samples;
    /** The mods its time is put down to (indexes into {@link Profile#mods}), and the time of each, in all and own. */
    public int[] mods = NO_MODS;
    public float[] modTotalMs = NO_TIMES, modSelfMs = NO_TIMES;
    public final List<CallNode> children = new ArrayList<>();

    /** Set when read: the caller, and how deep it is (the thread's root is 0). */
    public transient CallNode parent;
    public transient int depth;

    /** Its time in all, or the part of it put down to the mod ({@code mod} below 0: all of it). */
    public double total(int mod) {
        if (mod < 0) {
            return totalMs;
        }
        for (int i = 0; i < mods.length; i++) {
            if (mods[i] == mod) {
                return modTotalMs[i];
            }
        }
        return 0;
    }

    /** The time of its own code, or the part of it put down to the mod ({@code mod} below 0: all of it). */
    public double self(int mod) {
        if (mod < 0) {
            return selfMs;
        }
        for (int i = 0; i < mods.length; i++) {
            if (mods[i] == mod) {
                return modSelfMs[i];
            }
        }
        return 0;
    }

    /** The class without its package, e.g. "MinecraftServer". */
    public String simpleClassName() {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    /** The package of its class, e.g. "net.minecraft.server", empty for none. */
    public String packageName() {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    /** "MinecraftServer.tick()". */
    public String shortName() {
        return simpleClassName() + "." + method + "()";
    }

    /** "net.minecraft.server.MinecraftServer.tick()". */
    public String fullName() {
        return className + "." + method + "()";
    }

    void setMods(int[] mods, float[] total, float[] self) {
        this.mods = mods;
        this.modTotalMs = total;
        this.modSelfMs = self;
    }
}
