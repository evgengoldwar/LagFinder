package lagfinder.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import lagfinder.Config;
import lagfinder.core.LocationKind;
import lagfinder.core.Report;
import lagfinder.core.ReportEntry;

/**
 * Things picked in a report, outlined in the world through walls with a beam up to the sky, so they are found from
 * afar: a block, an entity, a chunk with the block that ticked in it, or a chunk section. An entity is followed
 * where it goes while the client sees it, and stays where it was last seen when it doesn't. The one the crosshair is
 * on (through walls too) is lit up, and the HUD shows its numbers beside the crosshair.
 */
public final class Highlighter {

    public static final Highlighter INSTANCE = new Highlighter();

    /** How far the crosshair finds a highlight (blocks). */
    private static final double REACH = 192;

    /** Colors given to highlights in turn. */
    private static final int[] COLORS = { 0xFFFF5C5C, 0xFFFFB648, 0xFF4C9AFF, 0xFF3FB950, 0xFFB37FEB, 0xFF39C5CF };

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
            if (entity.isDead) {
                return false;
            }
            if (entity instanceof EntityPlayer) {
                // The client knows players by other classes than the server does.
                return entry.label != null && entry.label.equals(entity.getCommandSenderName());
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

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (highlights.isEmpty() || mc.theWorld == null || mc.renderViewEntity == null) {
            hovered = null;
            return;
        }
        expire();
        int dim = mc.theWorld.provider.dimensionId;
        for (Highlight highlight : highlights) {
            follow(highlight, mc.theWorld, event.partialTicks, dim);
        }
        hovered = pick(mc.renderViewEntity, event.partialTicks, dim);
        double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 250.0);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_LINE_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glPushMatrix();
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        for (Highlight highlight : highlights) {
            if (highlight.dim != dim) {
                continue;
            }
            double[] box = box(highlight);
            int rgb = highlight.color & 0xFFFFFF;
            boolean lit = highlight == hovered;
            if (highlight.parts != null) {
                drawParts(highlight.parts, rgb, lit, pulse);
            }
            fillBox(box, rgb, lit ? (int) (80 + 50 * pulse) : (int) (30 + 40 * pulse));
            GL11.glLineWidth(lit ? 4f : 2.5f);
            outlineBox(box, rgb, 255);
            if (lit) {
                // A white edge just outside, so the one looked at stands out among the others.
                double g = 0.04;
                GL11.glLineWidth(1.5f);
                outlineBox(
                    new double[] { box[0] - g, box[1] - g, box[2] - g, box[3] + g, box[4] + g, box[5] + g },
                    0xFFFFFF,
                    (int) (120 + 100 * pulse));
            }
            double cx = (box[0] + box[3]) / 2, cz = (box[2] + box[5]) / 2;
            // A beam up from it, to find it from far away.
            GL11.glLineWidth(4f);
            Tessellator tess = Tessellator.instance;
            tess.startDrawing(GL11.GL_LINES);
            tess.setColorRGBA_I(rgb, (int) (120 + 100 * pulse));
            tess.addVertex(cx, box[4], cz);
            tess.setColorRGBA_I(rgb, 0);
            tess.addVertex(cx, Math.max(box[4] + 64, 256), cz);
            tess.draw();
            if (highlight.kind == LocationKind.CHUNK) {
                // The chunk's border at the height of the block that ticked.
                int chunkX = highlight.x >> 4 << 4, chunkZ = highlight.z >> 4 << 4;
                GL11.glLineWidth(1.5f);
                outlineBox(
                    new double[] { chunkX, highlight.y, chunkZ, chunkX + 16, highlight.y + 1, chunkZ + 16 },
                    rgb,
                    140);
            }
        }
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static final int PART_BATCH = 256;

    private static void drawParts(int[] parts, int rgb, boolean lit, double pulse) {
        Tessellator tess = Tessellator.instance;
        int count = parts.length / 3;
        int fill = lit ? (int) (50 + 40 * pulse) : (int) (20 + 30 * pulse);
        for (int from = 0; from < count; from += PART_BATCH) {
            int to = Math.min(count, from + PART_BATCH);
            tess.startDrawingQuads();
            tess.setColorRGBA_I(rgb, fill);
            for (int i = from; i < to; i++) {
                fillVertices(tess, partBox(parts, i));
            }
            tess.draw();
            GL11.glLineWidth(lit ? 2.5f : 1.5f);
            tess.startDrawing(GL11.GL_LINES);
            tess.setColorRGBA_I(rgb, 200);
            for (int i = from; i < to; i++) {
                outlineVertices(tess, partBox(parts, i));
            }
            tess.draw();
        }
    }

