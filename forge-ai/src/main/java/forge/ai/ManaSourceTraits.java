package forge.ai;

import forge.card.CardType;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostExile;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPutCardToLib;
import forge.game.cost.CostReturn;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;
import forge.game.cost.CostTapType;
import forge.game.cost.CostUntap;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-mana-ability characteristics used by sorting, efficiency scoring and filter detection.
 * <p>
 * The leaf predicates in {@link ManaFilterConsolidation} / {@link ManaPaymentExecution} are called many
 * times per ability per comparator; each one used to re-walk cost parts, call
 * {@link SpellAbility#amountOfManaGenerated(boolean)} (conditions + {@code Amount$} evaluation) or split
 * the produced-mana string. Everything here is computed once and memoized for the duration of the outer
 * payment (see {@link ManaPaymentContext.ManaPaymentPlanCache#bind}), the window during which these facts
 * are stable.
 */
final class ManaSourceTraits {
    /** All-false/zero traits for a {@code null} ability. */
    private static final ManaSourceTraits NONE = new ManaSourceTraits();

    final boolean isManaAbility;
    final boolean hasTapCost;
    final boolean hasManaActivationCost;
    final int activationCMC;
    final boolean disposable;
    final boolean sacrificesOther;
    /** Cost removes the host from the battlefield (sacrifice / exile / return / tuck itself). */
    final boolean hostLeavesBattlefield;
    /**
     * Exclusive host resources this cost consumes: {@code "tap"}, {@code "untap"}, {@code "self"}, or the
     * cost-part kind for anything else. Two abilities on one host conflict when these intersect
     * ({@link #leavesSiblingUsable}); mana parts are excluded since other sources pay them.
     */
    final Set<String> exclusiveCostKeys;
    /**
     * {@code X} is chosen by the activator through a non-mana cost part ({@code Remove X storage counters}
     * on Calciform Pools) and drives the amount produced ({@code SVar:X:Count$xPaid}). The planner must set
     * {@link SpellAbility#setXManaCostPaid} before predicting or producing mana; see
     * {@link ManaPaymentExecution#maxVariableManaX} / {@link ManaPaymentExecution#chooseVariableManaX}.
     */
    final boolean variableX;
    final boolean selfSacCreature;
    final int producedAmount;
    final int comboAmount;
    final boolean anyMana;
    final boolean comboMana;
    /** {@code mp.mana(ma)} for plain (non-any, non-combo) producers; {@code null} otherwise. */
    final String manaString;
    final boolean multiShardFilter;
    final boolean variableAmountFilter;
    final boolean multiPipFilter;
    final boolean comboFilter;
    final boolean anyManaFilter;
    final boolean requiresTappingOtherCreature;
    final boolean doesNotUntapNormally;
    final boolean producesOnlyColorless;
    final boolean producesColoredWithoutFilterCost;
    final boolean multiManaProducer;
    final boolean anyMultiManaProducer;
    final boolean multiManaDisposable;
    final boolean consolidatingCandidate;
    final boolean netPositiveConsolidator;
    final int netNegativeAnyManaFilterLoss;
    final boolean manaActivationConsolidator;
    final boolean manaReserveHost;
    /** {@link SpellAbility#calculateScoreForManaAbility()} (walks untap replacement effects; expensive). */
    final int manaScore;

    private ManaSourceTraits() {
        isManaAbility = false;
        hasTapCost = false;
        hasManaActivationCost = false;
        activationCMC = 0;
        disposable = false;
        sacrificesOther = false;
        hostLeavesBattlefield = false;
        exclusiveCostKeys = Collections.emptySet();
        variableX = false;
        selfSacCreature = false;
        producedAmount = 0;
        comboAmount = 0;
        anyMana = false;
        comboMana = false;
        manaString = null;
        multiShardFilter = false;
        variableAmountFilter = false;
        multiPipFilter = false;
        comboFilter = false;
        anyManaFilter = false;
        requiresTappingOtherCreature = false;
        doesNotUntapNormally = false;
        producesOnlyColorless = false;
        producesColoredWithoutFilterCost = false;
        multiManaProducer = false;
        anyMultiManaProducer = false;
        multiManaDisposable = false;
        consolidatingCandidate = false;
        netPositiveConsolidator = false;
        netNegativeAnyManaFilterLoss = 0;
        manaActivationConsolidator = false;
        manaReserveHost = false;
        manaScore = 0;
    }

    private ManaSourceTraits(final SpellAbility ma) {
        final Card host = ma.getHostCard();
        if (ma.getActivatingPlayer() == null && host != null && host.getController() != null) {
            // Amount$ evaluation, isUndoable and the untap score consult the activator; the sort/score
            // helpers used to set it right before calling, so mirror that here.
            ma.setActivatingPlayer(host.getController());
        }
        final Cost payCosts = ma.getPayCosts();
        final List<CostPart> parts = payCosts == null ? null : payCosts.getCostParts();
        final AbilityManaPart mp = ma.getManaPart();

        isManaAbility = ma.isManaAbility();
        hasTapCost = payCosts != null && payCosts.hasTapCost();
        hasManaActivationCost = payCosts != null && payCosts.hasManaCost();

        int cmc = 0;
        if (hasManaActivationCost) {
            final CostPartMana costMana = payCosts.getCostMana();
            if (costMana != null && costMana.getMana() != null) {
                cmc = costMana.getMana().getCMC();
            }
        }
        activationCMC = cmc;

        boolean anySac = false;
        boolean sacOther = false;
        boolean leaves = false;
        boolean tapsCreature = false;
        final Set<String> keys = new HashSet<>();
        if (parts != null) {
            for (final CostPart part : parts) {
                if (part instanceof CostPartMana) {
                    continue;
                }
                if (part instanceof CostSacrifice) {
                    anySac = true;
                    if (!part.payCostFromSource()) {
                        sacOther = true;
                    }
                } else if (part instanceof CostTapType && isCreatureTapType(part.getType())) {
                    tapsCreature = true;
                }
                if (part instanceof CostTap) {
                    keys.add("tap");
                } else if (part instanceof CostUntap) {
                    keys.add("untap");
                } else if (part.payCostFromSource() && (part instanceof CostSacrifice || part instanceof CostExile
                        || part instanceof CostReturn || part instanceof CostPutCardToLib)) {
                    leaves = true;
                    keys.add("self");
                } else {
                    keys.add(part.getClass().getSimpleName());
                }
            }
        }
        sacrificesOther = sacOther;
        hostLeavesBattlefield = leaves;
        exclusiveCostKeys = keys.isEmpty() ? Collections.emptySet() : keys;

        variableX = isManaAbility && payCosts != null && payCosts.hasXInAnyCostPart()
                && (payCosts.getCostMana() == null || payCosts.getCostMana().getAmountOfX() == 0)
                && "Count$xPaid".equals(ma.getSVar("X"));
        if (variableX && ma.getActivatingPlayer() != null) {
            // Amounts below read X: the planner's choice for this payment if it made one, else everything
            // the host can pay for (all storage counters) so the ability registers its full potential.
            ManaPaymentExecution.syncVariableManaX(ma, ma.getActivatingPlayer());
        }

        boolean disp = false;
        if (isManaAbility) {
            if (anySac || variableX) {
                // Sacrifice or spend stored counters: a one-shot resource, used only when needed.
                disp = true;
            } else if (!ma.isUndoable()) {
                disp = !(hasTapCost && !hasManaActivationCost);
            }
        }
        disposable = disp;
        selfSacCreature = disposable && !sacrificesOther && host != null && host.isCreature();

        producedAmount = ma.amountOfManaGenerated(true);

        anyMana = mp != null && mp.isAnyMana();
        comboMana = mp != null && mp.isComboMana();
        if (mp == null || !comboMana) {
            comboAmount = 0;
        } else if (ma.hasParam("Amount")) {
            comboAmount = host == null ? 0 : AbilityUtils.calculateAmount(host, ma.getParam("Amount"), ma);
        } else {
            comboAmount = 1;
        }
        final boolean multiManaCombo = comboAmount >= 2;

        manaString = mp != null && !anyMana && !comboMana ? mp.mana(ma) : null;
        final int manaTokens = manaString == null ? 0 : manaString.split(" ").length;

        multiShardFilter = hasManaActivationCost && manaString != null && manaTokens >= 2;
        variableAmountFilter = hasManaActivationCost && manaString != null && manaTokens < 2 && producedAmount >= 2;
        multiPipFilter = multiShardFilter || variableAmountFilter;
        // One-shot consolidators (Calciform Pools' storage counters) are never chained or consolidated
        // proactively; they stay ordinary fallback sources, sorted behind reusable producers.
        comboFilter = multiManaCombo && hasManaActivationCost && !disposable;
        anyManaFilter = hasManaActivationCost && mp != null && anyMana && !multiShardFilter && !comboFilter;

        requiresTappingOtherCreature = isManaAbility && !disposable && tapsCreature;
        doesNotUntapNormally = isManaAbility && !disposable && hasTapCost && host != null
                && !host.canUntap(host.getController(), true);

        producesOnlyColorless = manaString != null && "C".equals(manaString.trim());
        producesColoredWithoutFilterCost = !hasManaActivationCost && !disposable && manaString != null
                && !manaString.isEmpty() && !"C".equals(manaString.trim());

        multiManaProducer = !hasManaActivationCost && !disposable && !multiPipFilter && !multiManaCombo
                && producedAmount >= 2;
        anyMultiManaProducer = multiManaProducer && anyMana;
        multiManaDisposable = disposable && !sacrificesOther && producedAmount >= 2;
        consolidatingCandidate = (multiPipFilter || multiManaCombo || anyMultiManaProducer) && !disposable;
        netPositiveConsolidator = consolidatingCandidate && hasManaActivationCost && producedAmount > activationCMC;
        netNegativeAnyManaFilterLoss = anyManaFilter && !netPositiveConsolidator
                ? Math.max(0, activationCMC - producedAmount) : 0;
        manaActivationConsolidator = hasManaActivationCost && (netPositiveConsolidator || comboFilter);
        manaReserveHost = ManaFilterConsolidation.isManaReserveHost(host);
        manaScore = isManaAbility && payCosts != null ? ma.calculateScoreForManaAbility() : 0;
    }

    /** Traits for {@code ma}, memoized for the current outer payment when one is bound. */
    static ManaSourceTraits of(final SpellAbility ma) {
        if (ma == null) {
            return NONE;
        }
        final ManaPaymentContext.ManaPaymentPlanCache cache = ManaPaymentContext.ManaPaymentPlanCache.bound();
        if (cache == null) {
            return new ManaSourceTraits(ma);
        }
        ManaSourceTraits t = cache.sourceTraits.get(ma);
        if (t == null) {
            t = new ManaSourceTraits(ma);
            cache.sourceTraits.put(ma, t);
        }
        return t;
    }

    boolean isMultiManaCombo() {
        return comboAmount >= 2;
    }

    /**
     * After this ability was activated, can a sibling mana ability with traits {@code other} on the same
     * host still be activated? True when the host is still on the battlefield and the two costs consume
     * disjoint exclusive resources: Heart of Ramos {@code {T}} then {@code Sacrifice}, or a hypothetical
     * {@code {T}} then {@code {Q}}. Two {@code {T}} abilities, or anything after a self-sacrifice, conflict.
     */
    boolean leavesSiblingUsable(final ManaSourceTraits other) {
        return !hostLeavesBattlefield && Collections.disjoint(exclusiveCostKeys, other.exclusiveCostKeys);
    }

    /** Drop board-state-derived memos after a real (production) activation changed the board. */
    static void invalidate() {
        final ManaPaymentContext.ManaPaymentPlanCache cache = ManaPaymentContext.ManaPaymentPlanCache.bound();
        if (cache != null) {
            cache.invalidateBoardMemos();
        }
    }

    static boolean isCreatureTapType(final String type) {
        if (type == null) {
            return false;
        }
        for (final String option : type.split(";")) {
            final String core = option.split("\\.", 2)[0].trim();
            if ("Creature".equals(core) || CardType.isACreatureType(core)) {
                return true;
            }
        }
        return false;
    }
}
