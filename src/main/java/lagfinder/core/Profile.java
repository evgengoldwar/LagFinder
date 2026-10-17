package lagfinder.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the scanned thread was doing, from samples of its stack taken every few milliseconds while it worked (as Spark
 * does): the tree of calls from the thread's start down to the methods that took the time, the methods that took the
 * most time of their own wherever they were called from, and the time each mod is to blame for. A sample is put down
 * to the mod nearest the top of its stack: what a mod's code calls in the game or in Java is that mod's time.
 */
public final class Profile {

    /** Methods kept in the list of hot methods. */
    public static final int HOT_METHODS = 400;

    /** The thread sampled, e.g. "Server thread". */
    public String thread = "";
    /** Milliseconds between samples asked for, and the samples taken while the thread worked. */
    public float intervalMs;
    public int samples;
    /** Mods (and Minecraft, Forge, Java...) by index, and the time put down to each (ms). */
    public final List<String> mods = new ArrayList<>();
    public double[] modMs = new double[0];
    /** The thread's call tree, its root standing for the thread itself. */
    public CallNode root;
    /** Methods by the time spent in their own code, wherever they were called from (no children). */
    public final List<CallNode> methods = new ArrayList<>();

    public double totalMs() {
        return root == null ? 0 : root.totalMs;
    }

    public int modIndex(String name) {
        return mods.indexOf(name);
    }

    public String modName(int index) {
        return index >= 0 && index < mods.size() ? mods.get(index) : Describer.UNKNOWN_MOD;
    }

    // ---------------------------------------------------------------- Saving

    void write(DataOutputStream out) throws IOException {
        // Class and method names repeat a lot: written once, then by number.
        Map<String, Integer> strings = new HashMap<>();
        List<String> table = new ArrayList<>();
        collectStrings(root, strings, table);
        for (CallNode method : methods) {
            collectStrings(method, strings, table);
        }
        out.writeUTF(thread == null ? "" : thread);
        out.writeFloat(intervalMs);
        out.writeInt(samples);
        out.writeShort(mods.size());
        for (int i = 0; i < mods.size(); i++) {
            out.writeUTF(mods.get(i));
            out.writeDouble(modMs[i]);
        }
        out.writeInt(table.size());
        for (String text : table) {
            out.writeUTF(text.length() > 1000 ? text.substring(0, 1000) : text);
        }
        out.writeBoolean(root != null);
        if (root != null) {
            writeNode(out, root, strings, true);
        }
        out.writeInt(methods.size());
        for (CallNode method : methods) {
            writeNode(out, method, strings, false);
        }
    }

    private static void collectStrings(CallNode node, Map<String, Integer> strings, List<String> table) {
        if (node == null) {
            return;
        }
        intern(node.className, strings, table);
        intern(node.method, strings, table);
        for (CallNode child : node.children) {
            collectStrings(child, strings, table);
        }
    }

    private static void intern(String text, Map<String, Integer> strings, List<String> table) {
        if (!strings.containsKey(text)) {
            strings.put(text, table.size());
            table.add(text);
        }
    }

    private static void writeNode(DataOutputStream out, CallNode node, Map<String, Integer> strings, boolean children)
        throws IOException {
        out.writeInt(strings.get(node.className));
        out.writeInt(strings.get(node.method));
        out.writeShort(node.mod);
        out.writeDouble(node.totalMs);
        out.writeDouble(node.selfMs);
        out.writeInt(node.samples);
        out.writeShort(node.mods.length);
        for (int i = 0; i < node.mods.length; i++) {
            out.writeShort(node.mods[i]);
            out.writeFloat(node.modTotalMs[i]);
            out.writeFloat(node.modSelfMs[i]);
        }
        if (children) {
            out.writeInt(node.children.size());
            for (CallNode child : node.children) {
                writeNode(out, child, strings, true);
            }
        }
    }

    static Profile read(DataInputStream in) throws IOException {
        Profile profile = new Profile();
        profile.thread = in.readUTF();
        profile.intervalMs = in.readFloat();
        profile.samples = in.readInt();
        int mods = ReportInput.count(in, in.readShort(), 32767);
        profile.modMs = new double[mods];
        for (int i = 0; i < mods; i++) {
            profile.mods.add(in.readUTF());
            profile.modMs[i] = in.readDouble();
        }
        int count = ReportInput.count(in, in.readInt(), 200_000);
        String[] table = new String[count];
        for (int i = 0; i < count; i++) {
            table[i] = in.readUTF();
        }
        if (in.readBoolean()) {
            profile.root = readNode(in, table, null, true);
        }
        int methods = ReportInput.count(in, in.readInt(), 200_000);
        for (int i = 0; i < methods; i++) {
            profile.methods.add(readNode(in, table, null, false));
        }
        return profile;
    }

    private static CallNode readNode(DataInputStream in, String[] table, CallNode parent, boolean children)
        throws IOException {
        CallNode node = new CallNode();
        node.parent = parent;
        node.depth = parent == null ? 0 : parent.depth + 1;
        if (node.depth > 256) {
            throw new IOException("Report call tree is too deep");
        }
        node.className = table[ReportInput.index(in.readInt(), table.length)];
        node.method = table[ReportInput.index(in.readInt(), table.length)];
        node.mod = in.readShort();
        node.totalMs = in.readDouble();
        node.selfMs = in.readDouble();
        node.samples = in.readInt();
        int mods = ReportInput.count(in, in.readShort(), 32767);
        int[] ids = new int[mods];
        float[] total = new float[mods], self = new float[mods];
        for (int i = 0; i < mods; i++) {
            ids[i] = in.readShort();
            total[i] = in.readFloat();
            self[i] = in.readFloat();
        }
        node.setMods(ids, total, self);
        if (children) {
            int count = ReportInput.count(in, in.readInt(), 200_000);
            for (int i = 0; i < count; i++) {
                node.children.add(readNode(in, table, node, true));
            }
        }
        return node;
    }
}
