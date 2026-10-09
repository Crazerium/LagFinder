package lagfinder.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.IEventListener;
import lagfinder.core.Category;
import lagfinder.core.ProfileSession;

/** Times every event handler of every mod, tick handlers included. */
@Mixin(value = EventBus.class, remap = false)
public abstract class MixinEventBus {

    @WrapOperation(
        method = "post",
        at = @At(
            value = "INVOKE",
            target = "Lcpw/mods/fml/common/eventhandler/IEventListener;invoke(Lcpw/mods/fml/common/eventhandler/Event;)V"),
        remap = false)
    private void lagfinder$timeListener(IEventListener listener, Event event, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null || listener instanceof EventPriority) {
            original.call(listener, event);
            return;
        }
        long start = System.nanoTime();
        try {
            original.call(listener, event);
        } finally {
            session.record(Category.EVENTS, listener, System.nanoTime() - start);
        }
    }
}
