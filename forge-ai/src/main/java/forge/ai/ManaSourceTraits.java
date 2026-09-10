package forge.ai;

import forge.card.CardType;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTapType;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;

import java.util.List;

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
        boolean tapsCreature = false;
        if (parts != null) {
            for (final CostPart part : parts) {
                if (part instanceof CostSacrifice) {
                    anySac = true;
                    if (!part.payCostFromSource()) {
                        sacOther = true;
                    }
                } else if (part instanceof CostTapType && isCreatureTapType(part.getType())) {
                    tapsCreature = true;
                }
            }
        }
        sacrificesOther = sacOther;

        boolean disp = false;
        if (isManaAbility) {
            if (anySac) {
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
        comboFilter = multiManaCombo && hasManaActivationCost;
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
        consolidatingCandidate = multiPipFilter || multiManaCombo || anyMultiManaProducer;
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
