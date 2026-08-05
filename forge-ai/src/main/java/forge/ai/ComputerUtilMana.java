package forge.ai;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import forge.ai.AiCardMemory.MemorySet;
import forge.ai.ability.AnimateAi;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.*;
import forge.game.keyword.Keyword;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.ManaPool;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementLayer;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityManaConvert;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import forge.util.TextUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

public class ComputerUtilMana {

    /**
     * Full stranding / efficiency re-simulation runs for payment-prompt preview and production Auto-pay.
     * AI feasibility dry-runs ({@link #canPayManaCost}) use sort order only to avoid M2 timeouts.
     */
    static boolean useFullPaymentProbes(final boolean test, final ManaPaymentContext ctx) {
        if (!test) {
            return true;
        }
        return ctx != null && ctx.paymentPromptPreview;
    }

    public static boolean canPayManaCost(ManaCostBeingPaid cost, final SpellAbility sa, final Player ai, final boolean effect) {
        //check copy of cost so it doesn't modify the exist cost being paid
        cost = new ManaCostBeingPaid(cost);
        return payManaCost(cost, sa, ai, true, true, effect, ManaPaymentContext.outer()) != null;
    }
    public static boolean canPayManaCost(final SpellAbility sa, final Player ai, final int extraMana, final boolean effect) {
        return canPayManaCost(sa.getPayCosts(), sa, ai, extraMana, effect);
    }
    public static boolean canPayManaCost(final Cost cost, final SpellAbility sa, final Player ai, final int extraMana, final boolean effect) {
        return payManaCost(cost, sa, ai, true, extraMana, true, effect, ManaPaymentContext.outer());
    }

    public static boolean payManaCost(ManaCostBeingPaid cost, final SpellAbility sa, final Player ai, final boolean effect) {
        return payManaCost(cost, sa, ai, false, true, effect, null, ManaPaymentContext.outer()) != null;
    }
    public static boolean payManaCost(final Cost cost, final Player ai, final SpellAbility sa, final boolean effect) {
        return payManaCost(cost, sa, ai, false, 0, true, effect, ManaPaymentContext.outer());
    }
    private static boolean payManaCost(final Cost cost, final SpellAbility sa, final Player ai, final boolean test, final int extraMana, boolean checkPlayable, final boolean effect, final ManaPaymentContext ctx) {
        ManaCostBeingPaid manaCost = calculateManaCost(cost, sa, ai, test, extraMana, effect);
        return payManaCost(manaCost, sa, ai, test, checkPlayable, effect, null, ctx) != null;
    }

    /** Collect host cards from a dry-run for Auto-pay highlight preview. */
    private static void addPlanSource(final CardCollection planOut, final Card card) {
        if (planOut != null && card != null && !planOut.contains(card)) {
            planOut.add(card);
        }
    }

    private static void collectPlanSources(final CardCollection planOut, final List<Mana> manaSpentToPay,
            final List<SpellAbility> paymentList) {
        if (planOut == null) {
            return;
        }
        if (paymentList != null) {
            for (final SpellAbility paid : paymentList) {
                if (paid != null) {
                    addPlanSource(planOut, paid.getHostCard());
                }
            }
        }
        if (manaSpentToPay != null) {
            for (final Mana m : manaSpentToPay) {
                if (m != null) {
                    addPlanSource(planOut, m.getSourceCard());
                }
            }
        }
    }

    /**
     * Return the number of colors used for payment for Converge
     */
    public static int getConvergeCount(final SpellAbility sa, final Player ai) {
        ManaCostBeingPaid cost = calculateManaCost(sa.getPayCosts(), sa, ai, true, 0, false);
        if (payManaCost(cost, sa, ai, true, true, false, ManaPaymentContext.outer()) != null) {
            return cost.getSunburst();
        }
        // TODO return -1 so API can bail out since it's unpayable
        return 0;
    }

    // Does not check if mana sources can be used right now, just checks for potential chance.
    public static boolean hasEnoughManaSourcesToCast(final SpellAbility sa, final Player ai) {
        if (ai == null || sa == null)
            return false;
        sa.setActivatingPlayer(ai);
        return payManaCost(sa.getPayCosts(), sa, ai, true, 0, false, false, ManaPaymentContext.outer());
    }

    public static CardCollection getManaSourcesToPayCost(final ManaCostBeingPaid cost, final SpellAbility sa, final Player ai, final boolean effect) {
        if (cost == null || sa == null || ai == null) {
            return null;
        }
        final CardCollection plan = new CardCollection();
        if (payManaCost(cost, sa, ai, true, true, effect, plan, ManaPaymentContext.outer()) == null) {
            return null;
        }
        return plan;
    }

    /** Production Auto-pay from the human payment prompt (emits {@code [prod]} plan when enabled). */
    public static boolean payManaCostFromPaymentPrompt(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final boolean effect) {
        if (cost == null || sa == null || ai == null) {
            return false;
        }
        return payManaCost(cost, sa, ai, false, true, effect, null, ManaPaymentContext.outerForPaymentPromptCommit()) != null;
    }

    /** Dry-run for the human payment-prompt Auto preview. */
    public static CardCollection getManaSourcesToPayCostForPaymentPrompt(final ManaCostBeingPaid cost,
            final SpellAbility sa, final Player ai, final boolean effect) {
        if (cost == null || sa == null || ai == null) {
            return null;
        }
        final CardCollection plan = new CardCollection();
        if (payManaCost(cost, sa, ai, true, true, effect, plan, ManaPaymentContext.outerForPaymentPrompt()) == null) {
            return null;
        }
        return plan;
    }

    public static CardCollection getManaSourcesToPayCostForPaymentPrompt(final ManaCostBeingPaid cost,
            final SpellAbility sa, final Player ai) {
        return getManaSourcesToPayCostForPaymentPrompt(cost, sa, ai, false);
    }

    private static Integer scoreManaProducingCard(final Card card) {
        int score = 0;

        for (SpellAbility ability : card.getSpellAbilities()) {
            ability.setActivatingPlayer(card.getController());
            if (ability.isManaAbility()) {
                score += ability.calculateScoreForManaAbility();
                // TODO check TriggersWhenSpent: decrease score depending on context
            }
            else if (!ability.isTrigger() && ability.isPossible()) {
                score += 13; //add 13 for any non-mana activated abilities
            }
        }

        if (card.isCreature()) {
            // treat attacking and blocking as though they're non-mana abilities
            if (CombatUtil.canAttack(card)) {
                score += 13;
            }
            if (CombatUtil.canBlock(card)) {
                score += 13;
            }
        }

        return score;
    }

    public static SpellAbility chooseManaAbility(ManaCostBeingPaid cost, SpellAbility sa, Player ai, ManaCostShard toPay,
            Collection<SpellAbility> maList, boolean checkCosts) {
        Card saHost = sa.getHostCard();

        // CastTotalManaSpent (AIPreference:ManaFrom$Type or AIManaPref$ Type)
        String manaSourceType = "";
        if (saHost.hasSVar("AIPreference")) {
            String condition = saHost.getSVar("AIPreference");
            if (condition.startsWith("ManaFrom")) {
                manaSourceType = TextUtil.split(condition, '$')[1];
            }
        } else if (sa.hasParam("AIManaPref")) {
            manaSourceType = sa.getParam("AIManaPref");
        }
        if (manaSourceType != "") {
            List<SpellAbility> filteredList = Lists.newArrayList(maList);
            switch (manaSourceType) {
                case "Snow":
                    filteredList.sort((ab1, ab2) -> ab1.getHostCard() != null && ab1.getHostCard().isSnow()
                            && ab2.getHostCard() != null && !ab2.getHostCard().isSnow() ? -1 : 1);
                    maList = filteredList;
                    break;
                case "Treasure":
                    // Try to spend only one Treasure if possible
                    filteredList.sort((ab1, ab2) -> ab1.getHostCard() != null && ab1.getHostCard().getType().hasSubtype("Treasure")
                            && ab2.getHostCard() != null && !ab2.getHostCard().getType().hasSubtype("Treasure") ? -1 : 1);
                    SpellAbility first = filteredList.get(0);
                    if (first.getHostCard() != null && first.getHostCard().getType().hasSubtype("Treasure")) {
                        maList.remove(first);
                        List<SpellAbility> updatedList = Lists.newArrayList();
                        updatedList.add(first);
                        updatedList.addAll(maList);
                        maList = updatedList;
                    }
                    break;
                case "TreasureMax":
                    // Ok to spend as many Treasures as possible
                    filteredList.sort((ab1, ab2) -> ab1.getHostCard() != null && ab1.getHostCard().getType().hasSubtype("Treasure")
                            && ab2.getHostCard() != null && !ab2.getHostCard().getType().hasSubtype("Treasure") ? -1 : 1);
                    maList = filteredList;
                    break;
                case "NotSameCard":
                    String hostName = sa.getHostCard().getName();
                    maList = filteredList.stream()
                            .filter(saPay -> !saPay.getHostCard().getName().equals(hostName))
                            .collect(Collectors.toList());
                    break;
                default:
                    break;
            }
        }

        for (final SpellAbility ma : maList) {
            // this rarely seems like a good idea
            if (ma.getHostCard() == saHost) {
                continue;
            }

            if (ma.getPayCosts().hasTapCost() && AiCardMemory.isRememberedCard(ai, ma.getHostCard(), MemorySet.PAYS_TAP_COST)) {
                continue;
            }

            int amount = ma.hasParam("Amount") ? AbilityUtils.calculateAmount(ma.getHostCard(), ma.getParam("Amount"), ma) : 1;
            if (amount <= 0) {
                // wrong gamestate for variable amount
                continue;
            }

            if (sa.getApi() == ApiType.Animate) {
                // For abilities like Genju of the Cedars, make sure that we're not activating the aura ability by tapping the enchanted card for mana
                if (saHost.isAura() && "Enchanted".equals(sa.getParam("Defined"))
                        && ma.getHostCard() == saHost.getEnchantingCard()
                        && ma.getPayCosts().hasTapCost()) {
                    continue;
                }

                // If a manland was previously animated this turn, do not tap it to animate another manland
                if (saHost.isLand() && ma.getHostCard().isLand()
                        && ai.getController().isAI()
                        && AnimateAi.isAnimatedThisTurn(ai, ma.getHostCard())) {
                    continue;
                }
            } else if (sa.getApi() == ApiType.Pump) {
                if ((saHost.isInstant() || saHost.isSorcery())
                        && ma.getHostCard().isCreature()
                        && ai.getController().isAI()
                        && ma.getPayCosts().hasTapCost()
                        && sa.getTargets().getTargetCards().contains(ma.getHostCard())) {
                    // do not activate pump instants/sorceries targeting creatures by tapping targeted
                    // creatures for mana (for example, Servant of the Conduit)
                    continue;
                }
            } else if (sa.getApi() == ApiType.Attach
                    && "AvoidPayingWithAttachTarget".equals(saHost.getSVar("AIPaymentPreference"))) {
                // For cards like Genju of the Cedars, make sure we're not attaching to the same land that will
                // be tapped to pay its own cost if there's another untapped land like that available
                if (ma.getHostCard().equals(sa.getTargetCard())) {
                    if (CardLists.count(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.nameEquals(ma.getHostCard().getName()).and(CardPredicates.UNTAPPED)) > 1) {
                        continue;
                    }
                }
            }

            SpellAbility paymentChoice = ma;

            // Exception: when paying generic mana with Cavern of Souls, prefer the colored mana producing ability
            // to attempt to make the spell uncounterable when possible.
            if (ComputerUtilAbility.getAbilitySourceName(ma).equals("Cavern of Souls")
                    && saHost.getType().hasCreatureType(ma.getHostCard().getChosenType())) {
                if (toPay == ManaCostShard.COLORLESS && cost.getUnpaidShards().contains(ManaCostShard.GENERIC)) {
                    // Deprioritize Cavern of Souls, try to pay generic mana with it instead to use the NoCounter ability
                    continue;
                } else if (toPay == ManaCostShard.GENERIC || toPay == ManaCostShard.X) {
                    for (SpellAbility ab : maList) {
                        if (ab.isManaAbility() && ab.getManaPart().isAnyMana() && ab.hasParam("AddsNoCounter")) {
                            if (!ab.getHostCard().isTapped()) {
                                paymentChoice = ab;
                                break;
                            }
                        }
                    }
                }
            }

            if (!canPayShardWithSpellAbility(toPay, ai, paymentChoice, sa, cost, checkCosts, cost.getXManaCostPaidByColor())) {
                continue;
            }

            // these should come last since they reserve the paying cards
            // (this means if a mana ability has both parts it doesn't currently undo reservations if the second part fails)
            if (!ComputerUtilCost.checkForManaSacrificeCost(ai, ma.getPayCosts(), ma, ma.isTrigger())) {
                continue;
            }
            if (!ComputerUtilCost.checkTapTypeCost(ai, ma.getPayCosts(), ma.getHostCard(), sa, AiCardMemory.getMemorySet(ai, MemorySet.PAYS_TAP_COST))) {
                continue;
            }

            return paymentChoice;
        }
        return null;
    }

