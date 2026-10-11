package lagfinder.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The objects the game created during a scan, and who created them: samples of the allocations the JVM made (through
 * Java Flight Recorder), each with the type of the object and the stack it was created on. The class that created it
 * is the nearest one on the stack that isn't Java's or a library's: what {@code new ArrayList} or {@code list.add}
 * allocate goes to the class that called them. Each sample stands for the bytes the thread allocated since the
 * previous one, so the bytes add up to about all the game allocated.
 *
 * <p>
 * Where the JVM has no Flight Recorder (Java 8), the scanned thread's stack is sampled instead with the bytes it
 * allocated in between: who allocates is known roughly, what type of objects is not.
 */
public final class AllocationProfile {

    /** How the allocations were seen: samples of the JVM's own, or the thread's stack with its allocated bytes. */
    public static final int SOURCE_JFR = 0, SOURCE_STACKS = 1;
    /** The name standing for a type that isn't known (sampling stacks). */
    public static final String UNKNOWN_TYPE = "?";

    public int source = SOURCE_JFR;
    /** The thread scanned, e.g. "Server thread". */
    public String thread = "";
    /**
     * Seconds into the scan the allocations are known from: the Flight Recorder takes a moment to start, and the
     * moments before it show nothing. Rates a second are of the time from here on.
     */
    public float recordedFromSec;
    /** Why the Flight Recorder couldn't be used, or null. */
    public String note;
    /** Samples taken in all threads, and in the scanned one. */
    public int samples, targetSamples;
    /** Bytes allocated over the scan, by all threads and by the scanned one (as the samples add up). */
    public long bytes, targetBytes;
    /** Per moment of the graph: megabytes allocated by all threads and by the scanned one. */
    public float[] allMb = new float[0], targetMb = new float[0];

    /** Mods (and Minecraft, Forge, Java...) by index. */
    public final List<String> mods = new ArrayList<>();
    /** The types of the objects created, and the classes that created them. */
    public final List<Group> types = new ArrayList<>(), creators = new ArrayList<>();
    /** Each place objects were created at: a type, by a class, in a method. */
    public final List<Site> sites = new ArrayList<>();

    /** A type of objects, or a class that created objects: the bytes of all its samples. */
    public static final class Group {

        /** As in Java code: "java.lang.String", "int[]". */
        public String name = "";
        /** Index into {@link AllocationProfile#mods}. */
        public int mod;
        public long bytes, targetBytes;
        public int samples, targetSamples;
        /** Megabytes in each moment of the graph, by all threads and by the scanned one; null when not kept. */
        public float[] timeline, targetTimeline;

        public long bytes(boolean target) {
            return target ? targetBytes : bytes;
        }

        public int samples(boolean target) {
            return target ? targetSamples : samples;
        }

        public float[] timeline(boolean target) {
            return target ? targetTimeline : timeline;
        }

        public String simpleName() {
            return simpleName(name);
        }

        public String packageName() {
            return packageName(name);
        }

        public static String simpleName(String name) {
            int dot = name.lastIndexOf('.');
            return dot < 0 ? name : name.substring(dot + 1);
        }

        public static String packageName(String name) {
            int dot = name.lastIndexOf('.');
            return dot < 0 ? "" : name.substring(0, dot);
        }
    }

    /** Objects of a type created by a class in one of its methods, at a line. */
    public static final class Site {

        /** Indexes into {@link AllocationProfile#types} and {@link AllocationProfile#creators}. */
        public int type, creator;
        public String method = "";
        /** The line in the class's source, or -1 when not known. */
        public int line = -1;
        /**
         * What of Java or a library the class called to create them ("java.util.ArrayList.grow"), or "" when it
         * created them itself.
         */
        public String via = "";
        public long bytes, targetBytes;
        public int samples, targetSamples;

        public long bytes(boolean target) {
            return target ? targetBytes : bytes;
        }

        public int samples(boolean target) {
            return target ? targetSamples : samples;
        }
    }

    public String modName(int index) {
        return index >= 0 && index < mods.size() ? mods.get(index) : Describer.UNKNOWN_MOD;
    }

    public int modIndex(String name) {
        return mods.indexOf(name);
    }

    public long bytes(boolean target) {
        return target ? targetBytes : bytes;
    }

    public int samples(boolean target) {
        return target ? targetSamples : samples;
    }

    public float[] timeline(boolean target) {
        return target ? targetMb : allMb;
    }

    /** Whether the types of the objects are known (they aren't when the stacks were sampled). */
    public boolean typesKnown() {
        return source == SOURCE_JFR;
    }

    /** Keeps the first {@code buckets} moments only: the scan ended before its time. */
    void trim(int buckets) {
        if (buckets >= allMb.length) {
            return;
        }
        allMb = Arrays.copyOf(allMb, buckets);
        targetMb = Arrays.copyOf(targetMb, buckets);
        for (List<Group> groups : Arrays.asList(types, creators)) {
            for (Group group : groups) {
                if (group.timeline != null) {
                    group.timeline = Arrays.copyOf(group.timeline, buckets);
                    group.targetTimeline = Arrays.copyOf(group.targetTimeline, buckets);
                }
            }
        }
    }

