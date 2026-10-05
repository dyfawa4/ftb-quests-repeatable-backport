package local.questrepeat;

/** Settings attached to each Quest by the independent Mixin. */
public interface RepeatableSettings {
    boolean questRepeat$isEnabled();
    int questRepeat$getCooldownSeconds();
    boolean questRepeat$waitForAllMembers();
}
