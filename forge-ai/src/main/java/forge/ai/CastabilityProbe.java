package forge.ai;

import com.google.common.collect.ListMultimap;
import forge.ai.AiCardMemory.MemorySet;
import forge.ai.ManaAbilitySort.GenericColorPreference;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.cost.CostPartMana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Castability-aware mana source selection: counts hand/command spells still castable after each
 * candidate source is reserved. Can be disabled for performance on low-end devices.
 */
public final class CastabilityProbe {
    /** Max candidates evaluated per castability probe on large boards. */
    static final int CANDIDATE_CAP = 5;
    /** Soft CMC cap: skip castability dry-runs above this CMC when total mana budget is insufficient. */
    private static final int SOFT_CMC_CAP = 3;

    /** System property {@code forge.manaPayment.castabilityProbe} overrides preference when set. */
    private static final String SYS_PROP = "forge.manaPayment.castabilityProbe";

    private static volatile boolean defaultEnabled = true;
    /** Resolved once from {@link #SYS_PROP} + {@link #defaultEnabled}; cleared by the setters below. */
    private static volatile Boolean resolvedEnabled;
    private static int dryRunCountForTests;

    private CastabilityProbe() {
    }

    /** Whether castability probing is active (preference / system property). */
    public static boolean isEnabled() {
        Boolean enabled = resolvedEnabled;
        if (enabled == null) {
            final String prop = System.getProperty(SYS_PROP);
            enabled = prop != null ? Boolean.parseBoolean(prop) : defaultEnabled;
            resolvedEnabled = enabled;
        }
        return enabled;
    }

    /** Set default from Forge preferences at startup (see {@link forge.model.FModel}). */
    public static void setDefaultEnabled(final boolean enabled) {
        defaultEnabled = enabled;
        resolvedEnabled = null;
    }

    /** Test hook: enable probe and clear JVM override (see {@link forge.ai.controller.AutoPaymentTest}). */
    public static void enableForTests() {
        System.clearProperty(SYS_PROP);
        defaultEnabled = true;
        resolvedEnabled = null;
        ManaPaymentTracer.refreshFlags();
    }

    /**
     * Castability-aware source comparison during payment. Enabled for human payment-prompt Auto preview
     * and for AI production payment of hand/command spells only.
     */
    static boolean shouldUse(final SpellAbility sa, final boolean test, final ManaPaymentContext ctx) {
        if (!isEnabled()) {
            return false;
        }
        final Card host = sa.getHostCard();
        if (host == null) {
            return false;
        }
        if (host.isInZone(ZoneType.Hand) || host.isInZone(ZoneType.Command)) {
            return test ? ctx != null && ctx.paymentPromptPreview : true;
        }
        return false;
    }

    /** Nested filter activations use castability only when the spell being paid is in hand/command. */
    static boolean shouldUseForNestedActivation(final SpellAbility sa, final boolean test,
            final ManaPaymentContext ctx) {
        final Card host = sa.getHostCard();
        if (host == null || (!host.isInZone(ZoneType.Hand) && !host.isInZone(ZoneType.Command))) {
            return false;
        }
        return shouldUse(sa, test, ctx);
    }

    @FunctionalInterface
    interface ConsumedBuilder {
        Set<Card> build(SpellAbility cand, SpellAbility sa, Player ai, ManaPaymentContext ctx);
    }

