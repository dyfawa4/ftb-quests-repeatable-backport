package local.questrepeat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import dev.ftb.mods.ftblibrary.snbt.SNBT;
import dev.ftb.mods.ftbquests.FTBQuests;
import dev.ftb.mods.ftbquests.net.SyncQuestsMessage;
import dev.ftb.mods.ftbquests.net.SyncTeamDataMessage;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.StringTextComponent;

/** Add a child to FTB's existing command tree without replacing other commands. */
public final class ReloadCommands {
    private ReloadCommands() {}

    public static void register(CommandDispatcher<CommandSource> dispatcher) {
        CommandNode<CommandSource> root = dispatcher.getRoot().getChild("ftbquests");
        if (root == null || root.getChild("reload") != null) {
            FTBQuests.LOGGER.info("Repeatable backport: keeping the existing command tree; reload registration skipped");
            return;
        }
        root.addChild(LiteralArgumentBuilder.<CommandSource>literal("reload")
            .requires(source -> source.func_197034_c(2))
            .executes(context -> reload(context.getSource())).build());
    }

    private static int reload(CommandSource source) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null || file.isLoading()) {
            source.func_197021_a(new StringTextComponent("FTB Quests 尚未就绪。"));
            return 0;
        }
        Path backup = null;
        boolean applying = false;
        try {
            Path folder = file.getFolder();
            Path work = Files.createTempDirectory(folder.getParent(), "repeatable-reload-backup-");
            Path input = work.resolve("input");
            backup = work.resolve("previous");
            // Read one stable snapshot, even if a text editor saves during the command.
            copyDefinitions(folder, input);
            // Native readDataFull silently ignores some parse errors. Reject those first.
            validateFiles(input);
            ServerQuestFile candidate = new ServerQuestFile(file.server);
            candidate.readDataFull(input);
            if (candidate.getAllChapters().isEmpty() && !file.getAllChapters().isEmpty()) {
                throw new IOException("任务章节为空，已拒绝重载");
            }
            long expectedChapters;
            try (Stream<Path> chapters = Files.list(input.resolve("chapters"))) {
                expectedChapters = chapters.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".snbt")).count();
            }
            if (candidate.getAllChapters().size() != expectedChapters) {
                throw new IOException("部分章节加载失败，已拒绝重载");
            }
            // Snapshot definitions from memory. Never save over the edited source files.
            file.writeDataFull(backup);
            validateFiles(backup);
            applying = true;
            file.readDataFull(input);
            file.clearCachedData();
            QuestRepeatMod.invalidateDefinitions();
            sync(file);
            source.func_197030_a(new StringTextComponent("FTB Quests 已重载任务定义；队伍进度与领取记录保留。"), false);
            FTBQuests.LOGGER.info("Reloaded quest definitions; previous definitions backed up at {}", backup);
            return 1;
        } catch (Exception failure) {
            if (applying && backup != null) {
                try {
                    file.readDataFull(backup);
                    file.clearCachedData();
                    QuestRepeatMod.invalidateDefinitions();
                    sync(file);
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            FTBQuests.LOGGER.error("Quest reload failed", failure);
            source.func_197021_a(new StringTextComponent("任务重载失败，请查看 latest.log：" + failure.getMessage()));
            return 0;
        }
    }

    private static void copyDefinitions(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else if (path.toString().endsWith(".snbt")) Files.copy(path, destination);
            }
        }
    }

    private static void validateFiles(Path folder) throws IOException {
        if (!Files.isRegularFile(folder.resolve("data.snbt"))) throw new IOException("缺少 data.snbt");
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) files.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".snbt"))::iterator) {
                if (SNBT.read(path) == null) throw new IOException("无效 SNBT：" + folder.relativize(path));
            }
        }
    }

    private static void sync(ServerQuestFile file) {
        for (ServerPlayerEntity player : file.server.func_184103_al().func_181057_v()) {
            new SyncQuestsMessage(file).sendTo(player);
            TeamData self = TeamData.get(player);
            for (TeamData data : file.getAllData()) {
                new SyncTeamDataMessage(data, self != null && data.uuid.equals(self.uuid)).sendTo(player);
            }
        }
    }
}
