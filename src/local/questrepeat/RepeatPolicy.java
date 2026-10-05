package local.questrepeat;

/** Pure scheduling policy: rows are players, columns are rewards; zero is unclaimed. */
public final class RepeatPolicy {
    private RepeatPolicy() {}

    public static long readyTime(boolean enabled, boolean completed, boolean locked,
        boolean waitAll, long completedAt, long[][] claims, int rewardCount) {
        if (!enabled || !completed || locked || completedAt <= 0L || claims.length == 0) return -1L;
        if (rewardCount == 0) return completedAt;
        long eligibleAt = waitAll ? 0L : Long.MAX_VALUE;
        boolean found = false;
        for (long[] player : claims) {
            if (player.length != rewardCount) throw new IllegalArgumentException("Inconsistent reward matrix");
            long finishedAt = 0L;
            boolean claimedAll = true;
            for (long claimedAt : player) {
                if (claimedAt <= 0L) { claimedAll = false; break; }
                finishedAt = Math.max(finishedAt, claimedAt);
            }
            if (waitAll && !claimedAll) return -1L;
            if (claimedAll) {
                found = true;
                eligibleAt = waitAll ? Math.max(eligibleAt, finishedAt) : Math.min(eligibleAt, finishedAt);
            }
        }
        return found ? Math.max(completedAt, eligibleAt) : -1L;
    }

    public static boolean cooldownFinished(long readyAt, int seconds, long now) {
        return readyAt > 0L && now >= readyAt && now - readyAt >= Math.max(0, seconds) * 1000L;
    }
}
