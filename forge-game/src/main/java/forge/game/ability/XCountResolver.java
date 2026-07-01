package forge.game.ability;

import com.google.common.collect.*;
import com.google.common.math.IntMath;
import forge.card.CardType;
import forge.card.CardTypeView;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.CardTraitBase;
import forge.game.Game;
import forge.game.card.*;
import forge.game.keyword.Keyword;
import forge.game.mana.Mana;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.*;
import forge.game.TriggerReplacementBase;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.CostPaymentStack;
import forge.game.zone.ZoneType;
import forge.util.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves Count$ expressions for ability calculations.
 * Extracted from {@link AbilityUtils#xCount}.
 */
class XCountResolver {

    static final class XCountContext {
        Card c;
        CardTraitBase ctb;
        String expr;
        String[] l;
        String[] sq;
        String[] paidparts;
        Player player;
        Game game;
        Iterable<Card> someCards;

        static XCountContext parse(Card c, String s, CardTraitBase ctb) {
            XCountContext ctx = new XCountContext();
            ctx.c = c;
            ctx.ctb = ctb;
            final String s2 = AbilityUtils.applyAbilityTextChangeEffects(s, ctb);
            ctx.l = s2.split("/");
            ctx.expr = CardFactoryUtil.extractOperators(s2);
            ctx.game = c.getGame();

            if (ctb != null) {
                if (ctb instanceof SpellAbility) {
                    ctx.player = ((SpellAbility) ctb).getActivatingPlayer();
                }
                if (ctx.player == null) {
                    ctx.player = ctb.getHostCard().getController();
                }
            }
            return ctx;
        }

        int apply(int value) {
            return AbilityUtils.doXMath(value, expr, c, ctb);
        }

        int branch(boolean condition) {
            return apply(AbilityUtils.calculateAmount(c, sq[condition ? 1 : 2], ctb));
        }
    }

    static OptionalInt tryNumberAndSVar(XCountContext ctx) {
        // accept straight numbers
        if (ctx.l[0].startsWith("Number$")) {
            final String number = ctx.l[0].substring(7);
            return OptionalInt.of(ctx.apply(Integer.parseInt(number)));
        }

        if (ctx.l[0].startsWith("Count$")) {
            ctx.l[0] = ctx.l[0].substring(6);
        }

        if (ctx.l[0].startsWith("SVar$")) {
            String n = ctx.l[0].substring(5);
            String v = ctx.ctb == null ? ctx.c.getSVar(n) : ctx.ctb.getSVar(n);
            return OptionalInt.of(ctx.apply(resolve(ctx.c, v, ctx.ctb)));
        }

        ctx.sq = ctx.l[0].split("\\.");
        ctx.paidparts = ctx.l[0].split("\\$", 2);
        ctx.someCards = null;
        return OptionalInt.empty();
    }

    static int tryResolveMain(XCountContext ctx) {
        OptionalInt result;
        result = trySpellAbilityCounts(ctx);
        if (result.isPresent()) {
            return result.getAsInt();
        }

        result = trySharedCounts(ctx);
        if (result.isPresent()) {
            return result.getAsInt();
        }

        result = tryCardCounts(ctx);
        if (result.isPresent()) {
            return result.getAsInt();
        }

        result = tryPlayerCounts(ctx);
        if (result.isPresent()) {
            return result.getAsInt();
        }
        
        result = tryGameCounts(ctx);
        if (result.isPresent()) {
            return result.getAsInt();
        }

        return tryFallback(ctx);
    }

    static OptionalInt trySpellAbilityCounts(XCountContext ctx) {
        if (ctx.ctb != null) {
            // Count$Compare <int comparator value>.<True>.<False>
            if (ctx.sq[0].startsWith("Compare")) {
                final String[] compString = ctx.sq[0].split(" ");
                final int lhs = AbilityUtils.calculateAmount(ctx.c, compString[1], ctx.ctb);
                final int rhs =  AbilityUtils.calculateAmount(ctx.c, compString[2].substring(2), ctx.ctb);
                boolean v = Expressions.compare(lhs, compString[2], rhs);
                return OptionalInt.of(ctx.branch(v));
            }

            // Count$IsPrime <SVar>.<True>.<False>
            if (ctx.sq[0].startsWith("IsPrime")) {
                final String[] compString = ctx.sq[0].split(" ");
                final int lhs = AbilityUtils.calculateAmount(ctx.c, compString[1], ctx.ctb);
                boolean v = IntMath.isPrime(lhs);
                return OptionalInt.of(ctx.branch(v));
            }

            SpellAbility sa = null;
            if (ctx.ctb instanceof SpellAbility) {
                sa = (SpellAbility) ctx.ctb;
            } else if (ctx.sq[0].contains("xPaid") && ctx.ctb instanceof TriggerReplacementBase) {
                // try avoid fallback
                sa = ((TriggerReplacementBase) ctx.ctb).getOverridingAbility();
            }

            if (sa != null) {
                // special logic for xPaid in SpellAbility
                if (ctx.sq[0].contains("xPaid")) {
                    SpellAbility root = sa.getRootAbility();

                    // 107.3i If an object gains an ability, the value of X within that ability is the value defined by that ability,
                    // or 0 if that ability doesn't define a value of X. This is an exception to rule 107.3h. This may occur with ability-adding effects, text-changing effects, or copy effects.
                    if (root.getXManaCostPaid() != null) {
                        return OptionalInt.of(ctx.apply(root.getXManaCostPaid()));
                    }

                    // If the chosen creature has X in its mana cost, that X is considered to be 0.
                    // The value of X in Altered Ego’s last ability will be whatever value was chosen for X while casting Altered Ego.
                    if (sa.isCopiedTrait() && !sa.getHostCard().equals(ctx.c)) {
                        return OptionalInt.of(ctx.apply(0));
                    }

                    if (root.isTrigger()) {
                        Trigger t = root.getTrigger();

                        // ImmediateTrigger should check for the Ability which created the trigger
                        if (t.getSpawningAbility() != null) {
                            root = t.getSpawningAbility().getRootAbility();
                            return OptionalInt.of(ctx.apply(root.getXManaCostPaid() == null ? 0 : root.getXManaCostPaid()));
                        }

                        // 107.3k If an object’s enters-the-battlefield triggered ability or replacement effect refers to X,
                        // and the spell that became that object as it resolved had a value of X chosen for any of its costs,
                        // the value of X for that ability is the same as the value of X for that spell, although the value of X for that permanent is 0.
                        if (TriggerType.ChangesZone.equals(t.getMode()) && ZoneType.Battlefield.name().equals(t.getParam("Destination"))) {
                           int x = AbilityUtils.isUnlinkedFromCastSA(ctx.ctb, ctx.c) ? 0 : ctx.c.getXManaCostPaid();
                           return OptionalInt.of(ctx.apply(x));
                        } else if (TriggerType.SpellCast.equals(t.getMode())) {
                            // Cast Trigger like Hydroid Krasis
                            SpellAbility castSA = (SpellAbility) root.getTriggeringObject(AbilityKey.SpellAbility);
                            if (castSA == null || castSA.getXManaCostPaid() == null) {
                                return OptionalInt.of(ctx.apply(0));
                            }
                            return OptionalInt.of(ctx.apply(castSA.getXManaCostPaid()));
                        } else if (TriggerType.Cycled.equals(t.getMode())) {
                            SpellAbility cycleSA = (SpellAbility) sa.getTriggeringObject(AbilityKey.Cause);
                            if (cycleSA == null || cycleSA.getXManaCostPaid() == null) {
                                return OptionalInt.of(ctx.apply(0));
                            }
                            return OptionalInt.of(ctx.apply(cycleSA.getXManaCostPaid()));
                        } else if (TriggerType.TurnFaceUp.equals(t.getMode())) {
                            SpellAbility turnupSA = (SpellAbility) sa.getTriggeringObject(AbilityKey.Cause);
                            if (turnupSA == null || turnupSA.getXManaCostPaid() == null) {
                                return OptionalInt.of(ctx.apply(0));
                            }
                            return OptionalInt.of(ctx.apply(turnupSA.getXManaCostPaid()));
                        }
                    }

                    if (root.isReplacementAbility() && sa.hasParam("ETB")) {
                        int x = AbilityUtils.isUnlinkedFromCastSA(ctx.ctb, ctx.c) ? 0 : ctx.c.getXManaCostPaid();
                        return OptionalInt.of(ctx.apply(x));
                    }

                    return OptionalInt.of(ctx.apply(0));
                }

                // Count$Kicked.<numHB>.<numNotHB>
                if (ctx.sq[0].startsWith("Kicked")) {
                    boolean kicked = sa.isKicked() || (!AbilityUtils.isUnlinkedFromCastSA(ctx.ctb, ctx.c) && ctx.c.getKickerMagnitude() > 0);
                    return OptionalInt.of(ctx.branch(kicked));
                }

                if (ctx.sq[0].startsWith("Teamwork")) {
                    return OptionalInt.of(ctx.branch(sa.isOptionalCostPaid(OptionalCost.Teamwork)));
                }

                if (ctx.sq[0].startsWith("OptionalGenericCostPaid")) {
                    return OptionalInt.of(ctx.branch(sa.isOptionalCostPaid(OptionalCost.Generic)));
                }

                if (ctx.sq[0].startsWith("Bargain")) {
                    return OptionalInt.of(ctx.branch(sa.isBargained()));
                }

                if (ctx.sq[0].startsWith("Freerunning")) {
                    return OptionalInt.of(ctx.branch(sa.isFreerunning()));
                }

                // Count$Madness.<True>.<False>
                if (ctx.sq[0].startsWith("Madness")) {
                    return OptionalInt.of(ctx.branch(sa.isMadness()));
                }

                //Count$HasNumChosenColors.<DefinedCards related to spellability>
                if (ctx.sq[0].contains("HasNumChosenColors")) {
                    int sum = 0;
                    for (Card card : AbilityUtils.getDefinedCards(ctx.c, ctx.sq[1], sa)) {
                        sum += card.getColor().getSharedColors(ColorSet.fromNames(ctx.c.getChosenColors())).countColors();
                    }
                    return OptionalInt.of(sum);
                }

                if (ctx.sq[0].startsWith("TriggerRememberAmount")) {
                    int count = 0;
                    for (final Object o : sa.getTriggerRemembered()) {
                        if (o instanceof Integer) {
                            count += (Integer) o;
                        }
                    }
                    return OptionalInt.of(count);
                }

                // Count$TriggeredManaCostDevotion.<Color>
                if (ctx.sq[0].startsWith("TriggeredManaCostDevotion")) {
                    final SpellAbility root = sa.getRootAbility();
                    Card triggeringObject = (Card) root.getTriggeringObject(AbilityKey.Card);
                    int count = 0;
                    byte colorCode = ManaAtom.fromName(ctx.sq[1]);
                    for (ManaCostShard sh : triggeringObject.getManaCost()) {
                        if (sh.isColor(colorCode)) {
                            count++;
                        }
                    }
                    return OptionalInt.of(count);
                }

                // Count$TriggeredPayingMana.<Color1>.<Color2>
                if (ctx.sq[0].startsWith("TriggeredPayingMana")) {
                    final SpellAbility root = sa.getRootAbility();
                    String mana = (String) root.getTriggeringObject(AbilityKey.PayingMana);
                    int count = 0;
                    Matcher mat = Pattern.compile(StringUtils.join(ctx.sq, "|", 1, ctx.sq.length)).matcher(mana);
                    while (mat.find()) {
                        count++;
                    }
                    return OptionalInt.of(count);
                }

                // Count$ManaProduced
                if (ctx.sq[0].startsWith("AmountManaProduced")) {
                    final SpellAbility root = sa.getRootAbility();
                    int amount = 0;
                    if (root != null) {
                        for (AbilityManaPart amp : root.getAllManaParts()) {
                            amount = amount + amp.getLastManaProduced().size();
                        }
                    }
                    return OptionalInt.of(ctx.apply(amount));
                }

                // Count$NumTimesChoseMode
                if (ctx.sq[0].startsWith("NumTimesChoseMode")) {
                    int amount = 0;
                    SpellAbility tail = sa.getTailAbility();
                    if (tail.hasSVar("CharmOrder")) {
                        amount = tail.getSVarInt("CharmOrder");
                    }
                    return OptionalInt.of(ctx.apply(amount));
                }

                // Count$ManaColorsPaid
                if (ctx.sq[0].equals("ManaColorsPaid")) {
                    final SpellAbility root = sa.getRootAbility();
                    return OptionalInt.of(ctx.apply(root == null ? 0 : root.getPayingColors().countColors()));
                }

                // Count$Adamant.<Color>.<True>.<False>
                if (ctx.sq[0].startsWith("Adamant")) {
                    final String payingMana = StringUtils.join(sa.getRootAbility().getPayingMana());
                    final int num = ctx.sq[0].length() > 7 ? Integer.parseInt(ctx.sq[0].split("_")[1]) : 3;
                    final boolean adamant = StringUtils.countMatches(payingMana, MagicColor.toShortString(ctx.sq[1])) >= num;
                    return OptionalInt.of(ctx.apply(AbilityUtils.calculateAmount(ctx.c,ctx.sq[adamant ? 2 : 3], ctx.ctb)));
                }

                if (ctx.sq[0].startsWith("LastStateBattlefield")) {
                    final String[] k = ctx.paidparts[0].split(" ");
                    // this is only for spells that were cast
                    if (ctx.sq[0].contains("WithFallback")) {
                        if (!sa.getHostCard().wasCast()) {
                            return OptionalInt.of(ctx.apply(0));
                        }
                        ctx.someCards = sa.getHostCard().getCastSA().getLastStateBattlefield();
                    } else {
                        ctx.someCards = sa.getLastStateBattlefield();
                    }

                    if (ctx.someCards == null || Iterables.isEmpty(ctx.someCards)) {
                        // LastState is Empty
                        if (ctx.sq[0].contains("WithFallback")) {
                            ctx.someCards = ctx.game.getCardsIn(ZoneType.Battlefield);
                        } else {
                            return OptionalInt.of(ctx.apply(0));
                        }
                    }
                    ctx.someCards = CardLists.getValidCards(ctx.someCards, k[1], ctx.player, ctx.c, sa);
                }

                if (ctx.sq[0].startsWith("LastStateGraveyard")) {
                    final String[] k = ctx.l[0].split(" ");
                    CardCollectionView list;
                    // this is only for spells that were cast
                    if (ctx.sq[0].contains("WithFallback")) {
                        if (!sa.getHostCard().wasCast()) {
                            return OptionalInt.of(ctx.apply(0));
                        }
                        list = sa.getHostCard().getCastSA().getLastStateGraveyard();
                    } else {
                        list = sa.getLastStateGraveyard();
                    }
                    
                    if (sa.getLastStateGraveyard() == null || list.isEmpty()) {
                        // LastState is Empty
                        if (ctx.sq[0].contains("WithFallback")) {
                            list = ctx.game.getCardsIn(ZoneType.Graveyard);
                        } else {
                            return OptionalInt.of(ctx.apply(0));
                        }
                    }
                    list = CardLists.getValidCards(list, k[1], ctx.player, ctx.c, sa);
                    return OptionalInt.of(ctx.apply(list.size()));
                }

                if (ctx.sq[0].equals("ActivatedThisGame")) {
                    return OptionalInt.of(ctx.apply(sa.getActivationsThisGame()));
                }

                if (ctx.sq[0].equals("ResolvedThisTurn")) {
                    return OptionalInt.of(ctx.apply(sa.getResolvedThisTurn()));
                }

                if (ctx.sq[0].startsWith("TotalManaSpent ")) {
                    if (sa.getRootAbility().getPayingMana() == null) {
                        return OptionalInt.of(ctx.apply(0));
                    }
                    final String[] k = ctx.sq[0].split(" ");
                    int v = (int) sa.getRootAbility().getPayingMana().stream().map(Mana::getSourceCard)
                            .filter(Predicate.<Card>not(Objects::isNull).and(CardPredicates.restriction(k[1].split(","), ctx.player, ctx.c, ctx.ctb)))
                            .count();
                    return OptionalInt.of(ctx.apply(v));
                }

                // Count$FromNamedAbility[abilityName].<True>.<False>
                if (ctx.sq[0].startsWith("FromNamedAbility")) {
                    String abilityNamed = ctx.sq[0].substring(16);
                    SpellAbility trigSA = sa.getHostCard().getCastSA();
                    boolean fromNamedAbility = trigSA != null && trigSA.getName().equals(abilityNamed);
                    return OptionalInt.of(ctx.branch(fromNamedAbility));
                }
            } else {
                // fallback if ctx.ctb isn't a spellability
                if (ctx.sq[0].startsWith("LastStateBattlefield")) {
                    final String[] k = ctx.l[0].split(" ");
                    CardCollectionView list = ctx.game.getLastStateBattlefield();
                    list = CardLists.getValidCards(list, k[1], ctx.player, ctx.c, ctx.ctb);
                    return OptionalInt.of(ctx.apply(list.size()));
                }

                if (ctx.sq[0].startsWith("LastStateGraveyard")) {
                    final String[] k = ctx.l[0].split(" ");
                    CardCollectionView list = ctx.game.getLastStateGraveyard();
                    list = CardLists.getValidCards(list, k[1], ctx.player, ctx.c, ctx.ctb);
                    return OptionalInt.of(ctx.apply(list.size()));
                }

                if (ctx.sq[0].startsWith("xPaid")) {
                    return OptionalInt.of(ctx.apply(ctx.c.getXManaCostPaid()));
                }

            } // end SpellAbility

            if (ctx.sq[0].equals("CastTotalManaSpent")) {
                return OptionalInt.of(ctx.apply(ctx.c.getCastSA() != null ? ctx.c.getCastSA().getTotalManaSpent() : 0));
            }
            if (ctx.sq[0].startsWith("CastTotalManaSpent ")) {
                final String[] k = ctx.sq[0].split(" ");
                if (ctx.c.getCastSA() == null) {
                    return OptionalInt.of(ctx.apply(0));
                }
                int v = (int) ctx.c.getCastSA().getPayingMana().stream().map(Mana::getSourceCard)
                        .filter(Predicate.<Card>not(Objects::isNull).and(CardPredicates.restriction(k[1].split(","), ctx.player, ctx.c, ctx.ctb)))
                        .count();
                return OptionalInt.of(ctx.apply(v));
            }

            if (ctx.sq[0].equals("hasOptionalKeywordAmount")) {
                return OptionalInt.of(ctx.apply(ctx.c.getCastSA() != null && ctx.c.getCastSA().hasOptionalKeywordAmount(ctx.ctb.getKeyword()) ? 1 : 0));
            }
            if (ctx.sq[0].equals("OptionalKeywordAmount")) {
                return OptionalInt.of(ctx.apply(ctx.c.getCastSA() != null ? ctx.c.getCastSA().getOptionalKeywordAmount(ctx.ctb.getKeyword()) : 0));
            }

            // Count$DevotionDual.<color name>.<color name>
            // Count$Devotion.<color name>
            if (ctx.sq[0].contains("Devotion")) {
                int colorOccurrences = 0;
                String colorName = ctx.sq[1];
                if (colorName.contains("Chosen")) {
                    colorName = MagicColor.toShortString(ctx.c.getChosenColor());
                }
                byte colorCode = ManaAtom.fromName(colorName);
                if (ctx.sq[0].equals("DevotionDual")) {
                    colorCode |= ManaAtom.fromName(ctx.sq[2]);
                }
                for (Card c0 : ctx.player.getCardsIn(ZoneType.Battlefield)) {
                    for (ManaCostShard sh : c0.getManaCost()) {
                        if (sh.isColor(colorCode)) {
                            colorOccurrences++;
                        }
                    }
                }
                colorOccurrences += ctx.player.getDevotionMod();
                return OptionalInt.of(ctx.apply(colorOccurrences));
            }
        } // end ctx.ctb != null
        return OptionalInt.empty();
    }

    static OptionalInt trySharedCounts(XCountContext ctx) {

        //Count$SearchedLibrary.<DefinedPlayer>
        if (ctx.sq[0].contains("SearchedLibrary")) {
            int sum = 0;
            for (Player p : AbilityUtils.getDefinedPlayers(ctx.c, ctx.sq[1], ctx.ctb)) {
                sum += p.getLibrarySearched();
            }
            return OptionalInt.of(ctx.apply(sum));
        }

        // count valid cards in any specified zone/s
        if (ctx.sq[0].startsWith("Valid")) {
            String[] lparts = ctx.paidparts[0].split(" ", 2);

            CardCollectionView cardsInZones = null;
            if (lparts[0].contains("All")) {
                cardsInZones = ctx.game.getCardsInGame();
            } else if (lparts[0].endsWith("Self")) {
                cardsInZones = new CardCollection(ctx.c);
            } else {
                final List<ZoneType> zones = ZoneType.listValueOf(lparts[0].length() > 5 ? lparts[0].substring(5) : "Battlefield");
                boolean usedLastState = false;
                if (ctx.ctb instanceof SpellAbility && zones.size() == 1) {
                    SpellAbility sa = (SpellAbility) ctx.ctb;
                    if (sa.isReplacementAbility()) {
                        if (zones.get(0).equals(ZoneType.Battlefield)) {
                            cardsInZones = sa.getRootAbility().getLastStateBattlefield();
                            usedLastState = true;
                        } else if (zones.get(0).equals(ZoneType.Graveyard)) {
                            cardsInZones = sa.getRootAbility().getLastStateGraveyard();
                            usedLastState = true;
                        }
                    }
                }
                if (!usedLastState) {
                    cardsInZones = ctx.game.getCardsIn(zones);
                }
            }

            ctx.someCards = CardLists.getValidCards(cardsInZones, lparts[1], ctx.player, ctx.c, ctx.ctb);
        }

        if (ctx.sq[0].startsWith("RememberedSize")) {
            return OptionalInt.of(ctx.apply(ctx.c.getRememberedCount()));
        }
        if (ctx.sq[0].startsWith("ChosenSize")) {
            return OptionalInt.of(ctx.apply(ctx.c.getChosenCards().size()));
        }
        if (ctx.sq[0].startsWith("ImprintedSize")) {
            return OptionalInt.of(ctx.apply(ctx.c.getImprintedCards().size()));
        }

        if (ctx.sq[0].startsWith("RememberedNumber")) {
            int num = 0;
            for (final Object o : ctx.c.getRemembered()) {
                if (o instanceof Integer) {
                    num += (Integer) o;
                }
            }
            return OptionalInt.of(ctx.apply(num));
        }

        if (ctx.sq[0].startsWith("RememberedWithSharedCardType")) {
            int maxNum = 1;
            for (final Object o : ctx.c.getRemembered()) {
                if (o instanceof Card) {
                    int num = 1;
                    Card firstCard = (Card) o;
                    for (final Object p : ctx.c.getRemembered()) {
                        if (p instanceof Card) {
                            Card secondCard = (Card) p;
                            if (!firstCard.equals(secondCard) && firstCard.sharesCardTypeWith(secondCard)) {
                                num++;
                            }
                        }
                    }
                    if (num > maxNum) {
                        maxNum = num;
                    }
                }
            }
            return OptionalInt.of(ctx.apply(maxNum));
        }

        // might get called from editor
        if (ctx.game != null) {
            // CR 608.2h
            // we'll want to avoid grabbing LKI for params that can handle internal information
            // e.g. the remembering on Xenagos, the Reveler
            ctx.c = ctx.game.getChangeZoneLKIInfo(ctx.c);
        }

        ////////////////////
        return OptionalInt.empty();
    }

    static OptionalInt tryCardCounts(XCountContext ctx) {
        // card info

        // Count$CardMulticolor.<numMC>.<numNotMC>
        if (ctx.sq[0].contains("CardMulticolor")) {
            final boolean isMulti = ctx.c.getColor().isMulticolor();
            return OptionalInt.of(ctx.apply(Integer.parseInt(ctx.sq[isMulti ? 1 : 2])));
        }

        if (ctx.sq[0].equals("ColorsColorIdentity")) {
            return OptionalInt.of(ctx.apply(ctx.c.getController().getCommanderColorID().countColors()));
        }

        // Count$Foretold.<True>.<False>
        if (ctx.sq[0].startsWith("Foretold")) {
            return OptionalInt.of(ctx.branch(ctx.c.isForetold()));
        }

        if (ctx.sq[0].startsWith("Kicked")) { // fallback for not spellAbility
            return OptionalInt.of(ctx.branch(!AbilityUtils.isUnlinkedFromCastSA(ctx.ctb, ctx.c) && ctx.c.getKickerMagnitude() > 0));
        }
        if (ctx.sq[0].startsWith("PromisedGift")) {
            return OptionalInt.of(ctx.branch(ctx.c.getCastSA() != null && ctx.c.getCastSA().isGiftPromised()));
        }
        if (ctx.sq[0].startsWith("Teamwork")) {
            return OptionalInt.of(ctx.branch(ctx.c.getCastSA() != null && ctx.c.getCastSA().isTeamwork()));
        }
        if (ctx.sq[0].startsWith("Escaped")) {
            return OptionalInt.of(ctx.branch(ctx.c.getCastSA() != null && ctx.c.getCastSA().isEscape()));
        }
        if (ctx.sq[0].startsWith("Emerged")) {
            return OptionalInt.of(ctx.branch(!AbilityUtils.isUnlinkedFromCastSA(ctx.ctb, ctx.c) && ctx.c.getCastSA() != null && ctx.c.getCastSA().isEmerge()));
        }
        if (ctx.sq[0].startsWith("AltCost")) {
            return OptionalInt.of(ctx.branch(ctx.c.isOptionalCostPaid(OptionalCost.AltCost)));
        }

        if (ctx.sq[0].equals("CardPower")) {
            return OptionalInt.of(ctx.apply(ctx.c.getNetPower()));
        }
        if (ctx.sq[0].equals("CardBasePower")) {
            return OptionalInt.of(ctx.apply(ctx.c.getCurrentPower()));
        }
        if (ctx.sq[0].equals("CardToughness")) {
            return OptionalInt.of(ctx.apply(ctx.c.getNetToughness()));
        }
        if (ctx.sq[0].equals("CardBaseToughness")) {
            return OptionalInt.of(ctx.apply(ctx.c.getCurrentToughness()));
        }
        if (ctx.sq[0].equals("CardSumPT")) {
            return OptionalInt.of(ctx.apply(ctx.c.getNetPower() + ctx.c.getNetToughness()));
        }

        if (ctx.sq[0].equals("CardNumNotedTypes")) {
            return OptionalInt.of(ctx.apply(ctx.c.getNumNotedTypes()));
        }

        if (ctx.sq[0].equals("CardNumColors")) {
            return OptionalInt.of(ctx.apply(ctx.c.getColor().countColors()));
        }

        if (ctx.sq[0].equals("CardNumAttacksThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.c.getDamageHistory().getCreatureAttacksThisTurn()));
        }
        if (ctx.sq[0].equals("CardNumAttacksThisGame")) {
            return OptionalInt.of(ctx.apply(ctx.c.getDamageHistory().getAttacksThisGame()));
        }

        if (ctx.sq[0].equals("CrewSize")) {
            return OptionalInt.of(ctx.apply(ctx.c.getCrewedByThisTurn() == null ? 0 : ctx.c.getCrewedByThisTurn().size()));
        }

        if (ctx.sq[0].equals("Intensity")) {
            return OptionalInt.of(ctx.apply(ctx.c.getIntensity(true)));
        }

        if (ctx.sq[0].startsWith("CardCounters")) {
            // CardCounters.ALL to be used for Kinsbaile Borderguard and anything that cares about all counters
            int count = 0;
            if (ctx.sq[1].equals("ALL")) count = ctx.c.getNumAllCounters();
            else count = ctx.c.getCounters(CounterType.getType(ctx.sq[1]));
            return OptionalInt.of(ctx.apply(count));
        }

        if (ctx.sq[0].contains("TotalValue")) {
            return OptionalInt.of(ctx.apply(ctx.c.getKeywordMagnitude(Keyword.smartValueOf(ctx.l[0].split(" ")[1]))));
        }
        if (ctx.sq[0].contains("TimesKicked")) {
            return OptionalInt.of(ctx.apply(AbilityUtils.isUnlinkedFromCastSA(ctx.ctb, ctx.c) ? 0 : ctx.c.getKickerMagnitude()));
        }
        if (ctx.sq[0].contains("TimesMutated")) {
            return OptionalInt.of(ctx.apply(ctx.c.getTimesMutated()));
        }

        if (ctx.sq[0].equals("RegeneratedThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.c.getRegeneratedThisTurn()));
        }

        if (ctx.sq[0].contains("Converge")) {
            SpellAbility castSA = ctx.c.getCastSA();
            return OptionalInt.of(ctx.apply(castSA == null ? 0 : castSA.getPayingColors().countColors()));
        }

        if (ctx.sq[0].startsWith("EachPhyrexianPaidWithLife")) {
            SpellAbility castSA = ctx.c.getCastSA();
            if (castSA == null) {
                return OptionalInt.of(0);
            }
            return OptionalInt.of(ctx.apply(castSA.getSpendPhyrexianMana()));
        }

        if (ctx.sq[0].startsWith("EachSpentToCast")) {
            SpellAbility castSA = ctx.c.getCastSA();
            if (castSA == null) {
                return OptionalInt.of(0);
            }
            final List<Mana> paidMana = castSA.getPayingMana();
            final String type = ctx.sq[1];
            int count = 0;
            for (Mana m : paidMana) {
                if (m.toString().equals(type)) {
                    count++;
                }
            }
            return OptionalInt.of(ctx.apply(count));
        }

        // Count$wasCastFrom<Zone>.<true>.<false>
        if (ctx.sq[0].startsWith("wasCastFrom")) {
            boolean your = ctx.sq[0].contains("Your");
            boolean byYou = ctx.sq[0].contains("ByYou");
            String strZone = ctx.sq[0].substring(11);
            if (your) {
                strZone = strZone.substring(4);
            }
            if (byYou) {
                strZone = strZone.substring(0, strZone.indexOf("ByYou", 0));
            }
            boolean zonesMatch = ctx.c.getCastFrom() != null && ctx.c.getCastFrom().getZoneType() == ZoneType.smartValueOf(strZone)
                    && (!byYou || ctx.player.equals(ctx.c.getCastSA().getActivatingPlayer()))
                    && (!your || ctx.c.getCastFrom().getPlayer().equals(ctx.player));
            return OptionalInt.of(ctx.branch(zonesMatch));
        }

        // Count$Presence_<Type>.<True>.<False>
        if (ctx.sq[0].startsWith("Presence")) {
            final String type = ctx.sq[0].split("_")[1];
            boolean found = false;
            if (ctx.c.getCastFrom() != null && ctx.c.getCastSA() != null) {
                int revealed = AbilityUtils.calculateAmount(ctx.c, "Revealed$Valid " + type, ctx.c.getCastSA());
                int ctrl = AbilityUtils.calculateAmount(ctx.c, "Count$LastStateBattlefield " + type + ".YouCtrl", ctx.c.getCastSA());
                if (revealed + ctrl >= 1) {
                    found = true;
                }
            }
            return OptionalInt.of(ctx.branch(found));
        }

        if (ctx.sq[0].startsWith("Devoured")) {
            final String validDevoured = ctx.sq[0].split(" ")[1];
            CardCollection cl = CardLists.getValidCards(ctx.c.getDevouredCards(), validDevoured, ctx.player, ctx.c, ctx.ctb);
            return OptionalInt.of(ctx.apply(cl.size()));
        }

        if (ctx.sq[0].contains("ChosenNumber")) {
            Integer i = ctx.c.getChosenNumber();
            return OptionalInt.of(ctx.apply(i == null ? 0 : i));
        }

        // Count$IfCastInOwnMainPhase.<numMain>.<numNotMain>
        if (ctx.sq[0].endsWith("InOwnMainPhase")) {
            final PhaseHandler cPhase = ctx.game.getPhaseHandler();
            final boolean isMyMain = cPhase.getPhase().isMain() && cPhase.isPlayerTurn(ctx.player) &&
                    (!ctx.sq[0].startsWith("IfCast") || ctx.c.wasCast());
            return OptionalInt.of(ctx.apply(Integer.parseInt(ctx.sq[isMyMain ? 1 : 2])));
        }

        // Count$FinishedUpkeepsThisTurn
        if (ctx.sq[0].startsWith("FinishedUpkeepsThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.game.getPhaseHandler().getNumUpkeep() - (ctx.game.getPhaseHandler().is(PhaseType.UPKEEP) ? 1 : 0)));
        }

        // Count$FinishedEndOfTurnsThisTurn
        if (ctx.sq[0].startsWith("FinishedEndOfTurnsThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.game.getPhaseHandler().getNumEndOfTurn() - (ctx.game.getPhaseHandler().is(PhaseType.END_OF_TURN) ? 1 : 0)));
        }

        // Count$AttachedTo <restriction>
        if (ctx.sq[0].startsWith("AttachedTo")) {
            final String[] k = ctx.l[0].split(" ");
            int sum = CardLists.getValidCardCount(ctx.c.getAttachedCards(), k[1], ctx.player, ctx.c, ctx.ctb);
            return OptionalInt.of(ctx.apply(sum));
        }

        // Count$CardManaCost
        if (ctx.sq[0].startsWith("CardManaCost")) {
            int cmc = ctx.c.getCMC();

            if (ctx.sq[0].contains("LKI") && !ctx.c.isInZone(ZoneType.Stack) && ctx.c.getManaCost() != null) {
                if (ctx.ctb instanceof SpellAbility sa && sa.getXManaCostPaid() != null) {
                    cmc += sa.getXManaCostPaid() * ctx.c.getManaCost().countX();
                } else {
                    cmc += ctx.c.getXManaCostPaid() * ctx.c.getManaCost().countX();
                }
            }

            return OptionalInt.of(ctx.apply(cmc));
        }

        // Count$EnchantedControllerCreatures
        if (ctx.sq[0].equals("EnchantedControllerCreatures")) { // maybe refactor into a Valid with ControlledBy
            int v = 0;
            if (ctx.c.getEnchantingCard() != null) {
                v = CardLists.count(ctx.c.getEnchantingCard().getController().getCardsIn(ZoneType.Battlefield), CardPredicates.CREATURES);
            }
            return OptionalInt.of(ctx.apply(v));
        }

        ////////////////////////
        return OptionalInt.empty();
    }

    static OptionalInt tryPlayerCounts(XCountContext ctx) {
        // player info
        if (ctx.sq[0].equals("Hellbent")) {
            return OptionalInt.of(ctx.branch(ctx.player.hasHellbent()));
        }
        if (ctx.sq[0].equals("Metalcraft")) {
            return OptionalInt.of(ctx.branch(ctx.player.hasMetalcraft()));
        }
        if (ctx.sq[0].equals("Delirium")) {
            return OptionalInt.of(ctx.branch(ctx.player.hasDelirium()));
        }
        if (ctx.sq[0].equals("FatefulHour")) {
            return OptionalInt.of(ctx.branch(ctx.player.getLife() <= 5));
        }
        if (ctx.sq[0].equals("Revolt")) {
            return OptionalInt.of(ctx.branch(ctx.player.hasRevolt()));
        }
        if (ctx.sq[0].equals("Landfall")) {
            return OptionalInt.of(ctx.branch(ctx.player.hasLandfall()));
        }
        if (ctx.sq[0].equals("Monarch")) {
            return OptionalInt.of(ctx.branch(ctx.player.isMonarch()));
        }
        if (ctx.sq[0].equals("Initiative")) {
            return OptionalInt.of(ctx.branch(ctx.player.hasInitiative()));
        }
        if (ctx.sq[0].equals("StartingPlayer")) {
            return OptionalInt.of(ctx.branch(ctx.player.isStartingPlayer()));
        }
        if (ctx.sq[0].equals("Blessing")) {
            return OptionalInt.of(ctx.branch(ctx.player.hasBlessing()));
        }
        if (ctx.sq[0].equals("Threshold")) {
            return OptionalInt.of(ctx.branch(ctx.player.hasThreshold()));
        }
        if (ctx.sq[0].equals("CommittedCrimeThisTurn")) {
            return OptionalInt.of(ctx.branch(ctx.player.getCommittedCrimeThisTurn() > 0));
        }
        if (ctx.sq[0].equals("ExtraTurn")) {
            return OptionalInt.of(ctx.branch(ctx.game.getPhaseHandler().getPlayerTurn().isExtraTurn()));
        }
        if (ctx.sq[0].equals("YourStartingLife")) {
            return OptionalInt.of(ctx.apply(ctx.player.getStartingLife()));
        }

        if (ctx.sq[0].equals("YourLifeTotal")) {
            return OptionalInt.of(ctx.apply(ctx.player.getLife()));
        }
        if (ctx.sq[0].equals("OppGreatestLifeTotal")) {
            return OptionalInt.of(ctx.apply(ctx.player.getOpponentsGreatestLifeTotal()));
        }

        if (ctx.sq[0].equals("YouDrewThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.player.getNumDrawnThisTurn()));
        }
        if (ctx.sq[0].equals("YouDrewLastTurn")) {
            return OptionalInt.of(ctx.apply(ctx.player.getNumDrawnLastTurn()));
        }

        if (ctx.sq[0].equals("YouFlipThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.player.getNumFlipsThisTurn()));
        }

        if (ctx.sq[0].equals("YouRollThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.player.getNumRollsThisTurn()));
        }
        if (ctx.sq[0].startsWith("YouRolledThisTurn")) {
            int n = AbilityUtils.calculateAmount(ctx.c, ctx.sq[0].substring(17), ctx.ctb);
            return OptionalInt.of(ctx.apply(Collections.frequency(ctx.player.getDiceRollsThisTurn(), n)));
        }

        if (ctx.sq[0].equals("YouSurveilThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.player.getSurveilThisTurn()));
        }

        if (ctx.sq[0].equals("YouDescendedThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.player.getDescended()));
        }

        if (ctx.sq[0].equals("YouCastThisGame")) {
            return OptionalInt.of(ctx.apply(ctx.player.getSpellsCastThisGame()));
        }

        if (ctx.sq[0].equals("YourSpeed")) {
            return OptionalInt.of(ctx.apply(ctx.player.getSpeed()));
        }
        if (ctx.sq[0].equals("MaxSpeed")) {
            return OptionalInt.of(ctx.branch(ctx.player.maxSpeed()));
        }

        if (ctx.sq[0].equals("AllFourBend")) {
            return OptionalInt.of(ctx.branch(ctx.player.hasAllElementBend()));
        }

        if (ctx.sq[0].equals("Night")) {
            return OptionalInt.of(ctx.branch(ctx.game.isNight()));
        }

        if (ctx.sq[0].equals("NumPiledGuessedSA")) {
            return OptionalInt.of(ctx.apply(ctx.game.getNumPiledGuessedSA()));
        }

        if (ctx.sq[0].startsWith("CommanderCastFromCommandZone")) {
            // only used by Opal Palace, and it does add the trigger to the card
            return OptionalInt.of(ctx.apply(ctx.player.getCommanderCast(ctx.c)));
        }
        if (ctx.l[0].startsWith("TotalCommanderCastFromCommandZone")) {
            return OptionalInt.of(ctx.apply(ctx.player.getTotalCommanderCast()));
        }

        if (ctx.sq[0].contains("LifeYouLostThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.player.getLifeLostThisTurn()));
        }
        if (ctx.sq[0].contains("LifeYouGainedThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.player.getLifeGainedThisTurn()));
        }
        if (ctx.sq[0].contains("LifeYourTeamGainedThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.player.getLifeGainedByTeamThisTurn()));
        }
        if (ctx.sq[0].contains("LifeYouGainedTimesThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.player.getLifeGainedTimesThisTurn()));
        }
        if (ctx.sq[0].contains("LifeOppsLostThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.player.getOpponentLostLifeThisTurn()));
        }
        if (ctx.sq[0].equals("BloodthirstAmount")) {
            return OptionalInt.of(ctx.apply(ctx.player.getBloodthirstAmount()));
        }

        if (ctx.sq[0].startsWith("YourCounters")) {
            // "YourCountersExperience" or "YourCountersPoison"
            String counterType = ctx.sq[0].substring(12);
            return OptionalInt.of(ctx.apply(ctx.player.getCounters(CounterType.getType(counterType))));
        }

        if (ctx.sq[0].contains("TotalOppPoisonCounters")) {
            return OptionalInt.of(ctx.apply(ctx.player.getOpponentsTotalPoisonCounters()));
        }

        if (ctx.sq[0].equals("TotalDamageDoneByThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.c.getTotalDamageDoneBy()));
        }
        if (ctx.sq[0].equals("TotalDamageReceivedThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.c.getAssignedDamage()));
        }
        if (ctx.sq[0].equals("ExcessDamageReceivedThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.c.getExcessDamageThisTurn()));
        }

        if (ctx.sq[0].equals("MaxOppDamageThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.player.getMaxOpponentAssignedDamage()));
        }

        if (ctx.sq[0].equals("MaxCombatDamageThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.player.getMaxAssignedCombatDamage()));
        }

        if (ctx.sq[0].contains("TotalDamageThisTurn")) {
            String[] props = ctx.l[0].split(" ");
            int sum = 0;
            for (Pair<Integer, Boolean> p : ctx.c.getDamageReceivedThisTurn()) {
                if (ctx.game.getDamageLKI(p).getLeft().isValid(props[1], ctx.player, ctx.c, ctx.ctb)) {
                    sum += p.getLeft();
                }
            }
            return OptionalInt.of(ctx.apply(sum));
        }

        if (ctx.sq[0].equals("SingleMaxDamageThisTurn")) {
            int sum = ctx.game.getSingleMaxDamageDoneThisTurn();
            return OptionalInt.of(ctx.apply(sum));
        }

        if (ctx.sq[0].contains("DamageThisTurn")) {
            String[] props = ctx.l[0].split(" ");
            Boolean isCombat = null;
            if (ctx.sq[0].contains("CombatDamage")) {
                isCombat = !ctx.sq[0].contains("Non");
            }
            int num;
            List<Integer> dmgInstances = ctx.game.getDamageDoneThisTurn(isCombat, false, props[1], props[2], ctx.c, ctx.player, ctx.ctb);
            if (!dmgInstances.isEmpty() && ctx.sq[0].contains("Max")) {
                num = Collections.max(dmgInstances);
            } else if (ctx.sq[0].startsWith("Num")) {
                num = dmgInstances.size();
            } else {
                num = Aggregates.sum(dmgInstances);
            }
            return OptionalInt.of(ctx.apply(num));
        }

        if (ctx.sq[0].equals("YourTurns")) {
            return OptionalInt.of(ctx.apply(ctx.player.getTurn()));
        }

        if (ctx.sq[0].equals("NotedNumber")) {
            return OptionalInt.of(ctx.apply(ctx.player.getNotedNumberForName(ctx.c.getName())));
        }

        if (ctx.sq[0].equals("DraftNotesHighest")) {
            // Just in case you are playing this card in a deck without draft notes
            String note = ctx.player.getDraftNotes().getOrDefault(ctx.sq[1],  "0");
            int highest = 0;
            for (String n : note.split(",")) {
                int num = Integer.parseInt(n);
                if (num > highest) {
                    highest = num;
                }
            }

            return OptionalInt.of(ctx.apply(highest));
            // Other draft notes include: Names, Colors, Players, Creature Type.
            // But these aren't really things you count so they'll show up in properties most likely
        }

        if (ctx.sq[0].equals("DraftNotesCount")) {
            // Just in case you are playing this card in a deck without draft notes
            String note = ctx.player.getDraftNotes().getOrDefault(ctx.sq[1],  null);

            if (note == null) {
                return OptionalInt.of(0);
            }
            int highest = note.split(";").length;

            return OptionalInt.of(ctx.apply(highest));
            // Other draft notes include: Names, Colors, Players, Creature Type.
            // But these aren't really things you count so they'll show up in properties most likely
        }

        //Count$TypesSharedWith [defined]
        if (ctx.sq[0].startsWith("TypesSharedWith")) {
            Set<CardType.CoreType> thisTypes = Sets.newHashSet(ctx.c.getType().getCoreTypes());
            Set<CardType.CoreType> matches = new HashSet<>();
            for (Card c1 : AbilityUtils.getDefinedCards(ctx.ctb.getHostCard(), ctx.l[0].split(" ", 2)[1], ctx.ctb)) {
                for (CardType.CoreType type : Sets.newHashSet(c1.getType().getCoreTypes())) {
                    if (thisTypes.contains(type)) {
                        matches.add(type);
                    }
                }
            }
            return OptionalInt.of(matches.size());
        }

        // Count$TopOfLibraryCMC
        if (ctx.sq[0].equals("TopOfLibraryCMC")) {
            int cmc = ctx.player.getCardsIn(ZoneType.Library).isEmpty() ? 0 :
                ctx.player.getCardsIn(ZoneType.Library).getFirst().getCMC();
            return OptionalInt.of(ctx.apply(cmc));
        }

        // Count$AttackersDeclared
        if (ctx.sq[0].startsWith("AttackersDeclared")) {
            List<Card> attackers = ctx.player.getCreaturesAttackedThisTurn();
            List<Card> differentAttackers = new ArrayList<>();
            for (Card attacker : attackers) {
                boolean add = true;
                for (Card different : differentAttackers) {
                    if (different.equalsWithGameTimestamp(attacker)) {
                        add = false;
                        break;
                    }
                }
                if (add) {
                    differentAttackers.add(attacker);
                }
            }
            return OptionalInt.of(ctx.apply(differentAttackers.size()));
        }

        // Count$CardAttackedThisTurn <Valid>
        if (ctx.sq[0].startsWith("CreaturesAttackedThisTurn")) {
            final String[] workingCopy = ctx.l[0].split(" ", 2);
            final String validFilter = workingCopy[1];
            return OptionalInt.of(ctx.apply(CardLists.getValidCardCount(ctx.player.getCreaturesAttackedThisTurn(), validFilter, ctx.player, ctx.c, ctx.ctb)));
        }

        // Count$LeftBattlefieldThisTurn <Valid>
        if (ctx.sq[0].startsWith("LeftBattlefieldThisTurn")) {
            final String[] workingCopy = ctx.l[0].split(" ", 2);
            final String validFilter = workingCopy[1];
            return OptionalInt.of(ctx.apply(CardLists.getValidCardCount(ctx.game.getLeftBattlefieldThisTurn(), validFilter, ctx.player, ctx.c, ctx.ctb)));
        }
        if (ctx.sq[0].startsWith("LeftGraveyardThisTurn")) {
            final String[] workingCopy = ctx.l[0].split(" ", 2);
            final String validFilter = workingCopy[1];
            return OptionalInt.of(ctx.apply(CardLists.getValidCardCount(ctx.game.getLeftGraveyardThisTurn(), validFilter, ctx.player, ctx.c, ctx.ctb)));
        }

        if (ctx.sq[0].equals("UnlockedDoors")) {
            return OptionalInt.of(ctx.apply(ctx.player.getUnlockedDoors().size()));
        }
        // Counts the distinct names of unlocked doors. Used for the "Promising Stairs"
        if (ctx.sq[0].equals("DistinctUnlockedDoors")) {
            return OptionalInt.of(ctx.apply(Sets.newHashSet(ctx.player.getUnlockedDoors()).size()));
        }

        // Manapool
        if (ctx.sq[0].startsWith("ManaPool")) {
            final String color = ctx.l[0].split(":")[1];
            int v = 0;
            if (color.equals("All")) {
                v = ctx.player.getManaPool().totalMana();
            } else {
                v = ctx.player.getManaPool().getAmountOfColor(ManaAtom.fromName(color));
            }
            return OptionalInt.of(ctx.apply(v));
        }

        // Count$Domain
        if (ctx.sq[0].startsWith("Domain")) {
            int n = 0;
            Player neededPlayer = ctx.sq[0].equals("DomainActivePlayer") ? ctx.game.getPhaseHandler().getPlayerTurn() : ctx.player;
            CardCollection lands = neededPlayer.getLandsInPlay();
            for (String basic : MagicColor.Constant.BASIC_LANDS) {
                if (!CardLists.getType(lands, basic).isEmpty()) {
                    n++;
                }
            }
            return OptionalInt.of(ctx.apply(n));
        }

        if (ctx.sq[0].contains("AbilityYouCtrl")) {
            CardCollection all = CardLists.getValidCards(ctx.player.getCardsIn(ZoneType.Battlefield), "Creature", ctx.player, ctx.c, ctx.ctb);
            int count = 0;
            for (String ab : ctx.sq[0].substring(15).split(",")) {
                CardCollection found = CardLists.getValidCards(all, "Creature.with" + ab, ctx.player, ctx.c, ctx.ctb);
                if (!found.isEmpty()) {
                    count++;
                }
            }
            return OptionalInt.of(ctx.apply(count));
        }

        if (ctx.sq[0].contains("Party")) {
            Set<String> chosenParty = Sets.newHashSet();
            int wildcard = 0;
            ListMultimap<String, Card> multityped = MultimapBuilder.hashKeys().arrayListValues().build();
            List<Card> chosenMulti = Lists.newArrayList();

            // Figure out how to count each class separately.
            for (Card card : ctx.player.getCardsIn(ZoneType.Battlefield)) {
                if (!card.isCreature()) {
                    continue;
                }
                CardTypeView type = card.getType();
                Set<String> creatureTypes;

                // extra logic for "all creature types" cards
                if (type.hasAllCreatureTypes()) {
                    // one of the party types could be excluded, so check each of them separate
                    creatureTypes = CardType.Constant.PARTY_TYPES.stream().filter(p -> type.hasCreatureType(p)).collect(Collectors.toSet());
                } else { // shortcut for others 
                    creatureTypes = type.getCreatureTypes();
                    creatureTypes.retainAll(CardType.Constant.PARTY_TYPES);
                }

                switch (creatureTypes.size()) {
                case 0:
                    continue;
                case 4:
                    wildcard++;
                    break;
                case 1:
                    chosenParty.addAll(creatureTypes);
                    break;
                default:
                    for (String t : creatureTypes) {
                        multityped.put(t, card);
                    }
                }

                // found enough
                if (chosenParty.size() + wildcard >= 4) {
                    break;
                }
            }

            if (chosenParty.size() + wildcard < 4) {
                multityped.keySet().removeAll(chosenParty);

                // sort by amount of members
                Multimaps.asMap(multityped).entrySet().stream()
                    .sorted(Map.Entry.<String, List<Card>>comparingByValue(Comparator.<List<Card>>comparingInt(Collection::size)))
                    .forEach(e -> {
                        e.getValue().removeAll(chosenMulti);
                        if (e.getValue().size() > 0) {
                            chosenParty.add(e.getKey());
                            chosenMulti.add(e.getValue().get(0));
                        }
                    });
            }

            return OptionalInt.of(ctx.apply(Math.min(chosenParty.size() + wildcard, 4)));
        }

        // TODO make AI part to understand Sunburst better so this isn't needed
        if (ctx.sq[0].startsWith("UniqueManaColorsProduced")) {
            boolean untappedOnly = ctx.sq[1].contains("ByUntappedSources");
            int uniqueColors = 0;
            CardCollectionView otb = ctx.player.getCardsIn(ZoneType.Battlefield);
            outer: for (byte color : MagicColor.WUBRG) {
                for (Card card : otb) {
                    if (!card.isTapped() || !untappedOnly) {
                        for (SpellAbility ma : card.getManaAbilities()) {
                            if (ma.canProduce(MagicColor.toShortString(color))) {
                                uniqueColors++;
                                continue outer;
                            }
                        }
                    }
                }
            }
            return OptionalInt.of(ctx.apply(uniqueColors));
        }

        // TODO change into checking SpellAbility
        if (ctx.sq[0].contains("xColorPaid")) {
            String[] attrs = ctx.sq[0].split(" ");
            StringBuilder colors = new StringBuilder();
            for (int i = 1; i < attrs.length; i++) {
                colors.append(attrs[i]);
            }
            return OptionalInt.of(ctx.apply(ctx.c.getXManaCostPaidCount(colors.toString())));
        }

        // Count$UrzaLands.<numHB>.<numNotHB>
        if (ctx.sq[0].startsWith("UrzaLands")) {
            return OptionalInt.of(ctx.branch(ctx.player.hasUrzaLands()));
        }

        /////////////////
        return OptionalInt.empty();
    }

    static OptionalInt tryGameCounts(XCountContext ctx) {
        // game info
        // Count$Morbid.<True>.<False>
        if (ctx.sq[0].startsWith("Morbid")) {
            final List<Card> res = CardUtil.getThisTurnEntered(ZoneType.Graveyard, ZoneType.Battlefield, "Creature", ctx.c, ctx.ctb, ctx.player);
            return OptionalInt.of(ctx.branch(res.size() > 0));
        }
        // Count$Void.<True>.<False>
        if (ctx.sq[0].startsWith("Void")) {
            return OptionalInt.of(ctx.branch(ctx.game.isVoid()));
        }

        // Count$Chroma.<color name>
        if (ctx.sq[0].startsWith("Chroma")) {
            final CardCollectionView cards;
            if (ctx.sq[0].contains("ChromaSource")) { // Runs Chroma for passed in Source card
                cards = new CardCollection(ctx.c);
            } else {
                ZoneType sourceZone = ctx.sq[0].contains("ChromaInGrave") ?  ZoneType.Graveyard : ZoneType.Battlefield;
                cards = ctx.player.getCardsIn(sourceZone);
            }

            byte colorCode;
            if (ctx.sq.length > 1) {
                colorCode = ManaAtom.fromName(ctx.sq[1]);
            } else {
                colorCode = ManaAtom.ALL_MANA_COLORS;
            }

            return OptionalInt.of(ctx.apply(CardLists.getTotalChroma(cards, colorCode)));
        }

        if (ctx.l[0].contains("ExactManaCost")) {
            String[] sqparts = ctx.l[0].split(" ", 2);
            final String[] rest = sqparts[1].split(",");

            final CardCollectionView cardsInZones = sqparts[0].length() > 13
                ? ctx.game.getCardsIn(ZoneType.listValueOf(sqparts[0].substring(13)))
                : ctx.game.getCardsIn(ZoneType.Battlefield);

            CardCollection cards = CardLists.getValidCards(cardsInZones, rest, ctx.player, ctx.c, ctx.ctb);
            final Set<String> manaCost = Sets.newHashSet();

            for (Card card : cards) {
                manaCost.add(card.getManaCost().getShortString());
            }
            manaCost.remove(ManaCost.NO_COST.getShortString());

            return OptionalInt.of(ctx.apply(manaCost.size()));
        }

        if (ctx.sq[0].equals("StormCount")) {
            return OptionalInt.of(ctx.apply(ctx.game.getStack().getSpellsCastThisTurn().size() - 1));
        }

        if (ctx.sq[0].equals("FinalChapterNr")) {
            return OptionalInt.of(ctx.apply(ctx.c.getFinalChapterNr()));
        }

        if (ctx.sq[0].startsWith("PlanarDiceSpecialActionThisTurn")) {
            return OptionalInt.of(ctx.apply(ctx.game.getPhaseHandler().getPlanarDiceSpecialActionThisTurn()));
        }

        if (ctx.sq[0].equals("TotalTurns")) {
            return OptionalInt.of(ctx.apply(ctx.game.getPhaseHandler().getTurn()));
        }

        if (ctx.sq[0].equals("MaxDistinctOnStack")) {
            return OptionalInt.of(ctx.apply(ctx.game.getStack().getMaxDistinctSources()));
        }

        if (ctx.sq[0].equals("MaxSameStoredRolls")) {
            int max = 0;
            List<Integer> rolls = ctx.c.getStoredRolls();
            if (rolls != null) {
                int lastNum = 0;
                for (Integer roll : rolls) {
                    if (roll.equals(lastNum)) {
                        continue; // no need to count instances of the same roll multiple times
                    }
                    int tally = Collections.frequency(rolls, roll);
                    if (tally > max) {
                        max = tally;
                    }
                    lastNum = roll;
                }
            }
            return OptionalInt.of(ctx.apply(max));
        }

        //Count$Random.<Min>.<Max>
        if (ctx.sq[0].equals("Random")) {
            int min = AbilityUtils.calculateAmount(ctx.c, ctx.sq[1], ctx.ctb);
            int max = AbilityUtils.calculateAmount(ctx.c, ctx.sq[2], ctx.ctb);

            return OptionalInt.of(MyRandom.getRandom().nextInt(1+max-min) + min);
        }

        // Count$ThisTurnCast <Valid>
        // Count$LastTurnCast <Valid>
        // Count$CastSinceBeginningOfYourLastTurn_<Valid>
        if (ctx.sq[0].startsWith("ThisTurnCast") || ctx.sq[0].startsWith("LastTurnCast") 
            || ctx.sq[0].startsWith("CastSince")) {
            final String[] workingCopy = ctx.paidparts[0].split("_");
            final String validFilter = workingCopy[1];

            if (workingCopy[0].contains("This")) {
                ctx.someCards = CardUtil.getThisTurnCast(validFilter, ctx.c, ctx.ctb, ctx.player);
            } else if (workingCopy[0].contains("SinceBeginningOfYourLastTurn")) {
                ctx.someCards = CardUtil.getCastSinceBeginningOfYourLastTurn(validFilter, ctx.c, ctx.ctb, ctx.player);
            } else {
                ctx.someCards = CardUtil.getLastTurnCast(validFilter, ctx.c, ctx.ctb, ctx.player);
            }
        }
        if (ctx.sq[0].startsWith("ThisTurnActivated")) {
            final String[] workingCopy = ctx.paidparts[0].split("_");
            final String validFilter = workingCopy[1];
            // use objectXCount ?
            int activated = CardUtil.getThisTurnActivated(validFilter, ctx.c, ctx.ctb, ctx.player).size();
            for (CostPaymentStack.Entry i : ctx.game.costPaymentStack) {
                if (i.payment().getAbility().isValid(validFilter, ctx.player, ctx.c, ctx.ctb)) {
                    activated++;
                }
            }
            return OptionalInt.of(ctx.apply(activated));
        }

        // Count$ThisTurnEntered <ZoneDestination> [from <ZoneOrigin>] <Valid>
        if (ctx.sq[0].startsWith("ThisTurnEntered") || ctx.sq[0].startsWith("LastTurnEntered")) {
            final String[] workingCopy = ctx.paidparts[0].split("_", 5);
            ZoneType destination = ZoneType.smartValueOf(workingCopy[1]);
            final boolean hasFrom = workingCopy[2].equals("from");
            ZoneType origin = hasFrom ? ZoneType.smartValueOf(workingCopy[3]) : null;
            String validFilter = workingCopy[hasFrom ? 4 : 2];

            if (ctx.sq[0].startsWith("This")) {
                ctx.someCards = CardUtil.getThisTurnEntered(destination, origin, validFilter, ctx.c, ctx.ctb, ctx.player);
            } else {
                ctx.someCards = CardUtil.getLastTurnEntered(destination, origin, validFilter, ctx.c, ctx.ctb, ctx.player);
            }
        }

        if (ctx.sq[0].startsWith("CountersAddedThisTurn")) {
            final String[] parts = ctx.l[0].split(" ");
            CounterType cType = CounterType.getType(parts[1]);

            return OptionalInt.of(ctx.apply(ctx.game.getCounterAddedThisTurn(cType, parts[2], parts[3], ctx.c, ctx.player, ctx.ctb)));
        }
        if (ctx.sq[0].startsWith("CountersRemovedThisTurn")) {
            final String[] parts = ctx.l[0].split(" ");
            CounterType cType = CounterType.getType(parts[1]);

            return OptionalInt.of(ctx.apply(ctx.game.getCounterRemovedThisTurn(cType, parts[2], ctx.c, ctx.player, ctx.ctb)));
        }

        if (ctx.sq[0].startsWith("MostCardName")) {
            String[] lparts = ctx.l[0].split(" ", 2);
            final String[] rest = lparts[1].split(",");

            final CardCollectionView cardsInZones = lparts[0].length() > 12
                ? ctx.game.getCardsIn(ZoneType.listValueOf(lparts[0].substring(12)))
                : ctx.game.getCardsIn(ZoneType.Battlefield);

            CardCollection cards = CardLists.getValidCards(cardsInZones, rest, ctx.player, ctx.c, ctx.ctb);

            return OptionalInt.of((int)cards.stream().collect(Collectors.groupingBy(Card::getName, Collectors.counting())).values().stream().mapToLong(v -> v).max().orElse(0));
        }

        if (ctx.sq[0].startsWith("MostProminentCreatureType")) {
            String restriction = ctx.l[0].split(" ")[1];
            CardCollection list = CardLists.getValidCards(ctx.game.getCardsIn(ZoneType.Battlefield), restriction, ctx.player, ctx.c, ctx.ctb);
            return OptionalInt.of(ctx.apply(CardFactoryUtil.getMostProminentCreatureTypeSize(list)));
        }

        if (ctx.sq[0].startsWith("SecondMostProminentColor")) {
            String restriction = ctx.l[0].split(" ")[1];
            CardCollection list = CardLists.getValidCards(ctx.game.getCardsIn(ZoneType.Battlefield), restriction, ctx.player, ctx.c, ctx.ctb);
            int[] colorSize = CardFactoryUtil.SortColorsFromList(list);
            return OptionalInt.of(ctx.apply(colorSize[colorSize.length - 2]));
        }

        // TODO move below to handlePaid
        if (ctx.sq[0].startsWith("DifferentCounterKinds_")) {
            final Set<CounterType> kinds = Sets.newHashSet();
            final String rest = ctx.l[0].substring(22);
            CardCollection list = CardLists.getValidCards(ctx.game.getCardsIn(ZoneType.Battlefield), rest, ctx.player, ctx.c, ctx.ctb);
            for (final Card card : list) {
                kinds.addAll(card.getCounters().keySet());
            }
            return OptionalInt.of(ctx.apply(kinds.size()));
        }

        return OptionalInt.empty();
    }

    static int tryFallback(XCountContext ctx) {
        // Complex counting methods
        Integer num = null;
        if (ctx.someCards == null) {
            ctx.someCards = getCardListForXCount(ctx.c, ctx.player, ctx.sq, ctx.ctb);
        } else if (ctx.paidparts.length > 1) {
            num = AbilityUtils.handlePaid(ctx.someCards, ctx.paidparts[1], ctx.c, ctx.ctb);
        }
        if (num == null) {
            num = Iterables.size(ctx.someCards);
        }

        return ctx.apply(num);
    }

    static CardCollectionView getCardListForXCount(final Card c, final Player cc, final String[] sq, CardTraitBase ctb) {
        final List<Player> opps = cc.getOpponents();
        CardCollection someCards = new CardCollection();
        final Game game = c.getGame();

        // Generic Zone-based counting
        // Count$QualityAndZones.Subquality

        // build a list of cards in each possible specified zone

        if (sq[0].contains("YouCtrl")) {
            someCards.addAll(cc.getCardsIn(ZoneType.Battlefield));
        }

        if (sq[0].contains("InYourYard")) {
            someCards.addAll(cc.getCardsIn(ZoneType.Graveyard));
        }

        if (sq[0].contains("InYourLibrary")) {
            someCards.addAll(cc.getCardsIn(ZoneType.Library));
        }

        if (sq[0].contains("InYourHand")) {
            someCards.addAll(cc.getCardsIn(ZoneType.Hand));
        }

        if (sq[0].contains("InYourSideboard")) {
            someCards.addAll(cc.getCardsIn(ZoneType.Sideboard));
        }

        if (sq[0].contains("OppCtrl")) {
            for (final Player p : opps) {
                someCards.addAll(p.getZone(ZoneType.Battlefield).getCards());
            }
        }

        if (sq[0].contains("OnBattlefield")) {
            someCards.addAll(game.getCardsIn(ZoneType.Battlefield));
        }

        if (sq[0].contains("SpellsOnStack")) {
            someCards.addAll(game.getCardsIn(ZoneType.Stack));
        }

        if (sq[0].contains("InAllHands")) {
            someCards.addAll(game.getCardsIn(ZoneType.Hand));
        }

        // filter lists based on the specified quality

        // "Clerics you control" - Count$TypeYouCtrl.Cleric
        if (sq[0].contains("Type")) {
            someCards = CardLists.getType(someCards, sq[1]);
        }

        // "Named <CARDNAME> in all graveyards" - Count$NamedAllYards.<CARDNAME>

        if (sq[0].contains("Named")) {
            if (sq[1].equals("CARDNAME")) {
                sq[1] = c.getName();
            }
            someCards = CardLists.filter(someCards, CardPredicates.nameEquals(sq[1]));
        }

        // Refined qualities

        // "Untapped Lands" - Count$UntappedTypeYouCtrl.Land
        // if (sq[0].contains("Untapped")) { someCards = CardLists.filter(someCards, CardPredicates.UNTAPPED); }

        // if (sq[0].contains("Tapped")) { someCards = CardLists.filter(someCards, CardPredicates.TAPPED); }

//        String sq0 = sq[0].toLowerCase();
//        for (String color : MagicColor.Constant.ONLY_COLORS) {
//            if (sq0.contains(color))
//                someCards = someCards.filter(CardListFilter.WHITE);
//        }
        // "White Creatures" - Count$WhiteTypeYouCtrl.Creature
        // if (sq[0].contains("White")) someCards = CardLists.filter(someCards, CardPredicates.isColor(MagicColor.WHITE));
        // if (sq[0].contains("Blue"))  someCards = CardLists.filter(someCards, CardPredicates.isColor(MagicColor.BLUE));
        // if (sq[0].contains("Black")) someCards = CardLists.filter(someCards, CardPredicates.isColor(MagicColor.BLACK));
        // if (sq[0].contains("Red"))   someCards = CardLists.filter(someCards, CardPredicates.isColor(MagicColor.RED));
        // if (sq[0].contains("Green")) someCards = CardLists.filter(someCards, CardPredicates.isColor(MagicColor.GREEN));

        if (sq[0].contains("Multicolor")) {
            someCards = CardLists.filter(someCards, c1 -> c1.getColor().isMulticolor());
        }

        if (sq[0].contains("Monocolor")) {
            someCards = CardLists.filter(someCards, c12 -> c12.getColor().isMonoColor());
        }
        return someCards;
    }

    static int resolve(Card c, String s, CardTraitBase ctb) {
        XCountContext ctx = XCountContext.parse(c, s, ctb);
        OptionalInt result = tryNumberAndSVar(ctx);
        if (result.isPresent()) {
            return result.getAsInt();
        }
        return tryResolveMain(ctx);
    }

}
