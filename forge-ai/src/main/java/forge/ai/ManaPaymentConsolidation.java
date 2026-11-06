package forge.ai;

import com.google.common.collect.ListMultimap;
import forge.ai.AiCardMemory.MemorySet;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.cost.CostPartMana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Consolidator-vs-disposable reservation policy for {@link ManaPaymentExecution}.
 * Predicate detection stays in {@link ManaFilterConsolidation}; this class owns when a
 * consolidator should be reserved, preferred over a disposable, or used to cover a cost.
 */
final class ManaPaymentConsolidation {
    private ManaPaymentConsolidation() {
    }

    /**
     * Disposables are a last resort. Sacrificing a Petal/Treasure is only reasonable when no reusable
     * free producer exists for this shard and every any-mana filter alternative either cannot be
     * activated without a disposable or would strand the rest of the spell cost.
     */
    static boolean disposableIsReasonableForShard(final SpellAbility disposable,
            final ManaCostBeingPaid cost, final ManaCostShard toPay, final List<SpellAbility> alternatives,
            final Player ai, final ManaPaymentContext ctx) {
        if (!ManaFilterConsolidation.isDisposableManaAbility(disposable) || toPay.isGeneric()) {
            return false;
        }
        final boolean otherColoredShardsCovered = cost.getGenericManaAmount() == 0
                && hasReusableFreeProducerForEveryOtherColoredShard(cost, toPay, ai, ctx);
        if (ManaPaymentExecution.hasAlternativeExcept(alternatives, disposable, ma -> ManaPaymentExecution.isReusableFreeManaForShard(ma, toPay))) {
            return false;
        }
        final ListMultimap<Integer, SpellAbility> manaAbilityMap = ComputerUtilMana.getOrBuildManaAbilityMap(ai, true, ctx);
        final List<SpellAbility> filterAlts = consolidatingFilterAlternatives(alternatives, disposable);
        if (filterAlts.isEmpty()) {
            return otherColoredShardsCovered;
        }
        if (filterAlts.stream().anyMatch(ma -> consolidatorBeatsDisposable(ma, cost, toPay, ai, manaAbilityMap, ctx))) {
            return false;
        }
        // Reasonable only when this colored pip has no reusable cover and either the rest of the
        // colored cost is covered, or this disposable is the sole candidate for the pip (Black Lotus
        // for {R} when the board is only Swamps). Never treat "generic still unpaid" alone as enough —
        // that burned Lotus on {2}{B}{B} whenever Mana Crypt could cover {2}.
        if (otherColoredShardsCovered) {
            return true;
        }
        return !ManaPaymentExecution.hasAlternativeExcept(alternatives, disposable,
                ma -> ManaPaymentExecution.isReusableFreeManaForShard(ma, toPay)
                        || (ma != disposable && ManaPaymentExecution.isFreeDisposableForShard(ma, toPay)));
    }

    static List<SpellAbility> consolidatingFilterAlternatives(final List<SpellAbility> alternatives,
            final SpellAbility disposable) {
        return alternatives.stream()
                .filter(ma -> ma != disposable && (ManaFilterConsolidation.isAnyManaConsolidatingFilter(ma)
                        || ManaFilterConsolidation.isMultiPipActivationFilter(ma) || ManaFilterConsolidation.isComboConsolidatingFilter(ma)))
                .collect(Collectors.toList());
    }

    /** Produced mana exceeds the filter's activation cost (e.g. Signet {1} -> {G}{W}). */
    static boolean isNetPositiveConsolidator(final SpellAbility filter) {
        final ManaSourceTraits t = ManaSourceTraits.of(filter);
        // Storage / disposable multi must never run the consolidating whole-cost path.
        return t.netPositiveConsolidator && !t.variableX && !t.disposable;
    }

    /**
     * Keep a multi-shard signet available when the spell being paid is generic-only but hand/command
     * still needs a colored pip that only this consolidator can supply (see
     * {@code genericCostPreservesSignetForHandSpell}). Mono-color hand spells with another producer
     * for that color (e.g. Eternal Witness with a Forest while Sungrass Prairie is on board) do not
     * reserve the consolidator.
     * <p>
     * Never reserve when this cost cannot be paid without the consolidator (e.g. Study Hall + Sungrass
     * Prairie for Equip {@code {2}} with a GW spell in hand) — otherwise the planner spends the free
     * activator on the first pip and fails.
     */
    static boolean shouldReserveConsolidator(final SpellAbility filter, final SpellAbility sa,
            final ManaCostBeingPaid cost, final Player ai, final ManaPaymentContext ctx) {
        if (!ManaFilterConsolidation.isMultiPipActivationFilter(filter) || cost.getGenericManaAmount() <= 0) {
            return false;
        }
        int coloredUnpaid = ManaPaymentExecution.countUnpaidPips(cost) - cost.getGenericManaAmount();
        if (coloredUnpaid > 0) {
            return false;
        }
        if (!handHasSpellDependingOnConsolidator(filter, sa, ai, ctx)) {
            return false;
        }
        return hasAlternativeCoverageForGenericCost(filter, cost, ai, ctx);
    }

