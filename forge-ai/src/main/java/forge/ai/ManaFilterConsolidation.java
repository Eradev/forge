package forge.ai;

import com.google.common.collect.ListMultimap;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.combat.CombatUtil;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

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

    /**
     * Host cards with {@code SVar:AIManaReserve:True} (Karakas, Library of Alexandria) —
     * tap for mana only as last resort among reusable sources.
     */
    static final int MANA_RESERVE_HOST_PENALTY = 45;

    static boolean isManaReserveHost(final Card card) {
        return card != null && card.hasSVar("AIManaReserve")
                && "True".equalsIgnoreCase(card.getSVar("AIManaReserve"));
    }

    // All per-ability predicates below read from the memoized ManaSourceTraits snapshot.

    static boolean hasManaActivationCost(final SpellAbility ma) {
        return ManaSourceTraits.of(ma).hasManaActivationCost;
    }

    static boolean isAnyManaConsolidatingFilter(final SpellAbility ability) {
        return ManaSourceTraits.of(ability).anyManaFilter;
    }

    static int getComboManaAmount(final SpellAbility ability) {
        return ManaSourceTraits.of(ability).comboAmount;
    }

    static boolean isMultiManaComboAbility(final SpellAbility ability) {
        return ManaSourceTraits.of(ability).isMultiManaCombo();
    }

    static boolean isComboConsolidatingFilter(final SpellAbility ability) {
        return ManaSourceTraits.of(ability).comboFilter;
    }

    static int getManaProducedAmount(final SpellAbility ability) {
        return ManaSourceTraits.of(ability).producedAmount;
    }

    static boolean isVariableAmountConsolidatingFilter(final SpellAbility ability) {
        return ManaSourceTraits.of(ability).variableAmountFilter;
    }

    static boolean isMultiPipActivationFilter(final SpellAbility ability) {
        return ManaSourceTraits.of(ability).multiPipFilter;
    }

    static boolean isDisposableManaAbility(final SpellAbility ma) {
        return ManaSourceTraits.of(ma).disposable;
    }

    static boolean isDisposableManaCard(final Card card) {
        return anyManaAbility(card, t -> t.disposable);
    }

    static boolean sacrificesOtherPermanentsForMana(final SpellAbility ma) {
        return ManaSourceTraits.of(ma).sacrificesOther;
    }

    static boolean isSelfSacrificeCreatureMana(final SpellAbility ma) {
        return ManaSourceTraits.of(ma).selfSacCreature;
    }

    static boolean isCreatureTapType(final String type) {
        return ManaSourceTraits.isCreatureTapType(type);
    }

    static boolean requiresTappingOtherCreatureForMana(final SpellAbility ma) {
        return ManaSourceTraits.of(ma).requiresTappingOtherCreature;
    }

    static int getFilterActivationCMC(final SpellAbility filter) {
        return ManaSourceTraits.of(filter).activationCMC;
    }

    /** Mana lost per activation on any-mana filters that produce less than their activation cost. */
    static int netNegativeAnyManaFilterLoss(final SpellAbility ma) {
        return ManaSourceTraits.of(ma).netNegativeAnyManaFilterLoss;
    }

    private static boolean anyManaAbility(final Card card, final Predicate<ManaSourceTraits> test) {
        if (card == null) {
            return false;
        }
        for (final SpellAbility ma : card.getManaAbilities()) {
            if (test.test(ManaSourceTraits.of(ma))) {
                return true;
            }
        }
        return false;
    }

    /** Lower score is better. Used when building per-host rankings for sortManaAbilities. Memoized per payment. */
    static int scoreHostForSorting(final Card card) {
        if (card == null) {
            return Integer.MAX_VALUE;
        }
        final ManaPaymentContext.ManaPaymentPlanCache cache = ManaPaymentContext.ManaPaymentPlanCache.bound();
        if (cache == null) {
            return computeHostSortScore(card);
        }
        Integer score = cache.hostSortScores.get(card);
        if (score == null) {
            score = computeHostSortScore(card);
            cache.hostSortScores.put(card, score);
        }
        return score;
    }

    private static int computeHostSortScore(final Card card) {
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
                final ManaSourceTraits t = ManaSourceTraits.of(ability);
                score += t.manaScore;
                maxManaProduced = Math.max(maxManaProduced, t.producedAmount);
                if (t.hasManaActivationCost) {
                    hasManaCostAbility = true;
                }
                score += t.netNegativeAnyManaFilterLoss * NET_NEGATIVE_ANY_MANA_FILTER_PENALTY;
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
        if (anyManaAbility(card, t -> t.disposable)) {
            score += DISPOSABLE_MANA_PENALTY;
        }
        if (anyManaAbility(card, t -> t.selfSacCreature)) {
            score += SELF_SAC_CREATURE_MANA_PENALTY;
        }
        if (anyManaAbility(card, t -> t.requiresTappingOtherCreature)) {
            score += CREATURE_TAP_MANA_PENALTY;
        }
        if (anyManaAbility(card, t -> t.sacrificesOther)) {
            score += EXTERNAL_SACRIFICE_MANA_PENALTY;
        }
        if (isManaReserveHost(card)) {
            score += MANA_RESERVE_HOST_PENALTY;
        }

        return score;
    }

    /**
     * Whether a filter's activation cost can be paid from free battlefield sources (for ranking bonuses).
     */
    @FunctionalInterface
    interface ConsolidationFeasibility {
        boolean canActivateFilter(Player ai, SpellAbility filter,
                ListMultimap<Integer, SpellAbility> manaAbilityMap, boolean reusableOnly);
    }

    /**
     * Build host-card scores for sortManaAbilities. Lower is better.
     * {@code orderedCardsOut} receives hosts in discovery order before sort.
     */
    static Map<Card, Integer> buildManaCardRankings(final Player ai,
            final ListMultimap<ManaCostShard, SpellAbility> sourcesForShards,
            final ManaCostBeingPaid cost, final List<Card> orderedCardsOut,
            final SpellAbility spellBeingPaid, final int unpaidGeneric,
            final ListMultimap<Integer, SpellAbility> manaAbilityMap,
            final ConsolidationFeasibility probe) {
        final Map<Card, Integer> manaCardMap = new java.util.HashMap<>();
        if (sourcesForShards == null || cost == null || orderedCardsOut == null) {
            return manaCardMap;
        }
        final Map<Card, ConsolidationCoverage> coverage = new java.util.HashMap<>();
        final boolean scoreGenericConsolidators = probe != null && unpaidGeneric >= 2 && manaAbilityMap != null;

        // Single pass: base score per host plus everything the consolidation bonuses depend on.
        for (final ManaCostShard shard : sourcesForShards.keySet()) {
            if (shard == null) {
                continue;
            }
            final boolean generic = shard.isGeneric();
            // Only the first variable-amount filter listed for a 2+ pip colored shard earns that shard's bonus.
            boolean variableAmountBonusPending = !generic && cost.getUnpaidShards(shard) >= 2;
            for (final SpellAbility ability : sourcesForShards.get(shard)) {
                if (ability == null || ability.getHostCard() == null) {
                    continue;
                }
                final Card hostCard = ability.getHostCard();
                if (!manaCardMap.containsKey(hostCard)) {
                    manaCardMap.put(hostCard, scoreHostForSorting(hostCard));
                    orderedCardsOut.add(hostCard);
                }
                final ManaSourceTraits t = ManaSourceTraits.of(ability);
                if (generic) {
                    if (scoreGenericConsolidators && shard == ManaCostShard.GENERIC
                            && ((t.multiPipFilter && probe.canActivateFilter(ai, ability, manaAbilityMap, false))
                                    || (t.isMultiManaCombo() && !t.hasManaActivationCost))) {
                        coverage.computeIfAbsent(hostCard, k -> new ConsolidationCoverage()).genericConsolidator = true;
                    }
                    continue;
                }
                if (t.multiPipFilter) {
                    coverage.computeIfAbsent(hostCard, k -> new ConsolidationCoverage()).multiPipShards.add(shard);
                } else if (t.isMultiManaCombo()) {
                    coverage.computeIfAbsent(hostCard, k -> new ConsolidationCoverage()).comboShards.add(shard);
                }
                if (variableAmountBonusPending && t.variableAmountFilter) {
                    coverage.computeIfAbsent(hostCard, k -> new ConsolidationCoverage()).variableAmountBonuses++;
                    variableAmountBonusPending = false;
                }
            }
        }

        for (final Map.Entry<Card, ConsolidationCoverage> e : coverage.entrySet()) {
            final int bonuses = e.getValue().bonusCount(cost);
            if (bonuses > 0) {
                manaCardMap.merge(e.getKey(), -bonuses * FILTER_CONSOLIDATION_BONUS, Integer::sum);
            }
        }
        return manaCardMap;
    }

    /** Per-host facts feeding the {@link #FILTER_CONSOLIDATION_BONUS} passes of {@link #buildManaCardRankings}. */
    private static final class ConsolidationCoverage {
        /** Colored shards a multi-pip activation filter on this host can pay. */
        final Set<ManaCostShard> multiPipShards = new HashSet<>();
        /** Colored shards a free combo ability on this host can pay. */
        final Set<ManaCostShard> comboShards = new HashSet<>();
        /** One per 2+ pip colored shard where this host's variable-amount filter was listed first. */
        int variableAmountBonuses;
        boolean genericConsolidator;

        /** Number of {@link #FILTER_CONSOLIDATION_BONUS} units this host earns (bonuses stack). */
        int bonusCount(final ManaCostBeingPaid cost) {
            int bonuses = variableAmountBonuses;
            if (multiPipShards.size() >= 2) {
                bonuses++;
            }
            // Combo coverage only counts when no multi-pip filter on the same host was seen at all.
            if (multiPipShards.isEmpty() && !comboShards.isEmpty()) {
                int coverablePips = 0;
                for (final ManaCostShard s : comboShards) {
                    coverablePips += cost.getUnpaidShards(s);
                }
                if (coverablePips >= 2) {
                    bonuses++;
                }
            }
            if (genericConsolidator) {
                bonuses++;
            }
            return bonuses;
        }
    }
}
