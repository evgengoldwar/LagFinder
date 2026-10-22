package lagfinder;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {

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

    private static final ModConfigSpec SERVER_SPEC;
    private static final ModConfigSpec.BooleanValue ALLOW_TELEPORT;
    private static final ModConfigSpec.IntValue MAX_SCAN_SECONDS;
    private static final ModConfigSpec.DoubleValue SPIKE_TPS_MS;
    private static final ModConfigSpec.IntValue LIGHT_SCAN_EVERY;

    private static final ModConfigSpec CLIENT_SPEC;
    private static final ModConfigSpec.IntValue HIGHLIGHT_SECONDS;

    static {
        ModConfigSpec.Builder server = new ModConfigSpec.Builder();
        ALLOW_TELEPORT = server
            .comment("Players with the lagfinder.teleport permission (operators by default) may teleport from reports.")
            .define("allowTeleport", allowTeleport);
        MAX_SCAN_SECONDS = server.comment("Longest tick scan a player may run (seconds).")
            .defineInRange("maxScanSeconds", maxScanSeconds, 5, 3600);
        SPIKE_TPS_MS = server.comment("A tick in which one thing took at least this long (ms) counts as a spike for it.")
            .defineInRange("spikeTpsMs", spikeTpsMs, 0.01, 1000);
        LIGHT_SCAN_EVERY = server.comment(
            "A light tick scan measures one tick in this many and lets the others run untouched: the server barely slows down and averages stay right, but a single short spike can fall between measured ticks.")
            .defineInRange("lightScanEvery", lightScanEvery, 2, 20);
        SERVER_SPEC = server.build();

        ModConfigSpec.Builder client = new ModConfigSpec.Builder();
        HIGHLIGHT_SECONDS = client.comment("How long a highlight stays in the world (seconds).")
            .defineInRange("highlightSeconds", highlightSeconds, 5, 3600);
        CLIENT_SPEC = client.build();
    }

    private Config() {}

    static void register(ModContainer container, IEventBus modBus) {
        container.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
        modBus.addListener(ModConfigEvent.Loading.class, event -> read(event.getConfig()));
        modBus.addListener(ModConfigEvent.Reloading.class, event -> read(event.getConfig()));
    }

    private static void read(ModConfig config) {
        if (config.getSpec() == SERVER_SPEC) {
            allowTeleport = ALLOW_TELEPORT.get();
            maxScanSeconds = MAX_SCAN_SECONDS.get();
            spikeTpsMs = SPIKE_TPS_MS.get()
                .floatValue();
            lightScanEvery = LIGHT_SCAN_EVERY.get();
        } else if (config.getSpec() == CLIENT_SPEC) {
            highlightSeconds = HIGHLIGHT_SECONDS.get();
        }
    }
}
