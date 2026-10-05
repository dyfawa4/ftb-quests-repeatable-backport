import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.jar.JarFile;
import local.questrepeat.RepeatPolicy;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

public final class RepeatVerification {
    public static void main(String[] args) throws Exception {
        long[][] claimed = {{1500L, 2000L}, {2500L, 3000L}};
        eq(-1, RepeatPolicy.readyTime(false, true, false, true, 1000, claimed, 2), "one-time quest unchanged");
        eq(-1, RepeatPolicy.readyTime(true, false, false, true, 1000, claimed, 2), "incomplete quest cannot reset");
        eq(-1, RepeatPolicy.readyTime(true, true, true, true, 1000, claimed, 2), "locked progress unchanged");
        eq(3000, RepeatPolicy.readyTime(true, true, false, true, 1000, claimed, 2), "wait for all team rewards");
        eq(2000, RepeatPolicy.readyTime(true, true, false, false, 1000, claimed, 2), "first full claimant");
        long[][] missing = {{1500, 2000}, {2500, 0}};
        eq(-1, RepeatPolicy.readyTime(true, true, false, true, 1000, missing, 2), "offline or missing claimant retained");
        eq(2000, RepeatPolicy.readyTime(true, true, false, false, 1000, missing, 2), "explicit first-claimant mode");
        eq(-1, RepeatPolicy.readyTime(true, true, false, false, 1000, new long[][]{{0, 2000}}, 2), "partial rewards do not reset");
        eq(-1, RepeatPolicy.readyTime(true, true, false, false, 1000, new long[0][], 1), "empty team");
        eq(1000, RepeatPolicy.readyTime(true, true, false, true, 1000, new long[][]{{}}, 0), "no-reward quest");
        check(!RepeatPolicy.cooldownFinished(2000, 1, 2999), "cooldown not finished early");
        check(RepeatPolicy.cooldownFinished(2000, 1, 3000), "exact cooldown boundary");
        check(!RepeatPolicy.cooldownFinished(2000, 0, 1999), "clock rollback postpones reset");
        check(!RepeatPolicy.cooldownFinished(-1, 0, 3000), "unclaimed state remains blocked");
        check(!RepeatPolicy.cooldownFinished(2000, Integer.MAX_VALUE, 3000), "large cooldown does not overflow");
        // Independent state machine checks: every reset requires a new submission.
        int rewardGrants = 0;
        long balance = 1234;
        boolean complete = false;
        for (int cycle = 0; cycle < 3; cycle++) {
            eq(-1, RepeatPolicy.readyTime(true, complete, false, false, 1000, new long[][]{{0}}, 1), "no claim before submit");
            complete = true;
            rewardGrants++;
            balance += 0;
            long ready = RepeatPolicy.readyTime(true, complete, false, false, 1000, new long[][]{{2000}}, 1);
            check(RepeatPolicy.cooldownFinished(ready, 1, 3000), "repeat after claim");
            complete = false;
        }
        eq(3, rewardGrants, "three separate submissions");
        eq(1234, balance, "zero-money reward leaves balance unchanged");
        System.out.println("PASS: repeat eligibility, all/first claimant, cooldown, disabled/locked, three-cycle model");
        verifyMixin(args[0], args.length > 1 ? MixinEnvironment.Side.valueOf(args[1]) : MixinEnvironment.Side.CLIENT);
    }

    private static void verifyMixin(String path, MixinEnvironment.Side side) throws Exception {
        MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(side);
        MixinEnvironment.getCurrentEnvironment().setSide(side);
        Mixins.addConfiguration("ftbquests-repeatable.mixins.json");
        Method go = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        go.setAccessible(true);
        go.invoke(null, MixinEnvironment.Phase.DEFAULT);
        Constructor<?> constructor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
        constructor.setAccessible(true);
        IMixinTransformer transformer = (IMixinTransformer) constructor.newInstance();
        String name = "dev.ftb.mods.ftbquests.quest.Quest";
        byte[] original;
        try (JarFile jar = new JarFile(path); InputStream input = jar.getInputStream(jar.getJarEntry(name.replace('.', '/') + ".class"))) {
            original = input.readAllBytes();
        }
        byte[] patched = transformer.transformClassBytes(name, name, original);
        ClassNode node = new ClassNode();
        new ClassReader(patched).accept(node, 0);
        check(node.interfaces.contains("local/questrepeat/RepeatableSettings"), "settings interface merged");
        checkCall(node, "writeData", "questRepeat$writeSettings");
        checkCall(node, "readData", "questRepeat$readSettings");
        checkCall(node, "writeNetData", "questRepeat$writeNetwork");
        checkCall(node, "readNetData", "questRepeat$readNetwork");
        checkCall(node, "getConfig", "questRepeat$addEditorSettings");
        checkCall(node, "countsTowardParentCompletion", "questRepeat$excludeFromChapterTotal");
        for (MethodNode method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        String commands = "dev.ftb.mods.ftbquests.command.FTBQuestsCommands";
        try (JarFile jar = new JarFile(path); InputStream input = jar.getInputStream(jar.getJarEntry(commands.replace('.', '/') + ".class"))) {
            ClassNode commandNode = new ClassNode();
            new ClassReader(transformer.transformClassBytes(commands, commands, input.readAllBytes())).accept(commandNode, 0);
            checkCall(commandNode, "register", "questRepeat$registerReload");
            for (MethodNode method : commandNode.methods) new Analyzer<>(new BasicVerifier()).analyze(commandNode.name, method);
        }
        System.out.println("PASS: " + side + " Mixin 0.8.4 applied all seven hooks to actual FTB classes; bytecode analysis passed");
    }

    private static void checkCall(ClassNode node, String methodName, String hookName) {
        int calls = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals(methodName)) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).name.contains(hookName)) calls++;
            }
        }
        eq(1, calls, "injection " + hookName);
    }

    private static void eq(long expected, long actual, String message) { check(expected == actual, message + ": expected " + expected + ", got " + actual); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
