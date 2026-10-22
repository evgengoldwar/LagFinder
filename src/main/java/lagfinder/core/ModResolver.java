package lagfinder.core;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import lagfinder.LagFinder;

/**
 * Tells which mod a class, a block or a mod id belongs to. Classes go by the module they were loaded in: each mod's
 * jar is a module of its own, named after its first mod.
 */
public final class ModResolver {

    public static final String MINECRAFT = "Minecraft";
    public static final String FORGE = "NeoForge";
    public static final String JAVA = "Java";
    /** Libraries bundled with the game (Netty, Guava, LWJGL...): no mod's code. */
    public static final String LIBRARIES = "Libraries";

    private static final String[] JAVA_PACKAGES = { "java.", "javax.", "sun.", "jdk.", "com.sun." };
    private static final String[] LIBRARY_PACKAGES = { "com.google.", "io.netty.", "org.lwjgl.", "org.apache.",
        "it.unimi.", "com.mojang.", "org.objectweb.", "org.spongepowered.", "kotlin.", "scala.", "org.slf4j.",
        "cpw.mods.", "com.llamalad7." };

    private static final Map<Class<?>, String> CLASSES = new ConcurrentHashMap<>();
    private static final Map<String, String> CLASS_NAMES = new ConcurrentHashMap<>();
    private static volatile Map<String, String> modules;

    private ModResolver() {}

    /** The mod's name for a mod id ("mekanism" is "Mekanism"). */
    public static String nameOfModId(String modId) {
        if (modId == null || modId.isEmpty() || "minecraft".equals(modId)) {
            return MINECRAFT;
        }
        if ("neoforge".equals(modId)) {
            return FORGE;
        }
        try {
            return ModList.get()
                .getModContainerById(modId)
                .map(
                    container -> container.getModInfo()
                        .getDisplayName())
                .orElse(modId);
        } catch (Throwable t) {
            return modId;
        }
    }

    /** The mod that registered the block, or null if it isn't known. */
    public static String modOfBlock(Block block) {
        if (block == null) {
            return null;
        }
        try {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            return id == null ? null : nameOfModId(id.getNamespace());
        } catch (Throwable t) {
            return null;
        }
    }

    /** The mod the class comes from, {@link Describer#UNKNOWN_MOD} if no mod owns it. */
    public static String modOf(Class<?> type) {
        String mod = CLASSES.get(type);
        if (mod == null) {
            mod = find(type);
            CLASSES.put(type, mod);
        }
        return mod;
    }

    private static String find(Class<?> type) {
        String name = type.getName();
        if (name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")) {
            return MINECRAFT;
        }
        if (name.startsWith("net.neoforged.")) {
            return FORGE;
        }
        if (name.startsWith("lagfinder.")) {
            return LagFinder.NAME;
        }
        try {
            Module module = type.getModule();
            String mod = module != null && module.isNamed() ? modules().get(module.getName()) : null;
            if (mod != null) {
                return mod;
            }
        } catch (Throwable ignored) {
            // Not in a mod's module.
        }
        return Describer.UNKNOWN_MOD;
    }

    /**
     * The mod a class comes from, by its name only (a frame of a sampled stack): by package. Never loads the class, so
     * it is safe on any thread.
     */
    public static String modOfClassName(String name) {
        String mod = CLASS_NAMES.get(name);
        if (mod == null) {
            mod = findByName(name);
            CLASS_NAMES.put(name, mod);
        }
        return mod;
    }

    private static String findByName(String name) {
        int generated = name.indexOf("$$");
        if (generated > 0) {
            name = name.substring(0, generated);
        }
        for (String prefix : JAVA_PACKAGES) {
            if (name.startsWith(prefix)) {
                return JAVA;
            }
        }
        if (name.startsWith("net.minecraft.")) {
            return MINECRAFT;
        }
        if (name.startsWith("net.neoforged.")) {
            return FORGE;
        }
        if (name.startsWith("lagfinder.")) {
            return LagFinder.NAME;
        }
        for (String prefix : LIBRARY_PACKAGES) {
            if (name.startsWith(prefix)) {
                return LIBRARIES;
            }
        }
        return Describer.UNKNOWN_MOD;
    }

    /** Mods' names by the module their jar is loaded as. */
    private static Map<String, String> modules() {
        Map<String, String> map = modules;
        if (map != null) {
            return map;
        }
        map = new HashMap<>();
        for (IModInfo mod : ModList.get()
            .getMods()) {
            try {
                String module = mod.getOwningFile()
                    .moduleName();
                if (module != null && !map.containsKey(module)) {
                    map.put(module, mod.getDisplayName());
                }
            } catch (Throwable ignored) {
                // Left out.
            }
            map.putIfAbsent(mod.getModId(), mod.getDisplayName());
        }
        modules = map;
        return map;
    }
}