    /** Live spell cost + tap source while production Auto-pay runs (for TapsForMana combo choices). */
    private static final class ProductionPaymentState {
        final ManaCostBeingPaid cost;
        SpellAbility tapSource;

        ProductionPaymentState(final ManaCostBeingPaid cost) {
            this.cost = cost;
        }
    }

    private static final ThreadLocal<Deque<ProductionPaymentState>> activeProductionPayment =
            ThreadLocal.withInitial(ArrayDeque::new);

    static void beginProductionPayment(final ManaCostBeingPaid cost) {
        activeProductionPayment.get().push(new ProductionPaymentState(cost));
    }

    static void endProductionPayment() {
        final Deque<ProductionPaymentState> stack = activeProductionPayment.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            activeProductionPayment.remove();
        }
    }

    static void setProductionTapSource(final SpellAbility saPayment) {
        final Deque<ProductionPaymentState> stack = activeProductionPayment.get();
        if (!stack.isEmpty()) {
            stack.peek().tapSource = saPayment;
        }
    }

    /**
     * Cost-aware combo colors for {@code specifyManaCombo} during production payment. Trigger handlers
     * copy the resolving SA and drop express choice, so choices must be derived from the active cost.
     */
    static Map<Byte, Integer> specifyComboFromActivePayment(final Player ai, final SpellAbility triggerSa,
            final int manaAmount, final boolean different) {
        final Deque<ProductionPaymentState> stack = activeProductionPayment.get();
        if (stack.isEmpty()) {
            return null;
        }
        final ProductionPaymentState state = stack.peek();
        if (state.cost == null || state.cost.isPaid()) {
            return null;
        }
        final ManaCostBeingPaid probe = new ManaCostBeingPaid(state.cost);
        if (state.tapSource != null) {
            final String landMana = predictManaReplacement(state.tapSource, ai, ManaCostShard.GENERIC);
            if (!StringUtils.isBlank(landMana)) {
                payMultipleMana(probe, landMana.trim(), ai);
            }
        }
        if (probe.isPaid()) {
            return null;
        }
        final String choices = buildComboManaChoiceString(ai, triggerSa, probe, manaAmount, different);
        if (StringUtils.isBlank(choices) || "0".equals(choices)) {
            return null;
        }
        final Map<Byte, Integer> result = new HashMap<>();
        for (final String color : choices.split(" ")) {
            if (StringUtils.isBlank(color)) {
                continue;
            }
            final byte atom = ManaAtom.fromName(color);
            if (atom != 0) {
                result.merge(atom, 1, Integer::sum);
            }
        }
        return result.isEmpty() ? null : result;
    }

    public static String predictManaReplacement(SpellAbility saPayment, Player ai, ManaCostShard toPay) {
        Card hostCard = saPayment.getHostCard();
        Game game = hostCard.getGame();
        String manaProduced = toPay.isSnow() && hostCard.isSnow() ? "S" : GameActionUtil.generatedTotalMana(saPayment);

        final Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(hostCard);
        repParams.put(AbilityKey.Mana, manaProduced);
        repParams.put(AbilityKey.Activator, ai);
        repParams.put(AbilityKey.AbilityMana, saPayment); // RootAbility

        // TODO Damping Sphere might replace later?

        // add flags to replacementEffects to filter better?
        List<ReplacementEffect> reList = game.getReplacementHandler().getReplacementList(ReplacementType.ProduceMana, repParams, ReplacementLayer.Other);

        List<SpellAbility> replaceMana = Lists.newArrayList();
        List<SpellAbility> replaceType = Lists.newArrayList();
        List<SpellAbility> replaceAmount = Lists.newArrayList(); // currently only multi

        // try to guess the color the mana gets replaced to
        for (ReplacementEffect re : reList) {
            SpellAbility o = re.getOverridingAbility();

            if (o == null || o.getApi() != ApiType.ReplaceMana) {
                continue;
            }

            // this one does replace the amount too
            if (o.hasParam("ReplaceMana")) {
                replaceMana.add(o);
            } else if (o.hasParam("ReplaceType") || o.hasParam("ReplaceColor")) {
                // this one replaces the color/type
                // check if this one can be replaced into wanted mana shard
                replaceType.add(o);
            } else if (o.hasParam("ReplaceAmount")) {
                replaceAmount.add(o);
            }
        }

        // it is better to apply these ones first
        if (!replaceMana.isEmpty()) {
            for (SpellAbility saMana : replaceMana) {
                // one of then has to Any
                // one of then has to C
                // one of then has to B
                String m = saMana.getParam("ReplaceMana");
                if ("Any".equals(m)) {
                    byte rs = MagicColor.GREEN;
                    for (byte c : MagicColor.WUBRGC) {
                        if (toPay.canBePaidWithManaOfColor(c)) {
                            rs = c;
                            break;
                        }
                    }
                    manaProduced = MagicColor.toShortString(rs);
                } else {
                    manaProduced = m;
                }
            }
        }

        // then apply this one
        if (!replaceType.isEmpty()) {
            for (SpellAbility saMana : replaceAmount) {
                Card card = saMana.getHostCard();
                if (saMana.hasParam("ReplaceType")) {
                    // replace color and colorless
                    String color = saMana.getParam("ReplaceType");
                    if ("Any".equals(color)) {
                        byte rs = MagicColor.GREEN;
                        for (byte c : MagicColor.WUBRGC) {
                            if (toPay.canBePaidWithManaOfColor(c)) {
                                rs = c;
                                break;
                            }
                        }
                        color = MagicColor.toShortString(rs);
                    }
                    for (byte c : MagicColor.WUBRGC) {
                        String s = MagicColor.toShortString(c);
                        manaProduced = manaProduced.replace(s, color);
                    }
                } else if (saMana.hasParam("ReplaceColor")) {
                    String color = saMana.getParam("ReplaceColor");
                    if ("Chosen".equals(color)) {
                        if (card.hasChosenColor()) {
                            color = MagicColor.toShortString(card.getChosenColor());
                        }
                    }
                    if (saMana.hasParam("ReplaceOnly")) {
                        manaProduced = manaProduced.replace(saMana.getParam("ReplaceOnly"), color);
                    } else {
                        for (byte c : MagicColor.WUBRG) {
                            String s = MagicColor.toShortString(c);
                            manaProduced = manaProduced.replace(s, color);
                        }
                    }
                }
            }
        }

        // then multiply if able
        if (!replaceAmount.isEmpty()) {
            int totalAmount = 1;
            for (SpellAbility saMana : replaceAmount) {
                totalAmount *= Integer.parseInt(saMana.getParam("ReplaceAmount"));
            }
            manaProduced = StringUtils.repeat(manaProduced, " ", totalAmount);
        }

        return manaProduced;
    }

    public static String predictManafromSpellAbility(SpellAbility saPayment, Player ai, ManaCostShard toPay) {
        return predictManafromSpellAbility(saPayment, ai, toPay, null);
    }

    public static String predictManafromSpellAbility(SpellAbility saPayment, Player ai, ManaCostShard toPay,
            final ManaCostBeingPaid costHint) {
        Card hostCard = saPayment.getHostCard();

        StringBuilder manaProduced = new StringBuilder(predictManaReplacement(saPayment, ai, toPay));
        String originalProduced = manaProduced.toString();

        if (originalProduced.isEmpty()) {
            return originalProduced;
        }

        // Run triggers like Nissa
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(hostCard);
        runParams.put(AbilityKey.Activator, ai); // assuming AI would only ever gives itself mana
        runParams.put(AbilityKey.AbilityMana, saPayment);
        runParams.put(AbilityKey.Produced, originalProduced);
        for (Trigger tr : ai.getGame().getTriggerHandler().getActiveTrigger(TriggerType.TapsForMana, runParams)) {
            SpellAbility trSA = tr.ensureAbility();
            if (trSA == null) {
                continue;
            }
            if (ApiType.Mana.equals(trSA.getApi())) {
                final String bonus = predictTriggerManaProduction(trSA, ai, toPay, costHint, originalProduced);
                if (!bonus.isEmpty()) {
                    manaProduced.append(' ').append(bonus);
                }
            } else if (ApiType.ManaReflected.equals(trSA.getApi())) {
                final String colorOrType = trSA.getParamOrDefault("ColorOrType", "Color");
                // currently Color or Type, Type is colors + colorless
                final String reflectProperty = trSA.getParam("ReflectProperty");

                if (reflectProperty.equals("Produced") && !originalProduced.isEmpty()) {
                    // check if a colorless shard can be paid from the trigger
                    if (toPay.equals(ManaCostShard.COLORLESS) && colorOrType.equals("Type") && originalProduced.contains("C")) {
                        manaProduced.append(" " + "C");
                    } else if (originalProduced.length() == 1) {
                        // if length is only one, and it either is equal C == Type
                        if (colorOrType.equals("Type") || !originalProduced.equals("C")) {
                            manaProduced.append(" ").append(originalProduced);
                        }
                    } else {
                        // should it look for other shards too?
                        boolean found = false;
                        for (String s : originalProduced.split(" ")) {
                            if (colorOrType.equals("Type") || !s.equals("C") && toPay.canBePaidWithManaOfColor(MagicColor.fromName(s))) {
                                found = true;
                                manaProduced.append(" ").append(s);
                                break;
                            }
                        }
                        // no good mana found? just add the first generated color
                        if (!found) {
                            for (String s : originalProduced.split(" ")) {
                                if (colorOrType.equals("Type") || !s.equals("C")) {
                                    manaProduced.append(" ").append(s);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        return manaProduced.toString();
    }

    /**
     * Predict bonus mana from a {@link TriggerType#TapsForMana} {@code DB$ Mana} subability.
     * {@code landManaAlready} is what the tapped source produced before triggers (so combo/any
     * choices target only the remaining unpaid cost).
     */
    private static String predictTriggerManaProduction(final SpellAbility trSA, final Player ai,
            final ManaCostShard toPay, final ManaCostBeingPaid costHint, final String landManaAlready) {
        final AbilityManaPart mp = trSA.getManaPart();
        if (mp == null) {
            return "";
        }
        trSA.setActivatingPlayer(ai);
        final int pAmount = AbilityUtils.calculateAmount(trSA.getHostCard(),
                trSA.getParamOrDefault("Amount", "1"), trSA);
        final String producedParam = trSA.getParam("Produced");
        if (producedParam == null) {
            return "";
        }
        if ("Chosen".equals(producedParam)) {
            final String chosen = MagicColor.toShortString(trSA.getHostCard().getChosenColor());
            return StringUtils.repeat(chosen, " ", pAmount);
        }
        if (mp.isComboMana()) {
            return predictComboTriggerMana(trSA, ai, toPay, costHint, landManaAlready, pAmount);
        }
        if (mp.isAnyMana()) {
            return predictAnyTriggerMana(ai, toPay, costHint, landManaAlready, pAmount);
        }
        return StringUtils.repeat(producedParam, " ", pAmount);
    }

    private static String predictComboTriggerMana(final SpellAbility trSA, final Player ai,
            final ManaCostShard toPay, final ManaCostBeingPaid costHint, final String landManaAlready,
            final int pAmount) {
        if (costHint != null) {
            final ManaCostBeingPaid probe = new ManaCostBeingPaid(costHint);
            if (!StringUtils.isBlank(landManaAlready)) {
                payMultipleMana(probe, landManaAlready.trim(), ai);
            }
            if (!probe.isPaid()) {
                final String choice = buildComboManaChoiceString(ai, trSA, probe, pAmount,
                        requiresDifferentComboColors(trSA.getManaPart()));
                if (!StringUtils.isBlank(choice) && !"0".equals(choice)) {
                    return choice;
                }
            }
        }
        return predictComboTriggerManaForRegistration(trSA, toPay, pAmount);
    }

    /** When no cost context exists, expose every combo color so shard buckets stay accurate. */
    private static String predictComboTriggerManaForRegistration(final SpellAbility trSA,
            final ManaCostShard toPay, final int pAmount) {
        final AbilityManaPart mp = trSA.getManaPart();
        final String comboColors = mp.getComboColors(trSA);
        if (StringUtils.isBlank(comboColors)) {
            return "";
        }
        if (toPay != null && !toPay.isGeneric() && toPay != ManaCostShard.COLORLESS && !toPay.isPhyrexian()) {
            final String shardColor = toPay.toShortString();
            if (comboColors.contains(shardColor)) {
                return StringUtils.repeat(shardColor, " ", pAmount);
            }
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pAmount; i++) {
            for (final String color : comboColors.split(" ")) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(color);
            }
        }
        return sb.toString();
    }

    private static String predictAnyTriggerMana(final Player ai, final ManaCostShard toPay,
            final ManaCostBeingPaid costHint, final String landManaAlready, final int pAmount) {
        ManaCostBeingPaid probe = null;
        if (costHint != null) {
            probe = new ManaCostBeingPaid(costHint);
            if (!StringUtils.isBlank(landManaAlready)) {
                payMultipleMana(probe, landManaAlready.trim(), ai);
            }
        }
        final String color = MagicColor.toShortString(pickColorForAnyMana(ai, null, toPay, probe, null));
        return StringUtils.repeat(color, " ", pAmount);
    }

    /**
     * Pick a color for {@code Produced$ Any} (shard payment, trigger bonus, or dry-run probe).
     * Returns {@code 0} when no color can pay {@code toPay}.
     */
    private static byte pickColorForAnyMana(final Player ai, final SpellAbility saPaidFor,
            final ManaCostShard toPay, final ManaCostBeingPaid remainingCost, final Card sourceCard) {
        if (toPay != null && toPay.isOr2Generic()) {
            final byte c = toPay.getColorMask();
            if (canUseAnyManaColorForShard(ai, saPaidFor, sourceCard, toPay, c)) {
                return c;
            }
            return 0;
        }
        if (remainingCost != null) {
            for (final ManaCostShard shard : remainingCost.getDistinctShards()) {
                if (shard.isGeneric() || shard == ManaCostShard.COLORLESS || shard.isPhyrexian()) {
                    continue;
                }
                if (remainingCost.getUnpaidShards(shard) > 0) {
                    final byte c = ManaAtom.fromName(shard.toShortString());
                    final ManaCostShard probeShard = toPay != null ? toPay : shard;
                    if (canUseAnyManaColorForShard(ai, saPaidFor, sourceCard, probeShard, c)) {
                        return c;
                    }
                }
            }
        }
        if (toPay != null && !toPay.isGeneric() && toPay != ManaCostShard.COLORLESS && !toPay.isPhyrexian()) {
            final byte c = toPay.getColorMask();
            if (canUseAnyManaColorForShard(ai, saPaidFor, sourceCard, toPay, c)) {
                return c;
            }
        }
        for (final byte c : MagicColor.WUBRG) {
            if (toPay != null && canUseAnyManaColorForShard(ai, saPaidFor, sourceCard, toPay, c)) {
                return c;
            }
            if (toPay == null && (sourceCard == null || saPaidFor == null
                    || saPaidFor.allowsPayingWithShard(sourceCard, c))) {
                return c;
            }
        }
        final String prominent = ComputerUtilCard.getMostProminentColor(ai.getCardsIn(ZoneType.Hand));
        if (!StringUtils.isBlank(prominent)) {
            return MagicColor.fromName(MagicColor.toShortString(prominent));
        }
        return MagicColor.WHITE;
    }

    private static boolean canUseAnyManaColorForShard(final Player ai, final SpellAbility saPaidFor,
            final Card sourceCard, final ManaCostShard shard, final byte color) {
        if (sourceCard != null && saPaidFor != null
                && !saPaidFor.allowsPayingWithShard(sourceCard, color)) {
            return false;
        }
        return shard == null || shard.isGeneric() || shard == ManaCostShard.COLORLESS
                || ai.getManaPool().canPayForShardWithColor(shard, color);
    }

    /** Nested stranding probe from {@link ManaPaymentExecution}. */
    static boolean payManaCostNestedProbe(final ManaCostBeingPaid cost, final SpellAbility sa,
            final Player ai, final ManaPaymentContext ctx) {
        return payManaCost(cost, sa, ai, true, true, false, null, ctx.nestedWithFilterProbe()) != null;
    }

    /** Nested castability dry-run from {@link CastabilityProbe}. */
    static boolean payManaCostForCastabilityProbe(final forge.game.cost.Cost cost, final SpellAbility sa,
            final Player ai, final ManaPaymentContext ctx) {
        return payManaCost(cost, sa, ai, true, 0, true, false, ctx.nestedWithFilterProbe());
    }

    /** Test hook: reset castability nested dry-run counter. */
    public static void resetCastabilityProbeDryRunCountForTests() {
        CastabilityProbe.resetDryRunCountForTests();
    }

    /** Test hook: nested castability dry-runs since last reset. */
    public static int getCastabilityProbeDryRunCountForTests() {
        return CastabilityProbe.getDryRunCountForTests();
    }

    static String capComboManaProduced(final String manaProduced, final int maxMana) {
        if (manaProduced == null || manaProduced.isEmpty() || maxMana <= 0) {
            return manaProduced;
        }
        final String[] parts = TextUtil.split(manaProduced, ' ');
        if (parts.length <= maxMana) {
            return manaProduced;
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxMana; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static boolean requiresDifferentComboColors(final AbilityManaPart mp) {
        return mp != null && mp.getOrigProduced().contains("Different");
    }

    static String formatManaProducedForLog(final SpellAbility saPayment, final Player ai,
            final ManaCostShard toPay, final ManaCostBeingPaid cost) {
        String manaProduced = predictManafromSpellAbility(saPayment, ai, toPay, cost);
        if (ManaFilterConsolidation.isMultiManaComboAbility(saPayment)) {
            manaProduced = capComboManaProduced(manaProduced,
                    ManaFilterConsolidation.getComboManaAmount(saPayment));
        }
        return manaProduced;
    }

    // returns null if unpayable
    private static List<Mana> payManaCost(final ManaCostBeingPaid cost, final SpellAbility sa, final Player ai,
            final boolean test, boolean checkPlayable, boolean effect, final ManaPaymentContext ctx) {
        return payManaCost(cost, sa, ai, test, checkPlayable, effect, null, ctx);
    }

    private static List<Mana> payManaCost(final ManaCostBeingPaid cost, final SpellAbility sa, final Player ai,
            final boolean test, boolean checkPlayable, boolean effect, final CardCollection planOut,
            final ManaPaymentContext ctx) {
        if (ai == null || sa == null || cost == null || ctx == null) {
            return null;
        }
        if ((sa.isOffering() && sa.getSacrificedAsOffering() == null) || (sa.isEmerge() && sa.getSacrificedAsEmerge() == null)) {
            // nothing was chosen
            return null;
        }

        final ManaPaymentContext traceCtx = ctx.isOutermost() && ctx.costLabel == null
                ? ctx.withCostLabel(cost.toString()) : ctx;
        try {
            return payManaCostImpl(cost, sa, ai, test, checkPlayable, effect, planOut, traceCtx);
        } finally {
            traceCtx.finishIfOutermost(test, sa, cost.isPaid());
        }
    }

    private static List<Mana> payManaCostImpl(final ManaCostBeingPaid cost, final SpellAbility sa, final Player ai,
            final boolean test, boolean checkPlayable, boolean effect, final CardCollection planOut,
            final ManaPaymentContext ctx) {
        if (ai == null || sa == null || cost == null || ctx == null) {
            return null;
        }
        final boolean outermost = ctx.isOutermost();
        try {
            if (outermost && !test) {
                beginProductionPayment(cost);
            }
            return payManaCostImplBody(cost, sa, ai, test, checkPlayable, effect, planOut, ctx);
        } finally {
            if (outermost && !test) {
                endProductionPayment();
            }
        }
    }

    private static List<Mana> payManaCostImplBody(final ManaCostBeingPaid cost, final SpellAbility sa, final Player ai,
            final boolean test, boolean checkPlayable, boolean effect, final CardCollection planOut,
            final ManaPaymentContext ctx) {
        if (ai == null || sa == null || cost == null || ctx == null) {
            return null;
        }
        AiCardMemory.clearMemorySet(ai, MemorySet.PAYS_TAP_COST);
        AiCardMemory.clearMemorySet(ai, MemorySet.PAYS_SAC_COST);
        adjustManaCostToAvoidNegEffects(cost, sa.getHostCard(), ai);

        List<Mana> manaSpentToPay = test ? new ArrayList<>() : sa.getPayingMana();
        List<SpellAbility> paymentList = Lists.newArrayList();
        final ManaPool manapool = ai.getManaPool();

        // Snapshot before any pool spend so test rollback restores floating mana.
        // Nested castability/stranding probes must restore too — skipping left spent or refunded
        // mana in the real pool for the outer payment (phantom "pool pays").
        final List<Mana> poolSnapshotAtStart = test ? ManaPaymentExecution.snapshotPool(manapool) : null;

        if (ctx.isOutermost()) {
            ManaPaymentTracer.logMain(test, "paying " + cost + " for " + ManaPaymentTracer.manaPaymentSpellLabel(sa)
                    + (manapool.isEmpty() ? "" : " (floating: " + manapool + ")"), ctx);
        } else {
            ManaPaymentTracer.logMain(test, "paying " + cost + " for " + ManaPaymentTracer.manaPaymentSpellLabel(sa), ctx);
        }

        // Apply color/type conversion matrix if necessary (already done via autopay)
        if (ai.getControllingPlayer() == null) {
            manapool.restoreColorReplacements();
            CardPlayOption mayPlay = sa.getMayPlayOption();
            if (!effect) {
                if (sa.isSpell() && mayPlay != null) {
                    mayPlay.applyManaConvert(manapool);
                } else if (sa.isActivatedAbility() && sa.getGrantorStatic() != null && sa.getGrantorStatic().hasParam("ManaConversion")) {
                    AbilityUtils.applyManaColorConversion(manapool, sa.getGrantorStatic().getParam("ManaConversion"));
                }
            }
            if (sa.hasParam("ManaConversion")) {
                AbilityUtils.applyManaColorConversion(manapool, sa.getParam("ManaConversion"));
            }
            StaticAbilityManaConvert.manaConvert(manapool, ai, sa.getHostCard(), effect && !sa.isCastFromPlayEffect() ? null : sa);
        }

        // not worth checking if it makes sense to not spend floating first
        if (manapool.payManaCostFromPool(cost, sa, test, manaSpentToPay)) {
            ctx.recordStep(sa, test, "Pool pays (floating mana)");
            CostPayment.handleOfferings(sa, test, cost.isPaid());
            ManaPaymentTracer.logResult(test, true, "  result: PAID (pool)", ctx);
            if (test) {
                collectPlanSources(planOut, manaSpentToPay, paymentList);
                // payManaCostFromPool already refunds when fully paid in test mode
                if (poolSnapshotAtStart != null) {
                    ManaPaymentExecution.restorePool(ai, poolSnapshotAtStart);
                }
            }
            // paid all from floating mana
            return manaSpentToPay;
        }

        // Cheap BF estimate first — defer full mana-ability map until shard simulation is needed.
        if (!cost.containsOnlyPhyrexianMana()) {
            int need = cost.getConvertedManaCost();
            if (cost.containsPhyrexianMana()) {
                for (final ManaCostShard shard : cost.getDistinctShards()) {
                    if (shard.isPhyrexian()) {
                        need -= cost.getUnpaidShards(shard);
                    }
                }
            }
            final ManaAvailabilityEstimate estimate = estimateAvailableMana(ai, checkPlayable);

            // Quick-test early success: color table covers CMC + colored pips.
            if (test && !ctx.paymentPromptPreview && ctx.isOutermost() && estimate.canCover(cost, sa)) {
                ManaPaymentTracer.logResult(test, true, "  result: PAID (quick sufficiency) for "
                        + ManaPaymentTracer.manaPaymentSpellLabel(sa), ctx);
                manapool.refundMana(manaSpentToPay);
                if (poolSnapshotAtStart != null) {
                    ManaPaymentExecution.restorePool(ai, poolSnapshotAtStart);
                }
                return manaSpentToPay;
            }

            // Fail-fast when even optimistic total (map + TapsForMana) cannot cover CMC.
            // Cheap estimate undercounts ProduceMana/TapsForMana, so only after a short cheap
            // total (and no hand mana) do we build the map for the optimistic check.
            if (need > 0 && estimate.total < need && !handHasManaAbility(ai)) {
                final int maxAvailable = estimateMaxManaAvailable(ai, checkPlayable, ctx);
                if (maxAvailable < need) {
                    ManaPaymentTracer.logResult(test, false, "  result: FAILED (insufficient total mana "
                            + maxAvailable + "<" + need + ") for " + ManaPaymentTracer.manaPaymentSpellLabel(sa), ctx);
                    manapool.refundMana(manaSpentToPay);
                    if (test) {
                        if (poolSnapshotAtStart != null) {
                            ManaPaymentExecution.restorePool(ai, poolSnapshotAtStart);
                        }
                    } else {
                        sa.setSkip(true);
                    }
                    return null;
                }
            }
        }

        boolean purePhyrexian = cost.containsOnlyPhyrexianMana();
        boolean hasConverge = sa.getHostCard().hasConverge();
        ListMultimap<ManaCostShard, SpellAbility> sourcesForShards = getSourcesForShards(cost, sa, ai, test, checkPlayable, hasConverge, ctx);

        List<Mana> testDepositedSurplus = null;
        if (test) {
            testDepositedSurplus = ctx.testDepositedSurplus != null ? ctx.testDepositedSurplus : new ArrayList<>();
            ctx.testDepositedSurplus = testDepositedSurplus;
        }

        if (!ManaPaymentExecution.runPaymentLoop(cost, sa, ai, test, checkPlayable, effect, sourcesForShards,
                manaSpentToPay, testDepositedSurplus, paymentList, manapool, planOut, hasConverge, purePhyrexian, ctx)) {
            ManaCostShard toPay = ComputerUtilMana.getNextShardToPay(cost, sourcesForShards);
            ManaPaymentTracer.logResult(test, false, "  result: FAILED (unpaid " + toPay + ") for "
                    + ManaPaymentTracer.manaPaymentSpellLabel(sa), ctx);
            manapool.refundMana(manaSpentToPay);
            if (test) {
                ManaPaymentExecution.cleanupTestManaPayment(ai, manaSpentToPay, testDepositedSurplus);
                if (ctx.isOutermost()) {
                    ctx.testDepositedSurplus = null;
                }
                resetPayment(paymentList);
                if (poolSnapshotAtStart != null) {
                    ManaPaymentExecution.restorePool(ai, poolSnapshotAtStart);
                }
            } else {
                sa.setSkip(true);
            }
            return null;
        }

        CostPayment.handleOfferings(sa, test, cost.isPaid());

        ManaPaymentTracer.logResult(test, true, "  result: PAID", ctx);

        if (test) {
            collectPlanSources(planOut, manaSpentToPay, paymentList);
            // Do not refundMana(manaSpentToPay) blindly — surplus Mana objects would enter the real pool.
            ManaPaymentExecution.cleanupTestManaPayment(ai, manaSpentToPay, testDepositedSurplus);
            if (ctx.isOutermost()) {
                ctx.testDepositedSurplus = null;
            }
            resetPayment(paymentList);
            if (poolSnapshotAtStart != null) {
                ManaPaymentExecution.restorePool(ai, poolSnapshotAtStart);
            }
        }

        return manaSpentToPay;
    }

    private static void resetPayment(List<SpellAbility> payments) {
        for (SpellAbility sa : payments) {
            sa.getManaPart().clearExpressChoice();
        }
    }

    /**
     * Creates a mapping between the required mana shards and the available spell abilities to pay for them
     */
    private static ListMultimap<ManaCostShard, SpellAbility> getSourcesForShards(final ManaCostBeingPaid cost,
            final SpellAbility sa, final Player ai, final boolean test, final boolean checkPlayable,
            final boolean hasConverge, final ManaPaymentContext ctx) {
        // arrange all mana abilities by color produced.
        final ListMultimap<Integer, SpellAbility> manaAbilityMap = getOrBuildManaAbilityMap(ai, checkPlayable, ctx);
        ManaPaymentTracer.logMain(test, "  source colors: " + manaAbilityMap, ctx);
        if (manaAbilityMap.isEmpty()) {
            // no mana abilities, bailing out
            ManaPaymentTracer.logMain(test, "  no playable mana abilities found", ctx);
            return null;
        }

        // select which abilities may be used for each shard
        ListMultimap<ManaCostShard, SpellAbility> sourcesForShards = groupAndOrderToPayShards(ai, manaAbilityMap, cost);
        if (hasConverge) {
            // add extra colors for paying converge
            final int unpaidColors = cost.getUnpaidColors() + cost.getColorsPaid() ^ ManaCostShard.COLORS_SUPERPOSITION;
            for (final MagicColor.Color color : ColorSet.fromMask(unpaidColors)) {
                final byte b = color.getColorMask();
                final ManaCostShard shard = ManaCostShard.valueOf(b);
                if (!sourcesForShards.containsKey(shard)) {
                    if (ai.getManaPool().canPayForShardWithColor(shard, b)) {
                        for (SpellAbility saMana : manaAbilityMap.get((int)b)) {
                            sourcesForShards.get(shard).add(saMana);
                        }
                    }
                }
            }
        }

        ManaAbilitySort.sortManaAbilities(sourcesForShards, manaAbilityMap, sa, cost, ai, ctx,
                ManaPaymentExecution::canActivateFilter);
        ManaPaymentTracer.logMain(test, "  sources by shard: " + sourcesForShards, ctx);
        return sourcesForShards;
    }

    static void setComboManaChoice(final Player ai, final SpellAbility manaAb, final ManaCostBeingPaid cost) {
        final AbilityManaPart comboMana = manaAb.getManaPart();
        final int amount = manaAb.hasParam("Amount") ? AbilityUtils.calculateAmount(manaAb.getHostCard(), manaAb.getParam("Amount"), manaAb) : 1;
        final String expressHint = comboMana.getExpressChoice();
        comboMana.clearExpressChoice();
        final String preferredColor = expressHint != null && !expressHint.isEmpty() && !expressHint.contains(" ")
                ? expressHint : "";
        final String choices = buildComboManaChoiceString(ai, manaAb, cost, amount,
                requiresDifferentComboColors(comboMana), preferredColor);
        comboMana.setExpressChoice(choices);
    }

    static String buildComboManaChoiceString(final Player ai, final SpellAbility manaAb,
            final ManaCostBeingPaid cost, final int amount, final boolean different) {
        return buildComboManaChoiceString(ai, manaAb, cost, amount, different, "");
    }

    private static String buildComboManaChoiceString(final Player ai, final SpellAbility manaAb,
            final ManaCostBeingPaid cost, final int amount, final boolean different, final String preferredColor) {
        final StringBuilder choiceString = new StringBuilder();
        final AbilityManaPart comboMana = manaAb.getManaPart();
        final ManaCostBeingPaid testCost = new ManaCostBeingPaid(cost);
        final String[] comboColors = comboMana.getComboColors(manaAb).split(" ");
        ColorSet remainingOptions = ColorSet.fromNames(comboColors);

        for (int nMana = 1; nMana <= amount; nMana++) {
            String choice = "";
            if (nMana == 1 && !preferredColor.isEmpty()
                    && manaAb.canProduce(preferredColor)
                    && satisfiesColorChoice(comboMana, choiceString, preferredColor)
                    && colorAllowedForComboPick(preferredColor, different, remainingOptions)
                    && testCost.isAnyPartPayableWith(ManaAtom.fromName(preferredColor), ai.getManaPool())) {
                choice = preferredColor;
            }
            if (choice.isEmpty() && !testCost.isPaid()) {
                for (String color : comboColors) {
                    if (!colorAllowedForComboPick(color, different, remainingOptions)) {
                        continue;
                    }
                    if (satisfiesColorChoice(comboMana, choiceString, color)
                            && testCost.needsColor(ManaAtom.fromName(color), ai.getManaPool())) {
                        choice = color;
                        break;
                    }
                }
            }
            if (choice.isEmpty()) {
                String commonColor = ComputerUtilCard.getMostProminentColor(ai.getCardsIn(ZoneType.Hand));
                if (!commonColor.isEmpty()
                        && satisfiesColorChoice(comboMana, choiceString, MagicColor.toShortString(commonColor))
                        && colorAllowedForComboPick(MagicColor.toShortString(commonColor), different, remainingOptions)
                        && comboMana.getComboColors(manaAb).contains(MagicColor.toShortString(commonColor))) {
                    choice = MagicColor.toShortString(commonColor);
                } else {
                    for (String c : comboColors) {
                        if (!colorAllowedForComboPick(c, different, remainingOptions)) {
                            continue;
                        }
                        if (satisfiesColorChoice(comboMana, choiceString, c)) {
                            choice = c;
                            break;
                        }
                    }
                }
            }
            if (choice.isEmpty()) {
                break;
            }
            payMultipleMana(testCost, choice, ai);
            if (choiceString.length() > 0) {
                choiceString.append(' ');
            }
            choiceString.append(choice);
            if (different) {
                remainingOptions = ColorSet.fromMask(remainingOptions.getColor() - ManaAtom.fromName(choice));
            }
        }

        return choiceString.length() == 0 ? "0" : choiceString.toString();
    }

    private static boolean colorAllowedForComboPick(final String color, final boolean different,
            final ColorSet remainingOptions) {
        if (!different) {
            return true;
        }
        return (remainingOptions.getColor() & ManaAtom.fromName(color)) != 0;
    }

    private static boolean satisfiesColorChoice(AbilityManaPart abMana, StringBuilder choices, String choice) {
        return !abMana.getOrigProduced().contains("Different") || !choices.toString().contains(choice);
    }

    static boolean canPayShardWithSpellAbility(ManaCostShard toPay, Player ai, SpellAbility ma, SpellAbility sa, ManaCostBeingPaid cost, boolean checkCosts, Map<String, Integer> xManaCostPaidByColor) {
        final Card sourceCard = ma.getHostCard();

        if (isManaSourceReserved(ai, sourceCard)) {
            return false;
        }

        if (toPay.isSnow() && !sourceCard.isSnow()) {
            return false;
        }

        if (checkCosts) {
            ma.setActivatingPlayer(ai);
            if (ma.getPayCosts() != null && ma.getPayCosts().hasManaCost() && hasOnlyGenericManaCost(ma.getPayCosts())) {
                if (ma.getRestrictions() != null && ma.getRestrictions().isInstantSpeed()) {
                    return false;
                }
            } else if (!CostPayment.canPayAdditionalCosts(ma.getPayCosts(), ma, false)) {
                return false;
            } else if (ma.getRestrictions() != null && ma.getRestrictions().isInstantSpeed()) {
                return false;
            }
        }

        ma.setActivatingPlayer(ai);
        if (forEachActiveManaLink(ma, ai, sa, true, (root, tail, m) -> canPayShardWithActiveManaPart(toPay, ai,
                root, tail, sa, cost, xManaCostPaidByColor, m))) {
            return true;
        }

        return canPayShardViaTapsForManaBonus(toPay, ai, ma, sa, cost);
    }

    /** Match a shard against one conditional mana link (root or subAbility, e.g. Gemstone Caverns luck mode). */
    private static boolean canPayShardWithActiveManaPart(final ManaCostShard toPay, final Player ai,
            final SpellAbility ma, final SpellAbility tail, final SpellAbility sa, final ManaCostBeingPaid cost,
            final Map<String, Integer> xManaCostPaidByColor, final AbilityManaPart m) {
        final Card sourceCard = ma.getHostCard();

        if (m.isComboMana()) {
            for (String s : m.getComboColors(tail).split(" ")) {
                if (toPay == ManaCostShard.COLORED_X && !ManaCostBeingPaid.canColoredXShardBePaidByColor(s, xManaCostPaidByColor)) {
                    continue;
                }

                if (!sa.allowsPayingWithShard(sourceCard, ManaAtom.fromName(s))) {
                    continue;
                }

                if (ai.getManaPool().canPayForShardWithColor(toPay, ManaAtom.fromName(s))) {
                    final ColorSet shared = ColorSet.fromMask(toPay.getColorMask())
                            .getSharedColors(ColorSet.fromNames(m.getComboColors(tail).split(" ")));
                    if (!shared.isColorless()) {
                        m.setExpressChoice(shared.iterator().next().getShortName());
                    }
                    setComboManaChoice(ai, tail, cost);
                    return true;
                }
            }
            return false;
        }

        if (tail.getApi() == ApiType.ManaReflected) {
            final Set<String> reflected = CardUtil.getReflectableManaColors(tail);

            for (byte c : MagicColor.WUBRGC) {
                if (toPay == ManaCostShard.COLORED_X && !ManaCostBeingPaid.canColoredXShardBePaidByColor(MagicColor.toShortString(c), xManaCostPaidByColor)) {
                    continue;
                }

                if (!sa.allowsPayingWithShard(sourceCard, c)) {
                    continue;
                }

                if (ai.getManaPool().canPayForShardWithColor(toPay, c) && reflected.contains(MagicColor.toLongString(c))) {
                    m.setExpressChoice(MagicColor.toShortString(c));
                    return true;
                }
            }
            return false;
        }

        if (m.isAnyMana()) {
            final byte colorChoice = pickColorForAnyMana(ai, sa, toPay, cost, sourceCard);
            if (colorChoice == 0) {
                return false;
            }
            m.setExpressChoice(MagicColor.toShortString(colorChoice));
            return true;
        }

        final String[] producedColors = m.mana(tail).split(" ");
        final boolean multiColorProducer = producedColors.length > 1;
        if (multiColorProducer) {
            String payColor = null;
            for (String s : producedColors) {
                final byte c = MagicColor.fromName(s);
                if (c == 0 || !sa.allowsPayingWithShard(sourceCard, c)) {
                    continue;
                }
                if (toPay == ManaCostShard.COLORED_X) {
                    if (ManaCostBeingPaid.canColoredXShardBePaidByColor(s, xManaCostPaidByColor)) {
                        payColor = s;
                        break;
                    }
                } else if (ai.getManaPool().canPayForShardWithColor(toPay, c)) {
                    payColor = s;
                    break;
                }
            }
            if (payColor == null) {
                return false;
            }
            if (!toPay.isGeneric() && toPay != ManaCostShard.COLORED_X) {
                m.setExpressChoice(payColor);
            }
            return true;
        }

        if (producedColors.length == 0 || producedColors[0].isEmpty()) {
            return false;
        }

        if (!sa.allowsPayingWithShard(sourceCard, MagicColor.fromName(producedColors[0]))) {
            return false;
        }

        if (toPay == ManaCostShard.COLORED_X) {
            for (String s : producedColors) {
                if (ManaCostBeingPaid.canColoredXShardBePaidByColor(s, xManaCostPaidByColor)) {
                    return true;
                }
            }
            return false;
        }

        final byte producedColor = MagicColor.fromName(producedColors[0]);
        return ai.getManaPool().canPayForShardWithColor(toPay, producedColor);
    }

    /** When static card text is one color but TapsForMana adds others (Sprawl, Mana Flare). */
    private static boolean canPayShardViaTapsForManaBonus(final ManaCostShard toPay, final Player ai,
            final SpellAbility ma, final SpellAbility sa, final ManaCostBeingPaid cost) {
        if (ManaFilterConsolidation.hasManaActivationCost(ma)) {
            return false;
        }
        final AbilityManaPart rootMana = ma.getManaPart();
        if (rootMana == null || !ma.metConditions()) {
            return false;
        }
        final String rootManaProduced = rootMana.mana(ma);
        if (StringUtils.isBlank(rootManaProduced)) {
            return false;
        }
        final byte producedColor = MagicColor.fromName(rootManaProduced.split(" ")[0]);
        ma.setActivatingPlayer(ai);
        final String predicted = predictManafromSpellAbility(ma, ai, toPay, cost);
        if (StringUtils.isBlank(predicted)) {
            return false;
        }
        final Card sourceCard = ma.getHostCard();
        for (final String s : TextUtil.split(predicted.trim(), ' ')) {
            if (StringUtils.isNumeric(s) || "Any".equals(s)) {
                continue;
            }
            final byte c = MagicColor.fromName(MagicColor.toShortString(s));
            if (c == 0 || c == producedColor || !sa.allowsPayingWithShard(sourceCard, c)) {
                continue;
            }
            if (ai.getManaPool().canPayForShardWithColor(toPay, c)) {
                return true;
            }
        }
        return false;
    }

    // returns true if sourceCard is reserved as a mana source for payment
    // for the future spell to be cast in another phase. However, if the spell ability that is
    // being considered for casting is high priority, then mana source reservation will be ignored.
    private static boolean isManaSourceReserved(Player ai, Card sourceCard) {
        if (!(ai.getController() instanceof PlayerControllerAi)) {
            return false;
        }

        // reserved for spell synchronization
        if (AiCardMemory.isRememberedCard(ai, sourceCard, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL)) {
            return true;
        }

        PhaseType curPhase = ai.getGame().getPhaseHandler().getPhase();
        AiController aic = ((PlayerControllerAi)ai.getController()).getAi();

        // For combat tricks, always obey mana reservation
        if (curPhase == PhaseType.COMBAT_DECLARE_BLOCKERS || curPhase == PhaseType.CLEANUP) {
            if (ai.getGame().getPhaseHandler().isPlayerTurn(ai)) {
                AiCardMemory.clearMemorySet(ai, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_DECLBLK);
            } else {
                AiCardMemory.clearMemorySet(ai, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK);
                AiCardMemory.clearMemorySet(ai, AiCardMemory.MemorySet.CHOSEN_FOG_EFFECT);
            }
        } else if (AiCardMemory.isRememberedCard(ai, sourceCard, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_DECLBLK) ||
                AiCardMemory.isRememberedCard(ai, sourceCard, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK)) {
            // This mana source is held elsewhere for a combat trick.
            return true;
        }

        int chanceToReserve = aic.getIntProperty(AiProps.RESERVE_MANA_FOR_MAIN2_CHANCE);
        // TODO use Math.min(100 - AiAbilityDecision.rating(), chanceToReserve)
        if (chanceToReserve == 0 || !MyRandom.percentTrue(chanceToReserve)) {
            // using a reserved source might make rest of reservation pointless, but that's tricky to conclude
            return false;
        }

        if (curPhase == PhaseType.MAIN2 || curPhase == PhaseType.CLEANUP) {
            AiCardMemory.clearMemorySet(ai, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2);
        } else if (AiCardMemory.isRememberedCard(ai, sourceCard, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2)) {
            // mana source is held elsewhere for a Main 2 spell
            return true;
        }

        return false;
    }

    static ManaCostShard getNextShardToPay(ManaCostBeingPaid cost, Multimap<ManaCostShard, SpellAbility> sourcesForShards) {
        List<ManaCostShard> shardsToPay = Lists.newArrayList(cost.getDistinctShards());
        // optimize order so that the shards with less available sources are considered first
        if (sourcesForShards != null) {
            shardsToPay.sort(Comparator.comparingInt(shard -> sourcesForShards.get(shard).size()));
        }
        // mind the priorities
        // * Pay mono-colored first
        // * Pay 2/C with matching colors
        // * pay hybrids
        // * pay phyrexian, keep mana for colorless
        // * pay generic
        return cost.getShardToPayByPriority(shardsToPay, ColorSet.WUBRG.getColor());
    }

    private static void adjustManaCostToAvoidNegEffects(ManaCostBeingPaid cost, final Card card, Player ai) {
        // Make mana needed to avoid negative effect a mandatory cost for the AI
        for (String manaPart : card.getSVar("ManaNeededToAvoidNegativeEffect").split(",")) {
            // convert long color strings to short color strings
            if (manaPart.isEmpty()) {
                continue;
            }

            byte mask = ManaAtom.fromName(manaPart);

            // make mana mandatory for AI
            if (!cost.needsColor(mask, ai.getManaPool()) && cost.getGenericManaAmount() > 0) {
                ManaCostShard shard = ManaCostShard.valueOf(mask);
                cost.increaseShard(shard, 1);
                cost.decreaseGenericMana(1);
            }
        }
    }

    /**
     * <p>
     * payMultipleMana.
     * </p>
     * @param mana
     *            a {@link java.lang.String} object.
     * @return a boolean.
     */
    static String payMultipleMana(ManaCostBeingPaid testCost, String mana, final Player p) {
        List<String> unused = new ArrayList<>(4);
        for (String manaPart : TextUtil.split(mana, ' ')) {
            if (StringUtils.isNumeric(manaPart)) {
                for (int i = Integer.parseInt(manaPart); i > 0; i--) {
                    boolean wasNeeded = testCost.ai_payMana("1", p.getManaPool());
                    if (!wasNeeded) {
                        unused.add(Integer.toString(i));
                        break;
                    }
                }
            } else if ("Any".equals(manaPart)) {
                final byte anyColor = pickColorForAnyMana(p, null, null, testCost, null);
                if (anyColor == 0) {
                    unused.add("Any");
                } else {
                    final String color = MagicColor.toShortString(anyColor);
                    if (!testCost.ai_payMana(color, p.getManaPool())) {
                        unused.add(color);
                    }
                }
            } else {
                String color = MagicColor.toShortString(manaPart);
                boolean wasNeeded = testCost.ai_payMana(color, p.getManaPool());
                if (!wasNeeded) {
                    unused.add(color);
                }
            }
        }
        return unused.isEmpty() ? null : StringUtils.join(unused, ' ');
    }

    /**
     * Find all mana sources.
     * @param manaAbilityMap The map of SpellAbilities that produce mana.
     * @return Were all mana sources found?
     */
    static ListMultimap<ManaCostShard, SpellAbility> groupAndOrderToPayShards(final Player ai, final ListMultimap<Integer, SpellAbility> manaAbilityMap,
            final ManaCostBeingPaid cost) {
        ListMultimap<ManaCostShard, SpellAbility> res = ArrayListMultimap.create();
        final Map<ManaCostShard, Set<SpellAbility>> seenPerShard = new HashMap<>();

        if ((cost.getGenericManaAmount() > 0 || cost.hasAnyKind(ManaAtom.OR_2_GENERIC)) && manaAbilityMap.containsKey(ManaAtom.GENERIC)) {
            putShardAbilities(res, seenPerShard, ManaCostShard.GENERIC, manaAbilityMap.get(ManaAtom.GENERIC));
        }

        // loop over cost parts
        for (ManaCostShard shard : cost.getDistinctShards()) {
            if (shard == ManaCostShard.S) {
                putShardAbilities(res, seenPerShard, shard, manaAbilityMap.get(ManaAtom.IS_SNOW));
                continue;
            }

            if (shard.isOr2Generic()) {
                Integer colorKey = (int) shard.getColorMask();
                if (manaAbilityMap.containsKey(colorKey)) {
                    putShardAbilities(res, seenPerShard, shard, manaAbilityMap.get(colorKey));
                }
                if (manaAbilityMap.containsKey(ManaAtom.GENERIC)) {
                    putShardAbilities(res, seenPerShard, shard, manaAbilityMap.get(ManaAtom.GENERIC));
                }
                continue;
            }

            if (shard == ManaCostShard.GENERIC) {
                continue;
            }

            for (Integer colorint : manaAbilityMap.keySet()) {
                // apply mana color change matrix here
                if (ai.getManaPool().canPayForShardWithColor(shard, colorint.byteValue())) {
                    putShardAbilities(res, seenPerShard, shard, manaAbilityMap.get(colorint));
                }
            }
        }

        return res;
    }

    private static void putShardAbilities(final ListMultimap<ManaCostShard, SpellAbility> res,
            final Map<ManaCostShard, Set<SpellAbility>> seenPerShard, final ManaCostShard shard,
            final List<SpellAbility> abilities) {
        Set<SpellAbility> seen = seenPerShard.computeIfAbsent(shard, k -> new HashSet<>());
        for (SpellAbility sa : abilities) {
            if (seen.add(sa)) {
                res.put(shard, sa);
            }
        }
    }

    /**
     * Calculate the ManaCost for the given SpellAbility.
     * @param sa The SpellAbility to calculate for.
     * @param test test
     * @param extraMana extraMana
     * @return ManaCost
     */
    public static ManaCostBeingPaid calculateManaCost(final Cost cost, final SpellAbility sa, final Player payer, final boolean test, final int extraMana, final boolean effect) {
        Card host = sa.getHostCard();
        Zone castFromBackup = null;
        if (test && sa.isSpell() && !host.isInZone(ZoneType.Stack)) {
            castFromBackup = host.getCastFrom();
            host.setCastFrom(host.getZone() != null ? host.getZone() : null);
        }

        Cost payCosts;
        if (test) {
            payCosts = CostAdjustment.adjust(cost, sa, effect);
            // prevent asking Human when only predicting
            if (!payer.getController().isAI()) {
                sa.setMaxWaterbend(null);
            }
        } else {
            // when not testing CostPayment already handled raise
            payCosts = cost;
        }
        CostPartMana manapart = payCosts != null ? payCosts.getCostMana() : null;
        final ManaCost mana = payCosts != null ? ( manapart == null ? ManaCost.ZERO : manapart.getManaCostFor(sa) ) : ManaCost.NO_COST;

        ManaCostBeingPaid manaCost = new ManaCostBeingPaid(mana);

        // Tack xMana Payments into mana here if X is a set value
        if (manaCost.getXcounter() > 0 || extraMana > 0) {
            int manaToAdd = 0;
            int xCounter = manaCost.getXcounter();
            if (test && extraMana > 0) {
                final int multiplicator = Math.max(xCounter, 1);
                manaToAdd = extraMana * multiplicator;
            } else {
                manaToAdd = AbilityUtils.calculateAmount(host, sa.getParamOrDefault("XAlternative", "X"), sa) * xCounter;
            }

            if (manaToAdd < 1 && payCosts != null && payCosts.getCostMana().getXMin() > 0) {
                // AI cannot really handle X costs properly but this keeps AI from violating rules
                manaToAdd = 1;
            }

            String xColor = sa.getXColor();
            if (xColor == null) {
                xColor = "1";
            }
            if (host.hasKeyword("Spend only colored mana on X. No more than one mana of each color may be spent this way.")) {
                xColor = "WUBRGX";
            }
            if (xCounter > 0) {
                manaCost.setXManaCostPaid(manaToAdd / xCounter, xColor);
            } else {
                manaCost.increaseShard(ManaCostShard.parseNonGeneric(xColor), manaToAdd);
            }

            if (!test) {
                sa.setXManaCostPaid(manaToAdd / xCounter);
            }
        }

        CostAdjustment.adjust(manaCost, sa, payer, null, test, effect);

        if ("NumTimes".equals(sa.getParam("Announce"))) { // e.g. the Adversary cycle
            ManaCost mkCost = sa.getPayCosts().getTotalMana();
            ManaCost mCost = ManaCost.ZERO;
            for (int i = 0; i < 10; i++) {
                mCost = ManaCost.combine(mCost, mkCost);
                ManaCostBeingPaid mcbp = new ManaCostBeingPaid(mCost);
                if (!canPayManaCost(mcbp, sa, sa.getActivatingPlayer(), true)) {
                    host.setSVar("NumTimes", "Number$" + i);
                    break;
                }
            }
        }

        if (test && sa.isSpell() && !host.isInZone(ZoneType.Stack)) {
            host.setCastFrom(castFromBackup);
        }

        return manaCost;
    }

    // This method can be used to estimate the total amount of mana available to the player,
    // including the mana available in that player's mana pool
    public static int getAvailableManaEstimate(final Player p) {
        return getAvailableManaEstimate(p, true);
    }
    public static int getAvailableManaEstimate(final Player p, final boolean checkPlayable) {
        return estimateAvailableMana(p, checkPlayable).total;
    }

    /**
     * Per-color mana availability from a cheap battlefield scan (same loop as
     * {@link #getAvailableManaEstimate}). Dual/any sources add to each producible color bucket and
     * once to {@link ManaAvailabilityEstimate#total}; use {@link ManaAvailabilityEstimate#canCover}
     * so dual overcount cannot false-pay multicolor costs.
     */
    public static ManaAvailabilityEstimate estimateAvailableMana(final Player p) {
        return estimateAvailableMana(p, true);
    }

    public static ManaAvailabilityEstimate estimateAvailableMana(final Player p, final boolean checkPlayable) {
        final int[] colors = new int[5]; // WUBRG
        int colorless = 0;
        int total = 0;
        int producedWithCost = 0;
        boolean hasSourcesWithNoManaCost = false;

        final List<Card> srcs = CardLists.filter(p.getCardsIn(ZoneType.Battlefield), c -> !c.getManaAbilities().isEmpty());
        for (final Card src : srcs) {
            int maxProduced = 0;
            String bestProduced = "";
            int bestCost = 0;

            for (final SpellAbility ma : src.getManaAbilities()) {
                ma.setActivatingPlayer(p);
                if (checkPlayable && !ma.canPlay()) {
                    continue;
                }
                // CostPartMana.convertAmount() is the CostPart amount field (defaults to 1), not CMC.
                final int costsToActivate = ma.getPayCosts().getTotalMana().getCMC();
                // Use amountOfManaGenerated — naive Produced$ split counts "Combo R W" as 3 mana.
                final int producedTotal = ma.amountOfManaGenerated(true) - costsToActivate;
                final String produced = ma.getParamOrDefault("Produced", "");

                if (costsToActivate > 0) {
                    producedWithCost += Math.max(0, producedTotal);
                } else {
                    hasSourcesWithNoManaCost = true;
                }
                if (producedTotal > maxProduced) {
                    maxProduced = producedTotal;
                    bestProduced = produced;
                    bestCost = costsToActivate;
                }
            }

            if (maxProduced <= 0) {
                continue;
            }
            total += maxProduced;
            // Color buckets: only free activations (activation-costed filters inflate falsely).
            if (bestCost == 0) {
                addProducedToColorBuckets(colors, bestProduced, maxProduced);
                if (producesColorlessSymbol(bestProduced)) {
                    colorless += maxProduced;
                }
            }
        }

        final ManaPool pool = p.getManaPool();
        total += pool.totalMana();
        for (int i = 0; i < MagicColor.WUBRG.length; i++) {
            colors[i] += pool.getAmountOfColor(MagicColor.WUBRG[i]);
        }
        colorless += pool.getAmountOfColor((byte) ManaAtom.COLORLESS);

        if (producedWithCost > 0 && !hasSourcesWithNoManaCost) {
            total -= producedWithCost; // probably can't activate them, no other mana available
        }

        return new ManaAvailabilityEstimate(Math.max(0, total), colors, colorless);
    }

    private static void addProducedToColorBuckets(final int[] colors, final String produced, final int amount) {
        if (StringUtils.isBlank(produced) || amount <= 0) {
            return;
        }
        boolean any = false;
        final boolean[] hit = new boolean[5];
        for (final String part : produced.split(" ")) {
            if ("Any".equalsIgnoreCase(part) || part.startsWith("ComboAny")) {
                any = true;
                break;
            }
            if ("Combo".equals(part)) {
                continue;
            }
            for (int i = 0; i < MagicColor.WUBRG.length; i++) {
                if (MagicColor.toShortString(MagicColor.WUBRG[i]).equals(part)
                        || MagicColor.toLongString(MagicColor.WUBRG[i]).equalsIgnoreCase(part)) {
                    hit[i] = true;
                }
            }
        }
        // Combo lands: "Combo W U" → tokens Combo, W, U
        if (any) {
            for (int i = 0; i < 5; i++) {
                colors[i] += amount;
            }
            return;
        }
        for (int i = 0; i < 5; i++) {
            if (hit[i]) {
                colors[i] += amount;
            }
        }
    }

    private static boolean producesColorlessSymbol(final String produced) {
        if (StringUtils.isBlank(produced)) {
            return false;
        }
        for (final String part : produced.split(" ")) {
            if ("C".equals(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cheap mana availability table: total CMC capacity plus per-color / colorless pip capacity.
     */
    public static final class ManaAvailabilityEstimate {
        public final int total;
        /** WUBRG pip capacity (dual/any sources counted in each producible color). */
        public final int[] colors;
        public final int colorless;

        ManaAvailabilityEstimate(final int total, final int[] colors, final int colorless) {
            this.total = total;
            this.colors = colors;
            this.colorless = colorless;
        }

        /**
         * True when this estimate clearly covers {@code cost}: each mono colored/{@code C} pip
         * fits its bucket and total CMC fits {@link #total}. Dual overcount is blocked by the
         * total check. Returns false for complex costs (hybrid, snow, Phyrexian, Converge).
         */
        public boolean canCover(final ManaCostBeingPaid cost, final SpellAbility sa) {
            if (cost == null || costTooComplexForQuickSufficiency(cost, sa)) {
                return false;
            }
            int needGeneric = cost.getGenericManaAmount();
            int needColorless = cost.getUnpaidShards(ManaCostShard.COLORLESS);
            int needColored = 0;
            for (final ManaCostShard shard : cost.getDistinctShards()) {
                if (!shard.isMonoColor() || shard.isPhyrexian() || shard.isOr2Generic()) {
                    continue;
                }
                final int need = cost.getUnpaidShards(shard);
                if (need <= 0) {
                    continue;
                }
                final byte mask = shard.getColorMask();
                boolean matched = false;
                for (int i = 0; i < MagicColor.WUBRG.length; i++) {
                    if (mask == MagicColor.WUBRG[i]) {
                        if (need > colors[i]) {
                            return false;
                        }
                        needColored += need;
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    return false;
                }
            }
            if (needColorless > colorless) {
                return false;
            }
            return needColored + needColorless + needGeneric <= total;
        }
    }

    /** Optimistic upper bound including hand sources (map already filtered playable). */
    static int estimateMaxManaAvailable(final Player ai, final boolean checkPlayable,
            final ManaPaymentContext ctx) {
        int available = ai.getManaPool().totalMana();
        final Map<Card, Integer> maxByHost = new HashMap<>();
        for (final SpellAbility ma : getOrBuildUniqueManaAbilities(ai, checkPlayable, ctx)) {
            final Card host = ma.getHostCard();
            if (host == null) {
                continue;
            }
            maxByHost.merge(host, optimisticManaFromAbility(ma, ai), Math::max);
        }
        for (final int produced : maxByHost.values()) {
            available += produced;
        }
        return available;
    }

    /** Max of declared generation and predicted tap output (includes TapsForMana triggers). */
    private static int optimisticManaFromAbility(final SpellAbility ma, final Player ai) {
        int fromAmount = ma.amountOfManaGenerated(true);
        final String predicted = predictManafromSpellAbility(ma, ai, ManaCostShard.GENERIC);
        int fromPredicted = 0;
        if (StringUtils.isNotBlank(predicted)) {
            for (final String part : TextUtil.split(predicted.trim(), ' ')) {
                if (StringUtils.isNotBlank(part)) {
                    fromPredicted++;
                }
            }
        }
        return Math.max(fromAmount, fromPredicted);
    }

    private static boolean handHasManaAbility(final Player ai) {
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (c != null && !c.getManaAbilities().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean costTooComplexForQuickSufficiency(final ManaCostBeingPaid cost, final SpellAbility sa) {
        if (sa != null && sa.getHostCard() != null && sa.getHostCard().hasConverge()) {
            return true;
        }
        if (cost.containsPhyrexianMana()) {
            return true;
        }
        for (final ManaCostShard shard : cost.getDistinctShards()) {
            if (cost.getUnpaidShards(shard) <= 0) {
                continue;
            }
            if (shard.isSnow() || shard.isOr2Generic() || shard.isPhyrexian()) {
                return true;
            }
            if (!shard.isGeneric() && shard != ManaCostShard.COLORLESS && !shard.isMonoColor()) {
                return true; // hybrid
            }
        }
        return false;
    }

    public static CardCollection getAvailableManaSources(final Player ai, final boolean checkPlayable) {
        return getAvailableManaSources(ai, checkPlayable, null);
    }

    private static CardCollection getAvailableManaSources(final Player ai, final boolean checkPlayable,
            final ManaPaymentContext ctx) {
        final CardCollectionView list = CardCollection.combine(ai.getCardsIn(ZoneType.Battlefield), ai.getCardsIn(ZoneType.Hand));
        final List<Card> manaSources = CardLists.filter(list, c -> {
            for (final SpellAbility am : getAIPlayableMana(c, ctx)) {
                am.setActivatingPlayer(ai);
                if (!checkPlayable || (am.canPlay() && am.checkRestrictions(ai))) {
                    return true;
                }
            }
            return false;
        });

        final CardCollection sortedManaSources = new CardCollection();
        if (manaSources.isEmpty()) {
            return sortedManaSources;
        }

        final CardCollection otherManaSources = new CardCollection();
        final CardCollection useLastManaSources = new CardCollection();
        final CardCollection colorlessManaSources = new CardCollection();
        final CardCollection oneManaSources = new CardCollection();
        final CardCollection twoManaSources = new CardCollection();
        final CardCollection threeManaSources = new CardCollection();
        final CardCollection fourManaSources = new CardCollection();
        final CardCollection fiveManaSources = new CardCollection();
        final CardCollection anyColorManaSources = new CardCollection();

        // Sort mana sources
        // 1. Use lands that can only produce colorless mana without
        // drawback/cost first
        // 2. Search for mana sources that have a certain number of abilities
        // 3. Use lands that produce any color many
        // 4. all other sources (creature, costs, drawback, etc.)

        final boolean canDieToTapDamage = ai.canLoseLife() && !ai.cantLoseForZeroOrLessLife();
        for (Card card : manaSources) {
            // exclude creature sources that will tap as a part of an attack declaration
            if (card.isCreature()) {
                if (card.getGame().getPhaseHandler().is(PhaseType.COMBAT_DECLARE_ATTACKERS, ai)) {
                    Combat combat = card.getGame().getCombat();
                    if (combat.getAttackers().indexOf(card) != -1 && !card.hasKeyword(Keyword.VIGILANCE)) {
                        continue;
                    }
                }
            }
            // exclude cards that will deal lethal damage when tapped
            if (canDieToTapDamage) {
                boolean dealsLethalOnTap = false;
                for (Trigger t : card.getTriggers()) {
                    if (t.getMode() == TriggerType.Taps || t.getMode() == TriggerType.TapsForMana) {
                        SpellAbility trigSa = t.getOverridingAbility();
                        if (trigSa.getApi() == ApiType.DealDamage && trigSa.getParamOrDefault("Defined", "").equals("You")) {
                            int numDamage = AbilityUtils.calculateAmount(card, trigSa.getParam("NumDmg"), null);
                            numDamage = ai.staticReplaceDamage(numDamage, card, false);
                            if (ai.getLife() <= numDamage) {
                                dealsLethalOnTap = true;
                                break;
                            }
                        }
                    }
                }
                if (dealsLethalOnTap) {
                    continue;
                }
            }

            if (card.isCreature() || card.isEnchanted()) {
                otherManaSources.add(card);
                continue; // don't use creatures before other permanents
            }

            int usableManaAbilities = 0;
            boolean needsLimitedResources = false;
            boolean unpreferredCost = false;
            boolean producesAnyColor = false;
            final List<SpellAbility> manaAbilities = getAIPlayableMana(card, ctx);

            for (final SpellAbility m : manaAbilities) {
                if (m.getManaPart().isAnyMana()) {
                    producesAnyColor = true;
                }

                final Cost cost = m.getPayCosts();

                if (cost != null) {
                    // if the AI can't pay the additional costs skip the mana ability
                    m.setActivatingPlayer(ai);
                    if (!CostPayment.canPayAdditionalCosts(m.getPayCosts(), m, false)) {
                        continue;
                    }

                    if (!cost.isReusuableResource()) {
                        for (CostPart part : cost.getCostParts()) {
                            if (part instanceof CostSacrifice && !part.payCostFromSource()) {
                                unpreferredCost = true;
                            }
                        }
                        needsLimitedResources = !unpreferredCost;
                    }
                }

                AbilitySub sub = m.getSubAbility();
                // We really shouldn't be hardcoding names here. ChkDrawback should just return true for them
                if (sub != null && !card.getName().equals("Pristine Talisman") && !card.getName().equals("Zhur-Taa Druid")) {
                    if (!SpellApiToAi.Converter.get(sub).chkDrawbackWithSubs(ai, sub).willingToPlay()) {
                        continue;
                    }
                    needsLimitedResources = true; // TODO: check for good drawbacks (gainLife)
                }
                usableManaAbilities++;
            }

            if (unpreferredCost) {
                useLastManaSources.add(card);
            } else if (needsLimitedResources) {
                otherManaSources.add(card);
            } else if (producesAnyColor) {
                anyColorManaSources.add(card);
            } else if (usableManaAbilities == 1) {
                if (manaAbilities.get(0).getManaPart().mana(manaAbilities.get(0)).equals("C")) {
                    colorlessManaSources.add(card);
                } else {
                    oneManaSources.add(card);
                }
            } else if (usableManaAbilities == 2) {
                twoManaSources.add(card);
            } else if (usableManaAbilities == 3) {
                threeManaSources.add(card);
            } else if (usableManaAbilities == 4) {
                fourManaSources.add(card);
            } else {
                fiveManaSources.add(card);
            }
        }
        sortedManaSources.addAll(sortedManaSources.size(), colorlessManaSources);
        sortedManaSources.addAll(sortedManaSources.size(), oneManaSources);
        sortedManaSources.addAll(sortedManaSources.size(), twoManaSources);
        sortedManaSources.addAll(sortedManaSources.size(), threeManaSources);
        sortedManaSources.addAll(sortedManaSources.size(), fourManaSources);
        sortedManaSources.addAll(sortedManaSources.size(), fiveManaSources);
        sortedManaSources.addAll(sortedManaSources.size(), anyColorManaSources);
        //use better creatures later
        ComputerUtilCard.sortByEvaluateCreature(otherManaSources);
        Collections.reverse(otherManaSources);
        sortedManaSources.addAll(sortedManaSources.size(), otherManaSources);
        // This should be things like sacrifice other stuff.
        ComputerUtilCard.sortByEvaluateCreature(useLastManaSources);
        Collections.reverse(useLastManaSources);
        sortedManaSources.addAll(sortedManaSources.size(), useLastManaSources);

        return sortedManaSources;
    }

    static ListMultimap<Integer, SpellAbility> getOrBuildManaAbilityMap(final Player ai,
            final boolean checkPlayable, final ManaPaymentContext ctx) {
        if (ctx == null) {
            return groupSourcesByManaColor(ai, checkPlayable, null, null);
        }
        getOrBuildUniqueManaAbilities(ai, checkPlayable, ctx);
        return ctx.caches.manaAbilityMap;
    }

    /**
     * Unique mana abilities (one per ability, not per color bucket). Builds and caches the color
     * multimap as a side effect when {@code ctx} is non-null.
     */
    static List<SpellAbility> getOrBuildUniqueManaAbilities(final Player ai, final boolean checkPlayable,
            final ManaPaymentContext ctx) {
        if (ctx == null) {
            final List<SpellAbility> unique = new ArrayList<>();
            groupSourcesByManaColor(ai, checkPlayable, null, unique);
            return unique;
        }
        final long fp = paymentPlanReservationFingerprint(ai);
        if (ctx.caches.manaAbilityMap != null && ctx.caches.uniqueManaAbilities != null
                && ctx.caches.manaAbilityMapKey != null && ctx.caches.manaAbilityMapKey == fp) {
            return ctx.caches.uniqueManaAbilities;
        }
        final List<SpellAbility> unique = new ArrayList<>();
        final ListMultimap<Integer, SpellAbility> map = groupSourcesByManaColor(ai, checkPlayable, ctx, unique);
        ctx.caches.manaAbilityMap = map;
        ctx.caches.uniqueManaAbilities = unique;
        ctx.caches.manaAbilityMapKey = fp;
        return unique;
    }

    /** Fingerprint reserved / tapped mana sources so nested activation dry-runs can be cached per plan. */
    private static long paymentPlanReservationFingerprint(final Player ai) {
        long key = fingerprintMemorySet(ai, MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
        key = key * 31 + fingerprintMemorySet(ai, MemorySet.PAYS_TAP_COST);
        key = key * 31 + fingerprintMemorySet(ai, MemorySet.PAYS_SAC_COST);
        return key;
    }

    private static long fingerprintMemorySet(final Player ai, final MemorySet set) {
        final Set<Card> cards = AiCardMemory.getMemorySet(ai, set);
        if (cards == null || cards.isEmpty()) {
            return 0;
        }
        long fp = 1;
        for (final Card c : cards) {
            fp = fp * 31 + c.getId();
        }
        return fp;
    }

    @FunctionalInterface
    private interface ActiveManaLinkVisitor {
        /** @return {@code true} to stop iterating */
        boolean visit(SpellAbility root, SpellAbility tail, AbilityManaPart mp);
    }

    /**
     * Walk root -> subAbility mana links whose conditions are met. Optionally skips links that fail
     * {@link AbilityManaPart#meetsManaRestrictions(SpellAbility)} when paying a specific spell.
     */
    private static boolean forEachActiveManaLink(final SpellAbility root, final Player ai,
            final SpellAbility saPaidFor, final boolean checkManaRestrictions,
            final ActiveManaLinkVisitor visitor) {
        root.setActivatingPlayer(ai);
        for (SpellAbility tail = root; tail != null; tail = tail.getSubAbility()) {
            final AbilityManaPart mp = tail.getManaPart();
            if (mp == null || !tail.metConditions()) {
                continue;
            }
            if (checkManaRestrictions && saPaidFor != null && !mp.meetsManaRestrictions(saPaidFor)) {
                continue;
            }
            if (visitor.visit(root, tail, mp)) {
                return true;
            }
        }
        return false;
    }

    private static ListMultimap<Integer, SpellAbility> groupSourcesByManaColor(final Player ai, boolean checkPlayable,
            final ManaPaymentContext ctx, final List<SpellAbility> uniqueOut) {
        final ListMultimap<Integer, SpellAbility> manaMap = ArrayListMultimap.create();
        final Game game = ai.getGame();

        for (final Card sourceCard : getAvailableManaSources(ai, checkPlayable, ctx)) {
            for (final SpellAbility m : getAIPlayableMana(sourceCard, ctx)) {
                m.setActivatingPlayer(ai);
                // Per-ability canPlay: card may be included because a sibling ability is playable.
                if (checkPlayable && !m.canPlay()) {
                    continue;
                }

                // don't kill yourself
                final Cost abCost = m.getPayCosts();
                if (!ComputerUtilCost.checkLifeCost(ai, abCost, sourceCard, 1, m)) {
                    continue;
                }

                // don't use abilities with dangerous drawbacks
                // TODO this has already been checked earlier
                AbilitySub sub = m.getSubAbility();
                if (sub != null && !SpellApiToAi.Converter.get(sub).chkDrawbackWithSubs(ai, sub).willingToPlay()) {
                    continue;
                }

                manaMap.put(ManaAtom.GENERIC, m);
                if (uniqueOut != null) {
                    uniqueOut.add(m);
                }

                forEachActiveManaLink(m, ai, null, false,
                        (root, tail, mp) -> {
                            registerColorsForActiveManaLink(root, tail, mp, sourceCard, ai, game, manaMap);
                            return false;
                        });

                if (m.getHostCard().isSnow()) {
                    manaMap.put(ManaAtom.IS_SNOW, m);
                }
                registerTapsForManaBonusColors(m, ai, manaMap);
            } // end of mana abilities loop
        } // end of mana sources loop

        return manaMap;
    }

    /** Register shard buckets for one active mana link (root or conditional subAbility). */
    private static void registerColorsForActiveManaLink(final SpellAbility root, final SpellAbility tail,
            final AbilityManaPart mp, final Card sourceCard, final Player ai, final Game game,
            final ListMultimap<Integer, SpellAbility> manaMap) {
        // TODO Replacement Check currently doesn't work for reflected colors
        String origin = mp.getOrigProduced();
        final Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(sourceCard);
        repParams.put(AbilityKey.Mana, origin);
        repParams.put(AbilityKey.Activator, ai);
        repParams.put(AbilityKey.AbilityMana, root);

        List<ReplacementEffect> reList = game.getReplacementHandler().getReplacementList(ReplacementType.ProduceMana,
                repParams, ReplacementLayer.Other);

        if (reList.isEmpty()) {
            Set<String> reflectedColors = CardUtil.getReflectableManaColors(root);
            for (MagicColor.Color color : MagicColor.Color.values()) {
                if (mp.canProduce(color.getShortName(), tail) || reflectedColors.contains(color.getName())) {
                    manaMap.put((int) ManaAtom.fromName(color.getName()), root);
                }
            }
        } else {
            for (ReplacementEffect re : reList) {
                SpellAbility o = re.getOverridingAbility();
                String replaced = origin;
                if (o == null || o.getApi() != ApiType.ReplaceMana) {
                    continue;
                }
                if (o.hasParam("ReplaceMana")) {
                    replaced = o.getParam("ReplaceMana");
                } else if (o.hasParam("ReplaceType")) {
                    String color = o.getParam("ReplaceType");
                    for (byte c : MagicColor.WUBRGC) {
                        String s = MagicColor.toShortString(c);
                        replaced = replaced.replace(s, color);
                    }
                } else if (o.hasParam("ReplaceColor")) {
                    String color = o.getParam("ReplaceColor");
                    if (o.hasParam("ReplaceOnly")) {
                        replaced = replaced.replace(o.getParam("ReplaceOnly"), color);
                    } else {
                        for (byte c : MagicColor.WUBRG) {
                            String s = MagicColor.toShortString(c);
                            replaced = replaced.replace(s, color);
                        }
                    }
                }

                for (byte color : MagicColor.WUBRG) {
                    if ("Any".equals(replaced) || replaced.contains(MagicColor.toShortString(color))) {
                        manaMap.put((int) color, root);
                    }
                }

                if (replaced.contains("C")) {
                    manaMap.put(ManaAtom.COLORLESS, root);
                }
            }
        }
    }

    /**
     * Register colors a source can produce when tapped, including {@link TriggerType#TapsForMana} bonuses
     * (Utopia Sprawl, Mana Flare, etc.) so shard buckets and feasibility probes match tap-time output.
     */
    private static void registerTapsForManaBonusColors(final SpellAbility ma, final Player ai,
            final ListMultimap<Integer, SpellAbility> manaMap) {
        if (ma == null || ManaFilterConsolidation.hasManaActivationCost(ma)
                || ManaFilterConsolidation.isDisposableManaAbility(ma)) {
            return;
        }
        ma.setActivatingPlayer(ai);
        final String produced = predictManafromSpellAbility(ma, ai, ManaCostShard.GENERIC);
        if (StringUtils.isBlank(produced)) {
            return;
        }
        for (final String manaPart : TextUtil.split(produced.trim(), ' ')) {
            if (StringUtils.isNumeric(manaPart)) {
                manaMap.put(ManaAtom.GENERIC, ma);
            } else {
                final byte atom = ManaAtom.fromName(MagicColor.toShortString(manaPart));
                if (atom != 0) {
                    manaMap.put((int) atom, ma);
                }
            }
        }
    }

    /**
     * <p>
     * determineLeftoverMana.
     * </p>
     *
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param player
     *            a {@link forge.game.player.Player} object.
     * @return a int.
     * @since 1.0.15
     */
    public static int determineLeftoverMana(final SpellAbility sa, final Player player, final boolean effect) {
        int max = 99;
        if (sa.hasParam("XMax")) {
            max = Math.min(max, AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("XMax"), sa));
        }
        if (sa.hasParam("AIXMax")) {
            // when maximum depends on X calculate once before to avoid running more expensive checks for higher limit
            sa.setXManaCostPaid(max);
            max = Math.min(max, AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("AIXMax"), sa));
        }
        for (int i = 1; i <= max; i++) {
            if (!canPayManaCost(sa.getRootAbility(), player, i, effect)) {
                return i - 1;
            }
        }
        return max;
    }

    /**
     * <p>
     * determineLeftoverMana.
     * </p>
     *
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param player
     *            a {@link forge.game.player.Player} object.
     * @param shardColor
     *            a mana shard to specifically test for.
     * @return a int.
     * @since 1.5.59
     */
    public static int determineLeftoverMana(final SpellAbility sa, final Player player, final String shardColor, final boolean effect) {
        ManaCost origCost = sa.getRootAbility().getPayCosts().getTotalMana();

        String shardSurplus = shardColor;
        for (int i = 1; i < 100; i++) {
            ManaCost extra = new ManaCost(shardSurplus);
            if (!canPayManaCost(new ManaCostBeingPaid(ManaCost.combine(origCost, extra)), sa, player, effect)) {
                return i - 1;
            }
            shardSurplus += " " + shardColor;
        }
        return 99;
    }

    // Returns basic mana abilities plus "reflected mana" abilities
    /**
     * <p>
     * getAIPlayableMana.
     * </p>
     *
     * @return a {@link java.util.List} object.
     */
    public static List<SpellAbility> getAIPlayableMana(Card c) {
        return buildAIPlayableMana(c);
    }

    static List<SpellAbility> getAIPlayableMana(final Card c, final ManaPaymentContext ctx) {
        if (ctx == null) {
            return buildAIPlayableMana(c);
        }
        final Map<Card, List<SpellAbility>> cache = ctx.caches.playableManaCache;
        final List<SpellAbility> cached = cache.get(c);
        if (cached != null) {
            return cached;
        }
        final List<SpellAbility> res = buildAIPlayableMana(c);
        cache.put(c, res);
        return res;
    }

    private static List<SpellAbility> buildAIPlayableMana(Card c) {
        final List<SpellAbility> res = new ArrayList<>();
        for (final SpellAbility a : c.getManaAbilities()) {
            // if there is a parent ability the AI can't use it
            if (a.getApi() != ApiType.Mana && a.getApi() != ApiType.ManaReflected) {
                continue;
            }

            final Cost cost = a.getPayCosts();
            // Generic ({1}) and hybrid-only ({U/R}) activation costs are supported via nested payment
            // planning. Single-colored, X, and phyrexian activation costs are still excluded.
            if (cost != null && cost.hasManaCost() && !hasPlannableManaActivationCost(cost)) {
                continue;
            }

            if (a.getRestrictions() != null && a.getRestrictions().isInstantSpeed()) {
                continue;
            }

            if (!res.contains(a)) {
                if (cost == null || cost.isReusuableResource()) {
                    res.add(0, a);
                } else {
                    res.add(res.size(), a);
                }
            }
        }
        return res;
    }

    // True when the ability's mana cost is only generic mana (e.g. {1}, {2}) with no colored, hybrid, or X pips.
    private static boolean hasOnlyGenericManaCost(final Cost cost) {
        final CostPartMana manaCost = cost.getCostMana();
        if (manaCost == null) {
            return true;
        }
        final ManaCost mc = manaCost.getMana();
        return mc.getColorProfile() == 0 && mc.countX() == 0;
    }

    /**
     * True when nested payment planning can pay this ability's mana activation cost: generic-only
     * (signets, Skycloud Expanse) or hybrid-only (Cascade Bluffs, Flooded Grove).
     */
    private static boolean hasPlannableManaActivationCost(final Cost cost) {
        if (hasOnlyGenericManaCost(cost)) {
            return true;
        }
        final CostPartMana costMana = cost.getCostMana();
        if (costMana == null) {
            return true;
        }
        final ManaCost mc = costMana.getMana();
        if (mc.countX() > 0 || mc.getGenericCost() > 0) {
            return false;
        }
        boolean hasHybridShard = false;
        for (final ManaCostShard shard : mc) {
            hasHybridShard = true;
            if (shard.isPhyrexian() || shard.isOr2Generic() || shard.isMonoColor() || !shard.isMultiColor()) {
                return false;
            }
        }
        return hasHybridShard;
    }

    /**
     * Matches list of creatures to shards in mana cost for convoking.
     *
     * @param cost      cost of convoked ability
     * @param list      creatures to be evaluated
     * @param artifacts
     * @param creatures
     * @return map between creatures and shards to convoke
     */
    public static Map<Card, ManaCostShard> getConvokeOrImproviseFromList(final ManaCost cost, List<Card> list, boolean artifacts, boolean creatures) {
        final Map<Card, ManaCostShard> convoke = new HashMap<>();
        Card convoked = null;
        if (creatures && !artifacts) {
            // Run for convoke but not improvise or waterbending
            for (ManaCostShard toPay : cost) {
                if (toPay.isSnow() || toPay.isColorless()) {
                    continue;
                }
                for (Card c : list) {
                    final int mask = c.getColor().getColor() & toPay.getColorMask();
                    if (mask != 0) {
                        convoked = c;
                        convoke.put(c, toPay);
                        break;
                    }
                }
                if (convoked != null) {
                    list.remove(convoked);
                }
                convoked = null;
            }
        }
        for (int i = 0; i < list.size() && i < cost.getGenericCost(); i++) {
            convoke.put(list.get(i), ManaCostShard.GENERIC);
        }
        return convoke;
    }
}
