package forge.game.spellability;

import java.util.HashMap;
import java.util.Map;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/**
 * Powerstone-style RestrictValid$ !Spell,Spell.Artifact must allow casts of artifact spells and
 * any non-cast payment, including costs of SAs already on the stack (Ward, unless, etc.) and
 * Morph-up (a special action that never uses the stack).
 * Positive "spend only to cast …" lists must still refuse those ForEffect payments.
 */
public class ManaRestrictionForEffectTest extends AITest {

    private AbilityManaPart powerstoneMana(Player p) {
        Card powerstone = addToken("c_a_powerstone", p);
        SpellAbility manaAb = powerstone.getManaAbilities().get(0);
        return manaAb.getManaPart();
    }

    private SpellAbility powerstoneAbility(Player p) {
        Card powerstone = addToken("c_a_powerstone", p);
        return powerstone.getManaAbilities().get(0);
    }

    private AbilityManaPart restrictValid(Player p, String restrictValid) {
        Card source = addCard("Sol Ring", p);
        Map<String, String> params = new HashMap<>();
        params.put("Produced", "C");
        params.put("RestrictValid", restrictValid);
        return new AbilityManaPart(source, params);
    }

    private SpellAbility spellInHand(Player p, String name) {
        Card c = addCardToZone(name, p, ZoneType.Hand);
        SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        return sa;
    }

    /**
     * Push {@code sa} and return the instance actually on the stack. Activated abilities
     * are copied on add; checking the original would miss ForEffect and pass via {@code !Spell}.
     */
    private SpellAbility putOnStack(Game game, SpellAbility sa) {
        game.getStack().add(sa);
        SpellAbility stacked = game.getStack().peekAbility();
        AssertJUnit.assertNotNull("SA should have landed on the stack", stacked);
        AssertJUnit.assertNotNull("ForEffect detection keys off the stacked instance id",
                game.getStack().getInstanceMatchingSpellAbilityID(stacked.getRootAbility()));
        return stacked;
    }

