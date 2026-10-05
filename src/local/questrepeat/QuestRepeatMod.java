package local.questrepeat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.util.ProgressChange;
import dev.ftb.mods.ftbteams.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.data.Team;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.simple.SimpleChannel;

@Mod("ftbquests_repeatable")
@Mod.EventBusSubscriber(modid = "ftbquests_repeatable")
public final class QuestRepeatMod {
    // Quest network data is extended, so require the same protocol on both ends.
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("ftbquests_repeatable", "compatibility"),
        () -> "1", "1"::equals, "1"::equals);

    private static ServerQuestFile cachedFile;
    private static final List<Quest> repeatableQuests = new ArrayList<>();
    private static int ticks;

    public static void invalidateDefinitions() {
        cachedFile = null;
        repeatableQuests.clear();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null || file.isLoading()) {
            cachedFile = null;
            repeatableQuests.clear();
            ticks = 0;
            return;
        }
        boolean changedFile = cachedFile != file;
        if (changedFile) { cachedFile = file; ticks = 0; }
        // Refresh definitions once a second, including newly edited/reloaded quests.
        if (changedFile || ticks % 20 == 0) {
            repeatableQuests.clear();
            for (QuestObjectBase object : file.getAllObjects()) {
                if (object instanceof Quest && ((RepeatableSettings) object).questRepeat$isEnabled()) {
                    repeatableQuests.add((Quest) object);
                }
            }
        }
        if (++ticks % 5 != 0 || repeatableQuests.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (TeamData data : new ArrayList<>(file.getAllData())) {
            if (data.file != file || data.isLocked()) continue;
            Team team = FTBTeamsAPI.getManager().getTeamByID(data.uuid);
            if (team == null) continue;
            List<UUID> members = new ArrayList<>(team.getMembers());
            if (members.isEmpty()) continue;
            for (Quest quest : repeatableQuests) {
                RepeatableSettings settings = (RepeatableSettings) (Object) quest;
                if (quest.invalid || !settings.questRepeat$isEnabled() || quest.tasks.isEmpty()
                    || !data.isCompleted(quest)) continue;
                Date completed = data.getCompletedTime(quest.id);
                if (completed == null) continue;
                long[][] claims = new long[members.size()][quest.rewards.size()];
                for (int player = 0; player < members.size(); player++) {
                    for (int reward = 0; reward < quest.rewards.size(); reward++) {
                        Date date = data.getRewardClaimTime(members.get(player), quest.rewards.get(reward));
                        claims[player][reward] = date == null ? 0L : date.getTime();
                    }
                }
                long ready = RepeatPolicy.readyTime(true, true, false,
                    settings.questRepeat$waitForAllMembers(), completed.getTime(), claims, quest.rewards.size());
                if (RepeatPolicy.cooldownFinished(ready, settings.questRepeat$getCooldownSeconds(), now)) {
                    resetCycle(data, quest, members);
                }
            }
        }
    }

    private static void resetCycle(TeamData data, Quest quest, Collection<UUID> members) {
        ProgressChange change = new ProgressChange(data.file);
        change.origin = quest;
        change.reset = true;
        change.player = members.iterator().next();
        change.notifications = false;
        // Native reset clears task progress and completion and sends native packets.
        // It does not call Reward.claim() or touch an economy balance.
        quest.forceProgressRaw(data, change);
        for (Reward reward : quest.rewards) {
            for (UUID member : members) data.resetReward(member, reward);
            // Drop stale claim records of former members from this quest's old cycle.
            data.deleteReward(reward);
        }
        data.clearCachedProgress();
        data.save();
    }
}
