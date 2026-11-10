package forge.ai;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import forge.ai.AiCardMemory.MemorySet;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
import forge.game.cost.CostAdjustment;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.PaymentDecision;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.ManaPool;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Mana-banking / chaining execution for {@link ManaPaymentExecution}: signet→combo,
 * rock→any-filter→combo, nested filter activation, and single consolidator application.
 */
final class ManaPaymentBanking {
    private ManaPaymentBanking() {
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
                ManaPaymentConsolidation::canActivateFilter);

        // First spend any floating mana in the pool towards the activation cost.
        final ManaPool pool = ai.getManaPool();
        ManaPaymentExecution.payNestedCostFromPool(filterAb, nestedCost, pool, outerCost, test, manaSpentToPay);
        if (test) {
            ManaPaymentExecution.spendTestDepositedManaTowardNestedCost(filterAb, nestedCost, pool, testDepositedSurplus, manaSpentToPay, outerCost);
        }

        while (!nestedCost.isPaid()) {
            if (test) {
                ManaPaymentExecution.spendTestDepositedManaTowardNestedCost(filterAb, nestedCost, pool, testDepositedSurplus, manaSpentToPay, outerCost);
                if (nestedCost.isPaid()) {
                    break;
                }
            }
            final int unpaidBefore = ManaPaymentExecution.unpaidProgressKey(nestedCost);
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
                if (!ManaPaymentExecution.isFreeManaSourceForNestedActivation(ma, filterAb)) {
                    continue;
                }
                // Combo lands (Cascade Bluffs) bank mana; don't spend them paying another filter's activation
                // (the filter's own host paying for itself, e.g. Calciform Pools' {T}, is fine).
                if (ma.getHostCard() != filterHost && ManaPaymentExecution.hostHasComboConsolidator(ma.getHostCard())
                        && ManaPaymentExecution.isManaActivationConsolidator(filterAb)) {
                    continue;
                }
                if (!ManaPaymentExecution.isCurrentlyAvailableForNestedActivation(ai, ma, filterAb)) {
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
            // Do not burn the only Mountain for Study Hall's {1} when Mana Crypt can fund it and {R}
            // is still unpaid on the spell — otherwise Petal is forced onto the stranded colored pip.
            ManaPaymentConsolidation.excludeDedicatedColoredProducersForNestedActivation(freeCandidates, outerCost, ai, manaAbilityMap);
            // Black Lotus must pay the spell (e.g. {1}{B}{R} with Petal), not Study Hall's activation.
            ManaPaymentConsolidation.excludeMultiManaDisposablesThatCanCoverOuterCost(freeCandidates, outerCost);
            if (freeCandidates.isEmpty()) {
                ManaPaymentExecution.payNestedCostFromPool(filterAb, nestedCost, pool, outerCost, test, manaSpentToPay);
                if (test) {
                    ManaPaymentExecution.spendTestDepositedManaTowardNestedCost(filterAb, nestedCost, pool, testDepositedSurplus, manaSpentToPay, outerCost);
                }
                if (nestedCost.isPaid()) {
                    continue;
                }
                return false;
            }
            ManaPaymentExecution.sortFreeSourcesForNestedActivation(freeCandidates, toPay,
                    ManaAbilitySort.shouldReserveColorlessMana(ai, sa));

            final SpellAbility chosen = ManaPaymentExecution.chooseSourceForFilterActivation(sa, ai, filterAb, toPay, freeCandidates,
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
                ManaPaymentExecution.depositNestedManaSurplus(unused, chosen.getHostCard(), ai, testDepositedSurplus);
                ManaPaymentExecution.rememberManaSourceConsumed(ai, chosen);
            } else {
                if (ManaPaymentTracer.tapTraceEnabled(false, ctx)) {
                    ManaPaymentTracer.logNestedTap(false, chosen,
                            ComputerUtilMana.predictManafromSpellAbility(chosen, ai, toPay, nestedCost), filterHost, sa, ctx);
                }
                if (!executeFreeManaSource(chosen, filterAb, ai, nestedCost, effect)) {
                    return false;
                }
            }
            ManaPaymentExecution.removeUsedManaSource(nestedSourcesForShards.values(), chosen);
            ManaPaymentExecution.removeUsedManaSource(sourcesForShards.values(), chosen);
            if (unpaidBefore == ManaPaymentExecution.unpaidProgressKey(nestedCost)) {
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
        ManaSourceTraits.invalidateAfterActivation(ma);
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
        ManaPaymentExecution.refreshExpressChoice(cost, sa, ai, toPay, filterAb);
        if (!payNonManaAbilityCosts(filterAb, ai, effect)) {
            return false;
        }
        ai.getGame().getStack().addAndUnfreeze(filterAb);
        ManaPaymentExecution.forgetChosenVariableManaX(filterAb);
        ManaSourceTraits.invalidateAfterActivation(filterAb);
        if (ManaPaymentExecution.shouldApplyProducedManaToShardOnly(filterAb, cost, toPay, ai)) {
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
     * Prefer a mana-banking chain (signet → second consolidator, or rock → any-mana filter → combo land),
     * then a single net-positive consolidator, before falling through to shard-by-shard payment. Banking
     * stays ahead so Signet→Bluffs / Sol Ring→Study Hall→Wooded Bastion are not flattened into a lone tap
     * that spends the combo land's free {C} and strands its filter.
     */
    static boolean tryMultiPipPayment(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards,
            final List<Mana> manaSpentToPay, final List<Mana> testDepositedSurplus, final boolean test,
            final boolean effect, final ManaPool manapool, final CardCollection planOut,
            final List<SpellAbility> paymentList, final ManaPaymentContext ctx) {
        if (tryPayViaManaBankingChain(cost, sa, ai, sourcesForShards, manaSpentToPay, testDepositedSurplus,
                test, effect, manapool, planOut, paymentList, ctx)) {
            return true;
        }
        if (tryPayViaAnyFilterComboChain(cost, sa, ai, sourcesForShards, manaSpentToPay, testDepositedSurplus,
                test, effect, manapool, planOut, paymentList, ctx)) {
            return true;
        }
        return tryApplyConsolidatingFilter(cost, sa, ai, sourcesForShards, manaSpentToPay, testDepositedSurplus,
                test, effect, manapool, planOut, paymentList, ctx);
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
        if (cost.isPaid() || (ctx != null && ctx.inFilterActivationProbe) || ManaPaymentExecution.countUnpaidPips(cost) < 3
                || (ctx != null && !ctx.caches.boardHasBankingPair(ai))) {
            return false;
        }
        // Second-stage candidates: combo lands first (they need a colored input a signet supplies),
        // then other net-positive filters (a signet's surplus funds a Signet-like land's {1}).
        final List<SpellAbility> seconds = new ArrayList<>();
        final List<SpellAbility> signets = new ArrayList<>();
        for (final SpellAbility ma : ComputerUtilMana.getOrBuildUniqueManaAbilities(ai, true, ctx)) {
            final Card host = ma.getHostCard();
            if (host == null) {
                continue;
            }
            if (ManaPaymentExecution.hasTapCost(ma)
                    && AiCardMemory.isRememberedCard(ai, host, MemorySet.PAYS_TAP_COST)) {
                continue;
            }
            final ManaSourceTraits t = ManaSourceTraits.of(ma);
            if (t.comboFilter) {
                seconds.add(ma);
            } else if (t.netPositiveConsolidator) {
                signets.add(ma);
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

    /**
     * Bank a free rock, an any-mana filter, and a combo land, then pay the spell from the pool.
     * Sol Ring {@code {C}{C}} funds Study Hall {@code {1}}→{@code {G}}; that {@code {G}} funds Wooded
     * Bastion {@code {G/W}}→{@code {G}{G}}; leftover {@code {C}} plus {@code {G}{G}} pays {@code {1}{G}{G}}.
     * Shard payment would tap Wooded Bastion for {@code {C}} to fuel Study Hall and never reach the filter.
     * <p>
     * Skipped when a free colored source can already pay the combo (Forest → Bastion), so that path stays
     * on the single-consolidator planner.
     */
    static boolean tryPayViaAnyFilterComboChain(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards,
            final List<Mana> manaSpentToPay, final List<Mana> testDepositedSurplus, final boolean test,
            final boolean effect, final ManaPool manapool, final CardCollection planOut,
            final List<SpellAbility> paymentList, final ManaPaymentContext ctx) {
        if (cost.isPaid() || !ManaPaymentExecution.hasUnpaidColoredShards(cost) || ManaPaymentExecution.countUnpaidPips(cost) < 2
                || (ctx != null && ctx.inFilterActivationProbe)) {
            return false;
        }
        final List<SpellAbility> combos = new ArrayList<>();
        final List<SpellAbility> anyFilters = new ArrayList<>();
        final List<SpellAbility> rocks = new ArrayList<>();
        for (final SpellAbility ma : ComputerUtilMana.getOrBuildUniqueManaAbilities(ai, true, ctx)) {
            final Card host = ma.getHostCard();
            if (host == null) {
                continue;
            }
            if (ManaPaymentExecution.hasTapCost(ma) && AiCardMemory.isRememberedCard(ai, host, MemorySet.PAYS_TAP_COST)) {
                continue;
            }
            final ManaSourceTraits t = ManaSourceTraits.of(ma);
            if (t.comboFilter) {
                combos.add(ma);
            } else if (t.anyManaFilter) {
                anyFilters.add(ma);
            } else if (isReusableChainFuel(ma, ai)) {
                rocks.add(ma);
            }
        }
        if (combos.isEmpty() || anyFilters.isEmpty() || rocks.isEmpty()) {
            return false;
        }
        rocks.sort(ManaPaymentBanking::compareChainFuel);
        final ListMultimap<Integer, SpellAbility> manaAbilityMap =
                ComputerUtilMana.getOrBuildManaAbilityMap(ai, true, ctx);
        for (final SpellAbility combo : combos) {
            if (ManaPaymentConsolidation.canActivateFilter(ai, combo, manaAbilityMap, false)) {
                continue;
            }
            for (final SpellAbility anyFilter : anyFilters) {
                if (anyFilter.getHostCard() == combo.getHostCard()) {
                    continue;
                }
                final byte color = colorAnyFilterFeedsCombo(anyFilter, combo, cost);
                if (color == 0) {
                    continue;
                }
                for (final SpellAbility rock : rocks) {
                    if (rock.getHostCard() == combo.getHostCard()
                            || rock.getHostCard() == anyFilter.getHostCard()) {
                        continue;
                    }
                    if (!ManaPaymentExecution.isCurrentlyAvailableForNestedActivation(ai, rock, anyFilter)) {
                        continue;
                    }
                    if (canPayViaRockAnyFilterCombo(cost, sa, ai, rock, anyFilter, combo, color, ctx)) {
                        return executeRockAnyFilterCombo(cost, sa, ai, rock, anyFilter, combo, color,
                                sourcesForShards, manaSpentToPay, testDepositedSurplus, test, effect,
                                manapool, planOut, paymentList, ctx);
                    }
                }
            }
        }
        return false;
    }

    /** Free reusable mana (Sol Ring, a basic) that can be banked to pay an any-mana filter's {1}. */
    static boolean isReusableChainFuel(final SpellAbility ma, final Player ai) {
        if (ManaFilterConsolidation.isDisposableManaAbility(ma)
                || ManaFilterConsolidation.hasManaActivationCost(ma)
                || ManaFilterConsolidation.getManaProducedAmount(ma) <= 0) {
            return false;
        }
        return ManaPaymentExecution.isCurrentlyAvailableForNestedActivation(ai, ma, null);
    }

    /** Multi-mana colorless rocks first, so Sol Ring is tried before a single land that cannot leave {C}. */
    static int compareChainFuel(final SpellAbility a, final SpellAbility b) {
        final ManaSourceTraits ta = ManaSourceTraits.of(a);
        final ManaSourceTraits tb = ManaSourceTraits.of(b);
        if (ta.multiManaProducer != tb.multiManaProducer) {
            return ta.multiManaProducer ? -1 : 1;
        }
        if (ta.producesOnlyColorless != tb.producesOnlyColorless) {
            return ta.producesOnlyColorless ? -1 : 1;
        }
        return Integer.compare(tb.producedAmount, ta.producedAmount);
    }

    /**
     * Color the any-mana filter should add so one mana pays the combo land. Prefers a color the spell
     * also needs ({@code {G}} for Wooded Bastion into {@code {1}{G}{G}}).
     */
    static byte colorAnyFilterFeedsCombo(final SpellAbility anyFilter, final SpellAbility combo,
            final ManaCostBeingPaid cost) {
        if (anyFilter.getManaPart() == null) {
            return 0;
        }
        byte fallback = 0;
        for (final byte color : MagicColor.WUBRG) {
            if (!singleManaPaysComboActivation(combo, color)) {
                continue;
            }
            if (colorPaysUnpaidSpellShard(cost, color)) {
                return color;
            }
            if (fallback == 0) {
                fallback = color;
            }
        }
        return fallback;
    }

    /** True when one mana of {@code color} pays the combo land's whole activation ({@code {G/W}}, not {@code {1}}). */
    static boolean singleManaPaysComboActivation(final SpellAbility combo, final byte color) {
        if (combo.getPayCosts() == null || combo.getPayCosts().getCostMana() == null) {
            return false;
        }
        final ManaCost activation = combo.getPayCosts().getCostMana().getManaCostFor(combo);
        if (activation.getGenericCost() > 0 || activation.getCMC() != 1) {
            return false;
        }
        boolean sawShard = false;
        for (final ManaCostShard shard : activation) {
            sawShard = true;
            if (color == 0 || !shard.canBePaidWithManaOfColor(color)) {
                return false;
            }
        }
        return sawShard;
    }

    static boolean colorPaysUnpaidSpellShard(final ManaCostBeingPaid cost, final byte color) {
        for (final ManaCostShard shard : cost.getDistinctShards()) {
            if (shard.isGeneric() || shard == ManaCostShard.COLORLESS || shard.isPhyrexian()
                    || cost.getUnpaidShards(shard) <= 0) {
                continue;
            }
            if (shard.canBePaidWithManaOfColor(color)) {
                return true;
            }
        }
        return false;
    }

    static boolean canPayViaRockAnyFilterCombo(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final SpellAbility rock, final SpellAbility anyFilter, final SpellAbility combo,
            final byte color, final ManaPaymentContext ctx) {
        final AbilityManaPart mp = anyFilter.getManaPart();
        if (mp == null) {
            return false;
        }
        final String previous = mp.getExpressChoice();
        final List<Mana> surplus = new ArrayList<>();
        try (ManaPaymentExecution.ReservationSnapshot snap = ManaPaymentExecution.ReservationSnapshot.takeWithPool(ai)) {
            mp.setExpressChoice(MagicColor.toShortString(color));
            if (!simulateAndBankConsolidator(rock, sa, ai, cost, surplus, ctx)
                    || !simulateAndBankConsolidator(anyFilter, sa, ai, cost, surplus, ctx)
                    || !simulateAndBankConsolidator(combo, sa, ai, cost, surplus, ctx)) {
                return false;
            }
            final ManaCostBeingPaid probe = new ManaCostBeingPaid(cost);
            return payBankedManaTowardCost(probe, sa, ai.getManaPool(), surplus, new ArrayList<>(), true);
        } finally {
            mp.setExpressChoice(previous);
        }
    }

    static boolean executeRockAnyFilterCombo(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final SpellAbility rock, final SpellAbility anyFilter, final SpellAbility combo,
            final byte color, final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards,
            final List<Mana> manaSpentToPay, final List<Mana> testDepositedSurplus, final boolean test,
            final boolean effect, final ManaPool manapool, final CardCollection planOut,
            final List<SpellAbility> paymentList, final ManaPaymentContext ctx) {
        if (!activateAndBankConsolidator(rock, sa, ai, cost, sourcesForShards, manaSpentToPay,
                testDepositedSurplus, test, effect, planOut, paymentList, ctx)) {
            return false;
        }
        final AbilityManaPart mp = anyFilter.getManaPart();
        if (mp != null) {
            mp.setExpressChoice(MagicColor.toShortString(color));
        }
        if (!activateAndBankConsolidator(anyFilter, sa, ai, cost, sourcesForShards, manaSpentToPay,
                testDepositedSurplus, test, effect, planOut, paymentList, ctx)
                || !activateAndBankConsolidator(combo, sa, ai, cost, sourcesForShards, manaSpentToPay,
                        testDepositedSurplus, test, effect, planOut, paymentList, ctx)) {
            return false;
        }
        final boolean hadUnpaid = !cost.isPaid();
        final boolean paid = payBankedManaTowardCost(cost, sa, manapool, test ? testDepositedSurplus : null,
                manaSpentToPay, test);
        if (hadUnpaid && paid && ctx != null) {
            ctx.recordStep(sa, test, "  result: PAID (pool)");
        }
        return paid;
    }

    /** Spend banked surplus (test) and the floating pool toward {@code cost}. */
    static boolean payBankedManaTowardCost(final ManaCostBeingPaid cost, final SpellAbility sa,
            final ManaPool pool, final List<Mana> surplus, final List<Mana> manaSpentToPay, final boolean test) {
        if (test && surplus != null) {
            ManaPaymentExecution.spendTestDepositedManaTowardCost(sa, cost, pool, surplus, manaSpentToPay);
        }
        pool.payManaCostFromPool(cost, sa, test, manaSpentToPay);
        while (!cost.isPaid() && !pool.isEmpty()) {
            boolean found = false;
            for (final byte color : ManaAtom.MANATYPES) {
                if (pool.tryPayCostWithColor(color, sa, cost, manaSpentToPay)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                break;
            }
        }
        return cost.isPaid();
    }

    static boolean canPayViaSignetThenComboBanking(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final SpellAbility signet, final SpellAbility combo,
            final List<Mana> testDepositedSurplus, final ManaPaymentContext ctx) {
        final List<Mana> surplus = new ArrayList<>();
        try (ManaPaymentExecution.ReservationSnapshot snap = ManaPaymentExecution.ReservationSnapshot.takeWithPool(ai)) {
            if (!simulateAndBankConsolidator(signet, sa, ai, cost, surplus, ctx)) {
                return false;
            }
            if (!simulateAndBankConsolidator(combo, sa, ai, cost, surplus, ctx)) {
                return false;
            }
            final ManaCostBeingPaid probe = new ManaCostBeingPaid(cost);
            final ManaPool pool = ai.getManaPool();
            final List<Mana> probeSpent = new ArrayList<>();
            ManaPaymentExecution.spendTestDepositedManaTowardCost(sa, probe, pool, surplus, probeSpent);
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
            ManaPaymentExecution.spendTestDepositedManaTowardCost(sa, cost, manapool, testDepositedSurplus, manaSpentToPay);
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
        final boolean tapReserved = ManaPaymentExecution.hasTapCost(filterAb);
        if (tapReserved) {
            AiCardMemory.rememberCard(ai, filterAb.getHostCard(), MemorySet.PAYS_TAP_COST);
            ManaPaymentExecution.bumpPaymentReservations();
        }
        final Runnable rollback = () -> {
            paymentList.remove(filterAb);
            if (tapReserved) {
                AiCardMemory.forgetCard(ai, filterAb.getHostCard(), MemorySet.PAYS_TAP_COST);
            }
        };
        final CardCollection nestedTaps = test && planOut != null ? new CardCollection() : null;
        if (!ManaPaymentExecution.prepareManaSourceForPayment(filterAb, sa, ai, spellCost, sourcesForShards, manaSpentToPay,
                testDepositedSurplus, test, effect, nestedTaps, ctx)) {
            rollback.run();
            return false;
        }
        if (nestedTaps != null && !nestedTaps.isEmpty()) {
            planOut.addAll(nestedTaps);
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
        ManaPaymentExecution.removeUsedManaSource(sourcesForShards.values(), filterAb);
        if (test) {
            ManaPaymentExecution.rememberManaSourceConsumed(ai, filterAb);
        }
        return true;
    }

    static boolean simulateAndBankConsolidator(final SpellAbility filterAb, final SpellAbility sa,
            final Player ai, final ManaCostBeingPaid spellCost, final List<Mana> surplus,
            final ManaPaymentContext ctx) {
        final ManaPaymentContext probeCtx = ctx == null ? ManaPaymentContext.outer() : ctx.detachedProbe();
        if (!ManaPaymentExecution.prepareManaSourceForPayment(filterAb, sa, ai, spellCost, ArrayListMultimap.create(), null, surplus,
                true, false, null, probeCtx)) {
            return false;
        }
        ManaPaymentExecution.predictManaForPayment(filterAb, ai, ManaCostShard.GENERIC, spellCost, surplus);
        return true;
    }

    static boolean bankManaAfterActivation(final SpellAbility filterAb, final SpellAbility sa,
            final Player ai, final ManaCostBeingPaid spellCost, final List<Mana> testDepositedSurplus,
            final boolean test, final boolean effect) {
        if (ManaFilterConsolidation.isMultiManaComboAbility(filterAb)) {
            ComputerUtilMana.setComboManaChoice(ai, filterAb, spellCost, sa);
        }
        if (test) {
            ManaPaymentExecution.predictManaForPayment(filterAb, ai, ManaCostShard.GENERIC, spellCost, testDepositedSurplus);
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
        ManaPaymentExecution.forgetChosenVariableManaX(filterAb);
        ManaSourceTraits.invalidateAfterActivation(filterAb);
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
        if (ManaPaymentExecution.countUnpaidPips(cost) < 2 || sourcesForShards == null
                || (ctx != null && !ctx.caches.boardHasNetPositiveConsolidator(ai))) {
            return false;
        }
        // Pure generic/colorless: prefer free taps (Study Hall {C} + Graven Cairns {C} for {2}) over
        // burning a Lotus Petal to activate a net-positive combo land for the same pips.
        if (!ManaPaymentExecution.hasUnpaidColoredShards(cost)
                && ManaPaymentConsolidation.freeGenericSourcesCover(ManaPaymentExecution.countUnpaidPips(cost),
                        ManaFilterConsolidation::isDisposableManaCard, ai, ctx)) {
            return false;
        }
        // Colored costs: when free reusable lands/rocks already cover, do not greedily Bastion-filter
        // (fourth land + Lotus board should keep Lotus and pay with lands).
        if (ManaPaymentExecution.hasUnpaidColoredShards(cost)
                && ManaPaymentExecution.freeReusableSourceCountsCover(cost, Collections.emptySet(), ai, ctx)) {
            return false;
        }
        final Set<SpellAbility> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        SpellAbility best = null;
        int bestConsumed = Integer.MAX_VALUE;
        ManaCostShard bestShard = null;
        for (final SpellAbility ma : sourcesForShards.values()) {
            // Skip variable-X / disposable even if multiPipFilter were true (storage Combo×X).
            if (!seen.add(ma) || ManaSourceTraits.of(ma).variableX || ManaSourceTraits.of(ma).disposable
                    || !ManaPaymentConsolidation.isNetPositiveConsolidator(ma)) {
                continue;
            }
            final Set<Card> consumed = ManaPaymentConsolidation.consolidatorCoverage(ma, cost, sa, ai, ctx, test);
            if (consumed == null) {
                continue;
            }
            final ManaCostShard shard = ManaPaymentConsolidation.shardForConsolidatorProbe(cost);
            final int cardsConsumed = ManaPaymentExecution.effectiveCardsConsumedForPayment(cost, sa, ai, shard, ma, consumed);
            if (cardsConsumed < bestConsumed) {
                bestConsumed = cardsConsumed;
                best = ma;
                bestShard = shard;
            }
        }
        if (best == null || bestShard == null) {
            return false;
        }
        try (ManaPaymentExecution.ReservationSnapshot snap = ManaPaymentExecution.ReservationSnapshot.take(ai)) {
            if (!ManaPaymentExecution.passesManaPaymentReservationChecks(ai, best, sa)) {
                return false;
            }
        }

        paymentList.add(best);
        if (ManaPaymentExecution.hasTapCost(best)) {
            AiCardMemory.rememberCard(ai, best.getHostCard(), MemorySet.PAYS_TAP_COST);
            ManaPaymentExecution.bumpPaymentReservations();
        }
        // Verbose trace only; the plan gets the "tap ..." step from applyChosenManaPayment below.
        final SpellAbility bestForLog = best;
        ManaPaymentTracer.logMain(test, () -> "  consolidate via " + bestForLog.getHostCard() + " (paying " + cost + " for "
                + ManaPaymentTracer.manaPaymentSpellLabel(sa) + ")", ctx);
        if (!ManaPaymentExecution.applyChosenManaPayment(best, sa, ai, cost, bestShard, sourcesForShards, manaSpentToPay,
                testDepositedSurplus, test, effect, manapool, planOut, ctx)) {
            paymentList.remove(best);
            if (ManaPaymentExecution.hasTapCost(best)) {
                AiCardMemory.forgetCard(ai, best.getHostCard(), MemorySet.PAYS_TAP_COST);
            }
            return false;
        }
        if (planOut != null) {
            planOut.add(best.getHostCard());
        }
        if (test) {
            ManaPaymentExecution.rememberManaSourceConsumed(ai, best);
        }
        return true;
    }
}
