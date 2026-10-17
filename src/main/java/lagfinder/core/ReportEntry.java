package lagfinder.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One line of a finished report: a category, a mod, a kind of thing or one thing in the world, with its time over the
 * scan and over each bucket of it. Times are milliseconds.
 */
public final class ReportEntry {

    public int level;
    public Category category;
    /** Category id, mod name, type name, or (instances) the type's name again. */
    public String name;
    /** Class or handler behind it, may be null. */
    public String detail;
    /** What an instance is on its own (an item's name, a chunk), may be null. */
    public String label;
    public String translationKey;
    public int itemId = -1, itemDamage;
    public LocationKind kind = LocationKind.NONE;
    public int dim, x, y, z;
    /** For an entity: its id in its world, to follow it where it has gone since; -1 when not known. */
    public int entityId = -1;

    public double totalMs;
    public long calls;
    public float maxMs;
    public float maxAt;
    public int activePeriods;
    public int spikes;
    /** Things of this kind seen in the scan (types), even those the report leaves out. */
    public int instances;
    public float medianMs, p90Ms;
    public double gtActiveMs, gtIdleMs;
    public long gtActiveCalls, gtIdleCalls;
    /** Per bucket: time spent, and the worst single period; null for instances left without a graph. */
    public float[] sum, max;
    public final List<ReportEntry> children = new ArrayList<>();
    public static final int MAX_PARTS = 4096;
    public int[] parts;

    /** Set when read: the line above. */
    public transient ReportEntry parent;

    public boolean hasLocation() {
        return kind != LocationKind.NONE;
    }

    void write(DataOutputStream out) throws IOException {
        out.writeByte(level);
        out.writeByte(category.ordinal());
        out.writeUTF(clip(name));
        writeOptional(out, detail);
        writeOptional(out, label);
        writeOptional(out, translationKey);
        out.writeInt(itemId);
        out.writeShort(itemDamage);
        out.writeByte(kind.ordinal());
        if (kind != LocationKind.NONE) {
            out.writeInt(dim);
            out.writeInt(x);
            out.writeShort(y);
            out.writeInt(z);
            if (kind == LocationKind.ENTITY) {
                out.writeInt(entityId);
            }
        }
        out.writeDouble(totalMs);
        out.writeLong(calls);
        out.writeFloat(maxMs);
        out.writeFloat(maxAt);
        out.writeInt(activePeriods);
        out.writeInt(spikes);
        out.writeInt(instances);
        out.writeBoolean(sum != null);
        if (sum != null) {
            out.writeShort(sum.length);
            for (int i = 0; i < sum.length; i++) {
                out.writeFloat(sum[i]);
                out.writeFloat(max[i]);
            }
        }
        out.writeInt(children.size());
        for (ReportEntry child : children) {
            child.write(out);
        }
        out.writeFloat(medianMs);
        out.writeFloat(p90Ms);
        out.writeDouble(gtActiveMs);
        out.writeDouble(gtIdleMs);
        out.writeLong(gtActiveCalls);
        out.writeLong(gtIdleCalls);
        int partCount = parts == null ? 0 : Math.min(MAX_PARTS, parts.length / 3);
        out.writeShort(partCount);
        for (int i = 0; i < partCount; i++) {
            out.writeInt(parts[i * 3]);
            out.writeShort(parts[i * 3 + 1]);
            out.writeInt(parts[i * 3 + 2]);
        }
    }

    public int partCount() {
        return parts == null ? 0 : parts.length / 3;
    }

    static ReportEntry read(DataInputStream in, ReportEntry parent, int version, int buckets) throws IOException {
        int depth = 0;
        for (ReportEntry ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
            if (++depth > 64) {
                throw new IOException("Report entry tree is too deep");
            }
        }
        ReportEntry entry = new ReportEntry();
        entry.parent = parent;
        entry.level = in.readByte();
        entry.category = Category.values()[ReportInput.index(in.readByte(), Category.values().length)];
        entry.name = in.readUTF();
        entry.detail = readOptional(in);
        entry.label = readOptional(in);
        entry.translationKey = readOptional(in);
        entry.itemId = in.readInt();
        entry.itemDamage = in.readShort();
        entry.kind = LocationKind.values()[ReportInput.index(in.readByte(), LocationKind.values().length)];
        if (entry.kind != LocationKind.NONE) {
            entry.dim = in.readInt();
            entry.x = in.readInt();
            entry.y = in.readShort();
            entry.z = in.readInt();
            if (entry.kind == LocationKind.ENTITY && version >= 14) {
                entry.entityId = in.readInt();
            }
        }
        entry.totalMs = in.readDouble();
        entry.calls = in.readLong();
        entry.maxMs = in.readFloat();
        entry.maxAt = in.readFloat();
        entry.activePeriods = in.readInt();
        entry.spikes = in.readInt();
        entry.instances = in.readInt();
        if (in.readBoolean()) {
            // Not charged to the element budget: it must equal the report's bucket count, which is already bounded.
            // Charging it on every line made large reports (many instances with graphs) unreadable.
            int length = in.readShort();
            if (length != buckets) {
                throw new IOException("Invalid report entry graph");
            }
            entry.sum = new float[length];
            entry.max = new float[length];
            for (int i = 0; i < length; i++) {
                entry.sum[i] = in.readFloat();
                entry.max[i] = in.readFloat();
            }
        }
        int count = ReportInput.count(in, in.readInt(), 200_000);
        for (int i = 0; i < count; i++) {
            entry.children.add(read(in, entry, version, buckets));
        }
        if (version >= 13) {
            entry.medianMs = in.readFloat();
            entry.p90Ms = in.readFloat();
            entry.gtActiveMs = in.readDouble();
            entry.gtIdleMs = in.readDouble();
            entry.gtActiveCalls = in.readLong();
            entry.gtIdleCalls = in.readLong();
        }
        if (version >= 17) {
            int partCount = ReportInput.count(in, in.readShort(), MAX_PARTS);
            if (partCount > 0) {
                entry.parts = new int[partCount * 3];
                for (int i = 0; i < partCount; i++) {
                    entry.parts[i * 3] = in.readInt();
                    entry.parts[i * 3 + 1] = in.readShort();
                    entry.parts[i * 3 + 2] = in.readInt();
                }
            }
        }
        return entry;
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 512 ? text.substring(0, 512) : text;
    }

    private static void writeOptional(DataOutputStream out, String text) throws IOException {
        out.writeBoolean(text != null);
        if (text != null) {
            out.writeUTF(clip(text));
        }
    }

    private static String readOptional(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }
}
