package forge.ai;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import forge.ai.AiCardMemory.MemorySet;
import forge.card.MagicColor;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mana-source ranking and sorting for {@link ComputerUtilMana} payment planning.
 * Extracted from {@link ManaPaymentExecution}.
 */
final class ManaAbilitySort {
    /**
     * Small fixed headroom used when trimFungibleManaCandidates caps how many interchangeable mana
     * sources stay in each shard bucket after sorting.
     */
    private static final int FUNGIBLE_CANDIDATE_BUFFER = 2;

    private ManaAbilitySort() {
    }

    /**
     * How generic mana pips should be paid relative to colorless vs colored sources.
     * Lower {@link #rankGenericManaSource} ranks are better.
     */
    public enum GenericColorPreference {
        /** Colorless rocks carry generic; colored basics are acceptable fallback. */
        DEFAULT,
        /** Every colored pip has a reusable producer — spend {C} on generic, keep colored basics. */
        PREFER_COLORLESS,
        /** Hand still needs dedicated {C} pips (Eldrazi, etc.) — save rocks, spend colored on generic. */
        RESERVE_COLORLESS;

        boolean reservesColorless() {
            return this == RESERVE_COLORLESS;
        }
    }

    static GenericColorPreference genericColorPreference(final Player ai, final SpellAbility sa,
            final ManaCostBeingPaid cost, final int coloredShardCount,
            final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards,
            final ManaPaymentContext paymentCtx) {
        if (shouldReserveColorlessMana(ai, sa)) {
            return GenericColorPreference.RESERVE_COLORLESS;
        }
        if (coloredShardCount > 0
                && ManaPaymentExecution.hasReusableFreeProducerForEveryColoredShard(cost, ai, paymentCtx)
                && genericBucketHasColorlessSource(sourcesForShards)) {
            return GenericColorPreference.PREFER_COLORLESS;
        }
        // Colored shards may already be paid; still tap {C} before another colored basic for generic.
        if (genericBucketHasColorlessSource(sourcesForShards)
                && !ManaPaymentExecution.hasUnpaidColoredShards(cost)) {
            return GenericColorPreference.PREFER_COLORLESS;
        }
        return GenericColorPreference.DEFAULT;
    }

    /** Match nested filter activation / {@link ComputerUtilMana#chooseManaAbility} generic ranking. */
    static GenericColorPreference genericColorPreferenceForNestedActivation(final Player ai,
            final SpellAbility sa, final ManaCostBeingPaid cost) {
        if (ManaPaymentExecution.coloredBasicTappedThisPayment(ai)) {
            return GenericColorPreference.PREFER_COLORLESS;
        }
        if (shouldReserveColorlessMana(ai, sa)) {
            return GenericColorPreference.RESERVE_COLORLESS;
        }
        if (!ManaPaymentExecution.hasUnpaidColoredShards(cost)) {
            return GenericColorPreference.PREFER_COLORLESS;
        }
        return GenericColorPreference.DEFAULT;
    }

    /** Preference for paying generic shards this iteration (colored-basic-already-tapped wins over reserve). */
    public static GenericColorPreference resolveGenericColorPreference(final Player ai, final SpellAbility sa) {
        if (ManaPaymentExecution.coloredBasicTappedThisPayment(ai)) {
            return GenericColorPreference.PREFER_COLORLESS;
        }
        if (shouldReserveColorlessMana(ai, sa)) {
            return GenericColorPreference.RESERVE_COLORLESS;
        }
        return GenericColorPreference.DEFAULT;
    }

    public static int compareGenericCandidatesForPayment(final SpellAbility a, final SpellAbility b,
            final GenericColorPreference pref, final int unpaidGeneric, final SpellAbility spellBeingPaid,
            final Player ai) {
        if (unpaidGeneric >= 2) {
            final boolean noUntap1 = ManaPaymentExecution.doesNotUntapNormally(a);
            final boolean noUntap2 = ManaPaymentExecution.doesNotUntapNormally(b);
            if (noUntap1 != noUntap2) {
                return noUntap1 ? 1 : -1;
            }
            final boolean multi1 = ManaPaymentExecution.isMultiManaProducer(a);
            final boolean multi2 = ManaPaymentExecution.isMultiManaProducer(b);
            if (multi1 != multi2) {
                return multi1 ? -1 : 1;
            }
            if (multi1) {
                return Integer.compare(ManaFilterConsolidation.getManaProducedAmount(b),
                        ManaFilterConsolidation.getManaProducedAmount(a));
            }
            if (pref == GenericColorPreference.RESERVE_COLORLESS) {
                final boolean rock1 = isFreeColorlessManaRock(a);
                final boolean rock2 = isFreeColorlessManaRock(b);
                if (rock1 != rock2) {
                    return rock1 ? -1 : 1;
                }
            }
        }
        final int rankCmp = rankGenericManaSource(a, pref) - rankGenericManaSource(b, pref);
        if (rankCmp != 0) {
            return rankCmp;
        }
        if (ManaFilterConsolidation.isDisposableManaAbility(a) && ManaFilterConsolidation.isDisposableManaAbility(b)) {
            final int disposableCmp = ManaPaymentExecution.compareDisposableCandidates(a, b, unpaidGeneric);
            if (disposableCmp != 0) {
                return disposableCmp;
            }
        }
        final int filterCostCmp = ManaFilterConsolidation.compareAnyManaFilterActivationCost(a, b);
        if (filterCostCmp != 0) {
            return filterCostCmp;
        }
        final boolean land1 = a.getHostCard().isLand();
        final boolean land2 = b.getHostCard().isLand();
        if (land1 != land2) {
            return land1 ? 1 : -1;
        }
        return 0;
    }

