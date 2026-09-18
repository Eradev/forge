package forge.ai;

/**
 * Counters for mana-evaluation work (nested dry-runs, map rebuilds). Used to compare
 * planner cost before/after optimizations — not TestNG wall-clock time.
 */
public final class ManaPaymentEvalStats {
    private static int nestedStrandingProbes;
    private static int freeSourceCoverHits;
    private static int manaMapBuilds;
    private static int boardMemoFullInvalidations;
    private static int boardMemoIncrementalInvalidations;

    private ManaPaymentEvalStats() {
    }

    public static void reset() {
        nestedStrandingProbes = 0;
        freeSourceCoverHits = 0;
        manaMapBuilds = 0;
        boardMemoFullInvalidations = 0;
        boardMemoIncrementalInvalidations = 0;
        CastabilityProbe.resetDryRunCountForTests();
    }

    static void recordNestedStrandingProbe() {
        nestedStrandingProbes++;
    }

    static void recordFreeSourceCoverHit() {
        freeSourceCoverHits++;
    }

    static void recordManaMapBuild() {
        manaMapBuilds++;
    }

    static void recordBoardMemoFullInvalidation() {
        boardMemoFullInvalidations++;
    }

    static void recordBoardMemoIncrementalInvalidation() {
        boardMemoIncrementalInvalidations++;
    }

    public static int getNestedStrandingProbes() {
        return nestedStrandingProbes;
    }

    public static int getFreeSourceCoverHits() {
        return freeSourceCoverHits;
    }

    public static int getManaMapBuilds() {
        return manaMapBuilds;
    }

    public static int getBoardMemoFullInvalidations() {
        return boardMemoFullInvalidations;
    }

    public static int getBoardMemoIncrementalInvalidations() {
        return boardMemoIncrementalInvalidations;
    }

    public static int getCastabilityDryRuns() {
        return CastabilityProbe.getDryRunCountForTests();
    }

    /** Nested stranding probes + castability dry-runs — the dominant recursive cost. */
    public static int getTotalNestedPaymentProbes() {
        return nestedStrandingProbes + getCastabilityDryRuns();
    }

    public static String summary() {
        return "nestedStranding=" + nestedStrandingProbes
                + " castabilityDryRuns=" + getCastabilityDryRuns()
                + " totalNestedProbes=" + getTotalNestedPaymentProbes()
                + " freeSourceCoverHits=" + freeSourceCoverHits
                + " manaMapBuilds=" + manaMapBuilds
                + " memoFullInvalidate=" + boardMemoFullInvalidations
                + " memoIncrementalInvalidate=" + boardMemoIncrementalInvalidations;
    }
}