    /**
     * True when free (no mana activation cost) sources other than {@code consolidator}'s host can cover
     * the unpaid pips of a generic-only cost — so the consolidator can be saved for hand/command.
     */
    static boolean hasAlternativeCoverageForGenericCost(final SpellAbility consolidator,
            final ManaCostBeingPaid cost, final Player ai, final ManaPaymentContext ctx) {
        final int need = ManaPaymentExecution.countUnpaidPips(cost);
        if (need <= 0 || ai == null || consolidator == null) {
            return false;
        }
        final Card exclude = consolidator.getHostCard();
        return freeGenericSourcesCover(need, host -> host == exclude, ai, ctx);
    }

    /**
     * True when unreserved free (no mana activation cost) generic-capable sources on the battlefield, minus
     * hosts matching {@code excluded}, produce at least {@code need} mana (each host counted once).
     */
    static boolean freeGenericSourcesCover(final int need, final Predicate<Card> excluded, final Player ai,
            final ManaPaymentContext ctx) {
        return freeGenericSourcesCover(need, excluded, ai, ctx, false);
    }

    /**
     * @param reusableOnly when true, skip one-shots (Petal, Treasure) — used when asking whether a filter
     *                     activation still leaves spell generic payable without burning a disposable.
     */
    static boolean freeGenericSourcesCover(final int need, final Predicate<Card> excluded, final Player ai,
            final ManaPaymentContext ctx, final boolean reusableOnly) {
        final ListMultimap<Integer, SpellAbility> map = ComputerUtilMana.getOrBuildManaAbilityMap(ai, true, ctx);
        final Set<Card> counted = new HashSet<>();
        int available = 0;
        for (final SpellAbility ma : map.get(ManaAtom.GENERIC)) {
            final Card host = ma.getHostCard();
            if (host == null || excluded.test(host) || counted.contains(host)) {
                continue;
            }
            // Skip paid filters first so Study Hall's {1}:any does not mark the host before {T}:{C}.
            if (ManaFilterConsolidation.hasManaActivationCost(ma)) {
                continue;
            }
            if (reusableOnly && ManaFilterConsolidation.isDisposableManaAbility(ma)) {
                continue;
            }
            if (ManaPaymentExecution.hasTapCost(ma) && (host.isTapped()
                    || AiCardMemory.isRememberedCard(ai, host, MemorySet.PAYS_TAP_COST))) {
                continue;
            }
            if (AiCardMemory.isRememberedCard(ai, host, MemorySet.PAYS_SAC_COST)
                    || AiCardMemory.isRememberedCard(ai, host, MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL)) {
                continue;
            }
            counted.add(host);
            available += Math.max(1, ManaFilterConsolidation.getManaProducedAmount(ma));
            if (available >= need) {
                return true;
            }
        }
        return false;
    }

    static boolean handHasSpellDependingOnConsolidator(final SpellAbility filter, final SpellAbility sa,
            final Player ai, final ManaPaymentContext ctx) {
        final Card consolidatorHost = filter.getHostCard();
        final Set<ManaCostShard> consolidatorColors = getColoredShardsProducedByConsolidator(filter);
        if (consolidatorColors.isEmpty()) {
            return false;
        }
        final ListMultimap<Integer, SpellAbility> manaAbilityMap = ComputerUtilMana.getOrBuildManaAbilityMap(ai, true, ctx);
        final Card being = sa.getHostCard();
        for (final ZoneType zone : ManaPaymentExecution.HAND_AND_COMMAND) {
            for (Card c : ai.getCardsIn(zone)) {
                if (c == being) {
                    continue;
                }
                if (spellDependsOnConsolidatorForColor(c, consolidatorColors, consolidatorHost, manaAbilityMap, ai)) {
                    return true;
                }
            }
        }
        return false;
    }

    static Set<ManaCostShard> getColoredShardsProducedByConsolidator(final SpellAbility filter) {
        final Set<ManaCostShard> result = new HashSet<>();
        final AbilityManaPart mp = filter.getManaPart();
        if (mp == null) {
            return result;
        }
        for (final String color : mp.mana(filter).split(" ")) {
            if (color.isEmpty() || "C".equals(color)) {
                continue;
            }
            result.add(ManaCostShard.valueOf(ManaAtom.fromName(color)));
        }
        return result;
    }