    // ---------------------------------------------------------------- Saving

    void write(DataOutputStream out) throws IOException {
        out.writeByte(source);
        out.writeUTF(thread == null ? "" : thread);
        out.writeBoolean(note != null);
        if (note != null) {
            out.writeUTF(note.length() > 300 ? note.substring(0, 300) : note);
        }
        out.writeInt(samples);
        out.writeInt(targetSamples);
        out.writeLong(bytes);
        out.writeLong(targetBytes);
        out.writeFloat(recordedFromSec);
        int buckets = allMb.length;
        out.writeShort(buckets);
        for (int i = 0; i < buckets; i++) {
            out.writeFloat(allMb[i]);
            out.writeFloat(targetMb[i]);
        }
        out.writeShort(mods.size());
        for (String mod : mods) {
            out.writeUTF(mod);
        }
        writeGroups(out, types, buckets);
        writeGroups(out, creators, buckets);
        // Method names and what was called repeat a lot: written once, then by number.
        Map<String, Integer> strings = new HashMap<>();
        List<String> table = new ArrayList<>();
        for (Site site : sites) {
            index(site.method, strings, table);
            index(site.via, strings, table);
        }
        out.writeInt(table.size());
        for (String text : table) {
            out.writeUTF(text);
        }
        out.writeInt(sites.size());
        for (Site site : sites) {
            out.writeInt(site.type);
            out.writeInt(site.creator);
            out.writeInt(strings.get(site.method));
            out.writeInt(site.line);
            out.writeInt(strings.get(site.via));
            out.writeLong(site.bytes);
            out.writeLong(site.targetBytes);
            out.writeInt(site.samples);
            out.writeInt(site.targetSamples);
        }
    }

    private static void index(String text, Map<String, Integer> strings, List<String> table) {
        if (!strings.containsKey(text)) {
            strings.put(text, table.size());
            table.add(text);
        }
    }

    private static void writeGroups(DataOutputStream out, List<Group> groups, int buckets) throws IOException {
        out.writeInt(groups.size());
        for (Group group : groups) {
            out.writeUTF(group.name.length() > 1000 ? group.name.substring(0, 1000) : group.name);
            out.writeShort(group.mod);
            out.writeLong(group.bytes);
            out.writeLong(group.targetBytes);
            out.writeInt(group.samples);
            out.writeInt(group.targetSamples);
            boolean timeline = group.timeline != null && group.targetTimeline != null;
            out.writeBoolean(timeline);
            if (timeline) {
                for (int i = 0; i < buckets; i++) {
                    out.writeFloat(i < group.timeline.length ? group.timeline[i] : 0);
                    out.writeFloat(i < group.targetTimeline.length ? group.targetTimeline[i] : 0);
                }
            }
        }
    }

    private static void readGroups(DataInputStream in, List<Group> groups, int buckets) throws IOException {
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            Group group = new Group();
            group.name = in.readUTF();
            group.mod = in.readShort();
            group.bytes = in.readLong();
            group.targetBytes = in.readLong();
            group.samples = in.readInt();
            group.targetSamples = in.readInt();
            if (in.readBoolean()) {
                group.timeline = new float[buckets];
                group.targetTimeline = new float[buckets];
                for (int b = 0; b < buckets; b++) {
                    group.timeline[b] = in.readFloat();
                    group.targetTimeline[b] = in.readFloat();
                }
            }
            groups.add(group);
        }
    }

    static AllocationProfile read(DataInputStream in, int version) throws IOException {
        AllocationProfile profile = new AllocationProfile();
        profile.source = in.readByte();
        profile.thread = in.readUTF();
        if (in.readBoolean()) {
            profile.note = in.readUTF();
        }
        profile.samples = in.readInt();
        profile.targetSamples = in.readInt();
        profile.bytes = in.readLong();
        profile.targetBytes = in.readLong();
        if (version >= 6) {
            profile.recordedFromSec = in.readFloat();
        }
        int buckets = in.readShort();
        profile.allMb = new float[buckets];
        profile.targetMb = new float[buckets];
        for (int i = 0; i < buckets; i++) {
            profile.allMb[i] = in.readFloat();
            profile.targetMb[i] = in.readFloat();
        }
        int mods = in.readShort();
        for (int i = 0; i < mods; i++) {
            profile.mods.add(in.readUTF());
        }
        readGroups(in, profile.types, buckets);
        readGroups(in, profile.creators, buckets);
        int strings = in.readInt();
        String[] table = new String[strings];
        for (int i = 0; i < strings; i++) {
            table[i] = in.readUTF();
        }
        int sites = in.readInt();
        for (int i = 0; i < sites; i++) {
            Site site = new Site();
            site.type = in.readInt();
            site.creator = in.readInt();
            site.method = table[in.readInt()];
            site.line = in.readInt();
            site.via = table[in.readInt()];
            site.bytes = in.readLong();
            site.targetBytes = in.readLong();
            site.samples = in.readInt();
            site.targetSamples = in.readInt();
            profile.sites.add(site);
        }
        return profile;
    }
}
