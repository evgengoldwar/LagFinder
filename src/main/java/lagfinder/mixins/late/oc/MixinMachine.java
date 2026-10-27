package lagfinder.mixins.late.oc;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.core.ComputerWork;
import lagfinder.core.ProfileSession;
import li.cil.oc.api.machine.Architecture;
import li.cil.oc.api.machine.ExecutionResult;
import li.cil.oc.api.machine.Value;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Network;
import li.cil.oc.api.network.Node;
import li.cil.oc.server.machine.Machine;

@Mixin(value = Machine.class, remap = false)
public abstract class MixinMachine {

    @Unique
    private volatile long lagfinder$dueAt;

    @Unique
    private long lagfinder$callStart;

    @WrapOperation(
        method = "switchTo",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/concurrent/ScheduledExecutorService;schedule(Ljava/lang/Runnable;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;"),
        require = 0)
    private ScheduledFuture<?> lagfinder$scheduled(ScheduledExecutorService pool, Runnable task, long delay,
        TimeUnit unit, Operation<ScheduledFuture<?>> original) {
        lagfinder$dueAt = System.nanoTime() + unit.toNanos(delay);
        return original.call(pool, task, delay, unit);
    }

    @WrapOperation(
        method = "run",
        at = @At(
            value = "INVOKE",
            target = "Lli/cil/oc/api/machine/Architecture;runThreaded(Z)Lli/cil/oc/api/machine/ExecutionResult;"),
        require = 0)
    private ExecutionResult lagfinder$computerRun(Architecture architecture, boolean synchronizedReturn,
        Operation<ExecutionResult> original) {
        ProfileSession session = ComputerWork.session();
        if (session == null) {
            return original.call(architecture, synchronizedReturn);
        }
        long start = System.nanoTime();
        long due = lagfinder$dueAt;
        try {
            return original.call(architecture, synchronizedReturn);
        } finally {
            long wait = due > 0 ? Math.max(0, start - due) : 0;
            ComputerWork.ran(session, lagfinder$host(), System.nanoTime() - start, wait);
        }
    }

    @WrapOperation(
        method = "update",
        at = @At(value = "INVOKE", target = "Lli/cil/oc/api/machine/Architecture;runSynchronized()V"),
        require = 0)
    private void lagfinder$computerSync(Architecture architecture, Operation<Void> original) {
        ProfileSession session = ComputerWork.session();
        if (session == null) {
            original.call(architecture);
            return;
        }
        long start = System.nanoTime();
        try {
            original.call(architecture);
        } finally {
            ComputerWork.synced(session, lagfinder$host(), System.nanoTime() - start);
        }
    }

    @Inject(
        method = "invoke(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)[Ljava/lang/Object;",
        at = @At("HEAD"),
        require = 0)
    private void lagfinder$componentCallStarts(String address, String method, Object[] args,
        CallbackInfoReturnable<Object[]> cir) {
        lagfinder$callStarts();
    }

    @Inject(
        method = "invoke(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)[Ljava/lang/Object;",
        at = @At("RETURN"),
        require = 0)
    private void lagfinder$componentCallEnds(String address, String method, Object[] args,
        CallbackInfoReturnable<Object[]> cir) {
        lagfinder$callEnds(lagfinder$componentName(address), method);
    }

    @Inject(
        method = "invoke(Lli/cil/oc/api/machine/Value;Ljava/lang/String;[Ljava/lang/Object;)[Ljava/lang/Object;",
        at = @At("HEAD"),
        require = 0)
    private void lagfinder$valueCallStarts(Value value, String method, Object[] args,
        CallbackInfoReturnable<Object[]> cir) {
        lagfinder$callStarts();
    }

    @Inject(
        method = "invoke(Lli/cil/oc/api/machine/Value;Ljava/lang/String;[Ljava/lang/Object;)[Ljava/lang/Object;",
        at = @At("RETURN"),
        require = 0)
    private void lagfinder$valueCallEnds(Value value, String method, Object[] args,
        CallbackInfoReturnable<Object[]> cir) {
        lagfinder$callEnds(
            value == null ? "value"
                : value.getClass()
                    .getSimpleName(),
            method);
    }

    @Unique
    private void lagfinder$callStarts() {
        lagfinder$callStart = ProfileSession.currentServer() != null ? System.nanoTime() : 0;
    }

    @Unique
    private void lagfinder$callEnds(String component, String method) {
        long start = lagfinder$callStart;
        lagfinder$callStart = 0;
        ProfileSession session = start > 0 ? ProfileSession.currentServer() : null;
        if (session != null) {
            ComputerWork.called(session, component, String.valueOf(method), System.nanoTime() - start);
        }
    }

    @Unique
    private String lagfinder$componentName(String address) {
        Node node = ((li.cil.oc.api.machine.Machine) (Object) this).node();
        Network network = node != null ? node.network() : null;
        Node target = network != null && address != null ? network.node(address) : null;
        return target instanceof Component ? ((Component) target).name() : "component";
    }

    @Unique
    private Object lagfinder$host() {
        return ((li.cil.oc.api.machine.Machine) (Object) this).host();
    }
}
