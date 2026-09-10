package forge.ai;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import forge.ai.AiCardMemory.MemorySet;
import forge.ai.ability.AnimateAi;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.game.card.CounterEnumType;
import forge.game.cost.CostPayEnergy;
import forge.game.cost.CostPayment;
import forge.card.mana.ManaCostShard;
import forge.game.CardTraitPredicates;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.*;
import forge.game.cost.*;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.ManaPool;
import forge.game.player.Player;
import forge.game.player.PlayerPredicates;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.TextUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Nested filter activation and mana-banking execution loop for {@link ComputerUtilMana}.
 */
final class ManaPaymentExecution {
    static final ZoneType[] HAND_AND_COMMAND = { ZoneType.Hand, ZoneType.Command };

    private ManaPaymentExecution() {
    }

    static String formatShardsPaidDiff(final ManaCostBeingPaid before, final ManaCostBeingPaid after,
            final ManaCostShard fallback) {
        final StringBuilder paid = new StringBuilder();
        for (final ManaCostShard shard : before.getDistinctShards()) {
            for (int i = before.getUnpaidShards(shard) - after.getUnpaidShards(shard); i > 0; i--) {
                paid.append(shard);
            }
        }
        return paid.length() == 0 ? fallback.toString() : paid.toString();
    }

    static boolean hasTapCost(final SpellAbility sa) {
        return ManaSourceTraits.of(sa).hasTapCost;
    }

    static boolean isMultiManaDisposable(final SpellAbility ma) {
        return ManaSourceTraits.of(ma).multiManaDisposable;
    }

    /**
     * True when the mana ability does not untap normally (Mana Vault).
     */
    static boolean doesNotUntapNormally(final SpellAbility ma) {
        return ManaSourceTraits.of(ma).doesNotUntapNormally;
    }

    /**
     * Disposable sacrifice tier for sorting. Lower = preferred.
     * 0 = self-sac token/artifact (Treasure, Petal); 1 = self-sac creature; 2 = sacrifice-other outlet.
     */
    static int disposableSacrificeTier(final SpellAbility ma) {
        if (!ManaFilterConsolidation.isDisposableManaAbility(ma)) {
            return -1;
        }
        if (ManaFilterConsolidation.sacrificesOtherPermanentsForMana(ma)) {
            return 2;
        }
        if (ManaFilterConsolidation.isSelfSacrificeCreatureMana(ma)) {
            return 1;
        }
        return 0;
    }

    /**
     * Among disposable candidates only. Lower return = better.
     * When {@code remaining >= 2}, prefer higher output; when {@code remaining == 1}, prefer tight fit.
     */
    static int compareDisposableByYield(final SpellAbility a, final SpellAbility b, final int remaining) {
        final int prodA = ManaFilterConsolidation.getManaProducedAmount(a);
        final int prodB = ManaFilterConsolidation.getManaProducedAmount(b);
        if (remaining >= 2) {
            return Integer.compare(prodB, prodA);
        }
        return Integer.compare(prodA, prodB);
    }

    static int compareDisposableCandidates(final SpellAbility a, final SpellAbility b, final int remaining) {
        final int tierA = disposableSacrificeTier(a);
        final int tierB = disposableSacrificeTier(b);
        if (tierA != tierB) {
            return Integer.compare(tierA, tierB);
        }
        return compareDisposableByYield(a, b, remaining);
    }

    /**
     * A source with no mana activation cost that may pay a filter's nested generic cost ({1}, etc.).
     * Includes sacrifice rocks; {@link #sortFreeSourcesForNestedActivation} ranks them below tap sources.
     */
    static boolean isFreeManaSourceForNestedActivation(final SpellAbility ma, final Card filterHost) {
        if (ma == null || ma.getHostCard() == filterHost) {
            return false;
        }
        final Cost payCosts = ma.getPayCosts();
        return payCosts == null || !payCosts.hasManaCost();
    }

    /**
     * True when a free nested-activation source can actually be spent right now (not already consumed
     * this payment, and untapped when the ability has a tap cost).
     */
    static boolean isCurrentlyAvailableForNestedActivation(final Player ai, final SpellAbility ma,
            final Card filterHost) {
        if (!isFreeManaSourceForNestedActivation(ma, filterHost)) {
            return false;
        }
        final Card host = ma.getHostCard();
        if (AiCardMemory.isRememberedCard(ai, host, MemorySet.PAYS_SAC_COST)) {
            return false;
        }
        if (hasTapCost(ma)) {
            if (AiCardMemory.isRememberedCard(ai, host, MemorySet.PAYS_TAP_COST) || host.isTapped()) {
                return false;
            }
        }
        return true;
    }

    /** True when this mana ability produces only colorless mana (e.g. Mind Stone, Wastes). */
    static boolean producesOnlyColorless(final SpellAbility ma) {
        return ManaSourceTraits.of(ma).producesOnlyColorless;
    }