    private static double[] partBox(int[] parts, int index) {
        double e = 0.02;
        int x = parts[index * 3], y = parts[index * 3 + 1], z = parts[index * 3 + 2];
        return new double[] { x - e, y - e, z - e, x + 1 + e, y + 1 + e, z + 1 + e };
    }

    /** Moves an entity's highlight to where the entity is now, if the client sees it. */
    private static void follow(Highlight highlight, World world, float partialTicks, int dim) {
        highlight.followed = null;
        if (highlight.kind != LocationKind.ENTITY || highlight.entry.entityId < 0 || highlight.dim != dim) {
            return;
        }
        Entity entity = world.getEntityByID(highlight.entry.entityId);
        if (entity == null || !highlight.isIt(entity)) {
            return;
        }
        highlight.x = (int) Math.floor(entity.posX);
        highlight.y = (int) Math.floor(entity.posY);
        highlight.z = (int) Math.floor(entity.posZ);
        // Where it is drawn this frame, between its last two ticks, so the box moves smoothly with it.
        double px = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
        double py = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
        double pz = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;
        AxisAlignedBB bb = entity.boundingBox;
        double halfX = Math.max(0.3, (bb.maxX - bb.minX) / 2) + 0.1;
        double halfZ = Math.max(0.3, (bb.maxZ - bb.minZ) / 2) + 0.1;
        double midX = px + (bb.minX + bb.maxX) / 2 - entity.posX, midZ = pz + (bb.minZ + bb.maxZ) / 2 - entity.posZ;
        double bottom = py + bb.minY - entity.posY - 0.05, top = py + bb.maxY - entity.posY + 0.1;
        highlight.followed = new double[] { midX - halfX, bottom, midZ - halfZ, midX + halfX,
            Math.max(top, bottom + 0.6), midZ + halfZ };
    }

    /** The nearest highlight in this world the look goes through, the player standing outside it, or null. */
    private Highlight pick(EntityLivingBase viewer, float partialTicks, int dim) {
        Vec3 eye = viewer.getPosition(partialTicks);
        Vec3 look = viewer.getLook(partialTicks);
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
        double[] origin = { from.xCoord, from.yCoord, from.zCoord };
        double[] dir = { direction.xCoord, direction.yCoord, direction.zCoord };
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

    private static void fillBox(double[] b, int rgb, int alpha) {
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        tess.setColorRGBA_I(rgb, alpha);
        fillVertices(tess, b);
        tess.draw();
    }

    private static void fillVertices(Tessellator tess, double[] b) {
        // Bottom, top, and the four sides.
        quad(tess, b[0], b[1], b[2], b[3], b[1], b[2], b[3], b[1], b[5], b[0], b[1], b[5]);
        quad(tess, b[0], b[4], b[2], b[0], b[4], b[5], b[3], b[4], b[5], b[3], b[4], b[2]);
        quad(tess, b[0], b[1], b[2], b[0], b[4], b[2], b[3], b[4], b[2], b[3], b[1], b[2]);
        quad(tess, b[0], b[1], b[5], b[3], b[1], b[5], b[3], b[4], b[5], b[0], b[4], b[5]);
        quad(tess, b[0], b[1], b[2], b[0], b[1], b[5], b[0], b[4], b[5], b[0], b[4], b[2]);
        quad(tess, b[3], b[1], b[2], b[3], b[4], b[2], b[3], b[4], b[5], b[3], b[1], b[5]);
    }

    private static void quad(Tessellator tess, double... v) {
        for (int i = 0; i < 12; i += 3) {
            tess.addVertex(v[i], v[i + 1], v[i + 2]);
        }
    }

    private static void outlineBox(double[] b, int rgb, int alpha) {
        Tessellator tess = Tessellator.instance;
        tess.startDrawing(GL11.GL_LINES);
        tess.setColorRGBA_I(rgb, alpha);
        outlineVertices(tess, b);
        tess.draw();
    }

    private static void outlineVertices(Tessellator tess, double[] b) {
        double[][] corners = { { b[0], b[2] }, { b[3], b[2] }, { b[3], b[5] }, { b[0], b[5] } };
        for (int i = 0; i < 4; i++) {
            double[] a = corners[i], c = corners[(i + 1) % 4];
            tess.addVertex(a[0], b[1], a[1]);
            tess.addVertex(c[0], b[1], c[1]);
            tess.addVertex(a[0], b[4], a[1]);
            tess.addVertex(c[0], b[4], c[1]);
            tess.addVertex(a[0], b[1], a[1]);
            tess.addVertex(a[0], b[4], a[1]);
        }
    }
}
