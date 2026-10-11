package lagfinder.client;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import lagfinder.core.Category;
import lagfinder.core.ReportEntry;
import lagfinder.core.ScanMode;

/** What report lines are called on this client, in its language, and the items they are shown with. */
public final class Names {

    private static final Map<ReportEntry, String> NAMES = new IdentityHashMap<>();
    private static final Map<ReportEntry, Object> ICONS = new IdentityHashMap<>();
    private static final Object NONE = new Object();

    private Names() {}

    /** Forgets the names of the last report (another one is shown). */
    public static void clear() {
        NAMES.clear();
        ICONS.clear();
    }

    public static String category(Category category) {
        return I18n.format("lagfinder.category." + category.id);
    }

    /** The short name of a category in the given mode ("Tile entities" scans ticks or frames). */
    public static String category(Category category, ScanMode mode) {
        String key = "lagfinder.category." + category.id
            + "."
            + mode.name()
                .toLowerCase();
        return StatCollector.canTranslate(key) ? I18n.format(key) : category(category);
    }

    /** The name of the line: a category, a mod, a kind of thing, or for an instance its kind's name. */
    public static String of(ReportEntry entry) {
        if (entry.level == 0) {
            return category(entry.category);
        }
        if (entry.level == 1) {
            return entry.name;
        }
        if (entry.level == 3 && entry.parent != null) {
            return of(entry.parent);
        }
        String name = NAMES.get(entry);
        if (name == null) {
            name = find(entry);
            NAMES.put(entry, name);
        }
        return name;
    }

    private static String find(ReportEntry entry) {
        ItemStack stack = icon(entry);
        if (stack != null) {
            try {
                String name = stack.getDisplayName();
                if (name != null && !name.isEmpty() && !name.contains(".name")) {
                    return name;
                }
            } catch (Throwable ignored) {
                // Fall back to the server's name.
            }
        }
        if (entry.translationKey != null && StatCollector.canTranslate(entry.translationKey)) {
            return StatCollector.translateToLocal(entry.translationKey);
        }
        return entry.name;
    }

    /** The item standing for the line (its own or its kind's), or null. */
    public static ItemStack icon(ReportEntry entry) {
        if (entry.level == 3 && entry.parent != null && entry.itemId < 0) {
            return icon(entry.parent);
        }
        if (entry.itemId < 0) {
            return null;
        }
        Object stack = ICONS.get(entry);
        if (stack == null) {
            Item item = Item.getItemById(entry.itemId);
            stack = item == null ? NONE : new ItemStack(item, 1, entry.itemDamage);
            ICONS.put(entry, stack);
        }
        return stack == NONE ? null : (ItemStack) stack;
    }

    /** Where an instance is, e.g. "120, 64, -35". */
    public static String position(ReportEntry entry) {
        return entry.x + ", " + entry.y + ", " + entry.z;
    }

    /** A time in milliseconds, with as many decimals as it is worth. */
    public static String ms(double ms) {
        if (ms >= 100) {
            return String.format("%.0f ms", ms);
        }
        if (ms >= 10) {
            return String.format("%.1f ms", ms);
        }
        if (ms >= 0.01) {
            return String.format("%.2f ms", ms);
        }
        return String.format("%.0f µs", ms * 1000);
    }

    /** Frames a second lost, e.g. "-12 FPS". */
    public static String fpsLoss(double fps) {
        if (fps < 0.01) {
            return "0 FPS";
        }
        if (fps >= 10) {
            return String.format("-%.0f FPS", fps);
        }
        if (fps >= 1) {
            return String.format("-%.1f FPS", fps);
        }
        return String.format("-%.2f FPS", fps);
    }

    /** Frames a second, e.g. "47 FPS". */
    public static String fps(double fps) {
        return fps >= 10 ? String.format("%.0f FPS", fps) : String.format("%.1f FPS", fps);
    }

    /** Seconds as m:ss. */
    public static String clock(double seconds) {
        int total = (int) Math.max(0, Math.round(seconds));
        return total / 60 + ":" + (total % 60 < 10 ? "0" : "") + total % 60;
    }
}
