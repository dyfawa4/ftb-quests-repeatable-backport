package local.questrepeat.mixin;

import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import dev.ftb.mods.ftbquests.quest.Quest;
import local.questrepeat.RepeatableSettings;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Quest.class, remap = false)
public abstract class QuestMixin implements RepeatableSettings {
    @Unique private boolean questRepeat$enabled;
    @Unique private int questRepeat$cooldownSeconds = 0;
    @Unique private boolean questRepeat$waitAll = true;

    public boolean questRepeat$isEnabled() { return questRepeat$enabled; }
    public int questRepeat$getCooldownSeconds() { return questRepeat$cooldownSeconds; }
    public boolean questRepeat$waitForAllMembers() { return questRepeat$waitAll; }

    @Inject(method = "writeData(Lnet/minecraft/nbt/CompoundNBT;)V", at = @At("TAIL"), require = 1, allow = 1)
    private void questRepeat$writeSettings(CompoundNBT tag, CallbackInfo ci) {
        if (questRepeat$enabled) {
            tag.func_74757_a("can_repeat", true);
            tag.func_74768_a("repeat_cooldown", questRepeat$cooldownSeconds);
            tag.func_74757_a("repeat_wait_all_members", questRepeat$waitAll);
        }
    }

    @Inject(method = "readData(Lnet/minecraft/nbt/CompoundNBT;)V", at = @At("TAIL"), require = 1, allow = 1)
    private void questRepeat$readSettings(CompoundNBT tag, CallbackInfo ci) {
        questRepeat$enabled = tag.func_74767_n("can_repeat");
        questRepeat$cooldownSeconds = tag.func_74764_b("repeat_cooldown")
            ? Math.max(0, tag.func_74762_e("repeat_cooldown")) : 0;
        questRepeat$waitAll = !tag.func_74764_b("repeat_wait_all_members")
            || tag.func_74767_n("repeat_wait_all_members");
    }

    @Inject(method = "writeNetData(Lnet/minecraft/network/PacketBuffer;)V", at = @At("TAIL"), require = 1, allow = 1)
    private void questRepeat$writeNetwork(PacketBuffer buffer, CallbackInfo ci) {
        buffer.writeBoolean(questRepeat$enabled);
        buffer.func_150787_b(questRepeat$cooldownSeconds);
        buffer.writeBoolean(questRepeat$waitAll);
    }

    @Inject(method = "readNetData(Lnet/minecraft/network/PacketBuffer;)V", at = @At("TAIL"), require = 1, allow = 1)
    private void questRepeat$readNetwork(PacketBuffer buffer, CallbackInfo ci) {
        questRepeat$enabled = buffer.readBoolean();
        questRepeat$cooldownSeconds = Math.max(0, buffer.func_150792_a());
        questRepeat$waitAll = buffer.readBoolean();
    }

    @Inject(method = "getConfig(Ldev/ftb/mods/ftblibrary/config/ConfigGroup;)V", at = @At("TAIL"), require = 1, allow = 1)
    private void questRepeat$addEditorSettings(ConfigGroup config, CallbackInfo ci) {
        config.addBool("can_repeat", questRepeat$enabled, value -> questRepeat$enabled = value, false)
            .setNameKey("ftbquests_repeatable.config.can_repeat");
        config.addInt("repeat_cooldown", questRepeat$cooldownSeconds,
            value -> questRepeat$cooldownSeconds = value, 0, 0, Integer.MAX_VALUE)
            .setNameKey("ftbquests_repeatable.config.repeat_cooldown");
        config.addBool("repeat_wait_all_members", questRepeat$waitAll, value -> questRepeat$waitAll = value, true)
            .setNameKey("ftbquests_repeatable.config.wait_all");
    }

    @Inject(method = "countsTowardParentCompletion()Z", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void questRepeat$excludeFromChapterTotal(CallbackInfoReturnable<Boolean> cir) {
        if (questRepeat$enabled) cir.setReturnValue(false);
    }
}
