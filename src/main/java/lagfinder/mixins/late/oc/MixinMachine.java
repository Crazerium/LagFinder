package lagfinder.mixins.late.oc;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.core.ComputerWork;
import lagfinder.core.ProfileSession;
import li.cil.oc.api.machine.Architecture;
import li.cil.oc.api.machine.ExecutionResult;
import li.cil.oc.server.machine.Machine;

@Mixin(value = Machine.class, remap = false)
public abstract class MixinMachine {

    @Unique
    private volatile long lagfinder$dueAt;

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

    @Unique
    private Object lagfinder$host() {
        return ((li.cil.oc.api.machine.Machine) (Object) this).host();
    }
}
