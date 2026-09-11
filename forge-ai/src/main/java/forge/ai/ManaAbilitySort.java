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

    /**
     * Single resolver behind every {@link GenericColorPreference} lookup. First matching row wins:
     * <pre>
     *  coloredBasicTapped                          -> PREFER_COLORLESS  (already spent a basic; stop)
     *  reserveColorless                            -> RESERVE_COLORLESS (hand needs dedicated {C})
     *  !unpaidColored || colorlessCoversGeneric    -> PREFER_COLORLESS  (colored pips are safe)
     *  otherwise                                   -> DEFAULT
     * </pre>
     * {@code colorlessCoversGeneric} is a supplier because it can be expensive and is only needed
     * when colored pips remain unpaid.
     */
    static GenericColorPreference genericColorPreference(final boolean coloredBasicTapped,
            final boolean reserveColorless, final boolean unpaidColored,
            final java.util.function.BooleanSupplier colorlessCoversGeneric) {
        if (coloredBasicTapped) {
            return GenericColorPreference.PREFER_COLORLESS;
        }
        if (reserveColorless) {
            return GenericColorPreference.RESERVE_COLORLESS;
        }
        if (!unpaidColored || colorlessCoversGeneric.getAsBoolean()) {
            return GenericColorPreference.PREFER_COLORLESS;
        }
        return GenericColorPreference.DEFAULT;
    }

    /**
     * Preference for {@link #sortManaAbilities}: {C} carries generic when every colored pip has a reusable
     * free producer (and a {C} source is in the generic bucket) or once no colored pips remain unpaid.
     */
    static GenericColorPreference genericColorPreference(final Player ai, final SpellAbility sa,
            final ManaCostBeingPaid cost, final int coloredShardCount,
            final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards,
            final ManaPaymentContext paymentCtx) {
        return genericColorPreference(false, shouldReserveColorlessMana(ai, sa),
                ManaPaymentExecution.hasUnpaidColoredShards(cost),
                () -> coloredShardCount > 0 && genericBucketHasColorlessSource(sourcesForShards)
                        && ManaPaymentExecution.hasReusableFreeProducerForEveryColoredShard(cost, ai, paymentCtx));
    }

    /** Match nested filter activation / {@link ComputerUtilMana#chooseManaAbility} generic ranking. */
    static GenericColorPreference genericColorPreferenceForNestedActivation(final Player ai,
            final SpellAbility sa, final ManaCostBeingPaid cost) {
        return genericColorPreference(ManaPaymentExecution.coloredBasicTappedThisPayment(ai),
                shouldReserveColorlessMana(ai, sa), ManaPaymentExecution.hasUnpaidColoredShards(cost), () -> false);
    }

    /** Preference for paying generic shards this iteration (never DEFAULT: colored pips are handled elsewhere). */
    public static GenericColorPreference resolveGenericColorPreference(final Player ai, final SpellAbility sa) {
        return genericColorPreference(ManaPaymentExecution.coloredBasicTappedThisPayment(ai),
                shouldReserveColorlessMana(ai, sa), false, () -> false);
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
        final int rankCmp = Integer.compare(rankGenericManaSource(a, pref), rankGenericManaSource(b, pref));
        if (rankCmp != 0) {
            return rankCmp;
        }
        if (ManaFilterConsolidation.isDisposableManaAbility(a) && ManaFilterConsolidation.isDisposableManaAbility(b)) {
            final int disposableCmp = ManaPaymentExecution.compareDisposableCandidates(a, b, unpaidGeneric);
            if (disposableCmp != 0) {
                return disposableCmp;
            }
        }
        final int filterCostCmp = Integer.compare(anyManaFilterCmcKey(a), anyManaFilterCmcKey(b));
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
        final ManaPaymentContext.ManaPaymentPlanCache cache = ManaPaymentContext.ManaPaymentPlanCache.bound();
        final Card host = sa == null ? null : sa.getHostCard();
        if (cache == null || host == null) {
            return computeShouldReserveColorlessMana(ai, sa);
        }
        Boolean cached = cache.reserveColorlessByHost.get(host);
        if (cached == null) {
            cached = computeShouldReserveColorlessMana(ai, sa);
            cache.reserveColorlessByHost.put(host, cached);
        }
        return cached;
    }

    private static boolean computeShouldReserveColorlessMana(final Player ai, final SpellAbility sa) {
        final CardCollection remaining = new CardCollection(ai.getCardsIn(ZoneType.Hand));
        remaining.addAll(ai.getCardsIn(ZoneType.Command));
        remaining.remove(sa.getHostCard());
        // Command zone also holds effects/emblems/etc. with no CardRules; skip those.
        remaining.removeIf(Card::isImmutable);
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
        final ManaPaymentContext.ManaPaymentPlanCache cache = ManaPaymentContext.ManaPaymentPlanCache.bound();
        final Card host = sa.getHostCard();
        if (cache == null || host == null) {
            return computeHandColorPreferences(sa, ap);
        }
        List<Integer> prefs = cache.handColorPrefsByHost.get(host);
        if (prefs == null) {
            prefs = computeHandColorPreferences(sa, ap);
            cache.handColorPrefsByHost.put(host, prefs);
        }
        return prefs;
    }

    private static List<Integer> computeHandColorPreferences(final SpellAbility sa, final Player ap) {
        CardCollection hand = new CardCollection(ap.getCardsIn(ZoneType.Hand));
        hand.remove(sa.getHostCard());
        AiDeckStatistics stats = AiDeckStatistics.fromCards(hand);
        Integer[] orderedColorsIdx = {0, 1, 2, 3, 4};
        return Arrays.stream(orderedColorsIdx).sorted(Comparator.comparingInt(o -> stats.maxPips[(int) o]).reversed())
                .filter(idx -> stats.maxPips[idx] > 0)
                .map(idx -> (int) MagicColor.WUBRG[idx])
                .collect(Collectors.toList());
    }

    /**
     * Independent sort key so any-mana vs colorless vs colored is a total order.
     * Pairwise "prefer any-mana unless the other is colorless" is not transitive:
     * Study Hall &lt; Forest, Forest ≡ Wastes, Wastes ≡ Study Hall.
     */
    static int anyManaPreferenceClass(final ManaAbilitySortContext ctx, final SpellAbility ma,
            final boolean rejectColorlessOpponent) {
        final boolean any = ManaFilterConsolidation.isAnyManaConsolidatingFilter(ma) && ctx.consolidates(ma);
        // Free multi-mana combo/any (Firemind Vessel) must not lose to a {C}
        // land when several generic pips remain — one activation can cover them.
        if (ManaPaymentExecution.isMultiManaProducer(ma)) {
            return 0;
        }
        if (!rejectColorlessOpponent) {
            return any ? 0 : 1;
        }
        if (ctx.genericColorPref.reservesColorless()) {
            return any ? 0 : 1;
        }
        return any || ManaPaymentExecution.producesOnlyColorless(ma) ? 0 : 1;
    }

    static int genericMultiPipRank(final ManaAbilitySortContext ctx, final SpellAbility ma) {
        return ManaFilterConsolidation.isMultiPipActivationFilter(ma) && ctx.consolidates(ma) ? 0 : 1;
    }

    static int anyManaFilterCmcKey(final SpellAbility ma) {
        if (!ManaFilterConsolidation.isAnyManaConsolidatingFilter(ma)) {
            return Integer.MAX_VALUE;
        }
        return ManaFilterConsolidation.getFilterActivationCMC(ma);
    }

    /** 0 = consolidating multi-pip/combo filter, 1 = other. Independent of the other item. */
    static int consolidatingMultiOrComboRank(final ManaAbilitySortContext ctx, final SpellAbility ma) {
        if (!ctx.consolidates(ma)) {
            return 1;
        }
        return (ManaFilterConsolidation.isMultiPipActivationFilter(ma)
                || ManaFilterConsolidation.isComboConsolidatingFilter(ma)) ? 0 : 1;
    }

    /**
     * Filter vs non-filter as a total order. When unpaid colored &gt;= 2 and generic is paid,
     * consolidating any-mana sits ahead of non-filters, which sit ahead of other filters.
     */
    static int coloredFilterClass(final ManaAbilitySortContext ctx, final SpellAbility ma) {
        final boolean filter = ManaFilterConsolidation.hasManaActivationCost(ma);
        if (ctx.unpaidGeneric == 0 && ctx.unpaidColoredShards >= 2) {
            if (ctx.consolidates(ma) && ManaFilterConsolidation.isAnyManaConsolidatingFilter(ma)) {
                return 0;
            }
            return filter ? 2 : 1;
        }
        return filter ? 1 : 0;
    }

    /**
     * Direct vs any-multi vs overspend as independent classes. Pairwise "A is X and B is Y"
     * returns leave some pairs equal and create cycles with later keys.
     */
    static int coloredProducerClass(final SpellAbility ma, final ManaCostShard shard, final boolean multiUnpaid) {
        if (multiUnpaid) {
            if (ManaPaymentExecution.isSinglePipDirectColoredProducer(ma, shard)) {
                return 0;
            }
            if (ManaPaymentExecution.isDirectColoredMultiProducer(ma, shard)) {
                return 1;
            }
            if (ManaPaymentExecution.isAnyMultiManaProducer(ma)) {
                return 3;
            }
            return 2;
        }
        if (ManaPaymentExecution.isSinglePipDirectColoredProducer(ma, shard)) {
            return 0;
        }
        if (ManaPaymentExecution.isDirectColoredMultiProducer(ma, shard)) {
            return 2;
        }
        return 1;
    }

    static boolean producesShardMana(final SpellAbility ma, final ManaCostShard shard) {
        if (ma == null || ma.getManaPart() == null) {
            return false;
        }
        final String mana = ma.getManaPart().mana(ma);
        return mana != null && mana.contains(shard.toShortString());
    }

    static int colorlessTiebreakClass(final SpellAbility ma, final boolean reserveColorless) {
        final boolean colorless = ManaPaymentExecution.producesOnlyColorless(ma);
        if (reserveColorless) {
            return colorless ? 1 : 0;
        }
        return colorless ? 0 : 1;
    }

    /**
     * Independent per-ability key. TimSort requires a total order; pairwise
     * "if both have property P" tests and same-card vs different-card splits do not.
     */
    static int[] manaSortKey(final ManaAbilitySortContext ctx, final SpellAbility ma, final ManaCostShard shard) {
        final int[] key = new int[24];
        int i = 0;
        if (shard.isGeneric()) {
            final ManaSourceTraits t = ManaSourceTraits.of(ma);
            key[i++] = (ctx.unpaidGeneric == 1 && ManaPaymentExecution.isTightGenericProducer(ma, 1)) ? 0 : 1;
            key[i++] = ctx.unpaidGeneric >= 2 ? genericMultiPipRank(ctx, ma) : 0;
            key[i++] = ctx.unpaidGeneric >= 2 ? anyManaPreferenceClass(ctx, ma, true) : 0;
            key[i++] = rankGenericManaSource(ma, ctx.genericColorPref);
            key[i++] = anyManaFilterCmcKey(ma);
            key[i++] = ctx.unpaidGeneric >= 2 && ManaPaymentExecution.doesNotUntapNormally(ma) ? 1 : 0;
            key[i++] = ctx.unpaidGeneric >= 2 && ManaPaymentExecution.isMultiManaProducer(ma) ? 0 : 1;
            if (ctx.unpaidGeneric == 1) {
                key[i++] = t.producedAmount;
            } else if (ctx.unpaidGeneric >= 2) {
                key[i++] = -t.producedAmount;
            } else {
                key[i++] = 0;
            }
            final Integer score = ctx.manaCardMap.get(ma.getHostCard());
            key[i++] = score == null ? Integer.MAX_VALUE : score;
            key[i++] = colorlessTiebreakClass(ma, ctx.genericColorPref.reservesColorless());
            for (int c = 0; c < 5; c++) {
                if (ctx.colorsMostCommon != null && c < ctx.colorsMostCommon.size()) {
                    final Set<Card> hosts = ctx.hostsByColor.get(ctx.colorsMostCommon.get(c));
                    key[i++] = (hosts != null && hosts.contains(ma.getHostCard())) ? 1 : 0;
                } else {
                    key[i++] = 0;
                }
            }
        } else if (shard != ManaCostShard.COLORLESS) {
            key[i++] = ctx.unpaidColoredShards >= 2 ? consolidatingMultiOrComboRank(ctx, ma) : 0;
            key[i++] = coloredFilterClass(ctx, ma);
            final int unpaidForShard = ctx.cost.getUnpaidShards(shard);
            if (unpaidForShard >= 2) {
                key[i++] = coloredProducerClass(ma, shard, true);
            } else if (unpaidForShard == 1) {
                key[i++] = coloredProducerClass(ma, shard, false);
            } else {
                key[i++] = 0;
            }
            key[i++] = anyManaFilterCmcKey(ma);
        }
        final Card host = ma.getHostCard();
        key[i++] = ctx.cardRank.getOrDefault(host, Integer.MAX_VALUE);
        if (ctx.cost.getUnpaidShards(shard) >= 2 || (shard.isGeneric() && ctx.unpaidGeneric >= 2)) {
            key[i++] = -ManaFilterConsolidation.getComboManaAmount(ma);
        } else {
            key[i++] = 0;
        }
        key[i++] = ManaFilterConsolidation.hasManaActivationCost(ma) ? 1 : 0;
        key[i++] = producesShardMana(ma, shard) ? 0 : 1;
        key[i++] = ManaSourceTraits.of(ma).manaScore;
        key[i++] = host == null ? 0 : host.getId();
        key[i++] = ma.getId();
        return key;
    }

    static int compareSortKeys(final int[] a, final int[] b) {
        final int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            final int cmp = Integer.compare(a[i], b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.length, b.length);
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

        // AIManaPref ("W:2" etc.) is a property of the spell, not of the shard — parse it once.
        String manaPref = sa.getParamOrDefault("AIManaPref", "");
        if (manaPref.isEmpty() && sa.getHostCard() != null && sa.getHostCard().hasSVar("AIManaPref")) {
            manaPref = sa.getHostCard().getSVar("AIManaPref");
        }
        String preferredShard = null;
        int preferredShardAmount = 3;
        if (!manaPref.isEmpty()) {
            final String[] prefShardInfo = manaPref.split(":");
            if (!prefShardInfo[0].isEmpty()) {
                preferredShard = prefShardInfo[0];
                if (prefShardInfo.length > 1) {
                    preferredShardAmount = Integer.parseInt(prefShardInfo[1]);
                }
            }
        }

        for (final ManaCostShard shard : sourcesForShards.keySet()) {
            final List<SpellAbility> newAbilities = new ArrayList<>(sourcesForShards.get(shard));
            final Map<SpellAbility, int[]> sortKeys = new IdentityHashMap<>();
            for (final SpellAbility ma : newAbilities) {
                sortKeys.put(ma, manaSortKey(ctx, ma, shard));
            }
            newAbilities.sort((a1, a2) -> compareSortKeys(sortKeys.get(a1), sortKeys.get(a2)));
            List<SpellAbility> ordered = trimFungibleManaCandidates(newAbilities, shard, cost, ai);
            if (preferredShard != null) {
                ordered = applyAIManaPrefReorder(ordered, preferredShard, preferredShardAmount);
            }
            sourcesForShards.replaceValues(shard, ordered);
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
