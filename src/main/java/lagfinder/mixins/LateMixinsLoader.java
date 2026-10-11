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
        return mixins;
    }
}
