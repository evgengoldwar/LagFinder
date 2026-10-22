package lagfinder.mixins.ae2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.me.service.TickManagerService;
import lagfinder.core.ProfileSession;

/** Times each machine an AE2 network ticks (interfaces, buses, assemblers, add-ons' parts...), by network and place. */
@Mixin(value = TickManagerService.class, remap = false)
public abstract class MixinTickManagerService {

    @WrapOperation(
        method = "unsafeTickingRequest",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/networking/ticking/IGridTickable;tickingRequest(Lappeng/api/networking/IGridNode;I)Lappeng/api/networking/ticking/TickRateModulation;"),
        require = 0)
    private TickRateModulation lagfinder$timeMachine(IGridTickable machine, IGridNode node, int ticksSinceLastCall,
        Operation<TickRateModulation> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            return original.call(machine, node, ticksSinceLastCall);
        }
        Object network = node.getGrid();
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            return original.call(machine, node, ticksSinceLastCall);
        } finally {
            session.recordNetwork(network, node, true, start, outer);
        }
    }
}
