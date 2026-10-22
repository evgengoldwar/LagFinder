package lagfinder.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalDouble;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import lagfinder.Config;
import lagfinder.Dims;
import lagfinder.core.LocationKind;
import lagfinder.core.Report;
import lagfinder.core.ReportEntry;

/**
 * Things picked in a report, outlined in the world through walls with a beam up to the sky, so they are found from
 * afar: a block, an entity, a chunk with the block that ticked in it. An entity is followed where it goes while the
 * client sees it, and stays where it was last seen when it doesn't. The one the crosshair is on (through walls too) is
 * lit up, and the HUD shows its numbers beside the crosshair.
 */
public final class Highlighter {

    public static final Highlighter INSTANCE = new Highlighter();

    /** How far the crosshair finds a highlight (blocks). */
    private static final double REACH = 192;

    /** Colors given to highlights in turn. */
    private static final int[] COLORS = { 0xFFFF5C5C, 0xFFFFB648, 0xFF4C9AFF, 0xFF3FB950, 0xFFB37FEB, 0xFF39C5CF };

    private static final RenderType FILL = RenderType.create(
        "lagfinder_highlight_fill",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        4096,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setCullState(RenderStateShard.NO_CULL)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .createCompositeState(false));
    private static final RenderType THIN = lines("thin", 1.5);
    private static final RenderType MEDIUM = lines("medium", 2.5);
    private static final RenderType THICK = lines("thick", 4);