    /**
     * Among candidates, pick the source that leaves the most hand/command spells castable afterwards.
     */
    static SpellAbility pickBest(final ManaCostBeingPaid cost, final List<SpellAbility> candidates,
            final SpellAbility sa, final Player ai, final ManaCostShard toPay, final ConsumedBuilder consumedBuilder,
            final boolean preferMultiForGeneric, final boolean test, final ManaPaymentContext ctx) {
        SpellAbility best = null;
        int bestCastable = -1;
        int bestEfficiency = Integer.MAX_VALUE;
        final boolean multicolorHand = ManaPaymentExecution.handHasMulticolorManaSpells(ai, sa, ctx);
        final ManaPaymentContext probeCtx = ctx.withFilterProbe();
        ManaPaymentExecution.AlternativeScan altScan = null;
        List<SpellAbility> toProbe = capCandidates(candidates);
        if (ManaPaymentContext.fastHeuristics() && toProbe.size() > 1) {
            altScan = ManaPaymentExecution.AlternativeScan.of(candidates, toPay,
                    ManaPaymentExecution.remainingPipsForShard(cost, toPay));
            toProbe = mostEfficient(toProbe, cost, sa, ai, toPay, candidates, altScan, consumedBuilder, probeCtx);
        }
        for (final SpellAbility cand : toProbe) {
            final int castable;
            try (ManaPaymentExecution.ReservationSnapshot snap = ManaPaymentExecution.ReservationSnapshot.take(ai)) {
                final Set<Card> consumed = consumedBuilder.build(cand, sa, ai, probeCtx);
                if (consumed == null) {
                    continue;
                }
                castable = countCastableSpellsAfterPayment(ai, sa, consumed, probeCtx);
            }
            ManaPaymentTracer.logMain(test, () -> "    castability " + cand.getHostCard() + " -> " + castable
                    + " hand/command spells remain", ctx);
            final boolean preferCand = multicolorHand && ManaPaymentExecution.isAnyMultiManaProducer(cand)
                    && preferMultiForGeneric;
            final boolean preferBest = multicolorHand && best != null && ManaPaymentExecution.isAnyMultiManaProducer(best)
                    && preferMultiForGeneric;
            boolean takeCand = false;
            if (castable > bestCastable) {
                takeCand = true;
                bestEfficiency = Integer.MAX_VALUE;
            } else if (castable == bestCastable) {
                if (preferCand && !preferBest) {
                    takeCand = true;
                    bestEfficiency = Integer.MAX_VALUE;
                } else if (!preferCand && !preferBest) {
                    if (altScan == null) {
                        altScan = ManaPaymentExecution.AlternativeScan.of(candidates, toPay,
                                ManaPaymentExecution.remainingPipsForShard(cost, toPay));
                    }
                    final int candEfficiency = ManaPaymentExecution.evaluatePaymentImpact(cost,
                            sa, ai, toPay, cand, candidates, altScan, test, probeCtx).efficiencyScore();
                    if (best == null) {
                        takeCand = true;
                        bestEfficiency = candEfficiency;
                    } else {
                        if (bestEfficiency == Integer.MAX_VALUE) {
                            bestEfficiency = ManaPaymentExecution.evaluatePaymentImpact(cost, sa, ai, toPay, best,
                                    candidates, altScan, test, probeCtx).efficiencyScore();
                        }
                        if (tieBreakPrefers(cand, best, candEfficiency, bestEfficiency, toPay, cost, ai, sa)) {
                            takeCand = true;
                            bestEfficiency = candEfficiency;
                        }
                    }
                }
            }
            if (takeCand) {
                bestCastable = castable;
                best = cand;
            }
        }
        return best;
    }

    /**
     * Opt-in ({@link ManaPaymentContext#fastHeuristics}): keep only the candidates tied on the cheapest
     * {@link ManaPaymentExecution#paymentEfficiencyScore}, so castability dry-runs are spent on real ties
     * instead of every source. Candidates whose consumed set cannot be built are dropped.
     */
    private static List<SpellAbility> mostEfficient(final List<SpellAbility> probeList, final ManaCostBeingPaid cost,
            final SpellAbility sa, final Player ai, final ManaCostShard toPay, final List<SpellAbility> alternatives,
            final ManaPaymentExecution.AlternativeScan altScan, final ConsumedBuilder consumedBuilder,
            final ManaPaymentContext probeCtx) {
        final List<SpellAbility> best = new java.util.ArrayList<>();
        int bestScore = Integer.MAX_VALUE;
        for (final SpellAbility cand : probeList) {
            final int score;
            try (ManaPaymentExecution.ReservationSnapshot snap = ManaPaymentExecution.ReservationSnapshot.take(ai)) {
                final Set<Card> consumed = consumedBuilder.build(cand, sa, ai, probeCtx);
                if (consumed == null) {
                    continue;
                }
                score = ManaPaymentExecution.paymentEfficiencyScore(cand,
                        ManaPaymentExecution.effectiveCardsConsumedForPayment(cost, sa, ai, toPay, cand, consumed),
                        cost, toPay, alternatives, altScan, ai, sa, probeCtx);
            }
            if (score < bestScore) {
                bestScore = score;
                best.clear();
            }
            if (score == bestScore) {
                best.add(cand);
            }
        }
        return best.isEmpty() ? probeList : best;
    }

    /** Record a colored-shard failure with zero candidates during a castability nested dry-run. */
    static void recordNoSourceColoredShardFailure(final ManaPaymentContext ctx, final ManaCostShard toPay,
            final Collection<SpellAbility> saList) {
        if (ctx == null || ctx.caches.castabilityProbe.availableManaAfterReservation < 0
                || toPay == null || toPay.isGeneric() || toPay.isPhyrexian()
                || toPay == ManaCostShard.COLORLESS || toPay == ManaCostShard.X
                || toPay == ManaCostShard.COLORED_X || saList == null || !saList.isEmpty()) {
            return;
        }
        ctx.caches.castabilityProbe.lastFailedColoredShard = toPay;
        ctx.caches.castabilityProbe.lastFailureWasNoSources = true;
    }