    static boolean spellDependsOnConsolidatorForColor(final Card handSpell,
            final Set<ManaCostShard> consolidatorColors, final Card consolidatorHost,
            final ListMultimap<Integer, SpellAbility> manaAbilityMap, final Player ai) {
        final ManaCost mc = handSpell.getManaCost();
        if (mc == null || mc.isNoCost()) {
            return false;
        }
        for (final ManaCostShard needed : mc) {
            if (needed.isGeneric() || needed == ManaCostShard.COLORLESS || needed.isPhyrexian()) {
                continue;
            }
            if (!consolidatorProducesShard(needed, consolidatorColors)) {
                continue;
            }
            if (!hasOtherReusableProducerForShard(manaAbilityMap, needed, consolidatorHost, ai)) {
                // A disposable (Petal, Treasure) can still cover the hand spell's pip; use the
                // consolidator on the spell being paid instead of hoarding it.
                if (!hasDisposableProducerForShard(manaAbilityMap, needed, consolidatorHost, ai)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean hasDisposableProducerForShard(
            final ListMultimap<Integer, SpellAbility> manaAbilityMap, final ManaCostShard shard,
            final Card excludeHost, final Player ai) {
        for (final byte color : ManaAtom.MANATYPES) {
            if (!shard.canBePaidWithManaOfColor(color)) {
                continue;
            }
            for (final SpellAbility ma : manaAbilityMap.get((int) color)) {
                if (ma.getHostCard() == excludeHost || !ManaFilterConsolidation.isDisposableManaAbility(ma)) {
                    continue;
                }
                if (AiCardMemory.isRememberedCard(ai, ma.getHostCard(), MemorySet.PAYS_SAC_COST)) {
                    continue;
                }
                final AbilityManaPart mp = ma.getManaPart();
                if (mp != null && (mp.isAnyMana() || mp.canProduce(shard.toShortString(), ma))) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean consolidatorProducesShard(final ManaCostShard needed,
            final Set<ManaCostShard> consolidatorColors) {
        for (final ManaCostShard produced : consolidatorColors) {
            if (needed == produced) {
                return true;
            }
        }
        return false;
    }

    static boolean hasOtherReusableProducerForShard(
            final ListMultimap<Integer, SpellAbility> manaAbilityMap, final ManaCostShard shard,
            final Card excludeHost, final Player ai) {
        for (final byte color : ManaAtom.MANATYPES) {
            if (!shard.canBePaidWithManaOfColor(color)) {
                continue;
            }
            for (final SpellAbility ma : manaAbilityMap.get((int) color)) {
                if (ma.getHostCard() == excludeHost) {
                    continue;
                }
                if (!ManaPaymentExecution.isReusableFreeManaForShard(ma, shard)) {
                    continue;
                }
                if (ManaPaymentExecution.hasTapCost(ma)
                        && AiCardMemory.isRememberedCard(ai, ma.getHostCard(), MemorySet.PAYS_TAP_COST)) {
                    continue;
                }
                if (AiCardMemory.isRememberedCard(ai, ma.getHostCard(), MemorySet.PAYS_SAC_COST)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    static ManaCostShard shardForConsolidatorProbe(final ManaCostBeingPaid cost) {
        for (final ManaCostShard shard : cost.getDistinctShards()) {
            if (!shard.isGeneric() && shard != ManaCostShard.COLORLESS && !shard.isPhyrexian()
                    && cost.getUnpaidShards(shard) > 0) {
                return shard;
            }
        }
        return ManaCostShard.GENERIC;
    }

    /**
     * True when a net-positive consolidator can pay the entire remaining spell cost (including nested
     * activation), e.g. Lotus Petal -> Signet {1} -> {G}{W} for {1}{W}.
     * <p>
     * Also true when the consolidator pays all colored pips and leftover generic/colorless is still
     * covered by free sources not consumed by that activation (e.g. Petal -> Graven Cairns for
     * {@code {B}{R}}, Study Hall {@code {C}} for {@code {1}}).
     */
    static boolean consolidatorCoversSpellCost(final SpellAbility filter, final ManaCostBeingPaid cost,
            final SpellAbility sa, final Player ai, final ManaPaymentContext ctx, final boolean test) {
        return consolidatorCoverage(filter, cost, sa, ai, ctx, test) != null;
    }

    /**
     * Same as {@link #consolidatorCoversSpellCost} but returns the cards consumed by the consolidator's
     * activation (so callers need not re-run that nested dry-run), or {@code null} when it does not cover.
     */
    static Set<Card> consolidatorCoverage(final SpellAbility filter, final ManaCostBeingPaid cost,
            final SpellAbility sa, final Player ai, final ManaPaymentContext ctx, final boolean test) {
        final int costKey = ManaPaymentExecution.unpaidProgressKey(cost);
        final ManaPaymentContext.ManaPaymentPlanCache cache =
                (!ManaPaymentContext.skipOptimizations() && ctx != null) ? ctx.caches : null;
        if (cache != null) {
            final ManaPaymentContext.ConsolidatorCoverageMemo memo = cache.consolidatorCoverageByFilter.get(filter);
            if (memo != null && memo.costKey == costKey) {
                return memo.consumed == null ? null : new HashSet<>(memo.consumed);
            }
        }
        final Set<Card> consumed = computeConsolidatorCoverage(filter, cost, sa, ai, ctx, test);
        if (cache != null) {
            cache.consolidatorCoverageByFilter.put(filter,
                    new ManaPaymentContext.ConsolidatorCoverageMemo(costKey,
                            consumed == null ? null : new HashSet<>(consumed)));
        }
        return consumed;
    }

    static Set<Card> computeConsolidatorCoverage(final SpellAbility filter, final ManaCostBeingPaid cost,
            final SpellAbility sa, final Player ai, final ManaPaymentContext ctx, final boolean test) {
        if (!isNetPositiveConsolidator(filter) || shouldReserveConsolidator(filter, sa, cost, ai, ctx)) {
            return null;
        }
        if (!canActivateFilter(ai, filter, ComputerUtilMana.getOrBuildManaAbilityMap(ai, true, ctx), false)) {
            return null;
        }
        final Set<Card> consumed = ManaPaymentExecution.collectCardsConsumedByPayment(filter, sa, cost, ai, ctx);
        if (consumed == null) {
            return null;
        }
        final ManaCostShard probeShard = shardForConsolidatorProbe(cost);
        ManaPaymentExecution.refreshExpressChoice(cost, sa, ai, probeShard, filter);
        final ManaCostBeingPaid probe = new ManaCostBeingPaid(cost);
        ManaPaymentExecution.applyChosenPaymentToCostProbe(probe, filter, ai, probeShard);
        if (probe.isPaid()) {
            return consumed;
        }
        // Leftover must be payable by free reusables — do not treat Black Lotus / Petal as cover
        // (Bastion+Tower for {W}{W} leaving Lotus on leftover {1} is not coverage).
        return remainingAfterConsolidatorCoveredByReusables(probe, consumed, ai, ctx) ? consumed : null;
    }

    /**
     * After a consolidator pays colored pips, true when unpaid generic/colorless can be covered by free
     * reusable sources whose hosts were not consumed (and no colored shards remain). Disposables do not
     * count — otherwise Bastion looks covered by stranding Black Lotus on leftover generic.
     */
    static boolean remainingGenericPayableByUnconsumedFreeSources(final ManaCostBeingPaid remaining,
            final Set<Card> consumed, final Player ai, final ManaPaymentContext ctx) {
        if (remaining == null || remaining.isPaid() || ai == null) {
            return remaining != null && remaining.isPaid();
        }
        if (ManaPaymentExecution.hasUnpaidColoredShards(remaining)) {
            return false;
        }
        final int need = ManaPaymentExecution.countUnpaidPips(remaining);
        if (need <= 0) {
            return true;
        }
        return freeGenericSourcesCover(need, host -> consumed != null && consumed.contains(host), ai, ctx, true);
    }

    /**
     * True when unpaid shards of {@code remaining} are coverable by free reusable producers outside
     * {@code consumed} (no Black Lotus / Petal). Colored pips need a reusable free producer each;
     * generic uses {@link #freeGenericSourcesCover} with {@code reusableOnly}.
     */
    static boolean remainingAfterConsolidatorCoveredByReusables(final ManaCostBeingPaid remaining,
            final Set<Card> consumed, final Player ai, final ManaPaymentContext ctx) {
        if (remaining == null || remaining.isPaid() || ai == null) {
            return remaining != null && remaining.isPaid();
        }
        final Set<Card> used = consumed == null ? new HashSet<>() : new HashSet<>(consumed);
        for (final ManaCostShard shard : remaining.getDistinctShards()) {
            if (shard.isGeneric() || shard == ManaCostShard.COLORLESS || shard.isPhyrexian()) {
                continue;
            }
            final int unpaid = remaining.getUnpaidShards(shard);
            if (unpaid <= 0) {
                continue;
            }
            int producers = 0;
            for (final SpellAbility ma : ComputerUtilMana.getOrBuildUniqueManaAbilities(ai, true, ctx)) {
                final Card host = ma.getHostCard();
                if (host == null || used.contains(host)) {
                    continue;
                }
                if (!ManaPaymentExecution.isReusableFreeManaForShard(ma, shard)) {
                    continue;
                }
                used.add(host);
                producers++;
                if (producers >= unpaid) {
                    break;
                }
            }
            if (producers < unpaid) {
                return false;
            }
        }
        int genericNeed = remaining.getGenericManaAmount();
        if (remaining.getUnpaidShards(ManaCostShard.COLORLESS) > 0) {
            genericNeed += remaining.getUnpaidShards(ManaCostShard.COLORLESS);
        }
        return genericNeed <= 0
                || freeGenericSourcesCover(genericNeed, used::contains, ai, ctx, true);
    }

    static boolean consolidatorCoversRemainingCost(final Collection<SpellAbility> maList,
            final ManaCostBeingPaid cost, final SpellAbility sa, final Player ai,
            final ManaPaymentContext ctx, final boolean test) {
        for (final SpellAbility other : maList) {
            if (ManaFilterConsolidation.isDisposableManaAbility(other) || !ManaPaymentExecution.isConsolidatingCandidate(other)) {
                continue;
            }
            if (consolidatorCoversSpellCost(other, cost, sa, ai, ctx, test)) {
                return true;
            }
        }
        return false;
    }

    /** Study Hall-style filter is preferable to sacrificing a disposable. */
    static boolean anyManaFilterBeatsDisposable(final SpellAbility filter,
            final ManaCostBeingPaid cost, final ManaCostShard payingShard, final Player ai,
            final ListMultimap<Integer, SpellAbility> manaAbilityMap, final ManaPaymentContext ctx) {
        return canActivateFilter(ai, filter, manaAbilityMap, true)
                && !filterActivationCompetesForSpellGeneric(filter, cost, payingShard, ai, ctx)
                && !filterActivationStealsDedicatedColoredProducers(filter, cost, payingShard, ai, ctx)
                // Filter must actually save the disposable: remaining colored pips and spell generic
                // still coverable by reusables after activation (Mox Jet fueling Study Hall for {R}
                // while Swamp is needed for {B} still leaves Petal on {1} — does not qualify).
                && remainingColoredShardsCoveredByReusablesAfterFilterActivation(filter, cost, payingShard, ai, ctx);
    }

    /**
     * After paying {@code payingShard} via {@code filter} (and consuming surplus reusable activators for
     * its mana cost), can every other unpaid colored pip <em>and</em> the spell's generic still be paid
     * by reusable free producers (without a disposable)? Mana Crypt's spare {@code {C}} after funding
     * Study Hall counts toward generic; Mox Jet + Swamp for {@code {1}{B}{R}} does not — after Jet fuels
     * the filter and Swamp pays {@code {B}}, generic still needs Petal, so the filter must not "beat"
     * the disposable for {@code {R}}.
     */
    static boolean remainingColoredShardsCoveredByReusablesAfterFilterActivation(final SpellAbility filter,
            final ManaCostBeingPaid cost, final ManaCostShard payingShard, final Player ai,
            final ManaPaymentContext ctx) {
        if (filter == null || cost == null || ai == null || filter.getPayCosts() == null) {
            return true;
        }
        final CostPartMana costMana = filter.getPayCosts().getCostMana();
        if (costMana == null) {
            return true;
        }
        final int activationGeneric = costMana.getMana().getGenericCost();
        final ListMultimap<Integer, SpellAbility> manaAbilityMap =
                ComputerUtilMana.getOrBuildManaAbilityMap(ai, true, ctx);
        final Set<Card> consumed = new HashSet<>();
        final Card filterHost = filter.getHostCard();
        if (filterHost != null) {
            consumed.add(filterHost);
        }
        int needActivators = Math.max(0, activationGeneric);
        int activatorSurplus = 0;
        if (needActivators > 0) {
            for (final SpellAbility candidate : manaAbilityMap.get(ManaAtom.GENERIC)) {
                if (needActivators <= 0) {
                    break;
                }
                if (!isFreeReusableSourceForNestedActivation(candidate, filter)
                        || !ManaPaymentExecution.isCurrentlyAvailableForNestedActivation(ai, candidate, filter)) {
                    continue;
                }
                final Card host = candidate.getHostCard();
                if (host == null || consumed.contains(host)) {
                    continue;
                }
                if (isDedicatedProducerForOtherUnpaidColoredShard(candidate, cost, payingShard, manaAbilityMap, ai)) {
                    continue;
                }
                final int produced = Math.max(1, ManaFilterConsolidation.getManaProducedAmount(candidate));
                final int used = Math.min(needActivators, produced);
                needActivators -= used;
                activatorSurplus += produced - used;
                consumed.add(host);
            }
            if (needActivators > 0) {
                return false;
            }
        }
        for (final ManaCostShard shard : cost.getDistinctShards()) {
            if (shard.isGeneric() || shard == ManaCostShard.COLORLESS || shard.isPhyrexian()) {
                continue;
            }
            if (payingShard != null && shard == payingShard) {
                continue;
            }
            final int unpaid = cost.getUnpaidShards(shard);
            if (unpaid <= 0) {
                continue;
            }
            int producers = 0;
            final Set<Card> counted = new HashSet<>();
            for (final SpellAbility ma : ComputerUtilMana.getOrBuildUniqueManaAbilities(ai, true, ctx)) {
                final Card host = ma.getHostCard();
                if (host == null || consumed.contains(host) || !counted.add(host)) {
                    continue;
                }
                if (!ManaPaymentExecution.isReusableFreeManaForShard(ma, shard)) {
                    continue;
                }
                // One host can pay only one pip this payment — reserve it so a dual is not
                // counted toward both {W} and {U}.
                consumed.add(host);
                producers++;
                if (producers >= unpaid) {
                    break;
                }
            }
            if (producers < unpaid) {
                return false;
            }
        }
        // Study Hall must not "save" a Petal on a colored pip if that Petal is still required for {1}.
        int genericNeed = Math.max(0, cost.getGenericManaAmount() - activatorSurplus);
        return genericNeed <= 0
                || freeGenericSourcesCover(genericNeed, consumed::contains, ai, ctx, true);
    }

    static boolean consolidatorBeatsDisposable(final SpellAbility filter, final ManaCostBeingPaid cost,
            final ManaCostShard payingShard, final Player ai,
            final ListMultimap<Integer, SpellAbility> manaAbilityMap, final ManaPaymentContext ctx) {
        if (ManaFilterConsolidation.isAnyManaConsolidatingFilter(filter)) {
            return anyManaFilterBeatsDisposable(filter, cost, payingShard, ai, manaAbilityMap, ctx);
        }
        // Combo / multi-pip filters (Wooded Bastion): same bar as Study Hall — activation must leave
        // remaining colored + generic coverable by reusables, not by stranding Black Lotus on leftovers.
        return canActivateFilter(ai, filter, manaAbilityMap, false)
                && !filterActivationCompetesForSpellGeneric(filter, cost, payingShard, ai, ctx)
                && !filterActivationStealsDedicatedColoredProducers(filter, cost, payingShard, ai, ctx)
                && remainingColoredShardsCoveredByReusablesAfterFilterActivation(filter, cost, payingShard, ai, ctx);
    }

    /**
     * True when activating {@code filter} would have to consume a free reusable source that is the only
     * coverage for another unpaid colored pip of this cost (e.g. Island funding Study Hall for {@code {B}}
     * when {@code {U}} still needs that Island). Prefer a disposable for the missing color instead.
     */
    static boolean filterActivationStealsDedicatedColoredProducers(final SpellAbility filter,
            final ManaCostBeingPaid cost, final ManaCostShard payingShard, final Player ai,
            final ManaPaymentContext ctx) {
        if (filter == null || filter.getPayCosts() == null || cost == null || ai == null) {
            return false;
        }
        final CostPartMana costMana = filter.getPayCosts().getCostMana();
        if (costMana == null) {
            return false;
        }
        final int activationGeneric = costMana.getMana().getGenericCost();
        if (activationGeneric <= 0) {
            return false;
        }
        final ListMultimap<Integer, SpellAbility> manaAbilityMap =
                ComputerUtilMana.getOrBuildManaAbilityMap(ai, true, ctx);
        final Set<Card> surplusHosts = new HashSet<>();
        for (final SpellAbility candidate : manaAbilityMap.get(ManaAtom.GENERIC)) {
            if (!isFreeReusableSourceForNestedActivation(candidate, filter)
                    || !ManaPaymentExecution.isCurrentlyAvailableForNestedActivation(ai, candidate, filter)) {
                continue;
            }
            final Card host = candidate.getHostCard();
            if (host == null || surplusHosts.contains(host)) {
                continue;
            }
            if (isDedicatedProducerForOtherUnpaidColoredShard(candidate, cost, payingShard, manaAbilityMap, ai)) {
                continue;
            }
            surplusHosts.add(host);
            if (surplusHosts.size() >= activationGeneric) {
                return false;
            }
        }
        return true;
    }

    /**
     * Drop sole cover for an unpaid colored pip of {@code outerCost} when another free reusable can fund
     * the filter (Mana Crypt over Mountain while {@code {R}} remains). No-op when every candidate is
     * dedicated or {@code outerCost} is unknown (nested probe without the spell cost).
     */
    static void excludeDedicatedColoredProducersForNestedActivation(final List<SpellAbility> freeCandidates,
            final ManaCostBeingPaid outerCost, final Player ai,
            final ListMultimap<Integer, SpellAbility> manaAbilityMap) {
        if (outerCost == null || freeCandidates == null || freeCandidates.size() <= 1 || ai == null) {
            return;
        }
        boolean hasNonDedicated = false;
        for (final SpellAbility ma : freeCandidates) {
            if (!isDedicatedProducerForOtherUnpaidColoredShard(ma, outerCost, null, manaAbilityMap, ai)) {
                hasNonDedicated = true;
                break;
            }
        }
        if (!hasNonDedicated) {
            return;
        }
        freeCandidates.removeIf(ma ->
                isDedicatedProducerForOtherUnpaidColoredShard(ma, outerCost, null, manaAbilityMap, ai));
    }

    /**
     * Drop Black Lotus / other multi-mana one-shots from filter activation when they already produce
     * enough mana to finish {@code outerCost}. Burning Lotus for Study Hall's {@code {1}} then still
     * needing Petal for a color is strictly worse than Lotus + Petal paying the spell directly.
     */
    static void excludeMultiManaDisposablesThatCanCoverOuterCost(final List<SpellAbility> freeCandidates,
            final ManaCostBeingPaid outerCost) {
        if (outerCost == null || freeCandidates == null || freeCandidates.isEmpty()) {
            return;
        }
        final int unpaid = ManaPaymentExecution.countUnpaidPips(outerCost);
        if (unpaid <= 0) {
            return;
        }
        freeCandidates.removeIf(ma -> ManaPaymentExecution.isMultiManaDisposable(ma)
                && ManaFilterConsolidation.getManaProducedAmount(ma) >= unpaid);
    }

    /** True when {@code candidate}'s host is the only reusable free producer for some other unpaid colored pip. */
    static boolean isDedicatedProducerForOtherUnpaidColoredShard(final SpellAbility candidate,
            final ManaCostBeingPaid cost, final ManaCostShard payingShard,
            final ListMultimap<Integer, SpellAbility> manaAbilityMap, final Player ai) {
        final Card host = candidate == null ? null : candidate.getHostCard();
        if (host == null || cost == null) {
            return false;
        }
        for (final ManaCostShard shard : cost.getDistinctShards()) {
            if (shard.isGeneric() || shard == ManaCostShard.COLORLESS || shard.isPhyrexian()) {
                continue;
            }
            if (payingShard != null && shard == payingShard) {
                continue;
            }
            if (cost.getUnpaidShards(shard) <= 0) {
                continue;
            }
            if (!ManaPaymentExecution.isReusableFreeManaForShard(candidate, shard)) {
                continue;
            }
            if (!hasOtherReusableProducerForShard(manaAbilityMap, shard, host, ai)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when paying this filter's activation would consume reusable generic mana sources needed
     * to pay the spell's own generic pips (e.g. one Plains cannot fund Study Hall's {@code {1}} and
     * the spell's {@code {1}}). {@code {1}} accepts any mana; {@code {C}} sources count too.
     *
     * The filter host's own free {@code {C}} (Study Hall) is excluded via sibling conflict — tapping
     * for the paid any-mana line strands that path — so compete only when other reusable colorless
     * sources cannot cover spell generic + activation. Surplus rocks (Mind Stone, Thought Vessel, …)
     * and multi-mana rocks (Mana Crypt's {@code {C}{C}}) let Study Hall pay a colored pip instead of
     * burning a Lotus Petal.
     * <p>
     * Sources that are the sole reusable cover for another unpaid colored pip (e.g. the only Plains
     * for {@code {W}} while Study Hall would pay {@code {G}}) do not count as surplus — otherwise
     * Study Hall looks funded, burns the rock on activation, and still spends Petal on spell generic.
     * <p>
     * Cover is mana amount per host (not host count): one Mana Crypt covers both Study Hall's
     * {@code {1}} and the spell's {@code {1}}.
     */
    static boolean filterActivationCompetesForSpellGeneric(final SpellAbility filter,
            final ManaCostBeingPaid cost, final ManaCostShard payingShard, final Player ai,
            final ManaPaymentContext ctx) {
        if (cost.getGenericManaAmount() <= 0 || ai == null) {
            return false;
        }
        final CostPartMana costMana = filter.getPayCosts().getCostMana();
        if (costMana == null) {
            return false;
        }
        final int activationGeneric = costMana.getMana().getGenericCost();
        if (activationGeneric <= 0) {
            return false;
        }
        final ListMultimap<Integer, SpellAbility> manaAbilityMap = ComputerUtilMana.getOrBuildManaAbilityMap(ai, true, ctx);
        final int needed = cost.getGenericManaAmount() + activationGeneric;
        // Unique hosts: multiple free abilities on one rock must not inflate the cover count.
        // Sum produced amount so Mana Crypt ({C}{C}) covers activation + spell generic alone.
        final HashSet<Card> countedHosts = new HashSet<>();
        int available = 0;
        for (final SpellAbility candidate : manaAbilityMap.get(ManaAtom.GENERIC)) {
            if (!isFreeReusableSourceForNestedActivation(candidate, filter)
                    || !ManaPaymentExecution.isCurrentlyAvailableForNestedActivation(ai, candidate, filter)) {
                continue;
            }
            final Card host = candidate.getHostCard();
            if (host == null || countedHosts.contains(host)) {
                continue;
            }
            if (isDedicatedProducerForOtherUnpaidColoredShard(candidate, cost, payingShard, manaAbilityMap, ai)) {
                continue;
            }
            countedHosts.add(host);
            available += Math.max(1, ManaFilterConsolidation.getManaProducedAmount(candidate));
            if (available >= needed) {
                return false;
            }
        }
        return available < needed;
    }

    /** Free nested-activation source that is not a one-shot (Petal, Treasure, etc.). */
    static boolean isFreeReusableSourceForNestedActivation(final SpellAbility ma, final SpellAbility filterAb) {
        return ManaPaymentExecution.isFreeManaSourceForNestedActivation(ma, filterAb) && !ManaFilterConsolidation.isDisposableManaAbility(ma);
    }

    /**
     * True when a filter's activation cost can be paid by free sources on the battlefield.
     * {@code reusableOnly} excludes one-shot sources (Petal, Treasure) and, when true, only
     * considers any-mana filters (Study Hall).
     */
    static boolean canActivateFilter(final Player ai, final SpellAbility filter,
            final ListMultimap<Integer, SpellAbility> manaAbilityMap, final boolean reusableOnly) {
        if (filter.getPayCosts() == null || !filter.getPayCosts().hasManaCost()) {
            return false;
        }
        final AbilityManaPart mp = filter.getManaPart();
        if (mp == null) {
            return false;
        }
        if (reusableOnly) {
            if (!ManaFilterConsolidation.isAnyManaConsolidatingFilter(filter)) {
                return false;
            }
        } else if (!ManaFilterConsolidation.isMultiPipActivationFilter(filter) && !ManaFilterConsolidation.isComboConsolidatingFilter(filter)
                && !mp.isAnyMana()) {
            return false;
        }
        final CostPartMana costMana = filter.getPayCosts().getCostMana();
        if (costMana == null) {
            return false;
        }
        final ManaCost activation = reusableOnly ? costMana.getManaCostFor(filter) : costMana.getMana();
        if (activation.getGenericCost() > 0
                && !hasActivatorInGenericBucket(ai, manaAbilityMap, filter, reusableOnly)) {
            return false;
        }
        for (final ManaCostShard shard : activation) {
            if (reusableOnly && (shard.isGeneric() || shard == ManaCostShard.COLORLESS)) {
                continue;
            }
            if (!hasActivatorForShard(ai, manaAbilityMap, shard, filter, reusableOnly)) {
                return false;
            }
        }
        return true;
    }

    /** {@code {1}} activation: any free tap in the generic bucket (all mana sources are indexed here). */
    static boolean hasActivatorInGenericBucket(final Player ai,
            final ListMultimap<Integer, SpellAbility> manaAbilityMap, final SpellAbility filter,
            final boolean reusableOnly) {
        for (final SpellAbility candidate : manaAbilityMap.get(ManaAtom.GENERIC)) {
            if (reusableOnly) {
                if (!isFreeReusableSourceForNestedActivation(candidate, filter)) {
                    continue;
                }
            } else if (!ManaPaymentExecution.isFreeManaSourceForNestedActivation(candidate, filter)) {
                continue;
            }
            if (ManaPaymentExecution.isCurrentlyAvailableForNestedActivation(ai, candidate, filter)) {
                return true;
            }
        }
        return false;
    }

    /** True when a free activator can pay this colored/hybrid shard. */
    static boolean hasActivatorForShard(final Player ai,
            final ListMultimap<Integer, SpellAbility> manaAbilityMap, final ManaCostShard shard,
            final SpellAbility filter, final boolean reusableOnly) {
        for (final byte color : ManaAtom.MANATYPES) {
            if (!shard.canBePaidWithManaOfColor(color)) {
                continue;
            }
            for (final SpellAbility candidate : manaAbilityMap.get((int) color)) {
                if (reusableOnly) {
                    if (!isFreeReusableSourceForNestedActivation(candidate, filter)) {
                        continue;
                    }
                } else if (!ManaPaymentExecution.isFreeManaSourceForNestedActivation(candidate, filter)) {
                    continue;
                }
                if (ManaPaymentExecution.isCurrentlyAvailableForNestedActivation(ai, candidate, filter)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when every unpaid colored shard of {@code cost} can be paid by a reusable free source on the
     * battlefield. When {@code exclude} is non-null, that shard is skipped (e.g. the shard being paid now).
     */
    static boolean hasReusableFreeProducerForColoredShards(final ManaCostBeingPaid cost,
            final ManaCostShard exclude, final Player ai, final ManaPaymentContext ctx) {
        if (cost == null || ai == null) {
            return false;
        }
        final ListMultimap<Integer, SpellAbility> manaAbilityMap = ComputerUtilMana.getOrBuildManaAbilityMap(ai, true, ctx);
        boolean found = false;
        for (final ManaCostShard shard : cost.getDistinctShards()) {
            if (shard.isGeneric() || shard == ManaCostShard.COLORLESS || shard.isPhyrexian()) {
                continue;
            }
            if (exclude != null && shard == exclude) {
                continue;
            }
            if (cost.getUnpaidShards(shard) <= 0) {
                continue;
            }
            found = true;
            if (!hasActivatorForShard(ai, manaAbilityMap, shard, null, true)) {
                return false;
            }
        }
        return found;
    }

    static boolean hasReusableFreeProducerForEveryOtherColoredShard(final ManaCostBeingPaid cost,
            final ManaCostShard payingShard, final Player ai, final ManaPaymentContext ctx) {
        return payingShard != null && !payingShard.isGeneric()
                && hasReusableFreeProducerForColoredShards(cost, payingShard, ai, ctx);
    }

    static boolean hasReusableFreeProducerForEveryColoredShard(final ManaCostBeingPaid cost,
            final Player ai, final ManaPaymentContext ctx) {
        return hasReusableFreeProducerForColoredShards(cost, null, ai, ctx);
    }
}
