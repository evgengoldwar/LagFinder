package lagfinder.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import lagfinder.Config;
import lagfinder.core.Report;
import lagfinder.core.ScanMode;

public final class WorldMsOverlay {

    public static final WorldMsOverlay INSTANCE = new WorldMsOverlay();

    private final WorldMsIndex index = new WorldMsIndex();
    private Report indexedReport;
    private List<WorldMsIndex.BlockTime> nearby = Collections.emptyList();
    private long refreshAt;
    private Report staleReport;
    private final Map<WorldMsIndex.BlockTime, List<String>> shortCache = new IdentityHashMap<>();
    private final Map<WorldMsIndex.BlockTime, List<String>> detailCache = new IdentityHashMap<>();
    private int dimension = Integer.MIN_VALUE;
    private boolean enabled;

    private WorldMsOverlay() {}

    public void toggle() {
        enabled = !enabled;
        refreshAt = 0;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return;
        }
        String message = I18n.format(
            enabled ? "lagfinder.world_ms.enabled" : "lagfinder.world_ms.disabled",
            GameSettings.getKeyDisplayString(KeyHandler.WORLD_MS.getKeyCode()));
        if (enabled && !hasTickReport(ClientScanner.INSTANCE.report())) {
            message += " " + I18n.format("lagfinder.world_ms.no_report");
        }
        mc.thePlayer.addChatMessage(new ChatComponentText("[LagFinder] " + message));
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        ClientScanner.INSTANCE.schedule(() -> {
            staleReport = ClientScanner.INSTANCE.report();
            reset();
        });
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        ClientScanner.INSTANCE.schedule(() -> {
            staleReport = ClientScanner.INSTANCE.report();
            reset();
        });
    }

    private void reset() {
        enabled = false;
        indexedReport = null;
        index.build(null);
        nearby = Collections.emptyList();
        shortCache.clear();
        detailCache.clear();
        dimension = Integer.MIN_VALUE;
        refreshAt = 0;
    }

    private boolean hasTickReport(Report report) {
        return report != null && report != staleReport && report.mode == ScanMode.TPS && report.periods > 0;
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null
            || mc.renderViewEntity == null
            || mc.currentScreen != null
            || mc.gameSettings.showDebugInfo) {
            return;
        }
        Report report = ClientScanner.INSTANCE.report();
        if (!hasTickReport(report)) {
            return;
        }
        if (indexedReport != report) {
            index.build(report);
            shortCache.clear();
            detailCache.clear();
            indexedReport = report;
            refreshAt = 0;
        }
        int dim = mc.theWorld.provider.dimensionId;
        long now = System.currentTimeMillis();
        if (refreshAt <= now || dimension != dim) {
            dimension = dim;
            refreshAt = now + 300;
            nearby = index.nearby(
                dim,
                mc.thePlayer.posX,
                mc.thePlayer.posY,
                mc.thePlayer.posZ,
                Config.worldMsRadius,
                Math.min(256, Config.worldMsMaxLabels * 4),
                Config.worldMsMinMs);
        }
        WorldMsIndex.BlockTime focused = focusedBlock(mc, dim);
        MovingObjectPosition hit = mc.objectMouseOver;
        boolean missing = false;
        if (focused == null && hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            int x = hit.blockX, y = hit.blockY, z = hit.blockZ;
            double dx = x + 0.5 - mc.thePlayer.posX;
            double dy = y + 0.5 - mc.thePlayer.posY;
            double dz = z + 0.5 - mc.thePlayer.posZ;
            missing = dx * dx + dy * dy + dz * dz <= Config.worldMsRadius * Config.worldMsRadius
                && mc.theWorld.getTileEntity(x, y, z) != null;
        }
        Entity camera = mc.renderViewEntity;
        double yaw = Math
            .toRadians(camera.prevRotationYaw + (camera.rotationYaw - camera.prevRotationYaw) * event.partialTicks);
        double pitch = Math.toRadians(
            camera.prevRotationPitch + (camera.rotationPitch - camera.prevRotationPitch) * event.partialTicks);
        double cosPitch = Math.cos(pitch);
        Vec3 view = Vec3.createVectorHelper(-Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch);
        double eyeX = camera.prevPosX + (camera.posX - camera.prevPosX) * event.partialTicks;
        double eyeY = camera.prevPosY + (camera.posY - camera.prevPosY) * event.partialTicks + camera.getEyeHeight();
        double eyeZ = camera.prevPosZ + (camera.posZ - camera.prevPosZ) * event.partialTicks;
        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        try {
            int shown = 0;
            for (WorldMsIndex.BlockTime block : nearby) {
                if (block == focused || !present(mc, block) || !inView(block, eyeX, eyeY, eyeZ, view)) {
                    continue;
                }
                draw(
                    mc.fontRenderer,
                    block.x,
                    block.y,
                    block.z,
                    shortLines(block),
                    false,
                    severity(block.totalAverage()));
                if (++shown >= Config.worldMsMaxLabels) {
                    break;
                }
            }
            if (focused != null && present(mc, focused)) {
                draw(
                    mc.fontRenderer,
                    focused.x,
                    focused.y,
                    focused.z,
                    detailLines(focused),
                    true,
                    severity(focused.totalAverage()));
            } else if (missing && hit != null) {
                draw(
                    mc.fontRenderer,
                    hit.blockX,
                    hit.blockY,
                    hit.blockZ,
                    Collections.singletonList(I18n.format("lagfinder.world_ms.not_measured")),
                    true,
                    0xFF8E9EB2);
            }
        } finally {
            GL11.glPopAttrib();
            GL11.glColor4f(1f, 1f, 1f, 1f);
        }
    }

    private WorldMsIndex.BlockTime focusedBlock(Minecraft mc, int dim) {
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return null;
        }
        WorldMsIndex.BlockTime block = index.at(dim, hit.blockX, hit.blockY, hit.blockZ);
        return block != null && block.distanceSquared(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ)
            <= Config.worldMsRadius * Config.worldMsRadius ? block : null;
    }

    private boolean present(Minecraft mc, WorldMsIndex.BlockTime block) {
        return mc.theWorld.getChunkProvider()
            .chunkExists(block.x >> 4, block.z >> 4) && mc.theWorld.getBlock(block.x, block.y, block.z) != Blocks.air;
    }

    private static boolean inView(WorldMsIndex.BlockTime block, double x, double y, double z, Vec3 look) {
        double dx = block.x + 0.5 - x;
        double dy = block.y + 1.0 - y;
        double dz = block.z + 0.5 - z;
        return dx * look.xCoord + dy * look.yCoord + dz * look.zCoord > 0;
    }

    private List<String> shortLines(WorldMsIndex.BlockTime block) {
        List<String> cached = shortCache.get(block);
        if (cached != null) {
            return cached;
        }
        List<String> lines = new ArrayList<>(2);
        if (block.tileEntry != null) {
            lines.add(I18n.format("lagfinder.world_ms.short_tile", value(block.tileAverage)));
        }
        if (block.aeEntry != null) {
            lines.add(I18n.format("lagfinder.world_ms.short_ae", value(block.aeAverage)));
        }
        shortCache.put(block, lines);
        return lines;
    }

    private List<String> detailLines(WorldMsIndex.BlockTime block) {
        List<String> cached = detailCache.get(block);
        if (cached != null) {
            return cached;
        }
        List<String> lines = new ArrayList<>(4);
        lines.add(block.name());
        if (block.tileEntry != null) {
            lines.add(I18n.format("lagfinder.world_ms.detail_tile", value(block.tileAverage), value(block.tilePeak)));
        }
        if (block.aeEntry != null) {
            lines.add(I18n.format("lagfinder.world_ms.detail_ae", value(block.aeAverage), value(block.aePeak)));
        }
        lines.add(I18n.format("lagfinder.world_ms.snapshot"));
        detailCache.put(block, lines);
        return lines;
    }

    private static String value(double ms) {
        if (ms < 0.01) {
            return String.format(Locale.ROOT, "%.2f \u00b5s", ms * 1000.0);
        }
        if (ms < 1) {
            return String.format(Locale.ROOT, "%.3f ms", ms);
        }
        return String.format(Locale.ROOT, "%.2f ms", ms);
    }

    private static int severity(double averageMs) {
        return averageMs >= 1 ? 0xFFF16D6D : averageMs >= 0.2 ? 0xFFFFC068 : 0xFF7BBD8B;
    }

    private static void draw(FontRenderer font, int x, int y, int z, List<String> lines, boolean focused, int accent) {
        if (lines.isEmpty()) {
            return;
        }
        List<String> display = new ArrayList<>(lines.size());
        int width = 0;
        for (String line : lines) {
            String clipped = font.trimStringToWidth(line, 310);
            display.add(clipped);
            width = Math.max(width, font.getStringWidth(clipped));
        }
        int lineHeight = 11;
        int height = lines.size() * lineHeight + 5;
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(
                x + 0.5 - RenderManager.renderPosX,
                y + 1.25 - RenderManager.renderPosY,
                z + 0.5 - RenderManager.renderPosZ);
            GL11.glRotatef(-RenderManager.instance.playerViewY, 0f, 1f, 0f);
            GL11.glRotatef(RenderManager.instance.playerViewX, 1f, 0f, 0f);
            float scale = focused ? 0.018f : 0.014f;
            GL11.glScalef(-scale, -scale, scale);
            if (focused) {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            } else {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
            }
            rect(-width / 2 - 6, -height, width / 2 + 6, 1, focused ? 0xD609121C : 0xB609121C);
            rect(-width / 2 - 6, -height, -width / 2 - 4, 1, accent);
            for (int i = 0; i < lines.size(); i++) {
                String line = display.get(i);
                int color = focused && i == 0 ? 0xFFD0E8FF : i == lines.size() - 1 && focused ? 0xFF8E9EB2 : 0xFFFFFFFF;
                font.drawStringWithShadow(line, -font.getStringWidth(line) / 2, -height + 4 + i * lineHeight, color);
            }
        } finally {
            GL11.glPopMatrix();
        }
    }

    private static void rect(int x0, int y0, int x1, int y1, int argb) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA_I(argb & 0xFFFFFF, argb >>> 24);
        tessellator.addVertex(x0, y1, 0);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x1, y0, 0);
        tessellator.addVertex(x0, y0, 0);
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }
}
