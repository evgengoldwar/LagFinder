package lagfinder.mixins;

import java.util.List;
import java.util.Set;

import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;

public final class TimingMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        MixinExtrasBootstrap.init();
        IMixinTransformer transformer = (IMixinTransformer) MixinEnvironment.getCurrentEnvironment()
            .getActiveTransformer();
        Extensions extensions = (Extensions) transformer.getExtensions();
        for (IExtension extension : extensions.getExtensions()) {
            if (extension instanceof TimingExtension) {
                return;
            }
        }
        extensions.add(new TimingExtension());
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    private static final class TimingExtension implements IExtension {

        @Override
        public boolean checkActive(MixinEnvironment environment) {
            return true;
        }

        @Override
        public void preApply(ITargetClassContext context) {}

        @Override
        public void postApply(ITargetClassContext context) {
            IdleTimingOptimizer.optimize(context.getClassNode());
        }

        @Override
        public void export(MixinEnvironment environment, String name, boolean force, ClassNode classNode) {}
    }
}