    /** Untapped colorless rock with no mana activation cost (Reliquary Tower, Sol Ring, etc.). */
    static boolean isFreeColorlessManaRock(final SpellAbility ma) {
        return ManaPaymentExecution.producesOnlyColorless(ma) && !ManaFilterConsolidation.hasManaActivationCost(ma)
                && !ManaFilterConsolidation.isDisposableManaAbility(ma);
    }

    /**
     * Preference rank for paying a generic mana pip. Lower is better.
     */
    static int rankGenericManaSource(final SpellAbility ma, final GenericColorPreference pref) {
        if (ManaFilterConsolidation.isDisposableManaAbility(ma)) {
            if (ManaFilterConsolidation.sacrificesOtherPermanentsForMana(ma)) {
                return 55;
            }
            if (ManaFilterConsolidation.isSelfSacrificeCreatureMana(ma)) {
                return 54;
            }
            return ManaPaymentExecution.isMultiManaDisposable(ma) ? 48 : 50;
        }
        if (ManaFilterConsolidation.requiresTappingOtherCreatureForMana(ma)) {
            return 49;
        }
        if (ManaPaymentExecution.doesNotUntapNormally(ma)) {
            return 44;
        }
        if (ManaFilterConsolidation.isManaReserveHost(ma.getHostCard())) {
            return 45;
        }
        final Cost payCosts = ma.getPayCosts();
        final boolean hasManaCost = payCosts != null && payCosts.hasManaCost();
        if (ManaPaymentExecution.producesOnlyColorless(ma) && !hasManaCost) {
            if (pref == GenericColorPreference.PREFER_COLORLESS) {
                // Preserve Study Hall-style hosts for their paid any-mana filter; spend plain {C} lands first.
                if (ManaPaymentExecution.isFreeColorlessOnAnyManaFilterHost(ma)) {
                    return ma.getHostCard().isLand() ? 12 : 0;
                }
                return ma.getHostCard().isLand() ? 10 : 0;
            }
            if (pref == GenericColorPreference.RESERVE_COLORLESS) {
                return 30;
            }
            return ma.getHostCard().isLand() ? 15 : 0;
        }
        if (hasManaCost) {
            final int netLoss = ManaFilterConsolidation.netNegativeAnyManaFilterLoss(ma);
            return netLoss > 0 ? 40 + netLoss : 40;
        }
        final AbilityManaPart mp = ma.getManaPart();
        if (mp != null && mp.isAnyMana()) {
            return pref.reservesColorless() ? 10 : 20;
        }
        if (mp != null && mp.isComboMana() && !ManaPaymentExecution.producesOnlyColorless(ma)) {
            return pref.reservesColorless() ? 10 : 20;
        }
        if (pref == GenericColorPreference.PREFER_COLORLESS
                && ManaPaymentExecution.producesColoredManaWithoutFilterCost(ma)) {
            return 25;
        }
        return pref.reservesColorless() ? 0 : 10;
    }

    /**
     * True when other castable cards in hand or command zone need dedicated {@code {C}} pips, so colorless rocks
     * should be saved for those costs rather than spent on generic mana.
     */
    static boolean shouldReserveColorlessMana(final Player ai, final SpellAbility sa) {
        if (ai == null) {
            return false;
        }
        final CardCollection remaining = new CardCollection(ai.getCardsIn(ZoneType.Hand));
        remaining.addAll(ai.getCardsIn(ZoneType.Command));
        remaining.remove(sa.getHostCard());
        return AiDeckStatistics.fromCards(remaining).maxPips[5] > 0;
    }

