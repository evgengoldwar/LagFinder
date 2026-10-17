package lagfinder;

import java.io.File;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

public class Config {

    public static boolean allowTeleport = true;
    /** Longest scan a player may ask for (seconds). */
    public static int maxScanSeconds = 600;
    /** A tick (or frame) in which one thing took at least this long counts as a spike for it (ms). */
    public static float spikeTpsMs = 2.5f;
    public static float spikeFpsMs = 1.0f;
    /** A light tick scan measures one tick in this many; the others run without measuring. */
    public static int lightScanEvery = 4;
    /** How often a Methods scan samples the scanned thread's stack (ms). */
    public static int sampleIntervalMs = 5;
    /** How many times an Objects scan counts the objects in the heap by class, only those in use. */
    public static int heapSnapshots = 4;
    public static boolean heapLiveOnly = true;
    /** How many allocations a second the Flight Recorder takes samples of at most, in an allocations scan. */
    public static int allocSampleRate = 500;
    /** How long a highlight stays in the world (seconds). */
    public static int highlightSeconds = 120;
    public static int worldMsRadius = 16;
    public static int worldMsMaxLabels = 36;
    public static float worldMsMinMs = 0.01f;

    /** The file the settings were read from, to write the ones changed in the game back to it. */
    private static Configuration configuration;

    public static void synchronizeConfiguration(File configFile) {
        configuration = new Configuration(configFile);

        allowTeleport = configuration.getBoolean(
            "allowTeleport",
            "server",
            allowTeleport,
            "Operators and single player owners may teleport from reports, even where everyone may scan.");
        maxScanSeconds = configuration
            .getInt("maxScanSeconds", "server", maxScanSeconds, 5, 3600, "Longest tick scan a player may run.");
        spikeTpsMs = configuration.getFloat(
            "spikeTpsMs",
            "scan",
            spikeTpsMs,
            0.01f,
            1000f,
            "A tick in which one thing took at least this long (ms) counts as a spike for it.");
        spikeFpsMs = configuration.getFloat(
            "spikeFpsMs",
            "scan",
            spikeFpsMs,
            0.01f,
            1000f,
            "A frame in which one thing took at least this long (ms) counts as a spike for it.");
        lightScanEvery = configuration.getInt(
            "lightScanEvery",
            "scan",
            lightScanEvery,
            2,
            20,
            "A light tick scan measures one tick in this many and lets the others run untouched: the server barely slows down and averages stay right, but a single short spike can fall between measured ticks.");
        sampleIntervalMs = configuration.getInt(
            "sampleIntervalMs",
            "scan",
            sampleIntervalMs,
            1,
            100,
            "Milliseconds between samples of the stack. Lower is more precise but costs more.");
        heapSnapshots = configuration.getInt(
            "heapSnapshots",
            "memory",
            heapSnapshots,
            0,
            20,
            "How many times the objects in the heap are counted by class along an Objects scan (the first at its start, the last near its end; at least 2). Each count stops the game for a moment (seconds on a big heap).");
        heapLiveOnly = configuration.getBoolean(
            "heapLiveOnly",
            "memory",
            heapLiveOnly,
            "Count only the objects still in use: a full garbage collection is made before each count, which takes longer. Off counts garbage too.");
        allocSampleRate = configuration.getInt(
            "allocSampleRate",
            "memory",
            allocSampleRate,
            20,
            10000,
            "How many allocations a second Java's Flight Recorder takes samples of at most along an Allocations scan (Java 16 and later). More is more precise but costs more.");
        highlightSeconds = configuration.getInt(
            "highlightSeconds",
            "client",
            highlightSeconds,
            5,
            3600,
            "How long a highlight stays in the world.");

        worldMsRadius = configuration.getInt(
            "worldMsRadius",
            "client",
            worldMsRadius,
            4,
            48,
            "How far world millisecond labels are drawn, in blocks.");
        worldMsMaxLabels = configuration.getInt(
            "worldMsMaxLabels",
            "client",
            worldMsMaxLabels,
            1,
            96,
            "Maximum nearby machine time labels shown in the world.");
        worldMsMinMs = configuration.getFloat(
            "worldMsMinMs",
            "client",
            worldMsMinMs,
            0f,
            10f,
            "Minimum average milliseconds per tick for a nearby label. The block under the crosshair is always shown if measured.");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    /** Writes the settings as they are now to the file (changed in the settings tab). */
    public static void save() {
        if (configuration == null) {
            return;
        }
        put("server", "allowTeleport", allowTeleport);
        put("server", "maxScanSeconds", maxScanSeconds);
        put("scan", "spikeTpsMs", spikeTpsMs);
        put("scan", "spikeFpsMs", spikeFpsMs);
        put("scan", "sampleIntervalMs", sampleIntervalMs);
        put("memory", "heapSnapshots", heapSnapshots);
        put("memory", "heapLiveOnly", heapLiveOnly);
        put("memory", "allocSampleRate", allocSampleRate);
        put("client", "highlightSeconds", highlightSeconds);
        configuration.save();
    }

    private static void put(String category, String key, Object value) {
        if (!configuration.hasCategory(category)) {
            return;
        }
        Property property = configuration.getCategory(category)
            .get(key);
        if (property != null) {
            property.set(String.valueOf(value));
        }
    }
}