    /**
     * True for a host's free {@code {T}:{C}} when the same card also has a paid any-mana filter
     * (Study Hall {@code {1},{T}:any} + {@code {T}:{C}}).
     */
    static boolean isFreeColorlessOnAnyManaFilterHost(final SpellAbility ma) {
        final ManaSourceTraits t = ManaSourceTraits.of(ma);
        if (!t.producesOnlyColorless || t.hasManaActivationCost) {
            return false;
        }
        final Card host = ma.getHostCard();
        if (host == null) {
            return false;
        }
        for (final SpellAbility other : host.getManaAbilities()) {
            if (other != ma && ManaSourceTraits.of(other).anyManaFilter) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a free (no mana activation cost), reusable ability natively produces this colored shard
     * (e.g. Plains for {W}, Forest for {G}). Excludes any-mana and one-shot sources.
     */
    static boolean producesShardDirectly(final SpellAbility ma, final ManaCostShard shard) {
        if (ma == null || shard.isGeneric() || shard == ManaCostShard.COLORLESS || shard.isPhyrexian()) {
            return false;
        }
        final ManaSourceTraits t = ManaSourceTraits.of(ma);
        if (t.disposable || t.hasManaActivationCost || t.manaString == null) {
            return false;
        }
        return t.manaString.contains(shard.toShortString());
    }

    /** Dedicated 1-mana producer for this colored shard (Forest for {G}, etc.). */
    static boolean isSinglePipDirectColoredProducer(final SpellAbility ma, final ManaCostShard toPay) {
        return producesShardDirectly(ma, toPay) && ManaSourceTraits.of(ma).producedAmount == 1;
    }

    /** Reusable direct colored source that produces 2+ mana per activation (Gaea's Cradle, etc.). */
    static boolean isDirectColoredMultiProducer(final SpellAbility ma, final ManaCostShard toPay) {
        final ManaSourceTraits t = ManaSourceTraits.of(ma);
        return t.multiManaProducer && !t.anyMultiManaProducer && producesShardDirectly(ma, toPay);
    }

    /** Dedicated colored producer (e.g. Plains, Mountain) without a mana activation cost. */
    static boolean producesColoredManaWithoutFilterCost(final SpellAbility ma) {
        return ManaSourceTraits.of(ma).producesColoredWithoutFilterCost;
    }

    /** True when a colored basic has already been tapped (e.g. Plains for {W} before paying generic {1}). */
    static boolean coloredBasicTappedThisPayment(final Player ai) {
        if (ai == null) {
            return false;
        }
        final Set<Card> tapCost = AiCardMemory.getMemorySet(ai, MemorySet.PAYS_TAP_COST);
        if (tapCost != null) {
            for (final Card c : tapCost) {
                if (isColoredBasicLand(c)) {
                    return true;
                }
            }
        }
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (c.isTapped() && isColoredBasicLand(c)) {
                return true;
            }
        }
        return false;
    }

    static boolean isColoredBasicLand(final Card c) {
        if (c == null || !c.isLand()) {
            return false;
        }
        for (final SpellAbility ma : c.getManaAbilities()) {
            if (producesColoredManaWithoutFilterCost(ma)) {
                return true;
            }
        }
        return false;
    }

    /** True when hand/command still holds a spell whose mana cost uses two or more colors. */
    static boolean handHasMulticolorManaSpells(final Player ai, final SpellAbility spellBeingPaid,
            final ManaPaymentContext ctx) {
        return handProbe(ai, spellBeingPaid, ctx).multicolorManaSpells;
    }

    /** True when hand or command zone contains another spell besides the one being paid for. */
    static boolean hasOtherHandOrCommandSpells(final Player ai, final SpellAbility sa,
            final ManaPaymentContext ctx) {
        return handProbe(ai, sa, ctx).otherHandOrCommandSpells;
    }

    /**
     * True when hand/command still holds a cast with both generic and colored mana (e.g. Phelia {@code {1}{W}}),
     * so a multi-color signet should stay available.
     */
    static boolean handHasGenericAndColoredCast(final Player ai, final SpellAbility spellBeingPaid,
            final ManaPaymentContext ctx) {
        return handProbe(ai, spellBeingPaid, ctx).genericAndColoredCast;
    }

    /**
     * All three hand facts are derived in a single pass and cached together per spell, so a cache hit for
     * one never returns a value computed for a different spell.
     */
    private static ManaPaymentContext.HandProbe handProbe(final Player ai, final SpellAbility spellBeingPaid,
            final ManaPaymentContext ctx) {
        final Card being = spellBeingPaid.getHostCard();
        final int spellId = being == null ? -1 : being.getId();
        if (ctx != null && being != null) {
            final ManaPaymentContext.HandProbe cached = ctx.caches.handProbe;
            if (cached != null && cached.spellId == spellId) {
                return cached;
            }
        }
        final ManaPaymentContext.HandProbe probe = scanHand(ai, being, spellId);
        if (ctx != null && being != null) {
            ctx.caches.handProbe = probe;
        }
        return probe;
    }

    private static ManaPaymentContext.HandProbe scanHand(final Player ai, final Card being, final int spellId) {
        boolean multicolor = false;
        boolean otherSpells = false;
        boolean genericAndColored = false;
        for (final ZoneType zone : HAND_AND_COMMAND) {
            for (final Card c : ai.getCardsIn(zone)) {
                if (c == being) {
                    continue;
                }
                if (!genericAndColored) {
                    final ManaCost mc = c.getManaCost();
                    if (mc != null && !mc.isNoCost() && mc.getGenericCost() > 0
                            && ColorSet.fromManaCost(mc).countColors() >= 1) {
                        genericAndColored = true;
                    }
                }
                if (multicolor && otherSpells) {
                    continue;
                }
                for (final SpellAbility candSa : c.getSpellAbilities()) {
                    if (!candSa.isSpell() || candSa.getPayCosts() == null || !candSa.getPayCosts().hasManaCost()) {
                        continue;
                    }
                    otherSpells = true;
                    final CostPartMana costMana = candSa.getPayCosts().getCostMana();
                    if (costMana != null && ColorSet.fromManaCost(costMana.getMana()).countColors() >= 2) {
                        multicolor = true;
                        break;
                    }
                }
            }
        }
        return new ManaPaymentContext.HandProbe(spellId, multicolor, otherSpells, genericAndColored);
    }

    /**
     * True for reusable, free sources that can pay this colored shard: dedicated lands, Arcane Signet
     * (commander color identity), etc. Excludes one-shot mana and any-mana filters (Study Hall {@code {1},{T}:any}).
     */
    static boolean isReusableFreeManaForShard(final SpellAbility ma, final ManaCostShard shard) {
        if (producesShardDirectly(ma, shard)) {
            return true;
        }
        if (ma == null || shard.isGeneric() || shard == ManaCostShard.COLORLESS || shard.isPhyrexian()) {
            return false;
        }
        if (ManaFilterConsolidation.isDisposableManaAbility(ma) || ManaFilterConsolidation.isAnyManaConsolidatingFilter(ma)) {
            return false;
        }
        final Cost payCosts = ma.getPayCosts();
        if (payCosts != null && payCosts.hasManaCost()) {
            return false;
        }
        final AbilityManaPart mp = ma.getManaPart();
        if (mp == null) {
            return false;
        }
        return mp.canProduce(shard.toShortString(), ma);
    }

    /**
     * How generic mana pips should be paid relative to colorless vs colored sources.
     * Lower {@link #rankGenericManaSource} ranks are better.
     */
    /** Reusable sources first; sacrifice / one-shot mana remains available as a fallback. */
    static void sortFreeSourcesForNestedActivation(final List<SpellAbility> candidates,
            final ManaCostShard toPay, final boolean reserveColorless) {
        candidates.sort((a1, a2) -> {
            if (toPay == ManaCostShard.GENERIC || toPay == ManaCostShard.X) {
                final int colorlessCmp = ManaAbilitySort.compareColorlessPreference(a1, a2, reserveColorless);
                if (colorlessCmp != 0) {
                    return colorlessCmp;
                }
            }
            final int t1 = nestedActivationSourceTier(a1);
            final int t2 = nestedActivationSourceTier(a2);
            if (t1 != t2) {
                return Integer.compare(t1, t2);
            }
            if (ManaFilterConsolidation.isDisposableManaAbility(a1) && ManaFilterConsolidation.isDisposableManaAbility(a2)) {
                final int disposableCmp = compareDisposableCandidates(a1, a2, 1);
                if (disposableCmp != 0) {
                    return disposableCmp;
                }
            }
            return 0;
        });
    }

    /** Reusable (0) before creature-tap sources like Springleaf Drum (1) before disposables (2). */
    static int nestedActivationSourceTier(final SpellAbility ma) {
        if (ManaFilterConsolidation.isDisposableManaAbility(ma)) {
            return 2;
        }
        return ManaFilterConsolidation.requiresTappingOtherCreatureForMana(ma) ? 1 : 0;
    }

    /** Reusable source that produces exactly {@code remaining} mana with no filter activation cost. */
    static boolean isTightGenericProducer(final SpellAbility ma, final int remaining) {
        if (ma == null || remaining <= 0) {
            return false;
        }
        final ManaSourceTraits t = ManaSourceTraits.of(ma);
        return !t.hasManaActivationCost && !t.disposable && t.producedAmount == remaining;
    }

    /** One activation adds two or more mana (Sol Ring, Gilded Lotus, etc.) without a mana activation cost. */
    static boolean isMultiManaProducer(final SpellAbility ma) {
        return ManaSourceTraits.of(ma).multiManaProducer;
    }

    /** Gilded Lotus-style: any one color, two or more mana per activation. */
    static boolean isAnyMultiManaProducer(final SpellAbility ma) {
        return ManaSourceTraits.of(ma).anyMultiManaProducer;
    }

    static boolean isConsolidatingCandidate(final SpellAbility ma) {
        return ManaSourceTraits.of(ma).consolidatingCandidate;
    }

    static boolean hasAlternativeExcept(final List<SpellAbility> alternatives, final SpellAbility skip,
            final java.util.function.Predicate<SpellAbility> predicate) {
        for (final SpellAbility ma : alternatives) {
            if (ma != skip && predicate.test(ma)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One pass over the alternative sources for a shard; {@link #excluding} then yields the
     * "does some <em>other</em> candidate have property X" flags for any member in O(1).
     */
    static final class AlternativeScan {
        private static final int MULTI_SHARD = 0;
        private static final int MULTI_MANA = 1;
        private static final int MULTI_MANA_DISPOSABLE = 2;
        private static final int SELF_SAC_DISPOSABLE = 3;
        private static final int DIRECT_COLORED = 4;
        private static final int SINGLE_PIP_DIRECT = 5;
        private static final int TIGHT_GENERIC = 6;
        private static final int ANY_MULTI = 7;
        private static final int REUSABLE_NON_CREATURE_TAP = 8;
        private static final int REUSABLE_UNTAPPING = 9;
        private static final int PROPERTIES = 10;

        private final ManaCostShard toPay;
        private final int remaining;
        private final int[] counts = new int[PROPERTIES];
        private int minAnyFilterCmc = Integer.MAX_VALUE;
        private int minAnyFilterCmcCount;
        private int secondMinAnyFilterCmc = Integer.MAX_VALUE;

        private AlternativeScan(final ManaCostShard toPay, final int remaining) {
            this.toPay = toPay;
            this.remaining = remaining;
        }

        static AlternativeScan of(final List<SpellAbility> alternatives, final ManaCostShard toPay,
                final int remaining) {
            final AlternativeScan scan = new AlternativeScan(toPay, remaining);
            final boolean[] props = new boolean[PROPERTIES];
            for (final SpellAbility ma : alternatives) {
                scan.properties(ma, props);
                for (int i = 0; i < PROPERTIES; i++) {
                    if (props[i]) {
                        scan.counts[i]++;
                    }
                }
                final ManaSourceTraits t = ManaSourceTraits.of(ma);
                if (t.anyManaFilter) {
                    final int cmc = t.activationCMC;
                    if (cmc < scan.minAnyFilterCmc) {
                        scan.secondMinAnyFilterCmc = scan.minAnyFilterCmc;
                        scan.minAnyFilterCmc = cmc;
                        scan.minAnyFilterCmcCount = 1;
                    } else if (cmc == scan.minAnyFilterCmc) {
                        scan.minAnyFilterCmcCount++;
                    } else if (cmc < scan.secondMinAnyFilterCmc) {
                        scan.secondMinAnyFilterCmc = cmc;
                    }
                }
            }
            return scan;
        }

        private void properties(final SpellAbility ma, final boolean[] out) {
            final ManaSourceTraits t = ManaSourceTraits.of(ma);
            out[MULTI_SHARD] = t.multiPipFilter || t.comboFilter;
            out[MULTI_MANA] = t.multiManaProducer && !t.anyMultiManaProducer && t.producedAmount >= remaining;
            out[MULTI_MANA_DISPOSABLE] = t.multiManaDisposable && t.producedAmount >= remaining;
            out[SELF_SAC_DISPOSABLE] = t.disposable && !t.sacrificesOther;
            out[DIRECT_COLORED] = producesShardDirectly(ma, toPay);
            out[SINGLE_PIP_DIRECT] = out[DIRECT_COLORED] && t.producedAmount == 1;
            out[TIGHT_GENERIC] = isTightGenericProducer(ma, remaining);
            out[ANY_MULTI] = t.anyMultiManaProducer;
            out[REUSABLE_NON_CREATURE_TAP] = !t.disposable && !t.requiresTappingOtherCreature;
            out[REUSABLE_UNTAPPING] = out[REUSABLE_NON_CREATURE_TAP] && !t.doesNotUntapNormally;
        }

        /** Flags for the alternatives other than {@code skip}; {@code skip} must be a member of the scanned list. */
        AlternativeScanFlags excluding(final SpellAbility skip) {
            final boolean[] own = new boolean[PROPERTIES];
            properties(skip, own);
            final AlternativeScanFlags flags = new AlternativeScanFlags();
            flags.hasMultiShardAlt = others(MULTI_SHARD, own);
            flags.hasMultiManaAlt = others(MULTI_MANA, own);
            flags.hasMultiManaDisposableAlt = others(MULTI_MANA_DISPOSABLE, own);
            flags.hasSelfSacDisposableAlt = others(SELF_SAC_DISPOSABLE, own);
            flags.hasDirectColoredAlt = others(DIRECT_COLORED, own);
            flags.hasSinglePipDirectColoredAlt = others(SINGLE_PIP_DIRECT, own);
            flags.hasTightGenericAlt = others(TIGHT_GENERIC, own);
            flags.hasAnyMultiAlt = others(ANY_MULTI, own);
            flags.hasReusableNonCreatureTapAlt = others(REUSABLE_NON_CREATURE_TAP, own);
            flags.hasReusableUntappingAlt = others(REUSABLE_UNTAPPING, own);
            final ManaSourceTraits t = ManaSourceTraits.of(skip);
            if (t.anyManaFilter) {
                final int minOthers = t.activationCMC == minAnyFilterCmc && minAnyFilterCmcCount == 1
                        ? secondMinAnyFilterCmc : minAnyFilterCmc;
                flags.hasCheaperAnyManaFilterAlt = minOthers < t.activationCMC;
            }
            return flags;
        }

        private boolean others(final int property, final boolean[] own) {
            return counts[property] - (own[property] ? 1 : 0) > 0;
        }
    }

    /** "Some other alternative has property X" flags used by {@link #paymentEfficiencyScore}. */
    static final class AlternativeScanFlags {
        boolean hasMultiShardAlt;
        boolean hasMultiManaAlt;
        boolean hasMultiManaDisposableAlt;
        boolean hasSelfSacDisposableAlt;
        boolean hasDirectColoredAlt;
        boolean hasSinglePipDirectColoredAlt;
        boolean hasTightGenericAlt;
        boolean hasAnyMultiAlt;
        boolean hasReusableNonCreatureTapAlt;
        boolean hasReusableUntappingAlt;
        boolean hasCheaperAnyManaFilterAlt;
    }

    /** Cheap monotone key that changes whenever any shard of {@code cost} is paid (progress detection). */
    static int unpaidProgressKey(final ManaCostBeingPaid cost) {
        return cost.getConvertedManaCost() * 31 + cost.getXcounter();
    }

    static int countUnpaidPips(final ManaCostBeingPaid cost) {
        int n = cost.getGenericManaAmount();
        for (final ManaCostShard shard : cost.getDistinctShards()) {
            if (!shard.isGeneric() && shard != ManaCostShard.COLORLESS && !shard.isPhyrexian()) {
                n += cost.getUnpaidShards(shard);
            }
        }
        return n;
    }

    static int remainingPipsForShard(final ManaCostBeingPaid cost, final ManaCostShard toPay) {
        if (toPay == null) {
            return 0;
        }
        if (toPay.isGeneric() || toPay == ManaCostShard.X) {
            return cost.getGenericManaAmount();
        }
        if (toPay == ManaCostShard.COLORLESS) {
            return cost.getUnpaidShards(ManaCostShard.COLORLESS);
        }
        return cost.getUnpaidShards(toPay);
    }

    /** Signet-style or combo-land filter with a mana activation cost (e.g. Rakdos Signet, Cascade Bluffs). */
    static boolean isManaActivationConsolidator(final SpellAbility ma) {
        return ManaSourceTraits.of(ma).manaActivationConsolidator;
    }

    /** True when {@code host} has a combo-land mana mode (e.g. Cascade Bluffs' {@code {U/R},{T}:...}). */
    static boolean hostHasComboConsolidator(final Card host) {
        if (host == null) {
            return false;
        }
        for (final SpellAbility ma : host.getManaAbilities()) {
            if (ManaFilterConsolidation.isComboConsolidatingFilter(ma)) {
                return true;
            }
        }
        return false;
    }

    /** Lower is better. Disposable sources are heavily penalized so signets beat Lotus Petal for colored pips. */
    static int paymentEfficiencyScore(final SpellAbility chosen, final int consumedCount,
            final ManaCostBeingPaid cost, final ManaCostShard toPay, final List<SpellAbility> alternatives,
            final AlternativeScan altScan, final Player ai, final SpellAbility sa, final ManaPaymentContext ctx) {
        int score = consumedCount;
        final int remaining = remainingPipsForShard(cost, toPay);
        final AlternativeScanFlags altFlags = (altScan != null ? altScan
                : AlternativeScan.of(alternatives, toPay, remaining)).excluding(chosen);
        if (ManaFilterConsolidation.isDisposableManaAbility(chosen)) {
            if (altFlags.hasMultiShardAlt || !disposableIsReasonableForShard(chosen, cost, toPay, alternatives, ai, ctx)) {
                score += 100;
            }
        }
        if (ManaFilterConsolidation.sacrificesOtherPermanentsForMana(chosen) && altFlags.hasSelfSacDisposableAlt) {
            score += 75;
        }
        if (ManaFilterConsolidation.requiresTappingOtherCreatureForMana(chosen)
                && altFlags.hasReusableNonCreatureTapAlt) {
            score += 40;
        }
        if (doesNotUntapNormally(chosen) && altFlags.hasReusableUntappingAlt) {
            score += 60;
        }
        if (ManaFilterConsolidation.netNegativeAnyManaFilterLoss(chosen) > 0 && altFlags.hasCheaperAnyManaFilterAlt) {
            score += 25 * ManaFilterConsolidation.netNegativeAnyManaFilterLoss(chosen);
        }
        if (ManaFilterConsolidation.isAnyManaConsolidatingFilter(chosen) && cost.getGenericManaAmount() > 0 && !toPay.isGeneric()) {
            score += 50;
        }
        if (remaining >= 2 && ManaFilterConsolidation.getManaProducedAmount(chosen) < remaining && altFlags.hasMultiManaAlt
                && !altFlags.hasSinglePipDirectColoredAlt) {
            score += 50;
        }
        if (remaining >= 2 && ManaFilterConsolidation.isDisposableManaAbility(chosen) && ManaFilterConsolidation.getManaProducedAmount(chosen) < remaining
                && altFlags.hasMultiManaDisposableAlt) {
            score += 50;
        }
        if (isAnyMultiManaProducer(chosen) && ManaFilterConsolidation.getManaProducedAmount(chosen) > remaining
                && altFlags.hasDirectColoredAlt) {
            score += 25 * (ManaFilterConsolidation.getManaProducedAmount(chosen) - remaining);
        }
        if (isDirectColoredMultiProducer(chosen, toPay) && ManaFilterConsolidation.getManaProducedAmount(chosen) > remaining
                && altFlags.hasSinglePipDirectColoredAlt) {
            score += 25 * (ManaFilterConsolidation.getManaProducedAmount(chosen) - remaining);
        }
        if ((toPay.isGeneric() || toPay == ManaCostShard.X)
                && ManaFilterConsolidation.getManaProducedAmount(chosen) > remaining && altFlags.hasTightGenericAlt) {
            score += 25 * (ManaFilterConsolidation.getManaProducedAmount(chosen) - remaining);
        }
        if ((toPay.isGeneric() || toPay == ManaCostShard.X) && producesColoredManaWithoutFilterCost(chosen)
                && handHasMulticolorManaSpells(ai, sa, ctx) && altFlags.hasAnyMultiAlt) {
            score += 50;
        }
        return score;
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
        if (cost.getGenericManaAmount() == 0) {
            return hasReusableFreeProducerForEveryOtherColoredShard(cost, toPay, ai, ctx);
        }
        if (hasAlternativeExcept(alternatives, disposable, ma -> isReusableFreeManaForShard(ma, toPay))) {
            return false;
        }
        final ListMultimap<Integer, SpellAbility> manaAbilityMap = ComputerUtilMana.getOrBuildManaAbilityMap(ai, true, ctx);
        final List<SpellAbility> filterAlts = consolidatingFilterAlternatives(alternatives, disposable);
        if (filterAlts.isEmpty()) {
            return false;
        }
        return filterAlts.stream().noneMatch(ma -> consolidatorBeatsDisposable(ma, cost, ai, manaAbilityMap, ctx));
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
        return ManaSourceTraits.of(filter).netPositiveConsolidator;
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
        int coloredUnpaid = countUnpaidPips(cost) - cost.getGenericManaAmount();
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
        final int need = countUnpaidPips(cost);
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
            if (hasTapCost(ma) && (host.isTapped()
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
        for (final ZoneType zone : HAND_AND_COMMAND) {
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
                if (!isReusableFreeManaForShard(ma, shard)) {
                    continue;
                }
                if (hasTapCost(ma)
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
        if (!isNetPositiveConsolidator(filter) || shouldReserveConsolidator(filter, sa, cost, ai, ctx)) {
            return null;
        }
        if (!canActivateFilter(ai, filter, ComputerUtilMana.getOrBuildManaAbilityMap(ai, true, ctx), false)) {
            return null;
        }
        final Set<Card> consumed = collectCardsConsumedByPayment(filter, sa, ai, ctx);
        if (consumed == null) {
            return null;
        }
        final ManaCostShard probeShard = shardForConsolidatorProbe(cost);
        refreshExpressChoice(cost, sa, ai, probeShard, filter);
        final ManaCostBeingPaid probe = new ManaCostBeingPaid(cost);
        applyChosenPaymentToCostProbe(probe, filter, ai, probeShard);
        if (probe.isPaid()) {
            return consumed;
        }
        final boolean covers = ComputerUtilMana.useFullPaymentProbes(test, ctx)
                ? keepsRemainingCostPayableWithConsumed(cost, sa, ai, probeShard, filter, consumed, test, ctx)
                // Feasibility dry-run: only accept partial cover when leftover is generic/colorless from free taps.
                : remainingGenericPayableByUnconsumedFreeSources(probe, consumed, ai, ctx);
        return covers ? consumed : null;
    }

    /**
     * After a consolidator pays colored pips, true when unpaid generic/colorless can be covered by free
     * sources whose hosts were not consumed (and no colored shards remain).
     */
    static boolean remainingGenericPayableByUnconsumedFreeSources(final ManaCostBeingPaid remaining,
            final Set<Card> consumed, final Player ai, final ManaPaymentContext ctx) {
        if (remaining == null || remaining.isPaid() || ai == null) {
            return remaining != null && remaining.isPaid();
        }
        if (hasUnpaidColoredShards(remaining)) {
            return false;
        }
        final int need = countUnpaidPips(remaining);
        if (need <= 0) {
            return true;
        }
        return freeGenericSourcesCover(need, host -> consumed != null && consumed.contains(host), ai, ctx);
    }

    static boolean consolidatorCoversRemainingCost(final Collection<SpellAbility> maList,
            final ManaCostBeingPaid cost, final SpellAbility sa, final Player ai,
            final ManaPaymentContext ctx, final boolean test) {
        for (final SpellAbility other : maList) {
            if (ManaFilterConsolidation.isDisposableManaAbility(other) || !isConsolidatingCandidate(other)) {
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
            final ManaCostBeingPaid cost, final Player ai,
            final ListMultimap<Integer, SpellAbility> manaAbilityMap, final ManaPaymentContext ctx) {
        return canActivateFilter(ai, filter, manaAbilityMap, true)
                && !filterActivationCompetesForSpellGeneric(filter, cost, ai, ctx);
    }

    static boolean consolidatorBeatsDisposable(final SpellAbility filter, final ManaCostBeingPaid cost,
            final Player ai, final ListMultimap<Integer, SpellAbility> manaAbilityMap,
            final ManaPaymentContext ctx) {
        if (ManaFilterConsolidation.isAnyManaConsolidatingFilter(filter)) {
            return anyManaFilterBeatsDisposable(filter, cost, ai, manaAbilityMap, ctx);
        }
        return canActivateFilter(ai, filter, manaAbilityMap, false);
    }

    /**
     * True when the filter's host also has a free colorless mana ability (Study Hall {@code {T}:{C}}) that
     * can pay the spell's generic pips without the filter's paid activation.
     */
    static boolean hasFreeColorlessManaAbilityOnHost(final SpellAbility filter) {
        final Card host = filter == null ? null : filter.getHostCard();
        if (host == null) {
            return false;
        }
        for (final SpellAbility ma : host.getManaAbilities()) {
            if (ma == filter || !producesOnlyColorless(ma) || ManaFilterConsolidation.hasManaActivationCost(ma)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * True when paying this filter's activation would consume reusable generic mana sources needed
     * to pay the spell's own generic pips (e.g. one Plains cannot fund Study Hall's {@code {1}} and
     * the spell's {@code {1}}). {@code {1}} accepts any mana; {@code {C}} sources count too.
     *
     * Also true when the host has a free {@code {C}} ability that should pay generic directly — using
     * the filter's paid any-mana line for a colored pip taps the host and strands that path.
     */
    static boolean filterActivationCompetesForSpellGeneric(final SpellAbility filter,
            final ManaCostBeingPaid cost, final Player ai, final ManaPaymentContext ctx) {
        if (cost.getGenericManaAmount() <= 0 || ai == null) {
            return false;
        }
        if (ManaFilterConsolidation.isAnyManaConsolidatingFilter(filter) && hasFreeColorlessManaAbilityOnHost(filter)) {
            return true;
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
        final Card filterHost = filter.getHostCard();
        final int needed = cost.getGenericManaAmount() + activationGeneric;
        int reusableGenericSources = 0;
        for (final SpellAbility candidate : manaAbilityMap.get(ManaAtom.GENERIC)) {
            if (isFreeReusableSourceForNestedActivation(candidate, filterHost)) {
                reusableGenericSources++;
                if (reusableGenericSources >= needed) {
                    return false;
                }
            }
        }
        return reusableGenericSources < needed;
    }

    /** Free nested-activation source that is not a one-shot (Petal, Treasure, etc.). */
    static boolean isFreeReusableSourceForNestedActivation(final SpellAbility ma, final Card filterHost) {
        return isFreeManaSourceForNestedActivation(ma, filterHost) && !ManaFilterConsolidation.isDisposableManaAbility(ma);
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
        final Card filterHost = filter.getHostCard();
        final ManaCost activation = reusableOnly ? costMana.getManaCostFor(filter) : costMana.getMana();
        if (activation.getGenericCost() > 0
                && !hasActivatorInGenericBucket(ai, manaAbilityMap, filterHost, reusableOnly)) {
            return false;
        }
        for (final ManaCostShard shard : activation) {
            if (reusableOnly && (shard.isGeneric() || shard == ManaCostShard.COLORLESS)) {
                continue;
            }
            if (!hasActivatorForShard(ai, manaAbilityMap, shard, filterHost, reusableOnly)) {
                return false;
            }
        }
        return true;
    }

    /** {@code {1}} activation: any free tap in the generic bucket (all mana sources are indexed here). */
    static boolean hasActivatorInGenericBucket(final Player ai,
            final ListMultimap<Integer, SpellAbility> manaAbilityMap, final Card filterHost,
            final boolean reusableOnly) {
        for (final SpellAbility candidate : manaAbilityMap.get(ManaAtom.GENERIC)) {
            if (reusableOnly) {
                if (!isFreeReusableSourceForNestedActivation(candidate, filterHost)) {
                    continue;
                }
            } else if (!isFreeManaSourceForNestedActivation(candidate, filterHost)) {
                continue;
            }
            if (isCurrentlyAvailableForNestedActivation(ai, candidate, filterHost)) {
                return true;
            }
        }
        return false;
    }

    /** True when a free activator can pay this colored/hybrid shard. */
    static boolean hasActivatorForShard(final Player ai,
            final ListMultimap<Integer, SpellAbility> manaAbilityMap, final ManaCostShard shard,
            final Card filterHost, final boolean reusableOnly) {
        for (final byte color : ManaAtom.MANATYPES) {
            if (!shard.canBePaidWithManaOfColor(color)) {
                continue;
            }
            for (final SpellAbility candidate : manaAbilityMap.get((int) color)) {
                if (reusableOnly) {
                    if (!isFreeReusableSourceForNestedActivation(candidate, filterHost)) {
                        continue;
                    }
                } else if (!isFreeManaSourceForNestedActivation(candidate, filterHost)) {
                    continue;
                }
                if (isCurrentlyAvailableForNestedActivation(ai, candidate, filterHost)) {
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

    /** True when this mana ability's cost includes sacrificing a permanent. */
    static boolean needsManaSacrificeReservation(final SpellAbility ma) {
        return ma != null && (ComputerUtilCost.isSacrificeSelfCost(ma.getPayCosts())
                || ManaFilterConsolidation.sacrificesOtherPermanentsForMana(ma));
    }

    /**
     * Whether {@link MemorySet#PAYS_SAC_COST} already holds a valid target for {@code ma}'s sac cost
     * (so we must not call {@link ComputerUtilCost#checkForManaSacrificeCost} again — it would exclude
     * that card and may fail with a singleton board).
     */
    static boolean hasValidManaSacrificeReservation(final Player ai, final SpellAbility ma) {
        if (ai == null || ma == null || ma.getPayCosts() == null) {
            return false;
        }
        final Set<Card> remembered = AiCardMemory.getMemorySet(ai, MemorySet.PAYS_SAC_COST);
        if (remembered == null || remembered.isEmpty()) {
            return false;
        }
        final Card host = ma.getHostCard();
        for (final CostPart part : ma.getPayCosts().getCostParts()) {
            if (!(part instanceof CostSacrifice)) {
                continue;
            }
            if (part.payCostFromSource()) {
                if (!remembered.contains(host)) {
                    return false;
                }
                continue;
            }
            boolean found = false;
            for (final Card c : remembered) {
                if (c != null && c != host && c.isValid(part.getType().split(";"), ai, host, ma)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reservation checks ({@link ComputerUtilCost#checkForManaSacrificeCost},
     * {@link ComputerUtilCost#checkTapTypeCost}) have side effects and must run only for the ability
     * actually chosen, not while enumerating every candidate (see AutoPaymentTest).
     */
    static boolean passesManaPaymentReservationChecks(final Player ai, final SpellAbility ma,
            final SpellAbility sa) {
        return ComputerUtilCost.checkForManaSacrificeCost(ai, ma.getPayCosts(), ma, ma.isTrigger())
                && ComputerUtilCost.checkTapTypeCost(ai, ma.getPayCosts(), ma.getHostCard(), sa,
                        AiCardMemory.getMemorySet(ai, MemorySet.PAYS_TAP_COST));
    }

    static SpellAbility pickFirstReservedManaChoice(final Player ai, final SpellAbility sa,
            final List<SpellAbility> candidates) {
        for (final SpellAbility ma : candidates) {
            try (ReservationSnapshot snap = ReservationSnapshot.take(ai)) {
                if (passesManaPaymentReservationChecks(ai, ma, sa)) {
                    snap.keep();
                    return ma;
                }
            }
        }
        return null;
    }

    /** Match {@link #sortFreeSourcesForNestedActivation} / {@link #chooseManaAbility} generic ranking. */
    static SpellAbility chooseManaAbilityForShard(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final ManaCostShard toPay, Collection<SpellAbility> maList, final boolean checkCosts,
            final boolean test, final ManaPaymentContext ctx) {
        final List<SpellAbility> valid = collectValidManaPaymentChoices(cost, sa, ai, toPay, maList, checkCosts, test, ctx);
        if (valid.isEmpty()) {
            return null;
        }
        if (valid.size() == 1 || (ctx != null && ctx.inFilterActivationProbe) || !CastabilityProbe.shouldUse(sa, test, ctx)
                || ((ctx == null || !ctx.paymentPromptPreview)
                        && valid.stream().noneMatch(ManaPaymentExecution::isConsolidatingCandidate))
                || !hasOtherHandOrCommandSpells(ai, sa, ctx)) {
            return preferSourceThatKeepsRestPayable(cost, sa, ai, toPay, valid, test, ctx);
        }
        if ((toPay.isGeneric() || toPay == ManaCostShard.X)
                && (handHasMulticolorManaSpells(ai, sa, ctx) || handHasGenericAndColoredCast(ai, sa, ctx))
                && valid.stream().anyMatch(ManaPaymentExecution::isAnyMultiManaProducer)) {
            return preferSourceThatKeepsRestPayable(cost, sa, ai, toPay, valid, test, ctx);
        }

        final boolean preferMultiForGeneric = toPay.isGeneric() || toPay == ManaCostShard.X;
        final SpellAbility best = CastabilityProbe.pickBest(cost, valid, sa, ai, toPay,
                ManaPaymentExecution::collectCardsConsumedByPayment, preferMultiForGeneric, test, ctx);
        return best != null ? refreshExpressChoice(cost, sa, ai, toPay, best)
                : refreshExpressChoice(cost, sa, ai, toPay, valid.get(0));
    }

    /** Count reusable 1-mana producers for {@code toPay} among {@code valid} candidates. */
    static int countSinglePipDirectColoredProducers(final List<SpellAbility> valid,
            final ManaCostShard toPay) {
        int count = 0;
        for (final SpellAbility ma : valid) {
            if (isSinglePipDirectColoredProducer(ma, toPay)) {
                count++;
            }
        }
        return count;
    }

    /**
     * When basics cannot cover all remaining colored pips but a direct multi-producer can (e.g. Cradle
     * for 4 with only 2 Forests paying {G}{G}{G}), prefer one multi activation over piecing basics first.
     */
    static SpellAbility pickDirectColoredMultiWhenSinglePipsInsufficient(final ManaCostBeingPaid cost,
            final SpellAbility sa, final Player ai, final ManaCostShard toPay, final List<SpellAbility> valid,
            final boolean test, final ManaPaymentContext ctx) {
        if (toPay == null || toPay.isGeneric() || toPay == ManaCostShard.X || toPay == ManaCostShard.COLORLESS
                || toPay.isPhyrexian()) {
            return null;
        }
        final int remaining = remainingPipsForShard(cost, toPay);
        if (remaining <= 1 || countSinglePipDirectColoredProducers(valid, toPay) >= remaining) {
            return null;
        }
        SpellAbility bestMulti = null;
        int bestAmount = 0;
        for (final SpellAbility ma : valid) {
            if (!isDirectColoredMultiProducer(ma, toPay)) {
                continue;
            }
            final int prod = ManaFilterConsolidation.getManaProducedAmount(ma);
            if (prod >= remaining && prod > bestAmount) {
                bestAmount = prod;
                bestMulti = ma;
            }
        }
        if (bestMulti == null) {
            return null;
        }
        final PaymentImpact impact = probePaymentImpact(cost, sa, ai, toPay, bestMulti, valid, null, test, ctx);
        if (impact != null && impact.keepsRest) {
            return refreshExpressChoice(cost, sa, ai, toPay, bestMulti);
        }
        return null;
    }

    /**
     * {@link #evaluatePaymentImpact} with the reservation memory rolled back afterwards; {@code null}
     * when {@code cand} fails {@link #passesManaPaymentReservationChecks}.
     */
    static PaymentImpact probePaymentImpact(final ManaCostBeingPaid cost, final SpellAbility sa, final Player ai,
            final ManaCostShard toPay, final SpellAbility cand, final List<SpellAbility> alternatives,
            final AlternativeScan altScan, final boolean test, final ManaPaymentContext ctx) {
        try (ReservationSnapshot snap = ReservationSnapshot.take(ai)) {
            if (!passesManaPaymentReservationChecks(ai, cand, sa)) {
                return null;
            }
            return evaluatePaymentImpact(cost, sa, ai, toPay, cand, alternatives, altScan, test, ctx);
        }
    }

    /**
     * Among equally-ranked candidates, avoid one that would strand the rest of THIS cost. Using a
     * filter with a mana activation cost taps an extra source, which can leave later pips of the same
     * payment unpayable (e.g. tapping Plains to activate Study Hall for {G} strands the {W} and {1} that also
     * needed Plains). Only reorders when the top choice has such a cost and a cheaper alternative keeps the
     * remaining cost payable; otherwise the existing order is preserved.
     */
    static SpellAbility preferSourceThatKeepsRestPayable(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final ManaCostShard toPay, final List<SpellAbility> valid, final boolean test,
            final ManaPaymentContext ctx) {
        // Nested feasibility probes follow sort order only; stranding / efficiency checks are for the
        // outer spell payment (depth 1) so we don't re-simulate every land on every recursive call.
        if ((ctx != null && ctx.inFilterActivationProbe) || (ctx != null && ctx.depth > 1)) {
            final SpellAbility first = pickFirstReservedManaChoice(ai, sa, valid);
            return first == null ? null : refreshExpressChoice(cost, sa, ai, toPay, first);
        }

        final SpellAbility directMulti = pickDirectColoredMultiWhenSinglePipsInsufficient(cost, sa, ai, toPay, valid,
                test, ctx);
        if (directMulti != null) {
            return directMulti;
        }

        SpellAbility first = null;
        SpellAbility best = null;
        int bestEfficiency = Integer.MAX_VALUE;

        List<SpellAbility> ranked = valid;
        final boolean genericShard = toPay == ManaCostShard.GENERIC || toPay == ManaCostShard.X;
        // Reservation memory is snapshot/restored around each probe below, so this is loop-invariant.
        final ManaAbilitySort.GenericColorPreference pref = genericShard
                ? ManaAbilitySort.resolveGenericColorPreference(ai, sa) : null;
        final int unpaidGeneric = cost.getGenericManaAmount();
        if (genericShard) {
            ranked = Lists.newArrayList(valid);
            ranked.sort((a, b) -> ManaAbilitySort.compareGenericCandidatesForPayment(a, b, pref, unpaidGeneric, sa, ai));
        }
        final AlternativeScan altScan = AlternativeScan.of(valid, toPay, remainingPipsForShard(cost, toPay));

        for (final SpellAbility cand : CastabilityProbe.capCandidates(ranked)) {
            if (bestEfficiency <= 1 && best != null && producesShardDirectly(best, toPay)) {
                break;
            }
            final PaymentImpact impact = probePaymentImpact(cost, sa, ai, toPay, cand, valid, altScan, test, ctx);
            if (impact == null) {
                continue; // failed reservation checks
            }
            if (first == null) {
                first = cand;
            }
            if (!impact.keepsRest) {
                continue;
            }
            final int efficiency = impact.efficiencyScore();
            if (efficiency < bestEfficiency
                    || (efficiency == bestEfficiency && best != null && genericShard
                            && ManaAbilitySort.compareGenericCandidatesForPayment(cand, best, pref, unpaidGeneric, sa, ai) < 0)) {
                bestEfficiency = efficiency;
                best = cand;
            }
        }
        if (best != null) {
            return refreshExpressChoice(cost, sa, ai, toPay, best);
        }
        final SpellAbility consolidator = pickConsolidatorWhenDisposableWouldStrand(cost, sa, ai, toPay, valid, test, ctx);
        if (consolidator != null) {
            return refreshExpressChoice(cost, sa, ai, toPay, consolidator);
        }
        return first == null ? null : refreshExpressChoice(cost, sa, ai, toPay, first);
    }

    /**
     * When every candidate that passed {@code keepsRest} was skipped, avoid falling back to a disposable
     * that strands a net-positive consolidator (e.g. Lotus Petal on {W} before Signet can fire).
     */
    static SpellAbility pickConsolidatorWhenDisposableWouldStrand(final ManaCostBeingPaid cost,
            final SpellAbility sa, final Player ai, final ManaCostShard toPay,
            final List<SpellAbility> valid, final boolean test, final ManaPaymentContext ctx) {
        if (valid.isEmpty() || toPay.isGeneric()) {
            return null;
        }
        final List<SpellAbility> consolidators = new ArrayList<>();
        for (final SpellAbility cand : valid) {
            if (!isConsolidatingCandidate(cand) || !isNetPositiveConsolidator(cand)) {
                continue;
            }
            if (shouldReserveConsolidator(cand, sa, cost, ai, ctx)) {
                continue;
            }
            consolidators.add(cand);
        }
        for (final SpellAbility cand : CastabilityProbe.capCandidates(consolidators)) {
            final PaymentImpact impact = probePaymentImpact(cost, sa, ai, toPay, cand, valid, null, test, ctx);
            if (impact != null && impact.keepsRest) {
                return cand;
            }
        }
        return null;
    }

    static final class PaymentImpact {
        final boolean keepsRest;
        final int consumedCount;
        private final SpellAbility chosen;
        private final ManaCostBeingPaid cost;
        private final ManaCostShard toPay;
        private final List<SpellAbility> alternatives;
        private final AlternativeScan altScan;
        private final Player ai;
        private final SpellAbility sa;
        private final ManaPaymentContext ctx;
        private Integer efficiencyScore;

        private PaymentImpact(final boolean keepsRest, final int consumedCount, final SpellAbility chosen,
                final ManaCostBeingPaid cost, final ManaCostShard toPay, final List<SpellAbility> alternatives,
                final AlternativeScan altScan, final Player ai, final SpellAbility sa, final ManaPaymentContext ctx) {
            this.keepsRest = keepsRest;
            this.consumedCount = consumedCount;
            this.chosen = chosen;
            this.cost = cost;
            this.toPay = toPay;
            this.alternatives = alternatives;
            this.altScan = altScan;
            this.ai = ai;
            this.sa = sa;
            this.ctx = ctx;
        }

        /** Lower is better; computed on first use (callers discard impacts that do not keep the rest payable). */
        int efficiencyScore() {
            if (efficiencyScore == null) {
                efficiencyScore = paymentEfficiencyScore(chosen, consumedCount, cost, toPay, alternatives, altScan,
                        ai, sa, ctx);
            }
            return efficiencyScore;
        }
    }

    static int effectiveCardsConsumedForPayment(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final ManaCostShard toPay, final SpellAbility chosen, final Set<Card> consumed) {
        if (ManaFilterConsolidation.isMultiPipActivationFilter(chosen) || ManaFilterConsolidation.isMultiManaComboAbility(chosen)) {
            final ManaCostBeingPaid probe = new ManaCostBeingPaid(cost);
            applyChosenPaymentToCostProbe(probe, chosen, ai, toPay);
            if (probe.isPaid()) {
                return 1;
            }
        } else if (isMultiManaProducer(chosen) && !isAnyMultiManaProducer(chosen)) {
            final ManaCostBeingPaid probe = new ManaCostBeingPaid(cost);
            final int before = countUnpaidPips(probe);
            refreshExpressChoice(cost, sa, ai, toPay, chosen);
            ComputerUtilMana.payMultipleMana(probe, ComputerUtilMana.predictManafromSpellAbility(chosen, ai, toPay, cost), ai);
            if (before - countUnpaidPips(probe) >= 2) {
                return 1;
            }
        } else if (isMultiManaDisposable(chosen)) {
            final ManaCostBeingPaid probe = new ManaCostBeingPaid(cost);
            final int before = countUnpaidPips(probe);
            refreshExpressChoice(cost, sa, ai, toPay, chosen);
            ComputerUtilMana.payMultipleMana(probe, ComputerUtilMana.predictManafromSpellAbility(chosen, ai, toPay, cost), ai);
            if (before - countUnpaidPips(probe) >= 2) {
                return 1;
            }
        }
        if (ManaFilterConsolidation.requiresTappingOtherCreatureForMana(chosen)) {
            // The tapped creature is consumed too, but collectCardsConsumedByPayment only tracks
            // nested taps for filters with mana activation costs.
            return consumed.size() + 1;
        }
        return consumed.size();
    }

    /** Apply {@code chosen}'s mana production toward {@code remaining} for feasibility probes. */
    static void applyChosenPaymentToCostProbe(final ManaCostBeingPaid remaining, final SpellAbility chosen,
            final Player ai, final ManaCostShard toPay) {
        if (ManaFilterConsolidation.isMultiPipActivationFilter(chosen)) {
            ComputerUtilMana.payMultipleMana(remaining, ComputerUtilMana.predictManafromSpellAbility(chosen, ai, toPay, remaining), ai);
        } else if (ManaFilterConsolidation.isMultiManaComboAbility(chosen)) {
            ComputerUtilMana.setComboManaChoice(ai, chosen, remaining);
            try {
                ComputerUtilMana.payMultipleMana(remaining,
                        ComputerUtilMana.capComboManaProduced(ComputerUtilMana.predictManafromSpellAbility(chosen, ai, toPay, remaining),
                                ManaFilterConsolidation.getComboManaAmount(chosen)),
                        ai);
            } finally {
                chosen.getManaPart().clearExpressChoice();
            }
        } else if (!ManaFilterConsolidation.hasManaActivationCost(chosen)) {
            final String manaProduced = ComputerUtilMana.predictManafromSpellAbility(chosen, ai, toPay, remaining);
            if (shouldApplyProducedManaToShardOnly(chosen, remaining, toPay, ai)) {
                payProducedManaTowardShard(remaining, manaProduced, toPay, ai);
            } else {
                ComputerUtilMana.payMultipleMana(remaining, manaProduced, ai);
            }
        } else {
            remaining.decreaseShard(toPay, 1);
        }
    }

    /**
     * Single pass: cards consumed by paying with {@code chosen}, and whether the rest of {@code cost}
     * remains payable afterwards.
     */
    static PaymentImpact evaluatePaymentImpact(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final ManaCostShard toPay, final SpellAbility chosen,
            final List<SpellAbility> alternatives, final boolean test, final ManaPaymentContext ctx) {
        return evaluatePaymentImpact(cost, sa, ai, toPay, chosen, alternatives, null, test, ctx);
    }

    /** @param altScan optional shared scan of {@code alternatives} (same shard / remaining count). */
    static PaymentImpact evaluatePaymentImpact(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final ManaCostShard toPay, final SpellAbility chosen,
            final List<SpellAbility> alternatives, final AlternativeScan altScan, final boolean test,
            final ManaPaymentContext ctx) {
        refreshExpressChoice(cost, sa, ai, toPay, chosen);
        final Set<Card> consumed = collectCardsConsumedByPayment(chosen, sa, ai, ctx);
        if (consumed == null) {
            return new PaymentImpact(false, Integer.MAX_VALUE, chosen, cost, toPay, alternatives, altScan, ai, sa, ctx);
        }
        final int consumedCount = effectiveCardsConsumedForPayment(cost, sa, ai, toPay, chosen, consumed);
        if (!hasRemainingCostAfterShard(cost, toPay)) {
            return new PaymentImpact(true, consumedCount, chosen, cost, toPay, alternatives, altScan, ai, sa, ctx);
        }
        return new PaymentImpact(
                keepsRemainingCostPayableWithConsumed(cost, sa, ai, toPay, chosen, consumed, test, ctx),
                consumedCount, chosen, cost, toPay, alternatives, altScan, ai, sa, ctx);
    }

    static SpellAbility refreshExpressChoice(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final ManaCostShard toPay, final SpellAbility chosen) {
        final AbilityManaPart mp = chosen.getManaPart();
        if (mp != null && (mp.isAnyMana() || mp.isComboMana() || chosen.getApi() == ApiType.ManaReflected)) {
            ComputerUtilMana.canPayShardWithSpellAbility(toPay, ai, chosen, sa, cost, true, cost.getXManaCostPaidByColor());
        }
        return chosen;
    }

    /** True when the cost still has unpaid shards other than a single copy of the one about to be paid. */
    static boolean hasRemainingCostAfterShard(final ManaCostBeingPaid cost, final ManaCostShard toPay) {
        ManaCostShard first = null;
        for (final ManaCostShard shard : cost.getDistinctShards()) {
            if (first == null) {
                first = shard;
            } else {
                return true;
            }
        }
        return cost.getUnpaidShards(toPay) > 1;
    }

    /**
     * Simulate paying {@code toPay} with {@code chosen} (reserving the cards it and any nested activation
     * consume) and check the remaining shards of {@code cost} are still payable from what's left.
     */
    static boolean keepsRemainingCostPayableWithConsumed(final ManaCostBeingPaid cost,
            final SpellAbility sa, final Player ai, final ManaCostShard toPay, final SpellAbility chosen,
            final Set<Card> consumed, final boolean test, final ManaPaymentContext ctx) {
        if ((ctx != null && ctx.inFilterActivationProbe) || !ComputerUtilMana.useFullPaymentProbes(test, ctx)) {
            return true;
        }
        final ManaCostBeingPaid remaining = new ManaCostBeingPaid(cost);
        applyChosenPaymentToCostProbe(remaining, chosen, ai, toPay);
        if (remaining.isPaid()) {
            return true;
        }
        if (ManaPaymentContext.fastHeuristics() && isPlainFreeProducer(chosen)
                && freeSourceCountsCover(remaining, consumed, ai, ctx)) {
            return true; // enough untouched free sources remain for every pip; skip the nested dry-run
        }
        try (ReservationSnapshot snap = ReservationSnapshot.take(ai).holdingForNextSpell(consumed)) {
            return ComputerUtilMana.payManaCostNestedProbe(remaining, sa, ai, ctx);
        }
    }

    /** Free (no mana activation cost), non-combo, fixed-amount producer — the common land / rock case. */
    static boolean isPlainFreeProducer(final SpellAbility ma) {
        final ManaSourceTraits t = ManaSourceTraits.of(ma);
        return t.isManaAbility && !t.hasManaActivationCost && !t.comboMana && !t.variableAmountFilter
                && !t.sacrificesOther;
    }

    /**
     * Cheap sufficiency check (opt-in, see {@link ManaPaymentContext#fastHeuristics}): can the unpaid pips of
     * {@code remaining} be covered by free, unreserved sources whose hosts are not in {@code consumed},
     * counting one mana per host? Mono-colored and {C} pips are matched via Hall's condition over every
     * subset of needed types; generic pips take whatever hosts are left. Returns {@code false} (defer to the
     * full dry-run) for hybrid / Phyrexian / snow / X pips or when the counts fall short — never a false
     * positive relative to single-mana producers, and multi-mana hosts only make it more conservative.
     */
    static boolean freeSourceCountsCover(final ManaCostBeingPaid remaining, final Set<Card> consumed,
            final Player ai, final ManaPaymentContext ctx) {
        // needs[0..4] = WUBRG, needs[5] = {C}
        final int[] needs = new int[6];
        int generic = 0;
        int neededTypesMask = 0;
        for (final ManaCostShard shard : remaining.getDistinctShards()) {
            final int n = remaining.getUnpaidShards(shard);
            if (n <= 0) {
                continue;
            }
            if (shard.isGeneric()) {
                generic += n;
            } else if (shard == ManaCostShard.COLORLESS) {
                needs[5] += n;
                neededTypesMask |= 1 << 5;
            } else if (shard.isMonoColor() && !shard.isPhyrexian() && !shard.isSnow()) {
                final int idx = colorIndex(shard.getColorMask());
                if (idx < 0) {
                    return false;
                }
                needs[idx] += n;
                neededTypesMask |= 1 << idx;
            } else {
                return false; // hybrid / Phyrexian / snow / X: let the dry-run decide
            }
        }

        final Set<Card> tapReserved = AiCardMemory.getMemorySet(ai, MemorySet.PAYS_TAP_COST);
        final Set<Card> sacReserved = AiCardMemory.getMemorySet(ai, MemorySet.PAYS_SAC_COST);
        final Set<Card> held = AiCardMemory.getMemorySet(ai, MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
        final Map<Card, Integer> hostMasks = new IdentityHashMap<>();
        for (final SpellAbility ma : ComputerUtilMana.getOrBuildUniqueManaAbilities(ai, true, ctx)) {
            final Card host = ma.getHostCard();
            if (host == null || consumed.contains(host) || !isPlainFreeProducer(ma)) {
                continue;
            }
            if ((tapReserved != null && tapReserved.contains(host) && hasTapCost(ma))
                    || (sacReserved != null && sacReserved.contains(host))
                    || (held != null && held.contains(host))) {
                continue;
            }
            final AbilityManaPart mp = ma.getManaPart();
            if (mp == null) {
                continue;
            }
            int mask = 0;
            for (int i = 0; i < 6; i++) {
                if ((neededTypesMask & (1 << i)) != 0 && mp.canProduce(COUNT_TYPES[i], ma)) {
                    mask |= 1 << i;
                }
            }
            hostMasks.merge(host, mask, (a, b) -> a | b);
        }

        int coloredNeed = 0;
        for (final int n : needs) {
            coloredNeed += n;
        }
        if (hostMasks.size() < coloredNeed + generic) {
            return false;
        }
        // Hall's condition: every subset of needed types must have at least as many distinct hosts as pips.
        for (int subset = neededTypesMask; subset != 0; subset = (subset - 1) & neededTypesMask) {
            int need = 0;
            for (int i = 0; i < 6; i++) {
                if ((subset & (1 << i)) != 0) {
                    need += needs[i];
                }
            }
            int hosts = 0;
            for (final int mask : hostMasks.values()) {
                if ((mask & subset) != 0) {
                    hosts++;
                }
            }
            if (hosts < need) {
                return false;
            }
        }
        return true;
    }

    private static final String[] COUNT_TYPES = {"W", "U", "B", "R", "G", "C"};

    private static int colorIndex(final byte colorMask) {
        for (int i = 0; i < MagicColor.WUBRG.length; i++) {
            if (colorMask == MagicColor.WUBRG[i]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Scoped snapshot of the planner's reservation memory ({@link MemorySet#PAYS_SAC_COST} /
     * {@link MemorySet#PAYS_TAP_COST}) and optionally the mana pool. Restores on {@link #close()}
     * unless {@link #keep()} was called, so a probe that succeeds can hand its reservations to the caller:
     *
     * <pre>
     * try (ReservationSnapshot snap = ReservationSnapshot.take(ai)) {
     *     if (!passesManaPaymentReservationChecks(ai, ma, sa)) { continue; } // restored on close
     *     snap.keep();
     *     return ma;
     * }
     * </pre>
     */
    static final class ReservationSnapshot implements AutoCloseable {
        private final Player ai;
        private final Set<Card> sac;
        private final Set<Card> tap;
        private final List<Mana> pool;
        private List<Card> held;
        private boolean keep;

        private ReservationSnapshot(final Player ai, final boolean withPool) {
            this.ai = ai;
            this.sac = snapshotMemory(ai, MemorySet.PAYS_SAC_COST);
            this.tap = snapshotMemory(ai, MemorySet.PAYS_TAP_COST);
            this.pool = withPool ? snapshotPool(ai.getManaPool()) : null;
        }

        static ReservationSnapshot take(final Player ai) {
            return new ReservationSnapshot(ai, false);
        }

        /** Also snapshots (and restores) the floating mana pool. */
        static ReservationSnapshot takeWithPool(final Player ai) {
            return new ReservationSnapshot(ai, true);
        }

        /**
         * Mark {@code cards} as {@link MemorySet#HELD_MANA_SOURCES_FOR_NEXT_SPELL} for the duration of the
         * scope (cards already held stay held afterwards). Always undone on {@link #close()}.
         */
        ReservationSnapshot holdingForNextSpell(final Collection<Card> cards) {
            held = new ArrayList<>();
            for (final Card c : cards) {
                if (!AiCardMemory.isRememberedCard(ai, c, MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL)) {
                    AiCardMemory.rememberCard(ai, c, MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
                    held.add(c);
                }
            }
            return this;
        }

        /** Commit: leave the reservations made since the snapshot in place on {@link #close()}. */
        void keep() {
            keep = true;
        }

        /** Roll back now (also happens automatically on {@link #close()} unless {@link #keep()} was called). */
        void restore() {
            if (pool != null) {
                restorePool(ai, pool);
            }
            restoreMemory(ai, MemorySet.PAYS_SAC_COST, sac);
            restoreMemory(ai, MemorySet.PAYS_TAP_COST, tap);
        }

        @Override
        public void close() {
            if (held != null) {
                for (final Card c : held) {
                    AiCardMemory.forgetCard(ai, c, MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
                }
            }
            if (!keep) {
                restore();
            }
        }
    }

    /** Copy the current contents of an AI card memory set (or null if unavailable). */
    private static Set<Card> snapshotMemory(final Player ai, final MemorySet set) {
        final Set<Card> live = AiCardMemory.getMemorySet(ai, set);
        return live == null ? null : new HashSet<>(live);
    }

    /** Reset an AI card memory set back to a previously captured snapshot. */
    private static void restoreMemory(final Player ai, final MemorySet set, final Set<Card> snapshot) {
        if (snapshot == null) {
            return;
        }
        AiCardMemory.clearMemorySet(ai, set);
        for (final Card c : snapshot) {
            AiCardMemory.rememberCard(ai, c, set);
        }
    }

    /** True when hand or command zone contains another spell besides the one being paid for. */
    /** Cards tapped (including nested activation costs) if this mana ability is chosen. */
    static Set<Card> collectCardsConsumedByPayment(final SpellAbility saPayment, final SpellAbility sa,
            final Player ai, final ManaPaymentContext ctx) {
        final Set<Card> consumed = new HashSet<>();
        consumed.add(saPayment.getHostCard());
        if (saPayment.getPayCosts() != null && saPayment.getPayCosts().hasManaCost()) {
            final ManaPaymentContext predictCtx = ctx == null ? null : ctx.withFilterProbe();
            final CardCollection nested = predictNestedActivationTaps(saPayment, sa, ai, predictCtx);
            if (nested == null) {
                return null;
            }
            consumed.addAll(nested);
        }
        return consumed;
    }

    /** Dry-run nested generic activation cost payment; returns tapped cards or null if unpayable. */
    static CardCollection predictNestedActivationTaps(final SpellAbility filterAb,
            final SpellAbility sa, final Player ai, final ManaPaymentContext ctx) {
        final ManaPaymentContext probeCtx = ctx == null ? ManaPaymentContext.outer() : ctx;
        final CardCollection taps = new CardCollection();
        final List<Mana> probePoolRemovals = new ArrayList<>();
        final List<Mana> probeDeposited = new ArrayList<>();
        try (ReservationSnapshot snap = ReservationSnapshot.take(ai)) {
            if (!payNestedActivationCost(filterAb, sa, null, ai, ArrayListMultimap.create(), probePoolRemovals, probeDeposited,
                    true, false, taps, probeCtx)) {
                return null;
            }
            return taps;
        } finally {
            cleanupTestManaPayment(ai, probePoolRemovals, probeDeposited);
        }
    }

    static List<SpellAbility> collectValidManaPaymentChoices(final ManaCostBeingPaid cost,
            final SpellAbility sa, final Player ai, final ManaCostShard toPay,
            Collection<SpellAbility> maList, final boolean checkCosts, final boolean test,
            final ManaPaymentContext ctx) {
        Card saHost = sa.getHostCard();

        // When paying the activation cost of a filter (the ability being paid for is itself a mana
        // ability with a mana cost), only free sources may pay it — never another filter. Prevents filter chains.
        if (sa.isManaAbility() && sa.getPayCosts() != null && sa.getPayCosts().hasManaCost()) {
            final List<SpellAbility> freeOnly = new ArrayList<>();
            for (SpellAbility ma : maList) {
                if (ma.getPayCosts() == null || !ma.getPayCosts().hasManaCost()) {
                    freeOnly.add(ma);
                }
            }
            maList = freeOnly;
        }

        maList = applyManaSourcePreference(sa, maList);

        final List<SpellAbility> valid = new ArrayList<>();
        // Candidate-independent facts, resolved lazily at most once per call.
        Boolean consolidatorCovers = null;
        Boolean poolCoversShard = null;
        Set<Card> multiGenericConsolidatorHosts = null;
        final boolean deferAnyManaForMultiGeneric = toPay.isGeneric() && cost.getGenericManaAmount() >= 2;
        for (final SpellAbility paymentChoice : filterBasicManaCandidates(cost, sa, ai, toPay, maList, checkCosts, true)) {
            // Skip useless 1:1 filters (e.g. Initiates of the Ebon Hand: {1} -> {B}) when a direct,
            // free source for the same color is available. No net mana profit means the filter is wasteful.
            if (isUselessFilter(paymentChoice, toPay, maList, ai, cost, ctx)) {
                continue;
            }
            if (isWastefulSacLandForGeneric(paymentChoice, toPay, ai, ctx)) {
                continue;
            }
            final ManaSourceTraits traits = ManaSourceTraits.of(paymentChoice);
            if (traits.disposable && !toPay.isGeneric()) {
                if (consolidatorCovers == null) {
                    consolidatorCovers = consolidatorCoversRemainingCost(maList, cost, sa, ai, ctx, test);
                }
                if (consolidatorCovers) {
                    continue;
                }
            }
            if (shouldReserveConsolidator(paymentChoice, sa, cost, ai, ctx)) {
                continue;
            }
            if (traits.manaActivationConsolidator) {
                if (poolCoversShard == null) {
                    poolCoversShard = poolOrDepositedCanPayShard(ai, cost, toPay, ctx);
                }
                if (poolCoversShard) {
                    continue;
                }
            }
            // Keep Study Hall-style filters off single generic pips while a multi-mana consolidator (Sungrass
            // Prairie) can still cover two or more remaining generic shards in one activation.
            if (deferAnyManaForMultiGeneric && (traits.anyManaFilter || isFreeColorlessOnAnyManaFilterHost(paymentChoice))) {
                if (multiGenericConsolidatorHosts == null) {
                    multiGenericConsolidatorHosts = unreservedMultiPipConsolidatorHosts(maList, cost, sa, ai, ctx);
                }
                if (hasHostOtherThan(multiGenericConsolidatorHosts, paymentChoice.getHostCard())) {
                    continue;
                }
            }

            valid.add(paymentChoice);
        }
        return valid;
    }

    /**
     * Reorder / prune {@code maList} per the spell's {@code AIPreference:ManaFrom$Type} SVar or
     * {@code AIManaPref$ Type} param (Snow, Treasure, TreasureMax, NotSameCard). Returns {@code maList}
     * itself when no preference applies.
     */
    static Collection<SpellAbility> applyManaSourcePreference(final SpellAbility sa, final Collection<SpellAbility> maList) {
        final Card saHost = sa.getHostCard();
        String manaSourceType = "";
        if (saHost.hasSVar("AIPreference")) {
            final String condition = saHost.getSVar("AIPreference");
            if (condition.startsWith("ManaFrom")) {
                manaSourceType = TextUtil.split(condition, '$')[1];
            }
        } else if (sa.hasParam("AIManaPref")) {
            manaSourceType = sa.getParam("AIManaPref");
        }
        if (manaSourceType.isEmpty()) {
            return maList;
        }
        final List<SpellAbility> filteredList = Lists.newArrayList(maList);
        switch (manaSourceType) {
            case "Snow":
                filteredList.sort((ab1, ab2) -> ab1.getHostCard() != null && ab1.getHostCard().isSnow()
                        && ab2.getHostCard() != null && !ab2.getHostCard().isSnow() ? -1 : 1);
                return filteredList;
            case "Treasure": {
                // Try to spend only one Treasure if possible
                filteredList.sort((ab1, ab2) -> ab1.getHostCard() != null && ab1.getHostCard().getType().hasSubtype("Treasure")
                        && ab2.getHostCard() != null && !ab2.getHostCard().getType().hasSubtype("Treasure") ? -1 : 1);
                final SpellAbility first = filteredList.get(0);
                if (first.getHostCard() == null || !first.getHostCard().getType().hasSubtype("Treasure")) {
                    return maList;
                }
                final List<SpellAbility> updatedList = Lists.newArrayList(first);
                for (final SpellAbility ma : maList) {
                    if (ma != first) {
                        updatedList.add(ma);
                    }
                }
                return updatedList;
            }
            case "TreasureMax":
                // Ok to spend as many Treasures as possible
                filteredList.sort((ab1, ab2) -> ab1.getHostCard() != null && ab1.getHostCard().getType().hasSubtype("Treasure")
                        && ab2.getHostCard() != null && !ab2.getHostCard().getType().hasSubtype("Treasure") ? -1 : 1);
                return filteredList;
            case "NotSameCard": {
                final String hostName = saHost.getName();
                filteredList.removeIf(saPay -> saPay.getHostCard().getName().equals(hostName));
                return filteredList;
            }
            default:
                return maList;
        }
    }

    /**
     * Baseline candidate filter shared by {@link ComputerUtilMana#chooseManaAbility} and
     * {@link #collectValidManaPaymentChoices}: drops the spell's own host, sources already reserved this
     * payment, zero-amount variable producers, API-specific exclusions (Animate / Pump / Attach), and anything
     * that cannot pay {@code toPay}. Returns the payment choices in {@code maList} order; normally the
     * ability itself, but Cavern of Souls swaps in its uncounterable ability for generic pips.
     *
     * @param skipSacReserved also drop hosts remembered in {@link MemorySet#PAYS_SAC_COST}
     */
    static List<SpellAbility> filterBasicManaCandidates(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final ManaCostShard toPay, final Collection<SpellAbility> maList,
            final boolean checkCosts, final boolean skipSacReserved) {
        final Card saHost = sa.getHostCard();
        final ApiType api = sa.getApi();
        final boolean avoidAttachTarget = api == ApiType.Attach
                && "AvoidPayingWithAttachTarget".equals(saHost.getSVar("AIPaymentPreference"));
        Map<String, Integer> untappedCountByName = null;
        if (avoidAttachTarget && sa.getTargetCard() != null) {
            untappedCountByName = new HashMap<>();
            for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
                if (c.isUntapped()) {
                    untappedCountByName.merge(c.getName(), 1, Integer::sum);
                }
            }
        }
        final Set<Card> tapReserved = AiCardMemory.getMemorySet(ai, MemorySet.PAYS_TAP_COST);
        final Set<Card> sacReserved = skipSacReserved ? AiCardMemory.getMemorySet(ai, MemorySet.PAYS_SAC_COST) : null;
        final boolean isAi = ai.getController().isAI();

        final List<SpellAbility> out = new ArrayList<>();
        for (final SpellAbility ma : maList) {
            final Card host = ma.getHostCard();
            // this rarely seems like a good idea
            if (host == saHost) {
                continue;
            }
            if (hasTapCost(ma) && tapReserved != null && tapReserved.contains(host)) {
                continue;
            }
            if (sacReserved != null && sacReserved.contains(host)) {
                continue;
            }
            final int amount = ma.hasParam("Amount") ? AbilityUtils.calculateAmount(host, ma.getParam("Amount"), ma) : 1;
            if (amount <= 0) {
                // wrong gamestate for variable amount
                continue;
            }

            if (api == ApiType.Animate) {
                // For abilities like Genju of the Cedars, make sure that we're not activating the aura ability by tapping the enchanted card for mana
                if (saHost.isAura() && "Enchanted".equals(sa.getParam("Defined"))
                        && host == saHost.getEnchantingCard() && hasTapCost(ma)) {
                    continue;
                }
                // If a manland was previously animated this turn, do not tap it to animate another manland
                if (saHost.isLand() && host.isLand() && isAi && AnimateAi.isAnimatedThisTurn(ai, host)) {
                    continue;
                }
            } else if (api == ApiType.Pump) {
                // do not activate pump instants/sorceries targeting creatures by tapping targeted
                // creatures for mana (for example, Servant of the Conduit)
                if ((saHost.isInstant() || saHost.isSorcery()) && host.isCreature() && isAi && hasTapCost(ma)
                        && sa.getTargets().getTargetCards().contains(host)) {
                    continue;
                }
            } else if (avoidAttachTarget && host.equals(sa.getTargetCard())) {
                // For cards like Genju of the Cedars, make sure we're not attaching to the same land that will
                // be tapped to pay its own cost if there's another untapped land like that available
                final Integer untappedSameName = untappedCountByName == null ? null : untappedCountByName.get(host.getName());
                if (untappedSameName != null && untappedSameName > 1) {
                    continue;
                }
            }

            SpellAbility paymentChoice = ma;
            // Exception: when paying generic mana with Cavern of Souls, prefer the colored mana producing ability
            // to attempt to make the spell uncounterable when possible.
            if (ComputerUtilAbility.getAbilitySourceName(ma).equals("Cavern of Souls")
                    && saHost.getType().hasCreatureType(host.getChosenType())) {
                if (toPay == ManaCostShard.COLORLESS && cost.getUnpaidShards().contains(ManaCostShard.GENERIC)) {
                    // Deprioritize Cavern of Souls, try to pay generic mana with it instead to use the NoCounter ability
                    continue;
                } else if (toPay == ManaCostShard.GENERIC || toPay == ManaCostShard.X) {
                    for (final SpellAbility ab : maList) {
                        if (ab.isManaAbility() && ab.getManaPart().isAnyMana() && ab.hasParam("AddsNoCounter")
                                && !ab.getHostCard().isTapped()) {
                            paymentChoice = ab;
                            break;
                        }
                    }
                }
            }

            if (!ComputerUtilMana.canPayShardWithSpellAbility(toPay, ai, paymentChoice, sa, cost, checkCosts,
                    cost.getXManaCostPaidByColor())) {
                continue;
            }
            out.add(paymentChoice);
        }
        return out;
    }

    /** Hosts of net-positive multi-pip filters in {@code maList} that are not reserved for a hand spell. */
    private static Set<Card> unreservedMultiPipConsolidatorHosts(final Collection<SpellAbility> maList,
            final ManaCostBeingPaid cost, final SpellAbility sa, final Player ai, final ManaPaymentContext ctx) {
        final Set<Card> hosts = new HashSet<>();
        for (final SpellAbility other : maList) {
            final ManaSourceTraits t = ManaSourceTraits.of(other);
            if (t.multiPipFilter && t.netPositiveConsolidator && !shouldReserveConsolidator(other, sa, cost, ai, ctx)) {
                hosts.add(other.getHostCard());
            }
        }
        return hosts;
    }

    private static boolean hasHostOtherThan(final Set<Card> hosts, final Card exclude) {
        if (hosts.isEmpty()) {
            return false;
        }
        if (hosts.size() > 1 || exclude == null) {
            return true;
        }
        return !hosts.contains(exclude);
    }

    /**
     * Skip useless filters when a direct source in the candidate pool can pay {@code toPay} without
     * routing through the filter.
     */
    static boolean isUselessFilter(final SpellAbility ma, final ManaCostShard toPay,
            final Collection<SpellAbility> maList, final Player ai, final ManaCostBeingPaid cost,
            final ManaPaymentContext ctx) {
        final Cost payCosts = ma.getPayCosts();
        if (payCosts == null || !payCosts.hasManaCost()) {
            return false;
        }
        final AbilityManaPart mp = ma.getManaPart();
        if (mp == null) {
            return false;
        }
        if (mp.isComboMana() || mp.mana(ma).split(" ").length > 1) {
            return false;
        }
        if (mp.isAnyMana()) {
            // Fast heuristics: paymentEfficiencyScore already penalizes filters vs. free/direct sources,
            // so let ranking decide instead of running these overlapping exclusion scans.
            return !ManaPaymentContext.fastHeuristics() && isUselessAnyManaFilter(ma, maList, ai, cost, toPay, ctx);
        }
        final CostPartMana costMana = payCosts.getCostMana();
        final int activationCMC = costMana == null ? 0 : costMana.getMana().getCMC();
        if (ma.amountOfManaGenerated(true) > activationCMC) {
            return false;
        }
        for (SpellAbility other : maList) {
            if (other == ma || other.getHostCard() == ma.getHostCard()) {
                continue;
            }
            final Cost otherCost = other.getPayCosts();
            if (otherCost != null && !otherCost.hasManaCost()) {
                return true;
            }
        }
        return false;
    }

    static boolean isUselessAnyManaFilter(final SpellAbility filter, final Collection<SpellAbility> maList,
            final Player ai, final ManaCostBeingPaid cost, final ManaCostShard toPay,
            final ManaPaymentContext ctx) {
        final Card filterHost = filter.getHostCard();
        if (maList.stream().anyMatch(other -> other != filter && other.getHostCard() != filterHost
                && isReusableFreeManaForShard(other, toPay))) {
            return true;
        }
        if (!toPay.isGeneric() && cost != null && cost.getGenericManaAmount() == 0 && ai != null
                && maList.stream().anyMatch(other -> other != filter && other.getHostCard() != filterHost
                        && ManaFilterConsolidation.isDisposableManaAbility(other))
                && hasReusableFreeProducerForEveryOtherColoredShard(cost, toPay, ai, ctx)) {
            return true;
        }
        final ListMultimap<Integer, SpellAbility> manaAbilityMap = ai != null
                ? ComputerUtilMana.getOrBuildManaAbilityMap(ai, true, ctx) : null;
        if (cost != null && cost.getGenericManaAmount() > 0 && !toPay.isGeneric() && manaAbilityMap != null) {
            if (anyManaFilterBeatsDisposable(filter, cost, ai, manaAbilityMap, ctx)) {
                return false;
            }
            for (SpellAbility other : maList) {
                if (other == filter || other.getHostCard() == filterHost) {
                    continue;
                }
                if (ManaFilterConsolidation.isDisposableManaAbility(other)) {
                    return true;
                }
            }
        }
        if (toPay.isGeneric() || toPay == ManaCostShard.X) {
            for (SpellAbility other : maList) {
                if (other == filter || other.getHostCard() == filterHost) {
                    continue;
                }
                final Cost otherCost = other.getPayCosts();
                if (otherCost == null || !otherCost.hasManaCost()) {
                    return true;
                }
            }
        }
        if (manaAbilityMap != null && canActivateFilter(ai, filter, manaAbilityMap, true)) {
            return false;
        }
        for (SpellAbility other : maList) {
            if (other == filter || other.getHostCard() == filterHost) {
                continue;
            }
            final Cost otherCost = other.getPayCosts();
            if (otherCost != null && !otherCost.hasManaCost()
                    && ManaFilterConsolidation.isDisposableManaAbility(other)) {
                return true;
            }
        }
        return false;
    }

    /** Skip sacrificing a land for generic when another land can still tap for mana. */
    static boolean isWastefulSacLandForGeneric(final SpellAbility ma, final ManaCostShard toPay,
            final Player ai, final ManaPaymentContext ctx) {
        if (!toPay.isGeneric() && toPay != ManaCostShard.X) {
            return false;
        }
        if (!ManaFilterConsolidation.isDisposableManaAbility(ma) || !ma.getHostCard().isLand() || ai == null) {
            return false;
        }
        return hasReusableTapLandOnBattlefield(ai, ma.getHostCard(), ctx);
    }

    /** Non-disposable battlefield cards with a free {T} mana ability (board-derived; see the plan cache memo). */
    static Set<Card> findReusableTapLandSources(final Player ai, final ManaPaymentContext ctx) {
        final Set<Card> sources = new HashSet<>();
        for (Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (ManaFilterConsolidation.isDisposableManaCard(c)) {
                continue;
            }
            for (SpellAbility other : ComputerUtilMana.getAIPlayableMana(c, ctx)) {
                if (other.getPayCosts() != null && other.getPayCosts().hasTapCost()
                        && isFreeManaSourceForNestedActivation(other, null)) {
                    sources.add(c);
                    break;
                }
            }
        }
        return sources;
    }

    static boolean hasReusableTapLandOnBattlefield(final Player ai, final Card exclude,
            final ManaPaymentContext ctx) {
        final Set<Card> sources;
        if (ctx == null) {
            sources = findReusableTapLandSources(ai, null);
        } else {
            if (ctx.caches.reusableTapLandSet == null) {
                ctx.caches.reusableTapLandSet = findReusableTapLandSources(ai, ctx);
            }
            sources = ctx.caches.reusableTapLandSet;
        }
        for (Card c : sources) {
            if (c == exclude) {
                continue;
            }
            if (!AiCardMemory.isRememberedCard(ai, c, MemorySet.PAYS_TAP_COST)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drop {@code used} from a candidate pool after it paid something, along with every sibling mana
     * ability on the same host that can no longer be activated (test mode never really pays the cost, so
     * this is what prevents re-using the same tap / sacrifice). Siblings whose costs don't conflict stay —
     * Heart of Ramos can {@code {T}} for one {@code R} and then be sacrificed for the second — per
     * {@link ManaSourceTraits#leavesSiblingUsable}.
     */
    static void removeUsedManaSource(final Collection<SpellAbility> pool, final SpellAbility used) {
        final Card host = used.getHostCard();
        final ManaSourceTraits u = ManaSourceTraits.of(used);
        pool.removeIf(s -> s.getHostCard() == host && (s == used || !u.leavesSiblingUsable(ManaSourceTraits.of(s))));
    }

    /** Record tap/sacrifice reservation during test-mode planning so it matches production auto-pay. */
    static void rememberManaSourceConsumed(final Player ai, final SpellAbility ma) {
        if (hasTapCost(ma)) {
            AiCardMemory.rememberCard(ai, ma.getHostCard(), MemorySet.PAYS_TAP_COST);
        }
        if (ManaFilterConsolidation.isDisposableManaAbility(ma)) {
            AiCardMemory.rememberCard(ai, ma.getHostCard(), MemorySet.PAYS_SAC_COST);
        }
    }

    /**
     * Pay a mana ability's generic activation cost (e.g. a signet's {1}). Only free sources (no mana
     * activation cost of their own) may pay it, which blocks filter-for-filter chains. Consumed sources are
     * removed from the shared candidate pool so they can't be reused.
     * <p>
     * {@code test == true} simulates during planning (surplus goes to {@code testDepositedSurplus}, taps
     * are reported via {@code outTapped}); {@code test == false} physically taps the same sources the
     * planner chose so Auto-pay matches simulation / feasibility checks.
     * <p>
     * {@code outerCost} (nullable) is the spell cost this activation ultimately serves. When floating or
     * deposited mana pays the activation, mana that cannot pay one of its unpaid colored pips is spent
     * first (Signet {@code {G}{W}} funding Sungrass Prairie for {@code {1}{G}{G}} spends the {@code W}).
     *
     * @return true if the activation cost was fully paid from free sources.
     */
    static boolean payNestedActivationCost(final SpellAbility filterAb,
            final SpellAbility sa, final ManaCostBeingPaid outerCost, final Player ai,
            final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards,
            final List<Mana> manaSpentToPay, final List<Mana> testDepositedSurplus, final boolean test,
            final boolean effect, final CardCollection outTapped, final ManaPaymentContext ctx) {
        if (filterAb.getPayCosts() == null) {
            return true;
        }
        final CostPartMana costMana = filterAb.getPayCosts().getCostMana();
        if (costMana == null) {
            return true;
        }
        final ManaCost activationMana = costMana.getManaCostFor(filterAb);
        if (activationMana.isNoCost() || activationMana.getCMC() == 0) {
            return true;
        }

        final ManaCostBeingPaid nestedCost = new ManaCostBeingPaid(activationMana);
        final Card filterHost = filterAb.getHostCard();

        // The outer sourcesForShards only lists shards from the spell being cast (e.g. {W}{W} has no
        // GENERIC bucket), so build a dedicated map for paying this activation cost ({1}, etc.).
        final ListMultimap<Integer, SpellAbility> manaAbilityMap = ComputerUtilMana.getOrBuildManaAbilityMap(ai, true, ctx);
        final ListMultimap<ManaCostShard, SpellAbility> nestedSourcesForShards =
                ComputerUtilMana.groupAndOrderToPayShards(ai, manaAbilityMap, nestedCost);
        ManaAbilitySort.sortManaAbilities(nestedSourcesForShards, manaAbilityMap, sa, nestedCost, ai, ctx,
                ManaPaymentExecution::canActivateFilter);

        // First spend any floating mana in the pool towards the activation cost.
        final ManaPool pool = ai.getManaPool();
        payNestedCostFromPool(filterAb, nestedCost, pool, outerCost, test, manaSpentToPay);
        if (test) {
            spendTestDepositedManaTowardNestedCost(filterAb, nestedCost, pool, testDepositedSurplus, manaSpentToPay, outerCost);
        }

        while (!nestedCost.isPaid()) {
            if (test) {
                spendTestDepositedManaTowardNestedCost(filterAb, nestedCost, pool, testDepositedSurplus, manaSpentToPay, outerCost);
                if (nestedCost.isPaid()) {
                    break;
                }
            }
            final int unpaidBefore = unpaidProgressKey(nestedCost);
            final ManaCostShard toPay = ComputerUtilMana.getNextShardToPay(nestedCost, nestedSourcesForShards);
            if (toPay == null) {
                return false;
            }
            final Collection<SpellAbility> saList = nestedSourcesForShards.get(toPay);
            if (saList == null || saList.isEmpty()) {
                return false;
            }

            // Only free sources may pay a nested activation cost (no other signets/filters),
            // and the filter can never tap itself to pay its own cost.
            final List<SpellAbility> freeCandidates = new ArrayList<>();
            boolean reusableForThisShard = false;
            for (SpellAbility ma : saList) {
                if (!isFreeManaSourceForNestedActivation(ma, filterHost)) {
                    continue;
                }
                // Combo lands (Cascade Bluffs) bank mana; don't spend them paying another filter's activation.
                if (hostHasComboConsolidator(ma.getHostCard()) && isManaActivationConsolidator(filterAb)) {
                    continue;
                }
                if (!isCurrentlyAvailableForNestedActivation(ai, ma, filterHost)) {
                    continue;
                }
                if (!ManaFilterConsolidation.isDisposableManaAbility(ma)) {
                    reusableForThisShard = true;
                }
                freeCandidates.add(ma);
            }
            // Prefer Forests/Plains over Petal only when a reusable source can actually pay this shard
            // (Study Hall {C} must not block Petal from paying Graven Cairns {B/R}).
            if (reusableForThisShard) {
                freeCandidates.removeIf(ManaFilterConsolidation::isDisposableManaAbility);
            }
            if (freeCandidates.isEmpty()) {
                payNestedCostFromPool(filterAb, nestedCost, pool, outerCost, test, manaSpentToPay);
                if (test) {
                    spendTestDepositedManaTowardNestedCost(filterAb, nestedCost, pool, testDepositedSurplus, manaSpentToPay, outerCost);
                }
                if (nestedCost.isPaid()) {
                    continue;
                }
                return false;
            }
            sortFreeSourcesForNestedActivation(freeCandidates, toPay,
                    ManaAbilitySort.shouldReserveColorlessMana(ai, sa));

            final SpellAbility chosen = chooseSourceForFilterActivation(sa, ai, filterAb, toPay, freeCandidates,
                    nestedCost, nestedSourcesForShards, test, ctx);
            if (chosen == null) {
                return false;
            }

            if (outTapped != null) {
                outTapped.add(chosen.getHostCard());
            }

            if (test) {
                final String manaProduced = ComputerUtilMana.predictManafromSpellAbility(chosen, ai, toPay, nestedCost);
                ManaPaymentTracer.logNestedTap(true, chosen, manaProduced, filterHost, sa, ctx);
                final String unused = ComputerUtilMana.payMultipleMana(nestedCost, manaProduced, ai);
                depositNestedManaSurplus(unused, chosen.getHostCard(), ai, testDepositedSurplus);
                rememberManaSourceConsumed(ai, chosen);
            } else {
                if (ManaPaymentTracer.tapTraceEnabled(false, ctx)) {
                    ManaPaymentTracer.logNestedTap(false, chosen,
                            ComputerUtilMana.predictManafromSpellAbility(chosen, ai, toPay, nestedCost), filterHost, sa, ctx);
                }
                if (!executeFreeManaSource(chosen, filterAb, ai, nestedCost, effect)) {
                    return false;
                }
            }
            removeUsedManaSource(nestedSourcesForShards.values(), chosen);
            removeUsedManaSource(sourcesForShards.values(), chosen);
            if (unpaidBefore == unpaidProgressKey(nestedCost)) {
                return false;
            }
        }
        return true;
    }

    /** Pay tap/sac/etc. on a mana source without re-entering generic mana payment (already handled by nested planner). */
    static boolean payNonManaAbilityCosts(final SpellAbility ma, final Player ai, final boolean effect) {
        final Cost adjusted = CostAdjustment.adjust(ma.getPayCosts(), ma, effect);
        if (adjusted == null) {
            return true;
        }
        for (final CostPart part : adjusted.getCostParts()) {
            if (part instanceof CostPartMana) {
                continue;
            }
            final PaymentDecision pd = part.accept(new AiCostDecision(ai, ma, effect, true));
            if (pd == null || !part.payAsDecided(ai, pd, ma, effect)) {
                return false;
            }
        }
        return true;
    }

    /** Physically activate a free mana source and apply its mana toward {@code costToPay}. */
    static boolean executeFreeManaSource(final SpellAbility ma, final SpellAbility saPaidFor,
            final Player ai, final ManaCostBeingPaid costToPay, final boolean effect) {
        ma.setActivatingPlayer(ai);
        if (!ComputerUtilCost.checkForManaSacrificeCost(ai, ma.getPayCosts(), ma, ma.isTrigger())) {
            return false;
        }
        if (!ComputerUtilCost.checkTapTypeCost(ai, ma.getPayCosts(), ma.getHostCard(), saPaidFor, AiCardMemory.getMemorySet(ai, MemorySet.PAYS_TAP_COST))) {
            return false;
        }
        if (!payNonManaAbilityCosts(ma, ai, effect)) {
            return false;
        }
        ai.getGame().getStack().addAndUnfreeze(ma);
        ai.getManaPool().payManaFromAbility(saPaidFor, costToPay, ma);
        ManaSourceTraits.invalidate();
        return true;
    }

    /**
     * Activate a filter after {@link #executeNestedActivationCost} paid its generic activation cost.
     * Skips {@link ComputerUtilCost#checkTapTypeCost} on the filter host because the outer payment loop
     * already reserved it in {@link MemorySet#PAYS_TAP_COST} before calling here.
     */
    static boolean executeFilterManaSource(final SpellAbility filterAb, final SpellAbility sa,
            final Player ai, final ManaCostBeingPaid cost, final ManaCostShard toPay,
            final boolean effect, final ManaPool manapool) {
        filterAb.setActivatingPlayer(ai);
        if (!ComputerUtilCost.checkForManaSacrificeCost(ai, filterAb.getPayCosts(), filterAb, filterAb.isTrigger())) {
            return false;
        }
        refreshExpressChoice(cost, sa, ai, toPay, filterAb);
        if (!payNonManaAbilityCosts(filterAb, ai, effect)) {
            return false;
        }
        ai.getGame().getStack().addAndUnfreeze(filterAb);
        ManaSourceTraits.invalidate();
        if (shouldApplyProducedManaToShardOnly(filterAb, cost, toPay, ai)) {
            return payFilterManaTowardShardOnly(sa, cost, toPay, filterAb, manapool);
        }
        manapool.payManaFromAbility(sa, cost, filterAb);
        return true;
    }

    /** Apply filter mana toward one colored shard; leave other colors in the pool for chaining. */
    static boolean payFilterManaTowardShardOnly(final SpellAbility sa, final ManaCostBeingPaid cost,
            final ManaCostShard toPay, final SpellAbility filterAb, final ManaPool manapool) {
        sa.getPayingManaAbilities().add(filterAb);
        for (final AbilityManaPart mp : filterAb.getAllManaParts()) {
            for (final Mana mana : mp.getLastManaProduced()) {
                if (!sa.allowsPayingWithShard(mp.getSourceCard(), mana.getColor())) {
                    continue;
                }
                if (cost.getUnpaidShards(toPay) > 0
                        && manapool.canPayForShardWithColor(toPay, mana.getColor())
                        && manapool.tryPayCostWithMana(sa, cost, mana, false)) {
                    sa.getPayingMana().add(mana);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Bank mana through a Signet (or similar) then a second consolidator, then pay the spell from the
     * pool. The second stage is either a combo land (Signet {@code {B}{R}} -> Cascade Bluffs
     * {@code {R}{R}} -> {@code {1}{R}{R}}) or another net-positive filter on a different host
     * (Selesnya Signet {@code {G}{W}} -> {@code {G}} funds Sungrass Prairie -> {@code {G}{W}} ->
     * {@code {2}{W}}), so the first stage's surplus funds the second activation instead of being
     * spent on a generic pip while a free land is tapped for the remainder.
     */
    static boolean tryPayViaManaBankingChain(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards,
            final List<Mana> manaSpentToPay, final List<Mana> testDepositedSurplus, final boolean test,
            final boolean effect, final ManaPool manapool, final CardCollection planOut,
            final List<SpellAbility> paymentList, final ManaPaymentContext ctx) {
        if (cost.isPaid() || (ctx != null && ctx.inFilterActivationProbe) || countUnpaidPips(cost) < 3
                || (ctx != null && !ctx.caches.boardHasBankingPair(ai))) {
            return false;
        }
        // Second-stage candidates: combo lands first (they need a colored input a signet supplies),
        // then other net-positive filters (a signet's surplus funds a Signet-like land's {1}).
        final List<SpellAbility> seconds = new ArrayList<>();
        final List<SpellAbility> signets = new ArrayList<>();
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            for (final SpellAbility ma : c.getManaAbilities()) {
                if (ma.getHostCard() != c) {
                    continue;
                }
                if (hasTapCost(ma)
                        && AiCardMemory.isRememberedCard(ai, c, MemorySet.PAYS_TAP_COST)) {
                    continue;
                }
                if (ManaFilterConsolidation.isComboConsolidatingFilter(ma)) {
                    seconds.add(ma);
                } else if (isNetPositiveConsolidator(ma)) {
                    signets.add(ma);
                }
            }
        }
        seconds.addAll(signets);
        SpellAbility combo = null;
        SpellAbility signet = null;
        outer:
        for (final SpellAbility ma : seconds) {
            for (final SpellAbility signetMa : signets) {
                if (signetMa.getHostCard() == ma.getHostCard()) {
                    continue;
                }
                if (canPayViaSignetThenComboBanking(cost, sa, ai, signetMa, ma, testDepositedSurplus, ctx)) {
                    combo = ma;
                    signet = signetMa;
                    break outer;
                }
            }
        }
        if (combo == null || signet == null) {
            return false;
        }
        return executeSignetThenComboBanking(cost, sa, ai, signet, combo, sourcesForShards, manaSpentToPay,
                testDepositedSurplus, test, effect, manapool, planOut, paymentList, ctx);
    }

    static boolean canPayViaSignetThenComboBanking(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final SpellAbility signet, final SpellAbility combo,
            final List<Mana> testDepositedSurplus, final ManaPaymentContext ctx) {
        final List<Mana> surplus = new ArrayList<>();
        try (ReservationSnapshot snap = ReservationSnapshot.takeWithPool(ai)) {
            if (!simulateAndBankConsolidator(signet, sa, ai, cost, surplus, ctx)) {
                return false;
            }
            if (!simulateAndBankConsolidator(combo, sa, ai, cost, surplus, ctx)) {
                return false;
            }
            final ManaCostBeingPaid probe = new ManaCostBeingPaid(cost);
            final ManaPool pool = ai.getManaPool();
            final List<Mana> probeSpent = new ArrayList<>();
            spendTestDepositedManaTowardCost(sa, probe, pool, surplus, probeSpent);
            pool.payManaCostFromPool(probe, sa, true, probeSpent);
            while (!probe.isPaid() && !pool.isEmpty()) {
                boolean found = false;
                for (final byte color : ManaAtom.MANATYPES) {
                    if (pool.tryPayCostWithColor(color, sa, probe, probeSpent)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    break;
                }
            }
            return probe.isPaid();
        }
    }

    static boolean executeSignetThenComboBanking(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final SpellAbility signet, final SpellAbility combo,
            final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards,
            final List<Mana> manaSpentToPay, final List<Mana> testDepositedSurplus,
            final boolean test, final boolean effect, final ManaPool manapool,
            final CardCollection planOut, final List<SpellAbility> paymentList, final ManaPaymentContext ctx) {
        if (!activateAndBankConsolidator(signet, sa, ai, cost, sourcesForShards, manaSpentToPay,
                testDepositedSurplus, test, effect, planOut, paymentList, ctx)) {
            return false;
        }
        if (!activateAndBankConsolidator(combo, sa, ai, cost, sourcesForShards, manaSpentToPay,
                testDepositedSurplus, test, effect, planOut, paymentList, ctx)) {
            return false;
        }
        if (test) {
            spendTestDepositedManaTowardCost(sa, cost, manapool, testDepositedSurplus, manaSpentToPay);
        }
        final boolean hadUnpaid = !cost.isPaid();
        manapool.payManaCostFromPool(cost, sa, test, manaSpentToPay);
        while (!cost.isPaid() && !manapool.isEmpty()) {
            boolean found = false;
            for (final byte color : ManaAtom.MANATYPES) {
                if (manapool.tryPayCostWithColor(color, sa, cost, manaSpentToPay)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                break;
            }
        }
        if (hadUnpaid && cost.isPaid()) {
            ctx.recordStep(sa, test, "  result: PAID (pool)");
        }
        return cost.isPaid();
    }

    static boolean activateAndBankConsolidator(final SpellAbility filterAb, final SpellAbility sa,
            final Player ai, final ManaCostBeingPaid spellCost,
            final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards,
            final List<Mana> manaSpentToPay, final List<Mana> testDepositedSurplus,
            final boolean test, final boolean effect, final CardCollection planOut,
            final List<SpellAbility> paymentList, final ManaPaymentContext ctx) {
        paymentList.add(filterAb);
        final boolean tapReserved = hasTapCost(filterAb);
        if (tapReserved) {
            AiCardMemory.rememberCard(ai, filterAb.getHostCard(), MemorySet.PAYS_TAP_COST);
        }
        final Runnable rollback = () -> {
            paymentList.remove(filterAb);
            if (tapReserved) {
                AiCardMemory.forgetCard(ai, filterAb.getHostCard(), MemorySet.PAYS_TAP_COST);
            }
        };
        if (filterAb.getPayCosts() != null && filterAb.getPayCosts().hasManaCost()) {
            final CardCollection nestedTaps = test && planOut != null ? new CardCollection() : null;
            if (!payNestedActivationCost(filterAb, sa, spellCost, ai,
                    sourcesForShards == null ? ArrayListMultimap.create() : sourcesForShards,
                    manaSpentToPay, test ? testDepositedSurplus : null, test, effect, nestedTaps, ctx)) {
                rollback.run();
                return false;
            }
            if (nestedTaps != null && !nestedTaps.isEmpty()) {
                planOut.addAll(nestedTaps);
            }
        }
        if (!bankManaAfterActivation(filterAb, sa, ai, spellCost, testDepositedSurplus, test, effect)) {
            rollback.run();
            return false;
        }
        ctx.recordStep(sa, test, () -> "  " + ManaPaymentTracer.formatSourceAction(filterAb, ai) + " -> "
                + ComputerUtilMana.formatManaProducedForLog(filterAb, ai, ManaCostShard.GENERIC, spellCost)
                + " (bank for " + ManaPaymentTracer.manaPaymentSpellLabel(sa) + ")");
        if (planOut != null) {
            planOut.add(filterAb.getHostCard());
        }
        removeUsedManaSource(sourcesForShards.values(), filterAb);
        if (test) {
            rememberManaSourceConsumed(ai, filterAb);
        }
        return true;
    }

    static boolean simulateAndBankConsolidator(final SpellAbility filterAb, final SpellAbility sa,
            final Player ai, final ManaCostBeingPaid spellCost, final List<Mana> surplus,
            final ManaPaymentContext ctx) {
        if (filterAb.getPayCosts() != null && filterAb.getPayCosts().hasManaCost()) {
            if (!payNestedActivationCost(filterAb, sa, spellCost, ai, ArrayListMultimap.create(), null, surplus, true, false,
                    null, ctx == null ? ManaPaymentContext.outer() : ctx.detachedProbe())) {
                return false;
            }
        }
        if (ManaFilterConsolidation.isMultiManaComboAbility(filterAb)) {
            ComputerUtilMana.setComboManaChoice(ai, filterAb, spellCost);
        }
        String produced = ComputerUtilMana.predictManafromSpellAbility(filterAb, ai, ManaCostShard.GENERIC);
        if (ManaFilterConsolidation.isMultiManaComboAbility(filterAb)) {
            produced = ComputerUtilMana.capComboManaProduced(produced, ManaFilterConsolidation.getComboManaAmount(filterAb));
        }
        depositNestedManaSurplus(produced, filterAb.getHostCard(), ai, surplus);
        return true;
    }

    static boolean bankManaAfterActivation(final SpellAbility filterAb, final SpellAbility sa,
            final Player ai, final ManaCostBeingPaid spellCost, final List<Mana> testDepositedSurplus,
            final boolean test, final boolean effect) {
        if (ManaFilterConsolidation.isMultiManaComboAbility(filterAb)) {
            ComputerUtilMana.setComboManaChoice(ai, filterAb, spellCost);
        }
        if (test) {
            String produced = ComputerUtilMana.predictManafromSpellAbility(filterAb, ai, ManaCostShard.GENERIC);
            if (ManaFilterConsolidation.isMultiManaComboAbility(filterAb)) {
                produced = ComputerUtilMana.capComboManaProduced(produced, ManaFilterConsolidation.getComboManaAmount(filterAb));
            }
            depositNestedManaSurplus(produced, filterAb.getHostCard(), ai, testDepositedSurplus);
            return true;
        }
        filterAb.setActivatingPlayer(ai);
        if (!ComputerUtilCost.checkForManaSacrificeCost(ai, filterAb.getPayCosts(), filterAb, filterAb.isTrigger())) {
            return false;
        }
        if (!payNonManaAbilityCosts(filterAb, ai, effect)) {
            return false;
        }
        ai.getGame().getStack().addAndUnfreeze(filterAb);
        ManaSourceTraits.invalidate();
        return true;
    }

    static boolean poolOrDepositedCanPayShard(final Player ai, final ManaCostBeingPaid cost,
            final ManaCostShard toPay, final ManaPaymentContext ctx) {
        if (toPay == null || toPay.isGeneric() || toPay == ManaCostShard.COLORLESS || toPay == ManaCostShard.X
                || cost.getUnpaidShards(toPay) <= 0) {
            return false;
        }
        final ManaPool pool = ai.getManaPool();
        for (final Mana m : pool) {
            if (pool.canPayForShardWithColor(toPay, m.getColor())) {
                return true;
            }
        }
        final List<Mana> deposited = ctx != null ? ctx.testDepositedSurplus : null;
        if (deposited != null) {
            for (final Mana m : deposited) {
                if (pool.canPayForShardWithColor(toPay, m.getColor())) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean tryApplyConsolidatingFilter(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards,
            final List<Mana> manaSpentToPay, final List<Mana> testDepositedSurplus, final boolean test,
            final boolean effect, final ManaPool manapool, final CardCollection planOut,
            final List<SpellAbility> paymentList, final ManaPaymentContext ctx) {
        if (countUnpaidPips(cost) < 2 || sourcesForShards == null
                || (ctx != null && !ctx.caches.boardHasNetPositiveConsolidator(ai))) {
            return false;
        }
        final Set<SpellAbility> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        SpellAbility best = null;
        int bestConsumed = Integer.MAX_VALUE;
        ManaCostShard bestShard = null;
        for (final SpellAbility ma : sourcesForShards.values()) {
            if (!seen.add(ma) || !isNetPositiveConsolidator(ma)) {
                continue;
            }
            final Set<Card> consumed = consolidatorCoverage(ma, cost, sa, ai, ctx, test);
            if (consumed == null) {
                continue;
            }
            final ManaCostShard shard = shardForConsolidatorProbe(cost);
            final int cardsConsumed = effectiveCardsConsumedForPayment(cost, sa, ai, shard, ma, consumed);
            if (cardsConsumed < bestConsumed) {
                bestConsumed = cardsConsumed;
                best = ma;
                bestShard = shard;
            }
        }
        if (best == null || bestShard == null) {
            return false;
        }
        try (ReservationSnapshot snap = ReservationSnapshot.take(ai)) {
            if (!passesManaPaymentReservationChecks(ai, best, sa)) {
                return false;
            }
        }

        paymentList.add(best);
        if (hasTapCost(best)) {
            AiCardMemory.rememberCard(ai, best.getHostCard(), MemorySet.PAYS_TAP_COST);
        }
        // Verbose trace only; the plan gets the "tap ..." step from applyChosenManaPayment below.
        final SpellAbility bestForLog = best;
        ManaPaymentTracer.logMain(test, () -> "  consolidate via " + bestForLog.getHostCard() + " (paying " + cost + " for "
                + ManaPaymentTracer.manaPaymentSpellLabel(sa) + ")", ctx);
        if (!applyChosenManaPayment(best, sa, ai, cost, bestShard, sourcesForShards, manaSpentToPay,
                testDepositedSurplus, test, effect, manapool, planOut, ctx)) {
            paymentList.remove(best);
            if (hasTapCost(best)) {
                AiCardMemory.forgetCard(ai, best.getHostCard(), MemorySet.PAYS_TAP_COST);
            }
            return false;
        }
        if (planOut != null) {
            planOut.add(best.getHostCard());
        }
        if (test) {
            rememberManaSourceConsumed(ai, best);
        }
        return true;
    }

    /**
     * Apply a chosen mana source to {@code cost}. Test mode simulates; production executes the same plan
     * (including nested filter activation costs) so Auto-pay matches feasibility / simulation output.
     */
    static boolean applyChosenManaPayment(final SpellAbility saPayment, final SpellAbility sa,
            final Player ai, final ManaCostBeingPaid cost, final ManaCostShard toPay,
            final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards, final List<Mana> manaSpentToPay,
            final List<Mana> testDepositedSurplus, final boolean test, final boolean effect,
            final ManaPool manapool, final CardCollection outTapped, final ManaPaymentContext ctx) {
        final boolean traceTaps = ManaPaymentTracer.tapTraceEnabled(test, ctx);
        if (test) {
            if (saPayment.getPayCosts() != null && saPayment.getPayCosts().hasManaCost()) {
                if (!payNestedActivationCost(saPayment, sa, cost, ai, sourcesForShards, manaSpentToPay,
                        testDepositedSurplus, true, false, outTapped, ctx)) {
                    return false;
                }
            }
            if (ManaFilterConsolidation.isMultiManaComboAbility(saPayment)) {
                ComputerUtilMana.setComboManaChoice(ai, saPayment, cost);
            }
            saPayment.setActivatingPlayer(ai);
            // Reserve sac targets (Ashnod's / Phyrexian Tower / Petal) before plan logging so the
            // play-by-play can name the creature — castability probes may have restored memory.
            if (needsManaSacrificeReservation(saPayment) && !hasValidManaSacrificeReservation(ai, saPayment)
                    && !ComputerUtilCost.checkForManaSacrificeCost(ai, saPayment.getPayCosts(), saPayment,
                            saPayment.isTrigger())) {
                return false;
            }
            final String manaProduced = ComputerUtilMana.formatManaProducedForLog(saPayment, ai, toPay, cost);
            final ManaCostBeingPaid costBefore = traceTaps ? new ManaCostBeingPaid(cost) : null;
            final String unused = shouldApplyProducedManaToShardOnly(saPayment, cost, toPay, ai)
                    ? payProducedManaTowardShard(cost, manaProduced, toPay, ai)
                    : ComputerUtilMana.payMultipleMana(cost, manaProduced, ai);
            if (traceTaps) {
                ManaPaymentTracer.logTap(true, saPayment, sa, formatShardsPaidDiff(costBefore, cost, toPay), manaProduced, ctx);
            }
            depositNestedManaSurplus(unused, saPayment.getHostCard(), ai, testDepositedSurplus);
        } else if (saPayment.getPayCosts() != null && saPayment.getPayCosts().hasManaCost()) {
            if (!payNestedActivationCost(saPayment, sa, cost, ai, sourcesForShards, manaSpentToPay, null, false, effect,
                    null, ctx)) {
                return false;
            }
            final String manaProduced = traceTaps ? ComputerUtilMana.formatManaProducedForLog(saPayment, ai, toPay, cost) : null;
            final ManaCostBeingPaid costBefore = traceTaps ? new ManaCostBeingPaid(cost) : null;
            if (!executeFilterManaSource(saPayment, sa, ai, cost, toPay, effect, manapool)) {
                return false;
            }
            if (traceTaps) {
                ManaPaymentTracer.logTap(false, saPayment, sa, formatShardsPaidDiff(costBefore, cost, toPay), manaProduced, ctx);
            }
        } else {
            if (ManaFilterConsolidation.isMultiManaComboAbility(saPayment)) {
                ComputerUtilMana.setComboManaChoice(ai, saPayment, cost);
            }
            if (!test) {
                ComputerUtilMana.setProductionTapSource(saPayment);
            }
            final CostPayment pay = new CostPayment(saPayment.getPayCosts(), saPayment);
            if (!pay.payComputerCosts(new AiCostDecision(ai, saPayment, effect, true))) {
                return false;
            }
            final String manaProduced = traceTaps ? ComputerUtilMana.formatManaProducedForLog(saPayment, ai, toPay, cost) : null;
            final ManaCostBeingPaid costBefore = traceTaps ? new ManaCostBeingPaid(cost) : null;
            ai.getGame().getStack().addAndUnfreeze(saPayment);
            ManaSourceTraits.invalidate();
            manapool.payManaFromAbility(sa, cost, saPayment);
            if (traceTaps) {
                ManaPaymentTracer.logTap(false, saPayment, sa, formatShardsPaidDiff(costBefore, cost, toPay), manaProduced, ctx);
            }
        }
        removeUsedManaSource(sourcesForShards.values(), saPayment);
        return true;
    }

    /**
     * Choose which free source pays a filter's generic activation cost, preferring the source that keeps
     * the most castable spells in hand and command zone afterwards (castability-aware).
     * Falls back to {@link #chooseManaAbility}.
     */
    static SpellAbility chooseSourceForFilterActivation(final SpellAbility sa, final Player ai,
            final SpellAbility filterAb, final ManaCostShard toPay, final List<SpellAbility> candidates,
            final ManaCostBeingPaid nestedCost, final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards,
            final boolean test, final ManaPaymentContext ctx) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.stream().allMatch(ManaFilterConsolidation::isDisposableManaAbility)) {
            final List<SpellAbility> sorted = Lists.newArrayList(candidates);
            sortFreeSourcesForNestedActivation(sorted, toPay, ManaAbilitySort.shouldReserveColorlessMana(ai, sa));
            return sorted.get(0);
        }
        if (ctx == null || ctx.inFilterActivationProbe
                || !CastabilityProbe.shouldUseForNestedActivation(sa, test, ctx)) {
            return ComputerUtilMana.chooseManaAbility(nestedCost, sa, ai, toPay, candidates, true);
        }

        final SpellAbility best = CastabilityProbe.pickBest(nestedCost, candidates, sa, ai, toPay,
                (cand, spell, player, probeCtx) -> {
                    final Set<Card> consumed = new HashSet<>();
                    consumed.add(cand.getHostCard());
                    consumed.add(filterAb.getHostCard());
                    return consumed;
                }, false, test, ctx);
        if (best != null) {
            ManaPaymentTracer.logMain(test, () -> "    candidate for " + filterAb.getHostCard() + " {1}: "
                    + best.getHostCard() + " chosen by castability probe", ctx);
            return best;
        }
        return ComputerUtilMana.chooseManaAbility(nestedCost, sa, ai, toPay, candidates, true);
    }

    static void spendTestDepositedManaTowardCost(final SpellAbility sa,
            final ManaCostBeingPaid cost, final ManaPool manapool, final List<Mana> testDepositedSurplus,
            final List<Mana> manaSpentToPay) {
        if (testDepositedSurplus == null || testDepositedSurplus.isEmpty()) {
            return;
        }
        while (!cost.isPaid() && !testDepositedSurplus.isEmpty()) {
            boolean progress = false;
            final Iterator<Mana> it = testDepositedSurplus.iterator();
            while (it.hasNext() && !cost.isPaid()) {
                final Mana m = it.next();
                if (!cost.isNeeded(m, manapool)) {
                    continue;
                }
                if (hasUnpaidColoredShards(cost) && !canDepositedManaPayUnpaidColoredShard(cost, m, manapool)) {
                    continue;
                }
                if (!cost.payMana(m, manapool)) {
                    continue;
                }
                if (manaSpentToPay != null) {
                    manaSpentToPay.add(m);
                }
                it.remove();
                progress = true;
            }
            if (!progress) {
                break;
            }
        }
    }

    /**
     * Spend deposited surplus on a filter's activation cost. Mana the outer spell can't use for an unpaid
     * colored pip is spent first so the useful colors remain for the spell itself.
     */
    static void spendTestDepositedManaTowardNestedCost(final SpellAbility filterAb,
            final ManaCostBeingPaid nestedCost, final ManaPool pool, final List<Mana> testDepositedSurplus,
            final List<Mana> manaSpentToPay, final ManaCostBeingPaid outerCost) {
        if (testDepositedSurplus == null || testDepositedSurplus.isEmpty()) {
            return;
        }
        if (outerCost != null && testDepositedSurplus.size() > 1) {
            testDepositedSurplus.sort(Comparator.comparingInt(
                    m -> colorCanPayUnpaidColoredShard(outerCost, pool, m.getColor()) ? 1 : 0));
        }
        spendTestDepositedManaTowardCost(filterAb, nestedCost, pool, testDepositedSurplus, manaSpentToPay);
    }

    /**
     * Pay a filter's activation cost from floating mana. In production, colors the outer spell can't use
     * for an unpaid colored pip are spent first (the AI's default pool choice is arbitrary); the generic
     * {@link ManaPool#payManaCostFromPool} then covers anything left.
     */
    static void payNestedCostFromPool(final SpellAbility filterAb, final ManaCostBeingPaid nestedCost,
            final ManaPool pool, final ManaCostBeingPaid outerCost, final boolean test,
            final List<Mana> manaSpentToPay) {
        if (pool.isEmpty() || nestedCost.isPaid()) {
            return;
        }
        if (!test && outerCost != null) {
            final List<Byte> colors = new ArrayList<>();
            for (final Mana m : pool) {
                if (!colors.contains(m.getColor())) {
                    colors.add(m.getColor());
                }
            }
            colors.sort(Comparator.comparingInt(c -> colorCanPayUnpaidColoredShard(outerCost, pool, c) ? 1 : 0));
            for (final Byte color : colors) {
                while (!nestedCost.isPaid() && pool.tryPayCostWithColor(color, filterAb, nestedCost, manaSpentToPay)) {
                    // keep spending this color while it still pays something
                }
            }
            if (nestedCost.isPaid()) {
                return;
            }
        }
        pool.payManaCostFromPool(nestedCost, filterAb, test, manaSpentToPay);
    }

    static boolean hasUnpaidColoredShards(final ManaCostBeingPaid cost) {
        for (final ManaCostShard shard : cost.getDistinctShards()) {
            if (!shard.isGeneric() && shard != ManaCostShard.COLORLESS && !shard.isPhyrexian()
                    && cost.getUnpaidShards(shard) > 0) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code mana} can satisfy an unpaid colored pip, not only generic/{@code {X}}. */
    static boolean canDepositedManaPayUnpaidColoredShard(final ManaCostBeingPaid cost, final Mana mana,
            final ManaPool pool) {
        return colorCanPayUnpaidColoredShard(cost, pool, mana.getColor());
    }

    static boolean colorCanPayUnpaidColoredShard(final ManaCostBeingPaid cost, final ManaPool pool,
            final byte manaColor) {
        for (final ManaCostShard shard : cost.getDistinctShards()) {
            if (!shard.isGeneric() && shard != ManaCostShard.COLORLESS && !shard.isPhyrexian()
                    && cost.getUnpaidShards(shard) > 0
                    && pool.canPayForShardWithColor(shard, manaColor)) {
                return true;
            }
        }
        return false;
    }

    /** Copy the mana pool for test-mode simulation rollback. */
    static List<Mana> snapshotPool(final ManaPool pool) {
        return Lists.newArrayList(pool);
    }

    /** Restore the mana pool to a pre-simulation snapshot after test-mode planning. */
    static void restorePool(final Player ai, final List<Mana> snapshot) {
        final ManaPool pool = ai.getManaPool();
        final List<Mana> current = Lists.newArrayList(pool);
        if (!current.isEmpty()) {
            pool.removeMana(current);
        }
        if (snapshot != null && !snapshot.isEmpty()) {
            pool.addMana(snapshot);
        }
    }

    static void cleanupTestManaPayment(final Player ai, final List<Mana> manaSpentToPay,
            final List<Mana> testDepositedSurplus) {
        final ManaPool pool = ai.getManaPool();
        final Set<Mana> deposited = testDepositedSurplus == null || testDepositedSurplus.isEmpty()
                ? Collections.emptySet() : new HashSet<>(testDepositedSurplus);
        if (testDepositedSurplus != null) {
            testDepositedSurplus.clear();
        }
        if (manaSpentToPay != null && !manaSpentToPay.isEmpty()) {
            final List<Mana> preExistingPoolMana = new ArrayList<>();
            for (final Mana m : manaSpentToPay) {
                if (!deposited.contains(m)) {
                    preExistingPoolMana.add(m);
                }
            }
            manaSpentToPay.clear();
            pool.refundMana(preExistingPoolMana);
        }
    }

    static void depositNestedManaSurplus(final String unusedMana, final Card sourceCard,
            final Player ai, final List<Mana> testDepositedSurplus) {
        if (unusedMana == null || sourceCard == null || testDepositedSurplus == null) {
            return;
        }
        for (final String manaPart : TextUtil.split(unusedMana, ' ')) {
            if (StringUtils.isNumeric(manaPart)) {
                for (int i = Integer.parseInt(manaPart); i > 0; i--) {
                    testDepositedSurplus.add(new Mana((byte) ManaAtom.COLORLESS, sourceCard, null, ai));
                }
            } else {
                testDepositedSurplus.add(new Mana(ManaAtom.fromName(MagicColor.toShortString(manaPart)), sourceCard, null, ai));
            }
        }
    }

    /**
     * When a multi-mana source pays one colored shard of the spell (e.g. Signet {@code {B}{R}} for {@code {R}}),
     * only apply mana toward that shard so surplus colors stay in the pool for chaining another Signet.
     */
    static boolean shouldApplyProducedManaToShardOnly(final SpellAbility saPayment,
            final ManaCostBeingPaid cost, final ManaCostShard toPay, final Player ai) {
        if (toPay.isGeneric() || toPay == ManaCostShard.X || toPay == ManaCostShard.COLORLESS
                || cost.getUnpaidShards(toPay) <= 0 || ManaFilterConsolidation.getManaProducedAmount(saPayment) <= 1) {
            return false;
        }
        final ManaCostBeingPaid probe = new ManaCostBeingPaid(cost);
        probe.decreaseShard(toPay, 1);
        if (probe.isPaid()) {
            return false;
        }
        return hasUnreservedManaActivationConsolidator(ai, saPayment.getHostCard());
    }

    /**
     * Another (not tap-reserved) signet/combo land with a mana activation cost is on the battlefield;
     * {@code excludeHost} (nullable) is skipped. Such a source may still need floating mana to fire.
     */
    static boolean hasUnreservedManaActivationConsolidator(final Player ai, final Card excludeHost) {
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (c == excludeHost) {
                continue;
            }
            for (final SpellAbility ma : c.getManaAbilities()) {
                if (ma.getHostCard() != c || !isManaActivationConsolidator(ma)) {
                    continue;
                }
                if (hasTapCost(ma) && AiCardMemory.isRememberedCard(ai, c, MemorySet.PAYS_TAP_COST)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    /** True when floating mana can satisfy at least one unpaid colored pip of {@code cost}. */
    static boolean poolCanPayAnyUnpaidColoredShard(final ManaCostBeingPaid cost, final SpellAbility sa,
            final ManaPool manapool) {
        for (final ManaCostShard shard : cost.getDistinctShards()) {
            if (shard.isGeneric() || shard == ManaCostShard.COLORLESS || shard == ManaCostShard.X
                    || cost.getUnpaidShards(shard) <= 0) {
                continue;
            }
            for (final Mana m : manapool) {
                if (manapool.canPayForShardWithColor(shard, m.getColor())
                        && sa.allowsPayingWithShard(m.getSourceCard(), m.getColor())) {
                    return true;
                }
            }
        }
        return false;
    }

    static String payProducedManaTowardShard(final ManaCostBeingPaid cost, final String mana,
            final ManaCostShard toPay, final Player ai) {
        final List<String> unused = new ArrayList<>();
        for (final String manaPart : TextUtil.split(mana, ' ')) {
            if (cost.getUnpaidShards(toPay) <= 0) {
                unused.add(manaPart);
                continue;
            }
            if (StringUtils.isNumeric(manaPart)) {
                unused.add(manaPart);
                continue;
            }
            final String color = MagicColor.toShortString(manaPart);
            if (ai.getManaPool().canPayForShardWithColor(toPay, ManaAtom.fromName(color))
                    && cost.ai_payMana(color, ai.getManaPool())) {
                continue;
            }
            unused.add(manaPart);
        }
        return unused.isEmpty() ? null : StringUtils.join(unused, ' ');
    }

    /**
     * Main shard payment loop with nested filter activation, banking, and consolidation.
     */
    static boolean runPaymentLoop(final ManaCostBeingPaid cost, final SpellAbility sa, final Player ai,
            final boolean test, final boolean checkPlayable, final boolean effect,
            final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards,
            final List<Mana> manaSpentToPay, final List<Mana> testDepositedSurplus,
            final List<SpellAbility> paymentList, final ManaPool manapool,
            final CardCollection planOut, final boolean hasConverge, final boolean purePhyrexian,
            final ManaPaymentContext ctx) {
        int phyLifeToPay = 2;
        int testEnergyPool = ai.getCounters(CounterEnumType.ENERGY);
        ManaCostShard toPay = null;
        List<SpellAbility> saExcludeList = new ArrayList<>();
        boolean converge = hasConverge;

        while (!cost.isPaid()) {
            if (test && testDepositedSurplus != null && !testDepositedSurplus.isEmpty()) {
                final boolean recordPlan = ctx.planSteps != null;
                final ManaCostBeingPaid beforeSurplus = recordPlan ? new ManaCostBeingPaid(cost) : null;
                final int keyBefore = unpaidProgressKey(cost);
                spendTestDepositedManaTowardCost(sa, cost, manapool, testDepositedSurplus, manaSpentToPay);
                if (recordPlan && keyBefore != unpaidProgressKey(cost)) {
                    // Distinct from real floating-pool spends — surplus is simulated, not in ManaPool.
                    ctx.recordStep(sa, test, "  Surplus pays " + formatShardsPaidDiff(beforeSurplus, cost, ManaCostShard.GENERIC)
                            + " for " + ManaPaymentTracer.manaPaymentSpellLabel(sa));
                }
                if (cost.isPaid()) {
                    break;
                }
            }
            while (!cost.isPaid() && !manapool.isEmpty()) {
                if (hasUnpaidColoredShards(cost) && hasUnreservedManaActivationConsolidator(ai, null)
                        && !poolCanPayAnyUnpaidColoredShard(cost, sa, manapool)) {
                    break;
                }
                boolean found = false;
                for (byte color : ManaAtom.MANATYPES) {
                    if (manapool.tryPayCostWithColor(color, sa, cost, manaSpentToPay)) {
                        ctx.recordStep(sa, test, () -> "  Pool pays " + MagicColor.toShortString(color)
                                + " for " + ManaPaymentTracer.manaPaymentSpellLabel(sa));
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    break;
                }
            }
            if (cost.isPaid()) {
                break;
            }

            if (sourcesForShards == null && !purePhyrexian) {
                break;
            }

            if (tryPayViaManaBankingChain(cost, sa, ai, sourcesForShards, manaSpentToPay, testDepositedSurplus,
                    test, effect, manapool, planOut, paymentList, ctx)) {
                continue;
            }

            if (tryApplyConsolidatingFilter(cost, sa, ai, sourcesForShards, manaSpentToPay, testDepositedSurplus,
                    test, effect, manapool, planOut, paymentList, ctx)) {
                continue;
            }

            toPay = ComputerUtilMana.getNextShardToPay(cost, sourcesForShards);
            if (toPay == null) {
                break;
            }

            Collection<SpellAbility> saList = null;
            if (converge && (toPay == ManaCostShard.GENERIC || toPay == ManaCostShard.X)) {
                final int unpaidColors = cost.getUnpaidColors() + cost.getColorsPaid() ^ ManaCostShard.COLORS_SUPERPOSITION;
                for (final MagicColor.Color b : ColorSet.fromMask(unpaidColors)) {
                    final ManaCostShard shard = ManaCostShard.valueOf(b.getColorMask());
                    saList = sourcesForShards.get(shard);
                    if (saList != null && !saList.isEmpty()) {
                        toPay = shard;
                        break;
                    }
                }
                if (saList == null || saList.isEmpty()) {
                    saList = sourcesForShards.get(toPay);
                    converge = false;
                }
            } else if (sourcesForShards == null && purePhyrexian) {
                saList = Lists.newArrayList();
            } else {
                saList = sourcesForShards.get(toPay);
            }

            if (saList != null) {
                saList.removeAll(saExcludeList);
            }
            if (toPay != null && toPay.isGeneric() && saList != null && !saList.isEmpty()) {
                final ManaAbilitySort.GenericColorPreference pref = ManaAbilitySort.resolveGenericColorPreference(ai, sa);
                final List<SpellAbility> sorted = Lists.newArrayList(saList);
                sorted.sort((a, b) -> ManaAbilitySort.compareGenericCandidatesForPayment(a, b, pref, cost.getGenericManaAmount(), sa, ai));
                saList = sorted;
            }
            final ManaCostShard shardForLog = toPay;
            final Collection<SpellAbility> saListForLog = saList;
            ManaPaymentTracer.logMain(test, () -> "  shard " + shardForLog + " candidates: " + saListForLog, ctx);

            SpellAbility saPayment = saList == null || saList.isEmpty() ? null
                    : chooseManaAbilityForShard(cost, sa, ai, toPay, saList, checkPlayable || !test, test, ctx);
            ManaPaymentTracer.logMain(test, () -> "  chosen for " + shardForLog + ": "
                    + (saPayment == null ? "(none)" : saPayment.getHostCard()), ctx);

            if (saPayment != null && ComputerUtilCost.isSacrificeSelfCost(saPayment.getPayCosts())
                    && sa.isTargeting(saPayment.getHostCard())) {
                saExcludeList.add(saPayment);
                continue;
            }

            if (saPayment != null && "BlackLotus".equals(saPayment.getParam("AILogic"))
                    && !SpecialCardAi.BlackLotus.consider(ai, sa, cost)) {
                saExcludeList.add(saPayment);
                continue;
            }

            if (saPayment == null) {
                if (test && ctx != null && ctx.inFilterActivationProbe) {
                    CastabilityProbe.recordNoSourceColoredShardFailure(ctx, toPay, saList);
                }
                boolean lifeInsteadOfBlack = toPay.isBlack() && ai.hasKeyword("PayLifeInsteadOf:B");
                if ((!toPay.isPhyrexian() && !lifeInsteadOfBlack) || !ai.canPayLife(phyLifeToPay, false, sa)
                        || (ai.getLife() <= phyLifeToPay && !ai.cantLoseForZeroOrLessLife())) {
                    break;
                }
                if (test) {
                    phyLifeToPay += 2;
                }
                if (sa.hasParam("AIPhyrexianPayment")) {
                    if ("Never".equals(sa.getParam("AIPhyrexianPayment"))) {
                        break;
                    } else if (sa.getParam("AIPhyrexianPayment").startsWith("OnFatalDamage.")) {
                        int dmg = Integer.parseInt(sa.getParam("AIPhyrexianPayment").substring(14));
                        if (ai.getOpponents().stream().noneMatch(PlayerPredicates.lifeLessOrEqualTo(dmg))) {
                            break;
                        }
                    }
                }
                if (toPay.isPhyrexian()) {
                    cost.payPhyrexian();
                    if (!test) {
                        sa.setSpendPhyrexianMana(true);
                    }
                } else if (lifeInsteadOfBlack) {
                    cost.decreaseShard(ManaCostShard.BLACK, 1);
                }
                ctx.recordStep(sa, test, "pay 2 life (paying " + toPay + ")");
                if (!test) {
                    ai.payLife(2, sa, false);
                }
                continue;
            }

            paymentList.add(saPayment);
            if (hasTapCost(saPayment)) {
                AiCardMemory.rememberCard(ai, saPayment.getHostCard(), MemorySet.PAYS_TAP_COST);
            }

            if (test) {
                Cost payCosts = saPayment.getPayCosts();
                CostPayEnergy energyCost = payCosts != null ? payCosts.getCostEnergy() : null;
                if (energyCost != null) {
                    testEnergyPool -= Integer.parseInt(energyCost.getAmount());
                    if (testEnergyPool < 0) {
                        break;
                    }
                }
            }

            if (!applyChosenManaPayment(saPayment, sa, ai, cost, toPay, sourcesForShards, manaSpentToPay,
                    testDepositedSurplus, test, effect, manapool, planOut, ctx)) {
                if (ManaFilterConsolidation.hasManaActivationCost(saPayment)) {
                    ManaPaymentTracer.logResult(test, false, () -> "  reject " + saPayment.getHostCard()
                            + " (nested activation cost unpayable)", ctx);
                }
                saExcludeList.add(saPayment);
                paymentList.remove(saPayment);
                if (hasTapCost(saPayment)) {
                    AiCardMemory.forgetCard(ai, saPayment.getHostCard(), MemorySet.PAYS_TAP_COST);
                }
                if (!test && saList != null) {
                    saList.remove(saPayment);
                }
                continue;
            }

            if (planOut != null) {
                planOut.add(saPayment.getHostCard());
            }
            rememberManaSourceConsumed(ai, saPayment);

            if (!cost.isPaid() && saPayment.isActivatedAbility()
                    && !saPayment.getRestrictions().canPlay(saPayment.getHostCard(), saPayment)) {
                sourcesForShards.values().removeIf(s -> s == saPayment);
            }

            if (!test && converge) {
                sourcesForShards.values().removeIf(CardTraitPredicates.isHostCard(saPayment.getHostCard()));
            }
        }
        return cost.isPaid();
    }
}