    static int compareColorlessPreference(final SpellAbility a1, final SpellAbility a2,
            final boolean reserveColorless) {
        final boolean c1 = ManaPaymentExecution.producesOnlyColorless(a1);
        final boolean c2 = ManaPaymentExecution.producesOnlyColorless(a2);
        if (c1 == c2) {
            return 0;
        }
        return reserveColorless ? (c1 ? 1 : -1) : (c1 ? -1 : 1);
    }

    private enum FilterKind { NONE, MULTI_SHARD, ANY_MANA, COMBO_MULTI }

    /** Precomputed mana-source characteristics for sort and efficiency scoring. */
    static final class ManaSourceTraits {
        final SpellAbility ability;
        final FilterKind filterKind;
        final int producedAmount;
        final int genericRank;
        private Boolean consolidates;

        private ManaSourceTraits(final SpellAbility ability, final FilterKind filterKind, final int producedAmount,
                final int genericRank) {
            this.ability = ability;
            this.filterKind = filterKind;
            this.producedAmount = producedAmount;
            this.genericRank = genericRank;
        }

        static ManaSourceTraits of(final SpellAbility ma, final ManaAbilitySortContext ctx) {
            FilterKind kind = FilterKind.NONE;
            if (ManaFilterConsolidation.isComboConsolidatingFilter(ma)) {
                kind = FilterKind.COMBO_MULTI;
            } else if (ManaFilterConsolidation.isMultiPipActivationFilter(ma)) {
                kind = FilterKind.MULTI_SHARD;
            } else if (ManaFilterConsolidation.isAnyManaConsolidatingFilter(ma)) {
                kind = FilterKind.ANY_MANA;
            }
            return new ManaSourceTraits(ma, kind, ManaFilterConsolidation.getManaProducedAmount(ma),
                    rankGenericManaSource(ma, ctx.genericColorPref));
        }

        boolean tightFor(final int remaining) {
            return ManaPaymentExecution.isTightGenericProducer(ability, remaining);
        }

        boolean directFor(final ManaCostShard shard) {
            return ManaPaymentExecution.producesShardDirectly(ability, shard);
        }

        boolean consolidates(final ManaAbilitySortContext ctx) {
            if (consolidates == null) {
                consolidates = ctx.consolidates(ability);
            }
            return consolidates;
        }
    }

    static boolean genericBucketHasColorlessSource(
            final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards) {
        if (!sourcesForShards.containsKey(ManaCostShard.GENERIC)) {
            return false;
        }
        for (final SpellAbility ma : sourcesForShards.get(ManaCostShard.GENERIC)) {
            if (ManaPaymentExecution.producesOnlyColorless(ma) && !ManaFilterConsolidation.hasManaActivationCost(ma)) {
                return true;
            }
        }
        return false;
    }

    static boolean consolidatesFilter(final Player ai, final SpellAbility ma,
            final ListMultimap<Integer, SpellAbility> manaAbilityMap,
            final ManaFilterConsolidation.ConsolidationFeasibility consolidationFeasibility) {
        return ManaFilterConsolidation.hasManaActivationCost(ma)
                && consolidationFeasibility.canActivateFilter(ai, ma, manaAbilityMap, false);
    }

    static final class ManaAbilitySortContext {
        final Player ai;
        final ListMultimap<Integer, SpellAbility> manaAbilityMap;
        final ManaCostBeingPaid cost;
        final int unpaidGeneric;
        final int unpaidColoredShards;
        final GenericColorPreference genericColorPref;
        final Map<Card, Integer> manaCardMap;
        final Map<Card, Integer> cardRank;
        final Map<Integer, Set<Card>> hostsByColor;
        final List<Integer> colorsMostCommon;
        final SpellAbility spellBeingPaid;
        final ManaFilterConsolidation.ConsolidationFeasibility consolidationFeasibility;
        private final Map<SpellAbility, Boolean> consolidatesCache = new IdentityHashMap<>();
        private final Map<SpellAbility, ManaSourceTraits> traitsMap = new IdentityHashMap<>();

        ManaAbilitySortContext(final Player ai, final ListMultimap<Integer, SpellAbility> manaAbilityMap,
                final ManaCostBeingPaid cost, final int unpaidGeneric, final int unpaidColoredShards,
                final GenericColorPreference genericColorPref, final Map<Card, Integer> manaCardMap,
                final Map<Card, Integer> cardRank, final List<Integer> colorsMostCommon,
                final SpellAbility spellBeingPaid,
                final ManaFilterConsolidation.ConsolidationFeasibility consolidationFeasibility) {
            this.ai = ai;
            this.manaAbilityMap = manaAbilityMap;
            this.cost = cost;
            this.unpaidGeneric = unpaidGeneric;
            this.unpaidColoredShards = unpaidColoredShards;
            this.genericColorPref = genericColorPref;
            this.manaCardMap = manaCardMap;
            this.cardRank = cardRank;
            this.colorsMostCommon = colorsMostCommon;
            this.spellBeingPaid = spellBeingPaid;
            this.consolidationFeasibility = consolidationFeasibility;
            this.hostsByColor = buildHostsByColor(manaAbilityMap);
        }

