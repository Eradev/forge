package forge.ai;

import com.google.common.collect.ListMultimap;
import forge.card.mana.ManaCostShard;
import forge.card.CardType;
import forge.game.card.Card;
import forge.game.combat.CombatUtil;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTapType;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.ability.AbilityUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Filter detection and cost-aware host ranking for {@link ComputerUtilMana#sortManaAbilities}.
 * Lower host scores are preferred when ordering mana sources.
 */
final class ManaFilterConsolidation {
    private ManaFilterConsolidation() {
    }

    /**
     * Penalty on hosts with a mana activation cost (signets, Study Hall, etc.) so plain lands
     * are preferred for a single colored shard. Overridden by {@link #FILTER_CONSOLIDATION_BONUS}
     * when the filter can cover two or more unpaid colored shards.
     */
    static final int FILTER_SINGLE_SHARD_PENALTY = 8;

    /**
     * Score reduction when a consolidating filter can pay two or more unpaid colored shards
     * in one activation (multi-shard signets, variable-amount filters, multi-combo producers).
     */
    static final int FILTER_CONSOLIDATION_BONUS = 20;

    /** Multiplier for each mana lost on net-negative any-mana filters ({@code {2}} for one any pip). */
    static final int NET_NEGATIVE_ANY_MANA_FILTER_PENALTY = 8;

    /**
     * Penalty on sacrifice / one-shot mana (Lotus Petal, Treasure tokens) so a consolidating
     * signet is preferred when both can satisfy a colored pip.
     */
    static final int DISPOSABLE_MANA_PENALTY = 30;

    /** Outlets like Ashnod's Altar that sacrifice another permanent — below self-sac disposables. */
    static final int EXTERNAL_SACRIFICE_MANA_PENALTY = 15;

    /** Self-sac creature mana (Treva's Attendant) — above external-sac outlets, below tokens/Petal. */
    static final int SELF_SAC_CREATURE_MANA_PENALTY = 12;

    /**
     * Mana abilities that tap another creature (Springleaf Drum, Survivors' Encampment) —
     * below reusable lands/rocks, above disposables.
     */
    static final int CREATURE_TAP_MANA_PENALTY = 10;

    static boolean hasManaActivationCost(final SpellAbility ma) {
        final Cost c = ma == null ? null : ma.getPayCosts();
        return c != null && c.hasManaCost();
    }

    static boolean isMultiShardConsolidatingFilter(final SpellAbility ability) {
        if (ability == null || !hasManaActivationCost(ability)) {
            return false;
        }
        final AbilityManaPart mp = ability.getManaPart();
        if (mp == null || mp.isAnyMana() || mp.isComboMana()) {
            return false;
        }
        final String mana = mp.mana(ability);
        return mana != null && mana.split(" ").length >= 2;
    }

    static boolean isAnyManaConsolidatingFilter(final SpellAbility ability) {
        if (ability == null || !hasManaActivationCost(ability)) {
            return false;
        }
        final AbilityManaPart mp = ability.getManaPart();
        return mp != null && mp.isAnyMana() && !isMultiShardConsolidatingFilter(ability)
                && !isComboConsolidatingFilter(ability);
    }

    static int getComboManaAmount(final SpellAbility ability) {
        if (ability == null) {
            return 0;
        }
        final AbilityManaPart mp = ability.getManaPart();
        if (mp == null || !mp.isComboMana()) {
            return 0;
        }
        if (ability.hasParam("Amount")) {
            final Card host = ability.getHostCard();
            if (host == null) {
                return 0;
            }
            return AbilityUtils.calculateAmount(host, ability.getParam("Amount"), ability);
        }
        return 1;
    }

    static boolean isMultiManaComboAbility(final SpellAbility ability) {
        return getComboManaAmount(ability) >= 2;
    }

    static boolean isComboConsolidatingFilter(final SpellAbility ability) {
        if (ability == null || !isMultiManaComboAbility(ability)) {
            return false;
        }
        final Cost payCosts = ability.getPayCosts();
        return payCosts != null && payCosts.hasManaCost();
    }

    static int getManaProducedAmount(final SpellAbility ability) {
        return ability == null ? 0 : ability.amountOfManaGenerated(true);
    }

    static boolean isVariableAmountConsolidatingFilter(final SpellAbility ability) {
        if (ability == null || !hasManaActivationCost(ability)) {
            return false;
        }
        final AbilityManaPart mp = ability.getManaPart();
        if (mp == null || mp.isAnyMana() || mp.isComboMana()) {
            return false;
        }
        final String mana = mp.mana(ability);
        if (mana == null || mana.split(" ").length >= 2) {
            return false;
        }
        return getManaProducedAmount(ability) >= 2;
    }

    static boolean isMultiPipActivationFilter(final SpellAbility ability) {
        return isMultiShardConsolidatingFilter(ability) || isVariableAmountConsolidatingFilter(ability);
    }

    static boolean isDisposableManaAbility(final SpellAbility ma) {
        if (ma == null || !ma.isManaAbility()) {
            return false;
        }
        final Cost payCosts = ma.getPayCosts();
        if (payCosts != null) {
            final List<CostPart> parts = payCosts.getCostParts();
            if (parts != null) {
                for (final CostPart part : parts) {
                    if (part instanceof CostSacrifice) {
                        return true;
                    }
                }
            }
        }
        if (!ma.isUndoable()) {
            if (payCosts != null && payCosts.hasTapCost() && !payCosts.hasManaCost()) {
                return false;
            }
            return true;
        }
        return false;
    }

    private static boolean isDisposableManaCard(final Card card) {
        if (card == null) {
            return false;
        }
        for (final SpellAbility ma : card.getManaAbilities()) {
            if (isDisposableManaAbility(ma)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sacrificesOtherPermanentsForMana(final SpellAbility ma) {
        final Cost payCosts = ma == null ? null : ma.getPayCosts();
        if (payCosts == null) {
            return false;
        }
        final List<CostPart> parts = payCosts.getCostParts();
        if (parts == null) {
            return false;
        }
        for (final CostPart part : parts) {
            if (part instanceof CostSacrifice && !part.payCostFromSource()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExternalSacrificeManaOutlet(final Card card) {
        if (card == null) {
            return false;
        }
        for (final SpellAbility ma : card.getManaAbilities()) {
            if (sacrificesOtherPermanentsForMana(ma)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSelfSacrificeCreatureMana(final SpellAbility ma) {
        if (ma == null) {
            return false;
        }
        final Card host = ma.getHostCard();
        return isDisposableManaAbility(ma) && !sacrificesOtherPermanentsForMana(ma)
                && host != null && host.isCreature();
    }

    private static boolean hasSelfSacrificeCreatureMana(final Card card) {
        if (card == null) {
            return false;
        }
        for (final SpellAbility ma : card.getManaAbilities()) {
            if (isSelfSacrificeCreatureMana(ma)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCreatureTapType(final String type) {
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

    private static boolean requiresTappingOtherCreatureForMana(final SpellAbility ma) {
        if (ma == null || !ma.isManaAbility() || isDisposableManaAbility(ma)) {
            return false;
        }
        final Cost payCosts = ma.getPayCosts();
        if (payCosts == null) {
            return false;
        }
        final List<CostPart> parts = payCosts.getCostParts();
        if (parts == null) {
            return false;
        }
        for (final CostPart part : parts) {
            if (part instanceof CostTapType && isCreatureTapType(part.getType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCreatureTapManaAbility(final Card card) {
        if (card == null) {
            return false;
        }
        for (final SpellAbility ma : card.getManaAbilities()) {
            if (requiresTappingOtherCreatureForMana(ma)) {
                return true;
            }
        }
        return false;
    }

    private static int getFilterActivationCMC(final SpellAbility filter) {
        if (filter == null || !hasManaActivationCost(filter)) {
            return 0;
        }
        final Cost payCosts = filter.getPayCosts();
        if (payCosts == null) {
            return 0;
        }
        final CostPartMana costMana = payCosts.getCostMana();
        if (costMana == null || costMana.getMana() == null) {
            return 0;
        }
        return costMana.getMana().getCMC();
    }

    private static int netNegativeAnyManaFilterLoss(final SpellAbility ma) {
        if (!isAnyManaConsolidatingFilter(ma)) {
            return 0;
        }
        final int produced = getManaProducedAmount(ma);
        final int activation = getFilterActivationCMC(ma);
        if (produced > activation) {
            return 0;
        }
        return Math.max(0, activation - produced);
    }

    /** Lower score is better. Used when building per-host rankings for sortManaAbilities. */
    static int scoreHostForSorting(final Card card, final SpellAbility spellBeingPaid, final Player ai) {
        if (card == null) {
            return Integer.MAX_VALUE;
        }
        int score = 0;
        int maxManaProduced = 0;
        boolean hasManaCostAbility = false;
        final Player controller = card.getController();

        for (SpellAbility ability : card.getSpellAbilities()) {
            if (ability == null) {
                continue;
            }
            if (controller != null) {
                ability.setActivatingPlayer(controller);
            }
            if (ability.isManaAbility()) {
                score += ability.calculateScoreForManaAbility();
                maxManaProduced = Math.max(maxManaProduced, ability.amountOfManaGenerated(true));
                if (ability.getPayCosts() != null && ability.getPayCosts().hasManaCost()) {
                    hasManaCostAbility = true;
                }
                score += netNegativeAnyManaFilterLoss(ability) * NET_NEGATIVE_ANY_MANA_FILTER_PENALTY;
            } else if (!ability.isTrigger() && ability.isPossible()) {
                score += 13;
            }
        }

        if (card.isCreature()) {
            int combatPenalty = 0;
            if (CombatUtil.canAttack(card)) {
                combatPenalty += 13;
            }
            if (CombatUtil.canBlock(card)) {
                combatPenalty += 13;
            }
            if (maxManaProduced >= 3) {
                combatPenalty = 0;
            } else if (maxManaProduced == 2) {
                combatPenalty /= 2;
            }
            score += combatPenalty;
        }

        if (hasManaCostAbility) {
            score += FILTER_SINGLE_SHARD_PENALTY;
        }
        if (isDisposableManaCard(card)) {
            score += DISPOSABLE_MANA_PENALTY;
        }
        if (hasSelfSacrificeCreatureMana(card)) {
            score += SELF_SAC_CREATURE_MANA_PENALTY;
        }
        if (hasCreatureTapManaAbility(card)) {
            score += CREATURE_TAP_MANA_PENALTY;
        }
        if (hasExternalSacrificeManaOutlet(card)) {
            score += EXTERNAL_SACRIFICE_MANA_PENALTY;
        }

        return score;
    }

    /**
     * Build host-card scores for sortManaAbilities. Lower is better.
     * {@code orderedCardsOut} receives hosts in discovery order before sort.
     */
    static Map<Card, Integer> buildManaCardRankings(final Player ai,
            final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards,
            final ManaCostBeingPaid cost, final List<Card> orderedCardsOut,
            final SpellAbility spellBeingPaid) {
        final Map<Card, Integer> manaCardMap = new java.util.HashMap<>();
        if (sourcesForShards == null || cost == null || orderedCardsOut == null) {
            return manaCardMap;
        }
        final Map<Card, Set<ManaCostShard>> coloredShardsCovered = new java.util.HashMap<>();
        final Map<Card, Set<ManaCostShard>> comboShardsCovered = new java.util.HashMap<>();

        for (final ManaCostShard shard : sourcesForShards.keySet()) {
            if (shard == null) {
                continue;
            }
            for (SpellAbility ability : sourcesForShards.get(shard)) {
                if (ability == null) {
                    continue;
                }
                final Card hostCard = ability.getHostCard();
                if (hostCard == null) {
                    continue;
                }
                if (!manaCardMap.containsKey(hostCard)) {
                    manaCardMap.put(hostCard, scoreHostForSorting(hostCard, spellBeingPaid, ai));
                    orderedCardsOut.add(hostCard);
                }
                if (!shard.isGeneric()) {
                    if (isMultiPipActivationFilter(ability)) {
                        coloredShardsCovered.computeIfAbsent(hostCard, k -> new HashSet<>()).add(shard);
                    } else if (isMultiManaComboAbility(ability)) {
                        comboShardsCovered.computeIfAbsent(hostCard, k -> new HashSet<>()).add(shard);
                    }
                }
            }
        }

        for (Map.Entry<Card, Set<ManaCostShard>> e : coloredShardsCovered.entrySet()) {
            final Card key = e.getKey();
            final Set<ManaCostShard> shards = e.getValue();
            if (key == null || shards == null || shards.size() < 2) {
                continue;
            }
            final Integer score = manaCardMap.get(key);
            if (score != null) {
                manaCardMap.put(key, score - FILTER_CONSOLIDATION_BONUS);
            }
        }

        for (final ManaCostShard shard : sourcesForShards.keySet()) {
            if (shard == null || shard.isGeneric() || cost.getUnpaidShards(shard) < 2) {
                continue;
            }
            for (SpellAbility ability : sourcesForShards.get(shard)) {
                if (ability == null) {
                    continue;
                }
                if (isVariableAmountConsolidatingFilter(ability)) {
                    final Card hostCard = ability.getHostCard();
                    if (hostCard != null) {
                        final Integer score = manaCardMap.get(hostCard);
                        if (score != null) {
                            manaCardMap.put(hostCard, score - FILTER_CONSOLIDATION_BONUS);
                        }
                    }
                    break;
                }
            }
        }

        for (Map.Entry<Card, Set<ManaCostShard>> e : comboShardsCovered.entrySet()) {
            final Card key = e.getKey();
            final Set<ManaCostShard> shards = e.getValue();
            if (key == null || shards == null || coloredShardsCovered.containsKey(key)) {
                continue;
            }
            int coverablePips = 0;
            for (final ManaCostShard s : shards) {
                if (s != null) {
                    coverablePips += cost.getUnpaidShards(s);
                }
            }
            if (coverablePips >= 2) {
                final Integer score = manaCardMap.get(key);
                if (score != null) {
                    manaCardMap.put(key, score - FILTER_CONSOLIDATION_BONUS);
                }
            }
        }

        return manaCardMap;
    }
}