    private static RenderType lines(String name, double width) {
        return RenderType.create(
            "lagfinder_highlight_" + name,
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            4096,
            false,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(width)))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShard.NO_CULL)
                .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .createCompositeState(false));
    }

    public static final class Highlight {

        public final int dim;
        /** Where it is; for an entity, where it was last seen. */
        public int x, y, z;
        public final LocationKind kind;
        public final String name;
        public final int color;
        /** The report line it comes from, and its report, for its numbers. */
        public final ReportEntry entry;
        public final Report report;
        final int[] parts;
        final long until;
        /** The followed entity's box this frame, or null when it isn't in sight. */
        double[] followed;

        Highlight(Report report, ReportEntry entry, String name, int color) {
            this.dim = entry.dim;
            this.x = entry.x;
            this.y = entry.y;
            this.z = entry.z;
            this.kind = entry.kind;
            this.name = name;
            this.color = color;
            this.entry = entry;
            this.report = report;
            this.parts = entry.parts;
            this.until = System.currentTimeMillis() + Config.highlightSeconds * 1000L;
        }

        boolean same(ReportEntry other) {
            return other == entry || (other.dim == entry.dim && other.x == entry.x
                && other.y == entry.y
                && other.z == entry.z
                && other.kind == entry.kind);
        }

        /** Whether the entity is the one of the report: same id, and the same kind, not another given its id. */
        boolean isIt(Entity entity) {
            if (entity.isRemoved()) {
                return false;
            }
            if (entity instanceof Player player) {
                return entry.label != null && entry.label.equals(
                    player.getGameProfile()
                        .getName());
            }
            return entity.getClass()
                .getName()
                .equals(entry.detail);
        }
    }

    private final List<Highlight> highlights = new ArrayList<>();
    private int nextColor;
    /** The highlight the crosshair was on in the last frame, or null. */
    private Highlight hovered;

    private Highlighter() {}

    /** The highlight the crosshair is on, or null. */
    public Highlight hovered() {
        return hovered;
    }

    public List<Highlight> all() {
        expire();
        return highlights;
    }

    public boolean isHighlighted(ReportEntry entry) {
        for (Highlight highlight : all()) {
            if (highlight.same(entry)) {
                return true;
            }
        }
        return false;
    }

    /** Highlights the instance of the report, or takes its highlight away if it has one. */
    public void toggle(Report report, ReportEntry entry) {
        if (!entry.hasLocation()) {
            return;
        }
        for (Iterator<Highlight> it = highlights.iterator(); it.hasNext();) {
            if (it.next()
                .same(entry)) {
                it.remove();
                return;
            }
        }
        highlights.add(new Highlight(report, entry, Names.of(entry), COLORS[nextColor++ % COLORS.length]));
    }

    public void clear() {
        highlights.clear();
        hovered = null;
    }

    private void expire() {
        long now = System.currentTimeMillis();
        highlights.removeIf(highlight -> highlight.until < now);
    }

    public void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Entity viewer = mc.getCameraEntity();
        if (highlights.isEmpty() || mc.level == null || viewer == null) {
            hovered = null;
            return;
        }
        expire();
        float partialTicks = event.getPartialTick()
            .getGameTimeDeltaPartialTick(true);
        int dim = Dims.id(mc.level);
        for (Highlight highlight : highlights) {
            follow(highlight, mc.level, partialTicks, dim);
        }
        hovered = pick(viewer, partialTicks, dim);
        double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 250.0);
        Vec3 camera = event.getCamera()
            .getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        PoseStack.Pose last = pose.last();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers()
            .bufferSource();
        for (Highlight highlight : highlights) {
            if (highlight.dim != dim) {
                continue;
            }
            double[] box = box(highlight);
            int rgb = highlight.color & 0xFFFFFF;
            boolean lit = highlight == hovered;
            if (highlight.parts != null) {
                drawParts(buffers, last, highlight.parts, rgb, lit, pulse);
            }
            fillBox(buffers.getBuffer(FILL), last, box, rgb, lit ? (int) (80 + 50 * pulse) : (int) (30 + 40 * pulse));
            outlineBox(buffers.getBuffer(lit ? THICK : MEDIUM), last, box, rgb, 255);
            if (lit) {
                double g = 0.04;
                outlineBox(
                    buffers.getBuffer(THIN),
                    last,
                    new double[] { box[0] - g, box[1] - g, box[2] - g, box[3] + g, box[4] + g, box[5] + g },
                    0xFFFFFF,
                    (int) (120 + 100 * pulse));
            }
            double cx = (box[0] + box[3]) / 2, cz = (box[2] + box[5]) / 2;
            VertexConsumer beam = buffers.getBuffer(THICK);
            double topY = Math.max(box[4] + 64, mc.level.getMaxBuildHeight());
            line(beam, last, cx, box[4], cz, cx, topY, cz, rgb, (int) (120 + 100 * pulse), 0);
            if (highlight.kind == LocationKind.CHUNK) {
                int chunkX = highlight.x >> 4 << 4, chunkZ = highlight.z >> 4 << 4;
                outlineBox(
                    buffers.getBuffer(THIN),
                    last,
                    new double[] { chunkX, highlight.y, chunkZ, chunkX + 16, highlight.y + 1, chunkZ + 16 },
                    rgb,
                    140);
            }
        }
        buffers.endBatch(FILL);
        buffers.endBatch(THIN);
        buffers.endBatch(MEDIUM);
        buffers.endBatch(THICK);
        pose.popPose();
    }

    private static void drawParts(MultiBufferSource.BufferSource buffers, PoseStack.Pose pose, int[] parts, int rgb,
        boolean lit, double pulse) {
        int count = parts.length / 3;
        int fill = lit ? (int) (50 + 40 * pulse) : (int) (20 + 30 * pulse);
        VertexConsumer quads = buffers.getBuffer(FILL);
        for (int i = 0; i < count; i++) {
            fillBox(quads, pose, partBox(parts, i), rgb, fill);
        }
        VertexConsumer lines = buffers.getBuffer(lit ? MEDIUM : THIN);
        for (int i = 0; i < count; i++) {
            outlineBox(lines, pose, partBox(parts, i), rgb, 200);
        }
    }

    private static double[] partBox(int[] parts, int index) {
        double e = 0.02;
        int x = parts[index * 3], y = parts[index * 3 + 1], z = parts[index * 3 + 2];
        return new double[] { x - e, y - e, z - e, x + 1 + e, y + 1 + e, z + 1 + e };
    }

    /** Moves an entity's highlight to where the entity is now, if the client sees it. */
    private static void follow(Highlight highlight, Level world, float partialTicks, int dim) {
        highlight.followed = null;
        if (highlight.kind != LocationKind.ENTITY || highlight.entry.entityId < 0 || highlight.dim != dim) {
            return;
        }
        Entity entity = world.getEntity(highlight.entry.entityId);
        if (entity == null || !highlight.isIt(entity)) {
            return;
        }
        highlight.x = entity.getBlockX();
        highlight.y = entity.getBlockY();
        highlight.z = entity.getBlockZ();
        Vec3 at = entity.getPosition(partialTicks);
        AABB bb = entity.getBoundingBox();
        double halfX = Math.max(0.3, bb.getXsize() / 2) + 0.1;
        double halfZ = Math.max(0.3, bb.getZsize() / 2) + 0.1;
        double midX = at.x + (bb.minX + bb.maxX) / 2 - entity.getX();
        double midZ = at.z + (bb.minZ + bb.maxZ) / 2 - entity.getZ();
        double bottom = at.y + bb.minY - entity.getY() - 0.05, top = at.y + bb.maxY - entity.getY() + 0.1;
        highlight.followed = new double[] { midX - halfX, bottom, midZ - halfZ, midX + halfX,
            Math.max(top, bottom + 0.6), midZ + halfZ };
    }

    /** The nearest highlight in this world the look goes through, the player standing outside it, or null. */
    private Highlight pick(Entity viewer, float partialTicks, int dim) {
        Vec3 eye = viewer.getEyePosition(partialTicks);
        Vec3 look = viewer.getViewVector(partialTicks);
        Highlight best = null;
        double nearest = REACH;
        for (Highlight highlight : highlights) {
            if (highlight.dim != dim) {
                continue;
            }
            double hit = hit(box(highlight), eye, look);
            if (hit >= 0 && hit < nearest) {
                nearest = hit;
                best = highlight;
            }
            int parts = highlight.parts == null ? 0 : highlight.parts.length / 3;
            for (int i = 0; i < parts; i++) {
                double partHit = hit(partBox(highlight.parts, i), eye, look);
                if (partHit >= 0 && partHit < nearest) {
                    nearest = partHit;
                    best = highlight;
                }
            }
        }
        return best;
    }

    /** How far along the ray it enters the box, or -1 when it misses it or starts inside it. */
    private static double hit(double[] b, Vec3 from, Vec3 direction) {
        double[] origin = { from.x, from.y, from.z };
        double[] dir = { direction.x, direction.y, direction.z };
        double enter = Double.NEGATIVE_INFINITY, exit = Double.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            double min = b[axis], max = b[axis + 3];
            if (Math.abs(dir[axis]) < 1e-9) {
                if (origin[axis] < min || origin[axis] > max) {
                    return -1;
                }
                continue;
            }
            double t0 = (min - origin[axis]) / dir[axis], t1 = (max - origin[axis]) / dir[axis];
            enter = Math.max(enter, Math.min(t0, t1));
            exit = Math.min(exit, Math.max(t0, t1));
        }
        return enter <= exit && enter >= 0 ? enter : -1;
    }

    private static double[] box(Highlight highlight) {
        double e = 0.02;
        switch (highlight.kind) {
            case ENTITY:
                if (highlight.followed != null) {
                    return highlight.followed;
                }
                return new double[] { highlight.x - 0.1, highlight.y, highlight.z - 0.1, highlight.x + 1.1,
                    highlight.y + 2, highlight.z + 1.1 };
            case SECTION:
                return new double[] { highlight.x, highlight.y, highlight.z, highlight.x + 16, highlight.y + 16,
                    highlight.z + 16 };
            default:
                return new double[] { highlight.x - e, highlight.y - e, highlight.z - e, highlight.x + 1 + e,
                    highlight.y + 1 + e, highlight.z + 1 + e };
        }
    }

    private static void fillBox(VertexConsumer buffer, PoseStack.Pose pose, double[] b, int rgb, int alpha) {
        int color = alpha << 24 | rgb;
        quad(buffer, pose, color, b[0], b[1], b[2], b[3], b[1], b[2], b[3], b[1], b[5], b[0], b[1], b[5]);
        quad(buffer, pose, color, b[0], b[4], b[2], b[0], b[4], b[5], b[3], b[4], b[5], b[3], b[4], b[2]);
        quad(buffer, pose, color, b[0], b[1], b[2], b[0], b[4], b[2], b[3], b[4], b[2], b[3], b[1], b[2]);
        quad(buffer, pose, color, b[0], b[1], b[5], b[3], b[1], b[5], b[3], b[4], b[5], b[0], b[4], b[5]);
        quad(buffer, pose, color, b[0], b[1], b[2], b[0], b[1], b[5], b[0], b[4], b[5], b[0], b[4], b[2]);
        quad(buffer, pose, color, b[3], b[1], b[2], b[3], b[4], b[2], b[3], b[4], b[5], b[3], b[1], b[5]);
    }

    private static void quad(VertexConsumer buffer, PoseStack.Pose pose, int color, double... v) {
        for (int i = 0; i < 12; i += 3) {
            buffer.addVertex(pose, (float) v[i], (float) v[i + 1], (float) v[i + 2])
                .setColor(color);
        }
    }

    private static void outlineBox(VertexConsumer buffer, PoseStack.Pose pose, double[] b, int rgb, int alpha) {
        double[][] corners = { { b[0], b[2] }, { b[3], b[2] }, { b[3], b[5] }, { b[0], b[5] } };
        for (int i = 0; i < 4; i++) {
            double[] a = corners[i], c = corners[(i + 1) % 4];
            line(buffer, pose, a[0], b[1], a[1], c[0], b[1], c[1], rgb, alpha, alpha);
            line(buffer, pose, a[0], b[4], a[1], c[0], b[4], c[1], rgb, alpha, alpha);
            line(buffer, pose, a[0], b[1], a[1], a[0], b[4], a[1], rgb, alpha, alpha);
        }
    }

    private static void line(VertexConsumer buffer, PoseStack.Pose pose, double x0, double y0, double z0, double x1,
        double y1, double z1, int rgb, int alpha0, int alpha1) {
        float nx = (float) (x1 - x0), ny = (float) (y1 - y0), nz = (float) (z1 - z0);
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-6f) {
            return;
        }
        nx /= length;
        ny /= length;
        nz /= length;
        buffer.addVertex(pose, (float) x0, (float) y0, (float) z0)
            .setColor(alpha0 << 24 | rgb)
            .setNormal(pose, nx, ny, nz);
        buffer.addVertex(pose, (float) x1, (float) y1, (float) z1)
            .setColor(alpha1 << 24 | rgb)
            .setNormal(pose, nx, ny, nz);
    }
}