        private static Map<Integer, Set<Card>> buildHostsByColor(
                final ListMultimap<Integer, SpellAbility> manaAbilityMap) {
            final Map<Integer, Set<Card>> result = new HashMap<>();
            for (final Integer colorKey : manaAbilityMap.keySet()) {
                final Set<Card> hosts = new HashSet<>();
                for (final SpellAbility ma : manaAbilityMap.get(colorKey)) {
                    hosts.add(ma.getHostCard());
                }
                result.put(colorKey, hosts);
            }
            return result;
        }

        boolean consolidates(final SpellAbility ma) {
            return consolidatesCache.computeIfAbsent(ma, k ->
                    ManaFilterConsolidation.hasManaActivationCost(k)
                            && consolidationFeasibility.canActivateFilter(ai, k, manaAbilityMap, false));
        }

        ManaSourceTraits traits(final SpellAbility ma) {
            return traitsMap.computeIfAbsent(ma, k -> ManaSourceTraits.of(k, this));
        }

        int cardPreOrder(final SpellAbility a1, final SpellAbility a2) {
            final Integer r1 = cardRank.get(a1.getHostCard());
            final Integer r2 = cardRank.get(a2.getHostCard());
            return (r1 == null ? Integer.MAX_VALUE : r1) - (r2 == null ? Integer.MAX_VALUE : r2);
        }
    }

    static List<Integer> computeHandColorPreferences(final SpellAbility sa,
            final boolean hasGenericShard) {
        if (!hasGenericShard) {
            return null;
        }
        final Player ap = sa.getActivatingPlayer();
        if (ap == null) {
            return null;
        }
        CardCollection hand = new CardCollection(ap.getCardsIn(ZoneType.Hand));
        hand.remove(sa.getHostCard());
        AiDeckStatistics stats = AiDeckStatistics.fromCards(hand);
        Integer[] orderedColorsIdx = {0, 1, 2, 3, 4};
        return Arrays.stream(orderedColorsIdx).sorted(Comparator.comparingInt(o -> stats.maxPips[(int) o]).reversed())
                .filter(idx -> stats.maxPips[idx] > 0)
                .map(idx -> (int) MagicColor.WUBRG[idx])
                .collect(Collectors.toList());
    }

    static int compareAnyManaFilterPreference(final ManaAbilitySortContext ctx,
            final SpellAbility ability1, final SpellAbility ability2, final boolean rejectColorlessOpponent) {
        final boolean ab1Any = ManaFilterConsolidation.isAnyManaConsolidatingFilter(ability1) && ctx.consolidates(ability1);
        final boolean ab2Any = ManaFilterConsolidation.isAnyManaConsolidatingFilter(ability2) && ctx.consolidates(ability2);
        if (rejectColorlessOpponent) {
            if (ab1Any && !ab2Any && !ManaPaymentExecution.producesOnlyColorless(ability2)) {
                return -1;
            }
            if (ab2Any && !ab1Any && !ManaPaymentExecution.producesOnlyColorless(ability1)) {
                return 1;
            }
        } else {
            if (ab1Any && !ab2Any) {
                return -1;
            }
            if (ab2Any && !ab1Any) {
                return 1;
            }
        }
        return 0;
    }

