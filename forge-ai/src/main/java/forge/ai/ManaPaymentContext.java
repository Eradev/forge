package forge.ai;

import com.google.common.collect.ListMultimap;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Per-outer-payment caches shared by nested feasibility probes. */
    static final class ManaPaymentPlanCache {
        ListMultimap<Integer, SpellAbility> manaAbilityMap;
        Long manaAbilityMapKey;
        final Map<Card, List<SpellAbility>> playableManaCache = new HashMap<>();
        Long reusableTapLandKey;
        Set<Card> reusableTapLandSet;
        int handProbeSpellId = -1;
        Boolean handHasMulticolorManaSpells;
        Boolean hasOtherHandOrCommandSpells;
        Boolean handHasGenericAndColoredCast;
        final CastabilityProbeScratch castabilityProbe = new CastabilityProbeScratch();
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

    static ManaPaymentContext outer() {
        return outer(null);
    }

    static ManaPaymentContext outer(final List<String> planSteps) {
        return outer(planSteps, false, false);
    }

    /** Payment-prompt Auto preview dry-run ({@code [test]} plan). */
    static ManaPaymentContext outerForPaymentPrompt() {
        if (Boolean.getBoolean("forge.debugManaPayment.plan")) {
            return outer(new ArrayList<>(), true, true);
        }
        return outer(null, true, false);
    }

    /** Payment-prompt Auto commit ({@code [prod]} plan). */
    static ManaPaymentContext outerForPaymentPromptCommit() {
        if (!Boolean.getBoolean("forge.debugManaPayment.plan")) {
            return outer();
        }
        return outer(new ArrayList<>(), false, true);
    }

    static ManaPaymentContext outer(final List<String> planSteps, final boolean paymentPromptPreview,
            final boolean tracePaymentPlan) {
        final ManaPaymentContext ctx = new ManaPaymentContext(new ManaPaymentPlanCache(), 1, false,
                paymentPromptPreview, tracePaymentPlan, null);
        ctx.planSteps = planSteps;
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

    ManaPaymentContext withFilterProbe() {
        return new ManaPaymentContext(caches, depth, true, paymentPromptPreview, tracePaymentPlan,
                testDepositedSurplus);
    }

    ManaPaymentContext nestedWithFilterProbe() {
        return new ManaPaymentContext(caches, depth + 1, true, paymentPromptPreview, tracePaymentPlan,
                testDepositedSurplus);
    }

    void recordStep(final SpellAbility sa, final boolean test, final String msg) {
        if (msg == null || planSteps == null || !ManaPaymentTracer.shouldRecordPlanStep(sa, test, this)) {
            return;
        }
        planSteps.add(msg);
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