    /** Activated ability that actually costs mana (not a {T} mana ability). */
    private SpellAbility activatedWithManaCost(Player p, String name) {
        Card c = addCard(name, p);
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isActivatedAbility() && !sa.isManaAbility() && sa.getPayCosts().hasManaCost()) {
                sa.setActivatingPlayer(p);
                return sa;
            }
        }
        AssertJUnit.fail("no mana-costed activated ability on " + name);
        return null;
    }

    private SpellAbility wardAbility(Card host) {
        for (Trigger t : host.getTriggers()) {
            if (t.isKeyword(Keyword.WARD)) {
                SpellAbility sa = t.ensureAbility();
                AssertJUnit.assertNotNull("Ward trigger should have an ability", sa);
                AssertJUnit.assertEquals("Ward pays via Counter+UnlessCost", ApiType.Counter, sa.getApi());
                AssertJUnit.assertTrue("Ward ability should carry UnlessCost", sa.hasParam("UnlessCost"));
                sa.setActivatingPlayer(host.getController());
                return sa;
            }
        }
        AssertJUnit.fail("no Ward trigger on " + host.getName());
        return null;
    }

    /**
     * Morph-up is a special action ({@code ST$ SetState}), not a spell and not on the stack.
     * Powerstone-style {@code !Spell} must still allow it without ForEffect detection.
     */
    private SpellAbility morphUp(Player p, String name) {
        Card c = addCard(name, p);
        c.turnFaceDownNoUpdate();
        p.getGame().getAction().checkStateEffects(true);
        for (SpellAbility sa : c.getAllPossibleAbilities(p, false)) {
            if (sa.isMorphUp()) {
                sa.setActivatingPlayer(p);
                return sa;
            }
        }
        AssertJUnit.fail("no Morph-up ability on face-down " + name);
        return null;
    }

    private SpellAbility morphDownCast(Player p, String name) {
        Card c = addCardToZone(name, p, ZoneType.Hand);
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isCastFaceDown()) {
                sa.setActivatingPlayer(p);
                return sa;
            }
        }
        AssertJUnit.fail("no face-down Morph cast on " + name);
        return null;
    }

    private AbilityManaPart tinStreetGossipMana(Player p) {
        Card gossip = addCard("Tin Street Gossip", p);
        SpellAbility manaAb = gossip.getManaAbilities().get(0);
        return manaAb.getManaPart();
    }

    private SpellAbility wardOnStackFromTargeting(Game game, Player caster, Player wardOwner) {
        Card wardHost = addCard("Wilson, Refined Grizzly", wardOwner);
        game.getAction().checkStateEffects(true);

        SpellAbility shock = spellInHand(caster, "Shock");
        shock.getTargets().add(wardHost);
        game.getStack().add(shock);
        game.getTriggerHandler().runWaitingTriggers();
        // Triggers sit in the simultaneous list until ordered onto the real stack.
        game.getStack().addAllTriggeredAbilitiesToStack();

        for (SpellAbilityStackInstance si : game.getStack()) {
            SpellAbility sa = si.getSpellAbility();
            if (sa.isTrigger() && sa.getTrigger() != null && sa.getTrigger().isKeyword(Keyword.WARD)) {
                return sa;
            }
            if (sa.getApi() == ApiType.Counter && sa.hasParam("UnlessCost")
                    && wardHost.equals(sa.getHostCard())) {
                return sa;
            }
        }
        AssertJUnit.fail("Ward did not go on the stack after targeting " + wardHost.getName()
                + "; stack=" + game.getStack());
        return null;
    }

    @Test
    public void powerstoneAllowsArtifactSpell() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        AbilityManaPart mana = powerstoneMana(p);

        AssertJUnit.assertTrue("artifact spell is allowed",
                mana.meetsManaRestrictions(spellInHand(p, "Sol Ring")));
    }

    @Test
    public void powerstoneBlocksNonArtifactSpell() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        AbilityManaPart mana = powerstoneMana(p);

        AssertJUnit.assertFalse("nonartifact spell is forbidden",
                mana.meetsManaRestrictions(spellInHand(p, "Runeclaw Bear")));
    }

    @Test
    public void powerstoneAllowsActivatedAbility() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        AbilityManaPart mana = powerstoneMana(p);

        // Jayemdae Tome's {4}, {T}: Draw a card — a real mana payment that is not casting.
        AssertJUnit.assertTrue("activated abilities with mana costs are not casts",
                mana.meetsManaRestrictions(activatedWithManaCost(p, "Jayemdae Tome")));
    }

    @Test
    public void powerstoneAllowsPaymentWhileAbilityOnStack() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        AbilityManaPart mana = powerstoneMana(p);

        SpellAbility ability = putOnStack(game, activatedWithManaCost(p, "Jayemdae Tome"));

        AssertJUnit.assertTrue("payment toward a resolving ability is not casting",
                mana.meetsManaRestrictions(ability));
    }

    @Test
    public void powerstoneAllowsPaymentWhileNonArtifactSpellOnStack() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        AbilityManaPart mana = powerstoneMana(p);

        SpellAbility spell = putOnStack(game, spellInHand(p, "Runeclaw Bear"));

        AssertJUnit.assertTrue("paying an unless-cost of a resolving spell is not casting it",
                mana.meetsManaRestrictions(spell));
    }

    @Test
    public void powerstoneAllowsPaymentWhileArtifactSpellOnStack() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        AbilityManaPart mana = powerstoneMana(p);

        SpellAbility spell = putOnStack(game, spellInHand(p, "Sol Ring"));

        AssertJUnit.assertTrue("paying an unless-cost of a resolving artifact spell is not casting it",
                mana.meetsManaRestrictions(spell));
    }

    @Test
    public void spendOnlyToCastArtifactStillBlocksForEffect() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        AbilityManaPart mana = restrictValid(p, "Spell.Artifact");

        SpellAbility ability = putOnStack(game, activatedWithManaCost(p, "Jayemdae Tome"));

        AssertJUnit.assertFalse("Mishra's Workshop-style mana still cannot pay a resolving ability",
                mana.meetsManaRestrictions(ability));
    }

    @Test
    public void nonSpellAllowsForEffect() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        AbilityManaPart mana = restrictValid(p, "nonSpell");

        SpellAbility spell = putOnStack(game, spellInHand(p, "Runeclaw Bear"));

        AssertJUnit.assertTrue("Thran Turbine-style nonSpell allows ForEffect payments",
                mana.meetsManaRestrictions(spell));
        AssertJUnit.assertFalse("nonSpell still blocks casting a spell that is not on the stack",
                mana.meetsManaRestrictions(spellInHand(p, "Sol Ring")));
    }

    @Test
    public void cantCastFromHandAllowsForEffect() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        AbilityManaPart mana = restrictValid(p, "CantCast FromHand");

        SpellAbility fromHand = spellInHand(p, "Runeclaw Bear");
        AssertJUnit.assertFalse("cannot spend to cast from hand",
                mana.meetsManaRestrictions(fromHand));

        SpellAbility stacked = putOnStack(game, fromHand);
        AssertJUnit.assertTrue("ForEffect payment is not casting from hand",
                mana.meetsManaRestrictions(stacked));
    }

    @Test
    public void powerstoneAllowsPayingWardUnlessCost() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        AbilityManaPart mana = powerstoneMana(p);

        Card wardHost = addCard("Wilson, Refined Grizzly", opp);
        game.getAction().checkStateEffects(true);
        SpellAbility ward = putOnStack(game, wardAbility(wardHost));

        AssertJUnit.assertTrue("Powerstone mana may pay Ward's unless-cost",
                mana.meetsManaRestrictions(ward));
    }

    @Test
    public void powerstonePoolManaCanPayWard() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);

        SpellAbility manaAb = powerstoneAbility(p);
        manaAb.setActivatingPlayer(p);
        manaAb.getManaPart().produceMana(manaAb);
        AssertJUnit.assertFalse("powerstone should have floated mana", p.getManaPool().isEmpty());

        Card wardHost = addCard("Wilson, Refined Grizzly", opp);
        game.getAction().checkStateEffects(true);
        SpellAbility ward = putOnStack(game, wardAbility(wardHost));

        boolean usable = false;
        for (Mana m : p.getManaPool()) {
            if (m.meetsManaRestrictions(ward)) {
                usable = true;
                break;
            }
        }
        AssertJUnit.assertTrue("floating Powerstone mana must be legal for Ward payment", usable);

        ManaCostBeingPaid cost = new ManaCostBeingPaid(ManaCost.get(2));
        AssertJUnit.assertTrue("pool should be able to spend colorless Powerstone mana toward Ward {2}",
                p.getManaPool().tryPayCostWithColor((byte) ManaAtom.COLORLESS, ward, cost, ward.getPayingMana()));
    }

    @Test
    public void workshopManaCannotPayWard() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        AbilityManaPart mana = restrictValid(p, "Spell.Artifact");

        Card wardHost = addCard("Wilson, Refined Grizzly", opp);
        game.getAction().checkStateEffects(true);
        SpellAbility ward = putOnStack(game, wardAbility(wardHost));

        AssertJUnit.assertFalse("spend-only-to-cast-artifact mana cannot pay Ward",
                mana.meetsManaRestrictions(ward));
    }

    @Test
    public void powerstoneAllowsWardAfterTargeting() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        AbilityManaPart mana = powerstoneMana(p);

        SpellAbility ward = wardOnStackFromTargeting(game, p, opp);
        AssertJUnit.assertTrue("Powerstone mana may pay the real Ward trigger after targeting",
                mana.meetsManaRestrictions(ward));
    }

    @Test
    public void powerstoneAllowsForceSpikeUnlessCost() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        AbilityManaPart mana = powerstoneMana(p);

        // Prey spell for Force Spike to target, then Force Spike itself on the stack.
        SpellAbility prey = spellInHand(p, "Runeclaw Bear");
        game.getStack().add(prey);

        SpellAbility forceSpike = spellInHand(opp, "Force Spike");
        AssertJUnit.assertTrue(forceSpike.hasParam("UnlessCost"));
        forceSpike.getTargets().add(prey.getHostCard());
        game.getStack().add(forceSpike);
        AssertJUnit.assertNotNull("Force Spike should be on the stack",
                game.getStack().getInstanceMatchingSpellAbilityID(forceSpike));

        AssertJUnit.assertTrue("Powerstone mana may pay Force Spike's unless-cost",
                mana.meetsManaRestrictions(forceSpike));
        AssertJUnit.assertFalse("spend-only-to-cast-artifact still cannot",
                restrictValid(p, "Spell.Artifact").meetsManaRestrictions(forceSpike));
    }

    @Test
    public void powerstoneAllowsMorphUp() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        AbilityManaPart mana = powerstoneMana(p);

        SpellAbility morph = morphUp(p, "Willbender");
        AssertJUnit.assertFalse("Morph-up is a special action, not a stack payment",
                game.getStack().getInstanceMatchingSpellAbilityID(morph) != null);
        AssertJUnit.assertTrue("Powerstone mana may pay Morph (not a cast)",
                mana.meetsManaRestrictions(morph));
    }

    @Test
    public void workshopCannotPayMorphUp() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        AssertJUnit.assertFalse("spend-only-to-cast-artifact cannot pay Morph",
                restrictValid(p, "Spell.Artifact").meetsManaRestrictions(morphUp(p, "Willbender")));
    }

    @Test
    public void nonSpellAllowsMorphUp() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        AssertJUnit.assertTrue("Thran Turbine-style mana may pay Morph",
                restrictValid(p, "nonSpell").meetsManaRestrictions(morphUp(p, "Willbender")));
    }

    @Test
    public void tinStreetGossipAllowsMorphUp() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        AssertJUnit.assertTrue("Tin Street Gossip may pay to turn a creature face up",
                tinStreetGossipMana(p).meetsManaRestrictions(morphUp(p, "Willbender")));
    }

    @Test
    public void powerstoneBlocksFaceDownMorphCast() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        AssertJUnit.assertFalse("face-down Morph of a nonartifact is still a nonartifact spell",
                powerstoneMana(p).meetsManaRestrictions(morphDownCast(p, "Willbender")));
    }

    @Test
    public void tinStreetGossipAllowsFaceDownMorphCast() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        AssertJUnit.assertTrue("Tin Street Gossip may pay to cast a face-down spell",
                tinStreetGossipMana(p).meetsManaRestrictions(morphDownCast(p, "Willbender")));
    }

    @Test
    public void workshopCannotPayFaceDownMorphCast() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        AssertJUnit.assertFalse("spend-only-to-cast-artifact cannot pay a face-down creature spell",
                restrictValid(p, "Spell.Artifact").meetsManaRestrictions(morphDownCast(p, "Willbender")));
    }
}