    static int compareGenericShardAbilities(final ManaAbilitySortContext ctx,
            final SpellAbility ability1, final SpellAbility ability2) {
        if (ctx.unpaidGeneric == 1) {
            final ManaSourceTraits t1 = ctx.traits(ability1);
            final ManaSourceTraits t2 = ctx.traits(ability2);
            final boolean tight1 = t1.tightFor(1);
            final boolean tight2 = t2.tightFor(1);
            if (tight1 != tight2) {
                return tight1 ? -1 : 1;
            }
            if (!ManaFilterConsolidation.hasManaActivationCost(ability1)
                    && !ManaFilterConsolidation.hasManaActivationCost(ability2)) {
                if (t1.producedAmount != t2.producedAmount) {
                    return Integer.compare(t1.producedAmount, t2.producedAmount);
                }
            }
        }
        if (ctx.unpaidGeneric >= 2) {
            final boolean ab1Multi = ManaFilterConsolidation.isMultiPipActivationFilter(ability1) && ctx.consolidates(ability1);
            final boolean ab2Multi = ManaFilterConsolidation.isMultiPipActivationFilter(ability2) && ctx.consolidates(ability2);
            if (ab1Multi != ab2Multi) {
                return ab1Multi ? -1 : 1;
            }
            final boolean ab1Any = ManaFilterConsolidation.isAnyManaConsolidatingFilter(ability1) && ctx.consolidates(ability1);
            final boolean ab2Any = ManaFilterConsolidation.isAnyManaConsolidatingFilter(ability2) && ctx.consolidates(ability2);
            if (ab1Multi && ab2Any) {
                return -1;
            }
            if (ab2Multi && ab1Any) {
                return 1;
            }
            final int anyFilterCmp = compareAnyManaFilterPreference(ctx, ability1, ability2, true);
            if (anyFilterCmp != 0) {
                return anyFilterCmp;
            }
            final int prod1 = ManaFilterConsolidation.getManaProducedAmount(ability1);
            final int prod2 = ManaFilterConsolidation.getManaProducedAmount(ability2);
            if (!ManaFilterConsolidation.hasManaActivationCost(ability1)
                    && !ManaFilterConsolidation.hasManaActivationCost(ability2)) {
                final boolean noUntap1 = ManaPaymentExecution.doesNotUntapNormally(ability1);
                final boolean noUntap2 = ManaPaymentExecution.doesNotUntapNormally(ability2);
                if (noUntap1 != noUntap2) {
                    return noUntap1 ? 1 : -1;
                }
                final boolean multi1 = ManaPaymentExecution.isMultiManaProducer(ability1);
                final boolean multi2 = ManaPaymentExecution.isMultiManaProducer(ability2);
                if (multi1 != multi2) {
                    return multi1 ? -1 : 1;
                }
                if (multi1 && prod1 != prod2) {
                    return Integer.compare(prod2, prod1);
                }
            }
        }
        final int filterCostCmp = ManaFilterConsolidation.compareAnyManaFilterActivationCost(ability1, ability2);
        if (filterCostCmp != 0) {
            return filterCostCmp;
        }
        return rankGenericManaSource(ability1, effectiveGenericColorPreference(ctx))
                - rankGenericManaSource(ability2, effectiveGenericColorPreference(ctx));
    }

    /** Generic ranking may shift once colored pips are paid (prefer {C} over an extra basic). */
    static GenericColorPreference effectiveGenericColorPreference(final ManaAbilitySortContext ctx) {
        if (ctx.genericColorPref == GenericColorPreference.RESERVE_COLORLESS) {
            return ctx.genericColorPref;
        }
        if (!ManaPaymentExecution.hasUnpaidColoredShards(ctx.cost)) {
            return GenericColorPreference.PREFER_COLORLESS;
        }
        return ctx.genericColorPref;
    }

    static int compareColoredShardAbilities(final ManaAbilitySortContext ctx,
            final SpellAbility ability1, final SpellAbility ability2, final ManaCostShard shard) {
        final boolean ab1Filter = ManaFilterConsolidation.hasManaActivationCost(ability1);
        final boolean ab2Filter = ManaFilterConsolidation.hasManaActivationCost(ability2);
        final boolean ab1Consolidates = ctx.consolidates(ability1);
        final boolean ab2Consolidates = ctx.consolidates(ability2);
        if (ab1Consolidates != ab2Consolidates && ctx.unpaidColoredShards >= 2
                && (ManaFilterConsolidation.isMultiPipActivationFilter(ability1)
                        || ManaFilterConsolidation.isMultiPipActivationFilter(ability2)
                        || ManaFilterConsolidation.isComboConsolidatingFilter(ability1)
                        || ManaFilterConsolidation.isComboConsolidatingFilter(ability2))) {
            return ab1Consolidates ? -1 : 1;
        }
        if (ab1Filter != ab2Filter) {
            if (ctx.unpaidGeneric == 0 && ctx.unpaidColoredShards >= 2) {
                if (ab1Consolidates && ManaFilterConsolidation.isAnyManaConsolidatingFilter(ability1) && !ab2Filter) {
                    return -1;
                }
                if (ab2Consolidates && ManaFilterConsolidation.isAnyManaConsolidatingFilter(ability2) && !ab1Filter) {
                    return 1;
                }
            }
            return ab1Filter ? 1 : -1;
        }
        if (ctx.cost.getUnpaidShards(shard) >= 2) {
            final boolean direct1 = ctx.traits(ability1).directFor(shard);
            final boolean direct2 = ctx.traits(ability2).directFor(shard);
            final boolean anyMulti1 = ManaPaymentExecution.isAnyMultiManaProducer(ability1);
            final boolean anyMulti2 = ManaPaymentExecution.isAnyMultiManaProducer(ability2);
            if (anyMulti1 && direct2) {
                return 1;
            }
            if (anyMulti2 && direct1) {
                return -1;
            }
            final boolean directMulti1 = ManaPaymentExecution.isDirectColoredMultiProducer(ability1, shard);
            final boolean directMulti2 = ManaPaymentExecution.isDirectColoredMultiProducer(ability2, shard);
            final boolean singlePip1 = ManaPaymentExecution.isSinglePipDirectColoredProducer(ability1, shard);
            final boolean singlePip2 = ManaPaymentExecution.isSinglePipDirectColoredProducer(ability2, shard);
            if (directMulti1 && singlePip2) {
                return 1;
            }
            if (directMulti2 && singlePip1) {
                return -1;
            }
        } else if (ctx.cost.getUnpaidShards(shard) == 1) {
            final boolean directMulti1 = ManaPaymentExecution.isDirectColoredMultiProducer(ability1, shard);
            final boolean directMulti2 = ManaPaymentExecution.isDirectColoredMultiProducer(ability2, shard);
            final boolean singlePip1 = ManaPaymentExecution.isSinglePipDirectColoredProducer(ability1, shard);
            final boolean singlePip2 = ManaPaymentExecution.isSinglePipDirectColoredProducer(ability2, shard);
            if (directMulti1 && singlePip2) {
                return 1;
            }
            if (directMulti2 && singlePip1) {
                return -1;
            }
        }
        if (ab1Filter && ab2Filter) {
            final int filterCostCmp = ManaFilterConsolidation.compareAnyManaFilterActivationCost(ability1, ability2);
            if (filterCostCmp != 0) {
                return filterCostCmp;
            }
        }
        return 0;
    }

