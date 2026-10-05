package local.questrepeat.mixin;

import com.mojang.brigadier.CommandDispatcher;
import dev.ftb.mods.ftbquests.command.FTBQuestsCommands;
import local.questrepeat.ReloadCommands;
import net.minecraft.command.CommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FTBQuestsCommands.class, remap = false)
public abstract class CommandsMixin {
    @Inject(method = "register(Lcom/mojang/brigadier/CommandDispatcher;)V", at = @At("TAIL"), require = 1, allow = 1)
    private static void questRepeat$registerReload(CommandDispatcher<CommandSource> dispatcher, CallbackInfo ci) {
        ReloadCommands.register(dispatcher);
    }
}
