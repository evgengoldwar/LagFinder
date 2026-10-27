package lagfinder.mixins;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

@LateMixin
public class LateMixinsLoader implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.lagfinder.late.json";
    }

    @Nonnull
    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        List<String> mixins = new ArrayList<>();
        if (loadedMods.contains("appliedenergistics2")) {
            // AE2 networks tick from its own tick handler: timed network by network, machine by machine.
            mixins.add("ae2.MixinGrid");
            mixins.add("ae2.MixinTickManagerCache");
        }
        if (loadedMods.contains("gregtech") && hasClass("gregtech.common.covers.Cover")) {
            // GregTech covers work inside their tile's update: timed cover by cover, by block and side. Older
            // GregTech versions have another cover API, left alone.
            mixins.add("gt.MixinCoverableTileEntity");
        }
        if (loadedMods.contains("gregtech") && hasClass("gregtech.api.metatileentity.BaseMetaPipeEntity")
            && hasClass("gregtech.api.metatileentity.implementations.MTECable")) {
            mixins.add("gt.MixinBaseMetaPipeEntity");
            if (hasClass("gregtech.api.metatileentity.implementations.MTEItemPipe")) {
                mixins.add("gt.MixinMTEItemPipe");
            }
        }
        if (loadedMods.contains("EnderIO") && hasClass("crazypants.enderio.conduit.ConduitNetworkTickHandler")) {
            mixins.add("eio.MixinConduitNetworkTickHandler");
            if (hasClass("crazypants.enderio.conduit.TileConduitBundle")) {
                mixins.add("eio.MixinTileConduitBundle");
            }
        }
        if (loadedMods.contains("OpenComputers") && hasClass("li.cil.oc.server.machine.Machine")) {
            mixins.add("oc.MixinMachine");
        }
        return mixins;
    }

    /** Whether the class is there, without loading it (loading classes this early breaks other transformers). */
    private static boolean hasClass(String name) {
        return LateMixinsLoader.class.getClassLoader()
            .getResource(name.replace('.', '/') + ".class") != null;
    }
}