    static int compareGenericTiebreak(final ManaAbilitySortContext ctx,
            final SpellAbility ability1, final SpellAbility ability2) {
        if (!ctx.manaCardMap.get(ability1.getHostCard()).equals(ctx.manaCardMap.get(ability2.getHostCard()))) {
            return 0;
        }
        final int colorlessCmp = compareColorlessPreference(ability1, ability2, ctx.genericColorPref.reservesColorless());
        if (colorlessCmp != 0) {
            return colorlessCmp;
        }
        if (ctx.colorsMostCommon == null) {
            return 0;
        }
        for (Integer col : ctx.colorsMostCommon) {
            final Set<Card> hosts = ctx.hostsByColor.get(col);
            if (hosts == null) {
                continue;
            }
            final boolean fromCommonColorSource1 = hosts.contains(ability1.getHostCard());
            final boolean fromCommonColorSource2 = hosts.contains(ability2.getHostCard());
            if (fromCommonColorSource1 && !fromCommonColorSource2) {
                return 1;
            }
            if (!fromCommonColorSource1 && fromCommonColorSource2) {
                return -1;
            }
        }
        return 0;
    }

    static int compareDifferentCards(final ManaAbilitySortContext ctx, final SpellAbility ability1,
            final SpellAbility ability2, final ManaCostShard shard) {
        if (shard.isGeneric()) {
            final int genericCmp = compareGenericShardAbilities(ctx, ability1, ability2);
            if (genericCmp != 0) {
                return genericCmp;
            }
        } else if (!shard.isGeneric() && shard != ManaCostShard.COLORLESS) {
            final int coloredCmp = compareColoredShardAbilities(ctx, ability1, ability2, shard);
            if (coloredCmp != 0) {
                return coloredCmp;
            }
        }
        if (shard.isGeneric()) {
            final int tiebreak = compareGenericTiebreak(ctx, ability1, ability2);
            if (tiebreak != 0) {
                return tiebreak;
            }
        }
        return ctx.cardPreOrder(ability1, ability2);
    }