    /** Test hook: reset nested castability dry-run counter. */
    public static void resetDryRunCountForTests() {
        dryRunCountForTests = 0;
    }

    /** Test hook: nested castability dry-runs since last reset. */
    public static int getDryRunCountForTests() {
        return dryRunCountForTests;
    }

    static int countCastableSpellsAfterPayment(final Player ai, final SpellAbility spellBeingPaid,
            final Set<Card> consumed, final ManaPaymentContext ctx) {
        final Set<Card> reserved = new HashSet<>(consumed);
        final Set<Card> tapCost = AiCardMemory.getMemorySet(ai, MemorySet.PAYS_TAP_COST);
        if (tapCost != null) {
            reserved.addAll(tapCost);
        }
        final Set<Card> sacCost = AiCardMemory.getMemorySet(ai, MemorySet.PAYS_SAC_COST);
        if (sacCost != null) {
            reserved.addAll(sacCost);
        }
        final ManaPaymentContext.CastabilityProbeScratch probe = ctx.caches.castabilityProbe;
        probe.resetForProbe();
        probe.unavailableColoredShards.addAll(computeUnavailableColoredShards(ai, reserved, ctx));
        probe.availableManaAfterReservation = computeAvailableManaAfterReservation(ai, reserved, ctx);
        int count = countCastableSpellsInZone(ai, spellBeingPaid, reserved, ZoneType.Hand, ctx);
        count += countCastableSpellsInZone(ai, spellBeingPaid, reserved, ZoneType.Command, ctx);
        probe.resetForProbe();
        return count;
    }

    /** First {@link #CANDIDATE_CAP} candidates (bounds the number of nested dry-runs per shard). */
    static List<SpellAbility> capCandidates(final List<SpellAbility> candidates) {
        if (candidates.size() <= CANDIDATE_CAP) {
            return candidates;
        }
        return candidates.subList(0, CANDIDATE_CAP);
    }

