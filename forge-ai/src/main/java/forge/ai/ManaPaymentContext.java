package forge.ai;

import com.google.common.collect.ListMultimap;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Planner state passed through nested {@link ComputerUtilMana#payManaCost} calls.
 */
final class ManaPaymentContext {
    /** Per-outer-payment scratch shared by nested feasibility probes (CastabilityProbe). */
    static final class CastabilityProbeScratch {
        final Set<ManaCostShard> unavailableColoredShards = new HashSet<>();
        int availableManaAfterReservation = -1;
        ManaCostShard lastFailedColoredShard;
        boolean lastFailureWasNoSources;

        void resetForProbe() {
            unavailableColoredShards.clear();
            availableManaAfterReservation = -1;
            clearLastFailure();
        }

        void clearLastFailure() {
            lastFailedColoredShard = null;
            lastFailureWasNoSources = false;
        }
    }

    /** Hand-derived facts for one spell being paid (all three computed together, so they never mix spells). */
    static final class HandProbe {
        final int spellId;
        final boolean multicolorManaSpells;
        final boolean otherHandOrCommandSpells;
        final boolean genericAndColoredCast;

        HandProbe(final int spellId, final boolean multicolorManaSpells, final boolean otherHandOrCommandSpells,
                final boolean genericAndColoredCast) {
            this.spellId = spellId;
            this.multicolorManaSpells = multicolorManaSpells;
            this.otherHandOrCommandSpells = otherHandOrCommandSpells;
            this.genericAndColoredCast = genericAndColoredCast;
        }
    }

    /**
     * Per-outer-payment caches shared by nested feasibility probes. The outermost
     * {@link ComputerUtilMana#payManaCost} binds the active instance to the thread (see {@link #bind}) so
     * leaf helpers without a context parameter can still memoize (see {@link ManaSourceTraits#of}).
     */
    static final class ManaPaymentPlanCache {
        private static final ThreadLocal<ManaPaymentPlanCache> BOUND = new ThreadLocal<>();

        /** Color-bucketed mana abilities (board-state derived; see {@link #invalidateBoardMemos}). */
        ListMultimap<Integer, SpellAbility> manaAbilityMap;
        /** One entry per mana ability (not per color bucket). Built with {@link #manaAbilityMap}. */
        List<SpellAbility> uniqueManaAbilities;
        final Map<Card, List<SpellAbility>> playableManaCache = new HashMap<>();
        /** Memoized per-ability characteristics. */
        final Map<SpellAbility, ManaSourceTraits> sourceTraits = new IdentityHashMap<>();
        /** {@link ManaFilterConsolidation#scoreHostForSorting} per host card. */
        final Map<Card, Integer> hostSortScores = new IdentityHashMap<>();
        /** {@link ManaAbilitySort#shouldReserveColorlessMana} keyed by the host of the spell being paid. */
        final Map<Card, Boolean> reserveColorlessByHost = new IdentityHashMap<>();
        /** {@link ManaAbilitySort#computeHandColorPreferences} keyed by the host of the spell being paid. */
        final Map<Card, List<Integer>> handColorPrefsByHost = new IdentityHashMap<>();
        Set<Card> reusableTapLandSet;
        HandProbe handProbe;
        final CastabilityProbeScratch castabilityProbe = new CastabilityProbeScratch();
        private Boolean boardHasNetPositiveConsolidator;
        private Boolean boardHasBankingPair;
        /** Battlefield-only mana estimate, indexed by {@code checkPlayable ? 1 : 0}; pool is added live. */
        final ComputerUtilMana.ManaAvailabilityEstimate[] battlefieldManaEstimate =
                new ComputerUtilMana.ManaAvailabilityEstimate[2];

        /** Any net-positive consolidator (signet / combo land) on the battlefield, tapped or not. */
        boolean boardHasNetPositiveConsolidator(final Player ai) {
            if (boardHasNetPositiveConsolidator == null) {
                scanBoardConsolidators(ai);
            }
            return boardHasNetPositiveConsolidator;
        }

        /** Both a combo consolidator and a net-positive signet-style filter exist (mana banking chain). */
        boolean boardHasBankingPair(final Player ai) {
            if (boardHasBankingPair == null) {
                scanBoardConsolidators(ai);
            }
            return boardHasBankingPair;
        }

        private void scanBoardConsolidators(final Player ai) {
            boolean netPositive = false;
            boolean combo = false;
            for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
                for (final SpellAbility ma : c.getManaAbilities()) {
                    final ManaSourceTraits t = ManaSourceTraits.of(ma);
                    if (t.comboFilter) {
                        combo = true;
                    } else if (t.netPositiveConsolidator) {
                        netPositive = true;
                    }
                }
            }
            boardHasNetPositiveConsolidator = netPositive || combo;
            boardHasBankingPair = netPositive && combo;
        }

        /** The cache of the outer payment running on this thread, or {@code null} outside a payment. */
        static ManaPaymentPlanCache bound() {
            return BOUND.get();
        }

        /** Bind for the current thread; returns the previously bound cache (restore via {@link #unbind}). */
        static ManaPaymentPlanCache bind(final ManaPaymentPlanCache cache) {
            final ManaPaymentPlanCache prev = BOUND.get();
            BOUND.set(cache);
            return prev;
        }

        static void unbind(final ManaPaymentPlanCache prev) {
            if (prev == null) {
                BOUND.remove();
            } else {
                BOUND.set(prev);
            }
        }

        /** Drop board-state-derived memos after a real (production) activation changed the board. */
        void invalidateBoardMemos() {
            sourceTraits.clear();
            hostSortScores.clear();
            playableManaCache.clear();
            manaAbilityMap = null;
            uniqueManaAbilities = null;
            reusableTapLandSet = null;
            boardHasNetPositiveConsolidator = null;
            boardHasBankingPair = null;
            battlefieldManaEstimate[0] = null;
            battlefieldManaEstimate[1] = null;
        }
    }

    final ManaPaymentPlanCache caches;
    final int depth;
    final boolean inFilterActivationProbe;
    final boolean paymentPromptPreview;
    final boolean tracePaymentPlan;
    String costLabel;
    List<Mana> testDepositedSurplus;
    List<String> planSteps;

    private ManaPaymentContext(final ManaPaymentPlanCache caches, final int depth,
            final boolean inFilterActivationProbe, final boolean paymentPromptPreview,
            final boolean tracePaymentPlan, final List<Mana> testDepositedSurplus) {
        this.caches = caches;
        this.depth = depth;
        this.inFilterActivationProbe = inFilterActivationProbe;
        this.paymentPromptPreview = paymentPromptPreview;
        this.tracePaymentPlan = tracePaymentPlan;
        this.testDepositedSurplus = testDepositedSurplus;
    }

    /**
     * Opt-in "fast heuristics" ({@code -Dforge.ai.manaPayment.fastHeuristics=true}). Trades a few nested
     * dry-runs for cheap counting shortcuts and may change which source the AI taps in edge cases:
     * <ul>
     *   <li>{@link ManaPaymentExecution#evaluatePaymentImpact}: plain free producers skip the stranding
     *       dry-run when a Hall-condition count of the remaining free sources already covers the rest.</li>
     *   <li>{@link CastabilityProbe#pickBest}: castability dry-runs only for candidates tied on efficiency.</li>
     *   <li>{@link ManaPaymentExecution#collectValidManaPaymentChoices}: keeps {@code Any}-mana filters that
     *       {@link ManaPaymentExecution#isUselessAnyManaFilter} would drop and lets efficiency scoring rank them.</li>
     * </ul>
     */
    private static final String FAST_HEURISTICS_PROP = "forge.ai.manaPayment.fastHeuristics";
    private static volatile Boolean fastHeuristics;

    static boolean fastHeuristics() {
        Boolean enabled = fastHeuristics;
        if (enabled == null) {
            enabled = Boolean.getBoolean(FAST_HEURISTICS_PROP);
            fastHeuristics = enabled;
        }
        return enabled;
    }

    /** Test hook: force the fast-heuristics flag ({@code null} re-reads the system property). */
    public static void setFastHeuristicsForTests(final Boolean enabled) {
        fastHeuristics = enabled;
    }

    /** Fresh outermost context for an AI payment / feasibility check (no plan tracing). */
    static ManaPaymentContext outer() {
        return new ManaPaymentContext(new ManaPaymentPlanCache(), 1, false, false, false, null);
    }

    /**
     * Fresh outermost context for the human payment prompt's Auto button. {@code preview} is the
     * dry-run that shows the plan ({@code [test]}); {@code false} is the commit ({@code [prod]}). Plan
     * steps are recorded when {@code -Dforge.debugManaPayment.plan=true} (read live; tests toggle it).
     */
    static ManaPaymentContext outerForPrompt(final boolean preview) {
        final boolean tracePlan = ManaPaymentTracer.planEnabled();
        final ManaPaymentContext ctx = new ManaPaymentContext(new ManaPaymentPlanCache(), 1, false,
                preview, tracePlan, null);
        ctx.planSteps = tracePlan ? new ArrayList<>() : null;
        return ctx;
    }

    boolean isOutermost() {
        return depth == 1;
    }

    /** Payment trace for the outer spell only; nested feasibility probes stay silent. */
    boolean shouldLogMain() {
        return depth <= 1 && !inFilterActivationProbe;
    }

    ManaPaymentContext withCostLabel(final String label) {
        final ManaPaymentContext next = new ManaPaymentContext(caches, depth, inFilterActivationProbe,
                paymentPromptPreview, tracePaymentPlan, testDepositedSurplus);
        next.planSteps = planSteps;
        next.costLabel = label;
        return next;
    }

    ManaPaymentContext nested() {
        return new ManaPaymentContext(caches, depth + 1, inFilterActivationProbe, paymentPromptPreview,
                tracePaymentPlan, testDepositedSurplus);
    }

    /**
     * Standalone dry-run that shares this payment's memos but none of its flags, depth, surplus or plan
     * steps (behaves like {@link #outer()} without rebuilding the board-derived caches).
     */
    ManaPaymentContext detachedProbe() {
        return new ManaPaymentContext(caches, 1, false, false, false, null);
    }

    ManaPaymentContext withFilterProbe() {
        return new ManaPaymentContext(caches, depth, true, paymentPromptPreview, tracePaymentPlan,
                testDepositedSurplus);
    }

    /**
     * Nested feasibility / castability dry-run. Uses a fresh surplus list so nested deposits cannot
     * leak into the outer payment as phantom floating mana.
     */
    ManaPaymentContext nestedWithFilterProbe() {
        return new ManaPaymentContext(caches, depth + 1, true, paymentPromptPreview, tracePaymentPlan,
                null);
    }

    void recordStep(final SpellAbility sa, final boolean test, final String msg) {
        if (msg == null || planSteps == null || !ManaPaymentTracer.shouldRecordPlanStep(sa, test, this)) {
            return;
        }
        planSteps.add(msg);
    }

    /** Lazy variant: the step text is only built when a plan is being recorded. */
    void recordStep(final SpellAbility sa, final boolean test, final Supplier<String> msg) {
        if (planSteps == null || !ManaPaymentTracer.shouldRecordPlanStep(sa, test, this)) {
            return;
        }
        final String s = msg.get();
        if (s != null) {
            planSteps.add(s);
        }
    }

    void finishIfOutermost(final boolean test, final SpellAbility sa, final boolean paid) {
        if (!isOutermost() || planSteps == null || planSteps.isEmpty()) {
            return;
        }
        if (!ManaPaymentTracer.shouldRecordPlanStep(sa, test, this)) {
            return;
        }
        ManaPaymentTracer.logPaymentPlan(test, costLabel != null ? costLabel : "?", sa, planSteps, paid);
    }
}