    static int compareSameCard(final ManaAbilitySortContext ctx, final SpellAbility ability1,
            final SpellAbility ability2, final ManaCostShard shard) {
        final int unpaidForShard = ctx.cost.getUnpaidShards(shard);
        if (unpaidForShard >= 2 || (shard.isGeneric() && ctx.unpaidGeneric >= 2)) {
            final int combo1 = ManaFilterConsolidation.getComboManaAmount(ability1);
            final int combo2 = ManaFilterConsolidation.getComboManaAmount(ability2);
            if (combo1 >= 2 || combo2 >= 2) {
                if (combo1 != combo2) {
                    return Integer.compare(combo2, combo1);
                }
                final int colorlessCmp = compareColorlessPreference(ability1, ability2, false);
                if (colorlessCmp != 0) {
                    return colorlessCmp;
                }
            }
        }
        final boolean ab1HasManaCost = ManaFilterConsolidation.hasManaActivationCost(ability1);
        final boolean ab2HasManaCost = ManaFilterConsolidation.hasManaActivationCost(ability2);
        if (ab1HasManaCost != ab2HasManaCost) {
            if (shard.isGeneric() && ctx.unpaidGeneric >= 2) {
                final int anyFilterCmp = compareAnyManaFilterPreference(ctx, ability1, ability2, false);
                if (anyFilterCmp != 0) {
                    return anyFilterCmp;
                }
            }
            return ab1HasManaCost ? 1 : -1;
        }
        final String shardMana = shard.toShortString();
        final boolean payWithAb1 = ability1.getManaPart().mana(ability1).contains(shardMana);
        final boolean payWithAb2 = ability2.getManaPart().mana(ability2).contains(shardMana);
        if (payWithAb1 && !payWithAb2) {
            return -1;
        }
        if (payWithAb2 && !payWithAb1) {
            return 1;
        }
        return ability1.compareTo(ability2);
    }

    static int compareManaAbilities(final ManaAbilitySortContext ctx, final SpellAbility ability1,
            final SpellAbility ability2, final ManaCostShard shard) {
        final int preOrder = ctx.cardPreOrder(ability1, ability2);
        if (preOrder != 0) {
            return compareDifferentCards(ctx, ability1, ability2, shard);
        }
        return compareSameCard(ctx, ability1, ability2, shard);
    }

    static List<SpellAbility> applyAIManaPrefReorder(final List<SpellAbility> abilities,
            final String preferredShard, final int preferredShardAmount) {
        final List<SpellAbility> preferred = new ArrayList<>();
        final List<SpellAbility> rest = new ArrayList<>();
        for (SpellAbility ab : abilities) {
            if (preferred.size() < preferredShardAmount
                    && ab.getManaPart().mana(ab).contains(preferredShard)) {
                preferred.add(ab);
            } else {
                rest.add(ab);
            }
        }
        final List<SpellAbility> result = new ArrayList<>(preferred);
        result.addAll(rest);
        return result;
    }

    static void sortManaAbilities(final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards,
            final ListMultimap<Integer, SpellAbility> manaAbilityMap, final SpellAbility sa,
            final ManaCostBeingPaid cost, final Player ai, final ManaPaymentContext paymentCtx,
            final ManaFilterConsolidation.ConsolidationFeasibility consolidationFeasibility) {
        final int unpaidGeneric = cost.getGenericManaAmount();
        int coloredShardCount = 0;
        for (final ManaCostShard shard : cost.getDistinctShards()) {
            if (!shard.isGeneric() && shard != ManaCostShard.COLORLESS && !shard.isPhyrexian()) {
                coloredShardCount += cost.getUnpaidShards(shard);
            }
        }
        final List<Card> orderedCards = Lists.newArrayList();
        final Map<Card, Integer> manaCardMap = ManaFilterConsolidation.buildManaCardRankings(ai, sourcesForShards,
                cost, orderedCards, sa, unpaidGeneric, manaAbilityMap, consolidationFeasibility);
        orderedCards.sort(Comparator.comparingInt(manaCardMap::get));
        final Map<Card, Integer> cardRank = new HashMap<>();
        for (int i = 0; i < orderedCards.size(); i++) {
            cardRank.put(orderedCards.get(i), i);
        }

        final boolean hasGenericShard = sourcesForShards.keySet().stream().anyMatch(ManaCostShard::isGeneric);
        final List<Integer> colorsMostCommon = computeHandColorPreferences(sa, hasGenericShard);
        final GenericColorPreference genericColorPref = genericColorPreference(ai, sa, cost, coloredShardCount,
                sourcesForShards, paymentCtx);
        final ManaAbilitySortContext ctx = new ManaAbilitySortContext(ai, manaAbilityMap, cost, unpaidGeneric,
                coloredShardCount, genericColorPref, manaCardMap, cardRank, colorsMostCommon, sa,
                consolidationFeasibility);

        for (final ManaCostShard shard : sourcesForShards.keySet()) {
            final List<SpellAbility> newAbilities = new ArrayList<>(sourcesForShards.get(shard));
            newAbilities.sort((a1, a2) -> compareManaAbilities(ctx, a1, a2, shard));
            final List<SpellAbility> trimmed = trimFungibleManaCandidates(newAbilities, shard, cost, ai);
            sourcesForShards.replaceValues(shard, trimmed);

            String manaPref = sa.getParamOrDefault("AIManaPref", "");
            if (manaPref.isEmpty() && sa.getHostCard() != null && sa.getHostCard().hasSVar("AIManaPref")) {
                manaPref = sa.getHostCard().getSVar("AIManaPref");
            }
            if (!manaPref.isEmpty()) {
                final String[] prefShardInfo = manaPref.split(":");
                final String preferredShard = prefShardInfo[0];
                final int preferredShardAmount = prefShardInfo.length > 1
                        ? Integer.parseInt(prefShardInfo[1]) : 3;
                if (!preferredShard.isEmpty()) {
                    sourcesForShards.replaceValues(shard,
                            applyAIManaPrefReorder(trimmed, preferredShard, preferredShardAmount));
                }
            }
        }
    }