    private static int countCastableSpellsInZone(final Player ai, final SpellAbility spellBeingPaid,
            final Set<Card> consumed, final ZoneType zone, final ManaPaymentContext ctx) {
        int count = 0;
        final Card being = spellBeingPaid.getHostCard();
        final ManaPaymentContext.CastabilityProbeScratch probe = ctx.caches.castabilityProbe;
        for (Card c : ai.getCardsIn(zone)) {
            if (c == being) {
                continue;
            }
            for (SpellAbility candSa : c.getSpellAbilities()) {
                if (!candSa.isSpell() || candSa.getPayCosts() == null || !candSa.getPayCosts().hasManaCost()) {
                    continue;
                }
                candSa.setActivatingPlayer(ai);
                if (isUncastableByTotalManaBudget(candSa, probe.availableManaAfterReservation)) {
                    continue;
                }
                final CostPartMana costMana = candSa.getPayCosts().getCostMana();
                if (costMana == null) {
                    continue;
                }
                final ManaCost mc = costMana.getMana();
                if (spellRequiresUnavailableColoredShard(mc, probe.unavailableColoredShards)) {
                    continue;
                }
                if (canPayManaCostExcluding(candSa, ai, consumed, ctx)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static boolean spellRequiresUnavailableColoredShard(final ManaCost mc,
            final Set<ManaCostShard> unavailableColoredShards) {
        if (mc == null || unavailableColoredShards == null || unavailableColoredShards.isEmpty()) {
            return false;
        }
        final ManaCostBeingPaid probe = new ManaCostBeingPaid(mc);
        for (final ManaCostShard shard : probe.getDistinctShards()) {
            if (shard.isGeneric() || shard == ManaCostShard.COLORLESS || shard.isPhyrexian()
                    || shard == ManaCostShard.X || shard == ManaCostShard.COLORED_X) {
                continue;
            }
            if (unavailableColoredShards.contains(shard)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUncastableByTotalManaBudget(final SpellAbility candSa, final int availableMana) {
        if (availableMana < 0 || candSa == null || candSa.getPayCosts() == null
                || !candSa.getPayCosts().hasManaCost()) {
            return false;
        }
        final int cmc = candSa.getPayCosts().getCostMana().getMana().getCMC();
        return cmc > SOFT_CMC_CAP && cmc > availableMana;
    }

    private static Set<ManaCostShard> computeUnavailableColoredShards(final Player ai, final Set<Card> reserved,
            final ManaPaymentContext ctx) {
        final Set<ManaCostShard> unavailable = new HashSet<>();
        final ListMultimap<Integer, SpellAbility> map = ComputerUtilMana.getOrBuildManaAbilityMap(ai, true, ctx);
        for (final ManaCostShard shard : ManaCostShard.values()) {
            if (shard.isGeneric() || shard == ManaCostShard.COLORLESS || shard.isPhyrexian()
                    || shard == ManaCostShard.X || shard == ManaCostShard.COLORED_X) {
                continue;
            }
            if (!hasAvailableProducerForShard(ai, map, shard, reserved)) {
                unavailable.add(shard);
            }
        }
        return unavailable;
    }

    private static boolean hasAvailableProducerForShard(final Player ai,
            final ListMultimap<Integer, SpellAbility> manaAbilityMap, final ManaCostShard shard,
            final Set<Card> reserved) {
        for (final byte color : ManaAtom.MANATYPES) {
            if (!shard.canBePaidWithManaOfColor(color)) {
                continue;
            }
            for (final SpellAbility candidate : manaAbilityMap.get((int) color)) {
                if (isManaSourceAvailableAfterReservation(ai, candidate, reserved)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isManaSourceAvailableAfterReservation(final Player ai, final SpellAbility ma,
            final Set<Card> reserved) {
        if (ma == null || ai == null) {
            return false;
        }
        final Card host = ma.getHostCard();
        if (host == null || reserved.contains(host)) {
            return false;
        }
        if (AiCardMemory.isRememberedCard(ai, host, MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL)) {
            return false;
        }
        if (AiCardMemory.isRememberedCard(ai, host, MemorySet.PAYS_SAC_COST)) {
            return false;
        }
        if (ma.getPayCosts() != null && ma.getPayCosts().hasTapCost()) {
            if (AiCardMemory.isRememberedCard(ai, host, MemorySet.PAYS_TAP_COST) || host.isTapped()) {
                return false;
            }
        }
        ma.setActivatingPlayer(ai);
        return ma.canPlay();
    }

    private static int computeAvailableManaAfterReservation(final Player ai, final Set<Card> reserved,
            final ManaPaymentContext ctx) {
        int available = ai.getManaPool().totalMana();
        final Set<Card> seenHosts = new HashSet<>();
        for (final SpellAbility ma : ComputerUtilMana.getOrBuildUniqueManaAbilities(ai, true, ctx)) {
            final Card host = ma.getHostCard();
            if (host == null || seenHosts.contains(host)) {
                continue;
            }
            // Single availability (canPlay) pass per ability; the host counts only when {@code ma} itself
            // is available, and contributes the largest amount among its available mana abilities.
            boolean gateChecked = false;
            boolean gate = false;
            int maxForHost = 0;
            for (final SpellAbility ma2 : host.getManaAbilities()) {
                final boolean avail = isManaSourceAvailableAfterReservation(ai, ma2, reserved);
                if (ma2 == ma) {
                    gateChecked = true;
                    gate = avail;
                }
                if (avail) {
                    maxForHost = Math.max(maxForHost, ma2.amountOfManaGenerated(true));
                }
            }
            if (!gateChecked) {
                gate = isManaSourceAvailableAfterReservation(ai, ma, reserved);
            }
            if (!gate) {
                continue;
            }
            seenHosts.add(host);
            available += maxForHost;
        }
        return available;
    }

    private static boolean canPayManaCostExcluding(final SpellAbility candSa, final Player ai, final Set<Card> consumed,
            final ManaPaymentContext ctx) {
        final ManaPaymentContext.CastabilityProbeScratch probe = ctx.caches.castabilityProbe;
        probe.clearLastFailure();
        try (ManaPaymentExecution.ReservationSnapshot snap =
                ManaPaymentExecution.ReservationSnapshot.take(ai).holdingForNextSpell(consumed)) {
            dryRunCountForTests++;
            final boolean result = ComputerUtilMana.payManaCostForCastabilityProbe(candSa.getPayCosts(), candSa, ai,
                    ctx);
            if (!result && probe.lastFailureWasNoSources && probe.lastFailedColoredShard != null) {
                probe.unavailableColoredShards.add(probe.lastFailedColoredShard);
            }
            return result;
        } finally {
            probe.clearLastFailure();
        }
    }

    private static boolean tieBreakPrefers(final SpellAbility cand, final SpellAbility best,
            final int candEfficiency, final int bestEfficiency, final ManaCostShard toPay,
            final ManaCostBeingPaid cost, final Player ai, final SpellAbility sa) {
        if (candEfficiency < bestEfficiency) {
            return true;
        }
        if (candEfficiency > bestEfficiency || best == null) {
            return false;
        }
        if (toPay != ManaCostShard.GENERIC && toPay != ManaCostShard.X) {
            return false;
        }
        final GenericColorPreference pref = ManaAbilitySort.genericColorPreferenceForNestedActivation(ai, sa, cost);
        return ManaAbilitySort.rankGenericManaSource(cand, pref)
                < ManaAbilitySort.rankGenericManaSource(best, pref);
    }
}