    /**
     * After sorting, keep only enough fungible representatives per equivalence class to pay the
     * remaining shards (plus a small buffer for excluded retries).
     * <p>
     * For generic/{@code X}, also keep copies for unpaid colored pips: the same basics may be
     * spent on colored shards first, and sources trimmed out of the generic list (but still on
     * the colored list) are unavailable once colored is paid — e.g. Cabal Coffers + Drain Life
     * {@code {8}{1}{B}} stranding the final {@code {1}}.
     */
    static List<SpellAbility> trimFungibleManaCandidates(final List<SpellAbility> sorted,
            final ManaCostShard shard, final ManaCostBeingPaid cost, final Player ai) {
        if (sorted.size() <= 1) {
            return sorted;
        }
        int cap = FUNGIBLE_CANDIDATE_BUFFER;
        if (shard.isGeneric() || shard == ManaCostShard.X) {
            cap += ManaPaymentExecution.countUnpaidPips(cost);
        } else {
            cap += cost.getUnpaidShards(shard);
        }
        final Map<FungibleManaKey, Integer> classCounts = new HashMap<>();
        final List<SpellAbility> result = new ArrayList<>();
        for (final SpellAbility ma : sorted) {
            final FungibleManaKey key = FungibleManaKey.of(ma, ai);
            final int seen = classCounts.getOrDefault(key, 0);
            if (seen < cap) {
                classCounts.put(key, seen + 1);
                result.add(ma);
            }
        }
        return result;
    }

    static final class FungibleManaKey {
        private final String cardName;
        private final String abilitySignature;
        private final boolean tapped;
        private final boolean paysTap;
        private final boolean paysSac;
        private final boolean heldForNext;
        private final String chosenType;
        private final boolean snow;

        private FungibleManaKey(final String cardName, final String abilitySignature, final boolean tapped,
                final boolean paysTap, final boolean paysSac, final boolean heldForNext,
                final String chosenType, final boolean snow) {
            this.cardName = cardName;
            this.abilitySignature = abilitySignature;
            this.tapped = tapped;
            this.paysTap = paysTap;
            this.paysSac = paysSac;
            this.heldForNext = heldForNext;
            this.chosenType = chosenType;
            this.snow = snow;
        }

        static FungibleManaKey of(final SpellAbility ma, final Player ai) {
            final Card host = ma.getHostCard();
            return new FungibleManaKey(host.getName(), manaAbilitySignature(ma),
                    host.isTapped(),
                    AiCardMemory.isRememberedCard(ai, host, MemorySet.PAYS_TAP_COST),
                    AiCardMemory.isRememberedCard(ai, host, MemorySet.PAYS_SAC_COST),
                    AiCardMemory.isRememberedCard(ai, host, MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL),
                    host.getChosenType(),
                    host.isSnow());
        }

        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof FungibleManaKey)) {
                return false;
            }
            final FungibleManaKey other = (FungibleManaKey) o;
            return tapped == other.tapped && paysTap == other.paysTap && paysSac == other.paysSac
                    && heldForNext == other.heldForNext && snow == other.snow
                    && Objects.equals(cardName, other.cardName)
                    && Objects.equals(chosenType, other.chosenType)
                    && Objects.equals(abilitySignature, other.abilitySignature);
        }

        @Override
        public int hashCode() {
            return Objects.hash(cardName, abilitySignature, tapped, paysTap, paysSac, heldForNext, chosenType, snow);
        }
    }

    static String manaAbilitySignature(final SpellAbility ma) {
        final StringBuilder sb = new StringBuilder();
        sb.append(ma.getApi());
        final AbilityManaPart mp = ma.getManaPart();
        if (mp != null) {
            sb.append('|').append(mp.getOrigProduced());
        }
        sb.append('|').append(ma.getParamOrDefault("Produced", ""));
        final Cost cost = ma.getPayCosts();
        if (cost != null && cost.hasManaCost() && cost.getCostMana() != null) {
            sb.append('|').append(cost.getCostMana().getMana());
        }
        return sb.toString();
    }
}
