package forge.ai.controller;

import com.google.common.collect.Lists;
import forge.ai.CastabilityProbe;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.GameSimulator;
import forge.ai.simulation.Plan;
import forge.ai.simulation.SimulationTest;
import forge.ai.simulation.SpellAbilityPicker;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostParser;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;

public class AutoPaymentTest extends SimulationTest {

    @BeforeMethod
    public void enableCastabilityProbeForPaymentTests() {
        CastabilityProbe.enableForTests();
    }

    private static ManaCostBeingPaid cost(String s) {
        return new ManaCostBeingPaid(new ManaCost(new ManaCostParser(s)));
    }

    private boolean canAutoPay(Game game, Player p, ManaCostBeingPaid mc, SpellAbility sa) {
        final boolean[] result = new boolean[1];
        p.runWithController(() -> result[0] = ComputerUtilMana.canPayManaCost(mc, sa, p, false),
                new PlayerControllerAi(game, p, p.getOriginalLobbyPlayer()));
        return result[0];
    }

    private boolean prodAutoPay(Game game, Player p, ManaCostBeingPaid mc, SpellAbility sa) {
        final boolean[] result = new boolean[1];
        p.runWithController(() -> result[0] = ComputerUtilMana.payManaCost(mc, sa, p, false),
                new PlayerControllerAi(game, p, p.getOriginalLobbyPlayer()));
        return result[0];
    }

    private void assertProductionPayment(Game game, Player p, ManaCostBeingPaid mc, SpellAbility sa) {
        AssertJUnit.assertTrue(canAutoPay(game, p, mc, sa));
        AssertJUnit.assertTrue(prodAutoPay(game, p, mc, sa));
    }

    private int countTapped(Game game, String name) {
        int i = 0;
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(name) && c.isTapped()) {
                i++;
            }
        }
        return i;
    }

    private CardCollection predictedManaSources(Game game, Player p, ManaCostBeingPaid mc, SpellAbility sa) {
        final CardCollection[] sources = new CardCollection[1];
        p.runWithController(() -> sources[0] = ComputerUtilMana.getManaSourcesToPayCostForPaymentPrompt(mc, sa, p, false),
                new PlayerControllerAi(game, p, p.getOriginalLobbyPlayer()));
        return sources[0];
    }

    /** Runs payment-prompt preview and returns castability nested dry-run count (see {@link ComputerUtilMana}). */
    private int castabilityProbeDryRunsForPaymentPrompt(Game game, Player p, ManaCostBeingPaid mc, SpellAbility sa) {
        final int[] count = new int[1];
        p.runWithController(() -> {
            ComputerUtilMana.resetCastabilityProbeDryRunCountForTests();
            ComputerUtilMana.getManaSourcesToPayCostForPaymentPrompt(mc, sa, p, false);
            count[0] = ComputerUtilMana.getCastabilityProbeDryRunCountForTests();
        }, new PlayerControllerAi(game, p, p.getOriginalLobbyPlayer()));
        return count[0];
    }

    /** Place a spell on the game stack for payment tests (player zones have no Stack). */
    private Card addSpellOnStack(Game game, String name, Player p) {
        Card spell = createCard(name, p);
        spell.setGameTimestamp(game.getNextTimestamp());
        SpellAbility sa = spell.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        game.getStack().addAndUnfreeze(sa);
        return spell;
    }

    private Card findSpellCard(Game game, String name) {
        for (ZoneType zone : new ZoneType[] { ZoneType.Hand, ZoneType.Stack, ZoneType.Graveyard, ZoneType.Battlefield }) {
            for (Card c : game.getCardsIn(zone)) {
                if (c.getName().equals(name)) {
                    return c;
                }
            }
        }
        return null;
    }

    // --- Nested filter execution / mana banking ---

    @Test
    public void lotusPetalCanActivateSignetForOneWhiteCost() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Lotus Petal", p);
        addCard("Selesnya Signet", p);
        Card spell = addCardToZone("Luminarch Aspirant", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        ManaCostBeingPaid mc = cost("1 W");
        AssertJUnit.assertTrue(canAutoPay(game, p, mc, sa));

        CardCollection sources = predictedManaSources(game, p, mc, sa);
        AssertJUnit.assertTrue("Signet should pay", sources.anyMatch(c -> "Selesnya Signet".equals(c.getName())));
        AssertJUnit.assertFalse("Lotus Petal should not pay {W} directly",
                sources.size() == 1 && sources.anyMatch(c -> "Lotus Petal".equals(c.getName())));

        AssertJUnit.assertTrue("Production auto-pay should match feasibility", prodAutoPay(game, p, mc, sa));
    }

    @Test
    public void lotusPetalActivatesSignetForOneWhiteCostOnStack() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Lotus Petal", p);
        addCard("Selesnya Signet", p);
        Card spell = addSpellOnStack(game, "Luminarch Aspirant", p);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        ManaCostBeingPaid mc = cost("1 W");
        AssertJUnit.assertTrue(canAutoPay(game, p, mc, sa));

        CardCollection sources = predictedManaSources(game, p, mc, sa);
        AssertJUnit.assertTrue("Signet should pay", sources.anyMatch(c -> "Selesnya Signet".equals(c.getName())));
        AssertJUnit.assertTrue("Lotus Petal should activate the Signet",
                sources.anyMatch(c -> "Lotus Petal".equals(c.getName())));

        AssertJUnit.assertTrue("Production auto-pay should match feasibility", prodAutoPay(game, p, mc, sa));
    }

    @Test
    public void studyHallBeatsLotusPetalForSingleGreen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Plains", p);
        addCard("Study Hall", p);
        addCard("Lotus Petal", p);
        Card spell = addCardToZone("Hardened Scales", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        AssertJUnit.assertTrue(canAutoPay(game, p, cost("G"), sa));

        CardCollection sources = predictedManaSources(game, p, cost("G"), sa);
        AssertJUnit.assertTrue("Study Hall should produce {G}", sources.anyMatch(c -> "Study Hall".equals(c.getName())));
        AssertJUnit.assertTrue("Plains should pay Study Hall's {1}", sources.anyMatch(c -> "Plains".equals(c.getName())));
        AssertJUnit.assertFalse("Lotus Petal should not be sacrificed", sources.anyMatch(c -> "Lotus Petal".equals(c.getName())));
    }

    @Test
    public void studyHallBeatsLotusPetalWhenPlainsTapped() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        Card plains = addCard("Plains", p);
        plains.setTapped(true);
        Card prairie = addCard("Sungrass Prairie", p);
        prairie.setTapped(true);
        Card petal2 = addCard("Lotus Petal", p);
        petal2.setTapped(true);
        addCard("Forest", p);
        addCard("Study Hall", p);
        addCard("Lotus Petal", p);
        addCardToZone("Healing Salve", p, ZoneType.Hand);
        Card spell = addSpellOnStack(game, "Speaker of the Heavens", p);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        ManaCostBeingPaid mc = cost("W");
        AssertJUnit.assertTrue(canAutoPay(game, p, mc, sa));

        CardCollection sources = predictedManaSources(game, p, mc, sa);
        AssertJUnit.assertTrue("Study Hall should produce {W}", sources.anyMatch(c -> "Study Hall".equals(c.getName())));
        AssertJUnit.assertTrue("Forest should pay Study Hall's {1}", sources.anyMatch(c -> "Forest".equals(c.getName())));
        AssertJUnit.assertFalse("Lotus Petal should not be sacrificed", sources.anyMatch(c -> "Lotus Petal".equals(c.getName())));

        AssertJUnit.assertTrue("Production auto-pay should match feasibility", prodAutoPay(game, p, mc, sa));
    }

    @Test
    public void signetConsolidatesColoredShardsOverLotusPetal() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Wastes", p);
        addCard("Selesnya Signet", p);
        addCard("Lotus Petal", p);
        Card spell = addCardToZone("Arcus Acolyte", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        GameSimulator sim = createSimulator(p);
        int score = sim.simulateSpellAbility(spell.getFirstSpellAbility()).value;
        AssertJUnit.assertTrue(score > 0);
        Game simGame = sim.getSimulatedGameState();

        AssertJUnit.assertNotNull(findSpellCard(simGame, "Arcus Acolyte"));
        AssertJUnit.assertNotNull("Lotus Petal should not be sacrificed", findSpellCard(simGame, "Lotus Petal"));
        AssertJUnit.assertEquals("Signet should be tapped", 1, countTapped(simGame, "Selesnya Signet"));
    }

    @Test
    public void filterActivationDoesNotStrandGenericPip() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Plains", p);
        addCard("Lotus Petal", p);
        addCard("Study Hall", p);
        Card spell = addCardToZone("Calix, Guided by Fate", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        ManaCostBeingPaid mc = cost("1 G W");
        AssertJUnit.assertTrue(canAutoPay(game, p, mc, sa));

        CardCollection sources = predictedManaSources(game, p, mc, sa);
        AssertJUnit.assertTrue("Plains should pay {W}", sources.anyMatch(c -> "Plains".equals(c.getName())));
        AssertJUnit.assertTrue("Lotus Petal should pay {G}", sources.anyMatch(c -> "Lotus Petal".equals(c.getName())));
        AssertJUnit.assertTrue("Study Hall should pay generic {1}", sources.anyMatch(c -> "Study Hall".equals(c.getName())));

        AssertJUnit.assertTrue("Production auto-pay should match feasibility", prodAutoPay(game, p, mc, sa));
    }

    @Test
    public void signetAndCascadeBluffsPayTripleRedSpell() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Plains", p);
        addCard("Rakdos Signet", p);
        addCard("Cascade Bluffs", p);
        Card spell = addCardToZone("Aisha of Sparks and Smoke", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        ManaCostBeingPaid mc = new ManaCostBeingPaid(spell.getManaCost());

        AssertJUnit.assertTrue(canAutoPay(game, p, mc, sa));

        CardCollection sources = predictedManaSources(game, p, new ManaCostBeingPaid(spell.getManaCost()), sa);
        AssertJUnit.assertTrue("Plains should pay the Signet's {1}",
                sources.anyMatch(c -> "Plains".equals(c.getName())));
        AssertJUnit.assertTrue("Rakdos Signet should be used",
                sources.anyMatch(c -> "Rakdos Signet".equals(c.getName())));
        AssertJUnit.assertTrue("Cascade Bluffs should be used",
                sources.anyMatch(c -> "Cascade Bluffs".equals(c.getName())));
        AssertJUnit.assertFalse("A second Signet is not required",
                sources.stream().filter(c -> "Rakdos Signet".equals(c.getName())).count() > 1);

        AssertJUnit.assertTrue(prodAutoPay(game, p, new ManaCostBeingPaid(spell.getManaCost()), sa));
        AssertJUnit.assertEquals("Plains should be tapped", 1, countTapped(game, "Plains"));
        AssertJUnit.assertEquals("Signet should be tapped", 1, countTapped(game, "Rakdos Signet"));
        AssertJUnit.assertEquals("Cascade Bluffs should be tapped", 1, countTapped(game, "Cascade Bluffs"));
    }

    @Test
    public void paymentPlanPreviewIncludesPetalSacrificedForSignetActivation() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        // No free {1} land — Lotus Petal must pay Selesnya Signet's activation.
        addCard("Selesnya Signet", p);
        addCard("Lotus Petal", p);
        Card spell = addCardToZone("Arcus Acolyte", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        AssertJUnit.assertTrue("Signet + Petal should pay {G}{W}",
                canAutoPay(game, p, new ManaCostBeingPaid(spell.getManaCost()), sa));

        CardCollection sources = predictedManaSources(game, p,
                new ManaCostBeingPaid(spell.getManaCost()), sa);
        AssertJUnit.assertTrue("Selesnya Signet should be in the payment plan",
                sources.anyMatch(c -> "Selesnya Signet".equals(c.getName())));
        AssertJUnit.assertTrue("Lotus Petal should be listed (sac for Signet activation)",
                sources.anyMatch(c -> "Lotus Petal".equals(c.getName())));
    }

    @Test
    public void comboGateDoesNotInflateQuickSufficiencyTotal() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        // Boros Guildgate is Produced$ Combo R W — must count as 1 mana, not 3 ("Combo"+"R"+"W").
        addCard("Plains", p);
        Card gate = addCard("Boros Guildgate", p);
        gate.setTapped(false);

        Card spell = addCardToZone("Kalemne, Disciple of Iroas", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        ManaCostBeingPaid mc = cost("2 W R");

        ComputerUtilMana.ManaAvailabilityEstimate estimate = ComputerUtilMana.estimateAvailableMana(p, true);
        AssertJUnit.assertEquals("Plains + Guildgate should estimate 2 mana total, not 4+",
                2, estimate.total);
        AssertJUnit.assertFalse("Quick sufficiency must not claim {2}{W}{R} is payable from 2 lands",
                estimate.canCover(mc, sa));
        AssertJUnit.assertFalse("Full Auto-pay must also reject {2}{W}{R}",
                canAutoPay(game, p, mc, sa));
    }

    @Test
    public void paidManaAbilityDoesNotInflateQuickSufficiencyTotal() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        // Cascading Cataracts: {T}: Add {C}. / {5}, {T}: Add five mana in any combination.
        // CostPartMana.convertAmount() is always 1 (CostPart default), so a buggy estimate
        // treats the paid ability as net +4 and claims more mana than the free tap produces.
        addCard("Island", p);
        addCard("Forest", p);
        Card garden = addCard("Temple Garden", p);
        garden.setTapped(false);
        Card cataracts = addCard("Cascading Cataracts", p);
        cataracts.setTapped(false);
        Card druid = addCard("Harabaz Druid", p);
        druid.setTapped(false);
        druid.setSickness(false);
        addCard("Chasm Guide", p); // second Ally so Druid makes 2

        Card spell = addCardToZone("General Tazri", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        // Commander-taxed cast of Tazri: {4}{W}+{2} = {6}{W} needs 7; board has 6 (4 lands + Druid 2).
        ManaCostBeingPaid mc = cost("6 W");

        ComputerUtilMana.ManaAvailabilityEstimate estimate = ComputerUtilMana.estimateAvailableMana(p, true);
        AssertJUnit.assertEquals("4 lands + Harabaz Druid (2 Allies) should estimate 6, not Cataracts' paid ability",
                6, estimate.total);
        AssertJUnit.assertFalse("Quick sufficiency must not claim {6}{W} is payable from 6 mana",
                estimate.canCover(mc, sa));
        AssertJUnit.assertFalse("Full Auto-pay must also reject {6}{W}",
                canAutoPay(game, p, mc, sa));
    }

    @Test
    public void emitsPaymentPlanForBattlefieldAbility() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Plains", p);
        Card sword = addCard("Bonesplitter", p);
        Card bear = addCard("Grizzly Bears", p);
        bear.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final SpellAbility equip = findSAWithPrefix(sword, "Equip");
        AssertJUnit.assertNotNull(equip);
        equip.setActivatingPlayer(p);
        if (equip.usesTargeting()) {
            equip.getTargets().add(bear);
        }
        ManaCostBeingPaid mc = new ManaCostBeingPaid(equip.getPayCosts().getCostMana().getMana());

        String prevPlan = System.getProperty("forge.debugManaPayment.plan");
        try {
            System.setProperty("forge.debugManaPayment.plan", "true");
            final CardCollection[] sources = new CardCollection[1];
            p.runWithController(() -> sources[0] = ComputerUtilMana.getManaSourcesToPayCostForPaymentPrompt(
                    new ManaCostBeingPaid(mc), equip, p, false),
                    new PlayerControllerAi(game, p, p.getOriginalLobbyPlayer()));
            AssertJUnit.assertNotNull("Equip Auto preview should succeed", sources[0]);
            AssertJUnit.assertTrue("Plains should be in Equip payment plan sources",
                    sources[0].anyMatch(c -> "Plains".equals(c.getName())));
        } finally {
            if (prevPlan == null) {
                System.clearProperty("forge.debugManaPayment.plan");
            } else {
                System.setProperty("forge.debugManaPayment.plan", prevPlan);
            }
        }
    }

    // Study Hall {T}:{C} must feed Sungrass Prairie's {1} so GW covers Equip {2}, not spend C on the first pip.
    @Test
    public void studyHallFeedsSungrassForEquipTwo() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Study Hall", p);
        addCard("Sungrass Prairie", p);
        Card spear = addCard("Shadowspear", p);
        Card bear = addCard("Grizzly Bears", p);
        bear.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final SpellAbility equip = findSAWithPrefix(spear, "Equip");
        AssertJUnit.assertNotNull(equip);
        equip.setActivatingPlayer(p);
        if (equip.usesTargeting()) {
            equip.getTargets().add(bear);
        }
        ManaCostBeingPaid mc = new ManaCostBeingPaid(equip.getPayCosts().getCostMana().getMana());

        CardCollection sources = predictedManaSources(game, p, new ManaCostBeingPaid(mc), equip);
        AssertJUnit.assertNotNull("Study Hall + Sungrass should pay Equip {2}", sources);
        AssertJUnit.assertTrue("Study Hall should tap for Sungrass activation",
                sources.anyMatch(c -> "Study Hall".equals(c.getName())));
        AssertJUnit.assertTrue("Sungrass Prairie should produce {G}{W} for {2}",
                sources.anyMatch(c -> "Sungrass Prairie".equals(c.getName())));

        AssertJUnit.assertTrue(prodAutoPay(game, p, new ManaCostBeingPaid(mc), equip));
        AssertJUnit.assertEquals(1, countTapped(game, "Study Hall"));
        AssertJUnit.assertEquals(1, countTapped(game, "Sungrass Prairie"));
    }

    // Same board with a GW hand spell that only Sungrass colors — must still pay Equip {2} now, not strand.
    @Test
    public void studyHallFeedsSungrassForEquipTwoDespiteGwHandSpell() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Study Hall", p);
        addCard("Sungrass Prairie", p);
        addCardToZone("Armadillo Cloak", p, ZoneType.Hand);
        Card spear = addCard("Shadowspear", p);
        Card bear = addCard("Grizzly Bears", p);
        bear.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final SpellAbility equip = findSAWithPrefix(spear, "Equip");
        AssertJUnit.assertNotNull(equip);
        equip.setActivatingPlayer(p);
        if (equip.usesTargeting()) {
            equip.getTargets().add(bear);
        }
        ManaCostBeingPaid mc = new ManaCostBeingPaid(equip.getPayCosts().getCostMana().getMana());

        CardCollection sources = predictedManaSources(game, p, new ManaCostBeingPaid(mc), equip);
        AssertJUnit.assertNotNull("Study Hall + Sungrass must pay Equip {2} even with GW in hand", sources);
        AssertJUnit.assertTrue(sources.anyMatch(c -> "Study Hall".equals(c.getName())));
        AssertJUnit.assertTrue(sources.anyMatch(c -> "Sungrass Prairie".equals(c.getName())));

        AssertJUnit.assertTrue(prodAutoPay(game, p, new ManaCostBeingPaid(mc), equip));
        AssertJUnit.assertEquals(1, countTapped(game, "Study Hall"));
        AssertJUnit.assertEquals(1, countTapped(game, "Sungrass Prairie"));
    }

    // With a free colorless land covering {2}, reserve Sungrass for the GW hand spell.
    @Test
    public void reservesSungrassWhenWastesCoverGenericEquip() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Wastes", p);
        addCard("Study Hall", p);
        addCard("Sungrass Prairie", p);
        addCardToZone("Armadillo Cloak", p, ZoneType.Hand);
        Card spear = addCard("Shadowspear", p);
        Card bear = addCard("Grizzly Bears", p);
        bear.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final SpellAbility equip = findSAWithPrefix(spear, "Equip");
        AssertJUnit.assertNotNull(equip);
        equip.setActivatingPlayer(p);
        if (equip.usesTargeting()) {
            equip.getTargets().add(bear);
        }
        ManaCostBeingPaid mc = new ManaCostBeingPaid(equip.getPayCosts().getCostMana().getMana());

        CardCollection sources = predictedManaSources(game, p, new ManaCostBeingPaid(mc), equip);
        AssertJUnit.assertNotNull(sources);
        AssertJUnit.assertTrue("Wastes should help pay Equip {2}",
                sources.anyMatch(c -> "Wastes".equals(c.getName())));
        AssertJUnit.assertTrue("Study Hall {C} should help pay Equip {2}",
                sources.anyMatch(c -> "Study Hall".equals(c.getName())));
        AssertJUnit.assertFalse("Sungrass should be reserved for Armadillo Cloak",
                sources.anyMatch(c -> "Sungrass Prairie".equals(c.getName())));

        AssertJUnit.assertTrue(prodAutoPay(game, p, new ManaCostBeingPaid(mc), equip));
        AssertJUnit.assertEquals(1, countTapped(game, "Wastes"));
        AssertJUnit.assertEquals(1, countTapped(game, "Study Hall"));
        AssertJUnit.assertEquals(0, countTapped(game, "Sungrass Prairie"));
    }

    @Test
    public void emitsPaymentPlanWhenDebugEnabled() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCards("Forest", 2, p);
        Card bear = addSpellOnStack(game, "Grizzly Bears", p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = bear.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        ManaCostBeingPaid mc = new ManaCostBeingPaid(sa.getPayCosts().getCostMana().getMana());

        String prevPlan = System.getProperty("forge.debugManaPayment.plan");
        try {
            System.setProperty("forge.debugManaPayment.plan", "true");
            final CardCollection[] sources = new CardCollection[1];
            p.runWithController(() -> sources[0] = ComputerUtilMana.getManaSourcesToPayCostForPaymentPrompt(
                    new ManaCostBeingPaid(mc), sa, p, false),
                    new PlayerControllerAi(game, p, p.getOriginalLobbyPlayer()));
            AssertJUnit.assertNotNull(sources[0]);
            AssertJUnit.assertFalse("Preview should list cards Auto would tap", sources[0].isEmpty());
            AssertJUnit.assertTrue("Forest should be in payment preview",
                    sources[0].anyMatch(c -> "Forest".equals(c.getName())));
        } finally {
            if (prevPlan == null) {
                System.clearProperty("forge.debugManaPayment.plan");
            } else {
                System.setProperty("forge.debugManaPayment.plan", prevPlan);
            }
        }
    }

    @Test
    public void canPayManaCostFromHandDoesNotEmitPaymentPlan() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCards("Forest", 2, p);
        Card bear = addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = bear.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        ManaCostBeingPaid mc = new ManaCostBeingPaid(sa.getPayCosts().getCostMana().getMana());

        String prevPlan = System.getProperty("forge.debugManaPayment.plan");
        try {
            System.setProperty("forge.debugManaPayment.plan", "true");
            AssertJUnit.assertTrue(ComputerUtilMana.canPayManaCost(new ManaCostBeingPaid(mc), sa, p, false));
        } finally {
            if (prevPlan == null) {
                System.clearProperty("forge.debugManaPayment.plan");
            } else {
                System.setProperty("forge.debugManaPayment.plan", prevPlan);
            }
        }
    }

    @Test
    public void dontPayWithAshnodsAltar() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        String llanowar = "Llanowar Elves";

        Card elf = addCard(llanowar,  p);
        elf.setSickness(false);
        Card altar = addCard("Ashnod's Altar", p);
        Card treasure = addToken("c_a_treasure_sac", p);

        // Two choices tap elf and sac treasure
        // OR Sac elf to Altar

        String stone = "Mind Stone";
        Card mindstone = addCardToZone(stone, p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        GameSimulator sim = createSimulator(p);
        int score = sim.simulateSpellAbility(mindstone.getFirstSpellAbility()).value;

        AssertJUnit.assertTrue(score > 0);
        Game simGame = sim.getSimulatedGameState();

        Card mindstoneBF = findCardWithName(simGame, stone);
        AssertJUnit.assertNotNull(mindstoneBF);

        Card elfCopy = findCardWithName(simGame, llanowar);
        AssertJUnit.assertNotNull(elfCopy);
    }

    @Test
    public void payWithTreasuresOverPhyrexianAltar() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        String squire = "Squire";

        List<Card> squires = addCards(squire, 6,  p);
        Card altar = addCard("Phyrexian Altar", p);
        List<Card> treasures = addTokens("c_a_treasure_sac", 6, p);

        String shivan = "Shivan Dragon";
        Card dragon = addCardToZone(shivan, p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        GameSimulator sim = createSimulator(p);
        int score = sim.simulateSpellAbility(dragon.getFirstSpellAbility()).value;

        AssertJUnit.assertTrue(score > 0);
        Game simGame = sim.getSimulatedGameState();

        Card dragonBF = findCardWithName(simGame, shivan);
        AssertJUnit.assertNotNull(dragonBF);
        AssertJUnit.assertEquals(dragonBF.getZone().getZoneType(), ZoneType.Battlefield);

        Card squireCopy = findCardWithName(simGame, squire);
        AssertJUnit.assertNotNull(squireCopy);

        Card treasureCopy = findCardWithName(simGame, "Treasure Token");
        AssertJUnit.assertNull(treasureCopy);
    }

    @Test
    public void payWithCreaturesOverSacrificeLands() {
        // Do not sacrifice debris. It can be tapped for Blue or Plains tapped for white. Tap elf instead.
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        Card elf = addCard("Llanowar Elves",  p);
        addCard("Seafloor Debris", p);
        addCard("Plains", p);
        addCard("Fervor", p);

        String griz = "Grizzly Bears";
        Card bears = addCardToZone(griz, p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        GameSimulator sim = createSimulator(p);
        int score = sim.simulateSpellAbility(bears.getFirstSpellAbility()).value;

        AssertJUnit.assertTrue(score > 0);
        Game simGame = sim.getSimulatedGameState();

        // Grizzly cast, Seafloor not sacrificed, Elf tapped
        Card grizBF = findCardWithName(simGame, griz);
        AssertJUnit.assertNotNull(grizBF);
        AssertJUnit.assertEquals(ZoneType.Battlefield, grizBF.getZone().getZoneType());

        Card debrisCopy = findCardWithName(simGame, "Seafloor Debris");
        AssertJUnit.assertNotNull(debrisCopy);

        Card elfCopy = findCardWithName(simGame, "Llanowar Elves");
        AssertJUnit.assertNotNull(elfCopy);
        AssertJUnit.assertTrue(elfCopy.isTapped());
    }

    @Test
    public void testKeepColorsOpen() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCards("Forest", 2, p);
        addCards("Swamp", 2, p);
        addCardToZone("Bear Cub", p, ZoneType.Hand);
        addCardToZone("Bear Cub", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p);
        game.getAction().checkStateEffects(true);

        SpellAbilityPicker picker = new SpellAbilityPicker(p);
        SpellAbility sa = picker.chooseSpellAbilityToPlay(null);
        AssertJUnit.assertTrue(sa.getHostCard().isCreature());

        // AI able to cast both creatures
        Plan plan = picker.getPlan();
        AssertJUnit.assertEquals(2, plan.getDecisions().size());
    }

    // {R} alone with Mountain + Signet should tap the Mountain, not the Signet (single-shard penalty).
    @Test
    public void singleShardPrefersBasicOverSignet() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Mountain", p);
        addCard("Boros Signet", p);
        Card spell = addCardToZone("Shock", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        assertProductionPayment(game, p, cost("R"), sa);

        AssertJUnit.assertEquals("Mountain should be tapped for R", 1, countTapped(game, "Mountain"));
        AssertJUnit.assertEquals("Signet should be untapped", 0, countTapped(game, "Boros Signet"));
    }

    // {W} with Plains + Karakas: tap Plains; keep Karakas for its bounce ability.
    @Test
    public void karakasReservedWhenPlainsAvailable() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Plains", p);
        addCard("Karakas", p);
        Card spell = addCardToZone("Healing Salve", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        assertProductionPayment(game, p, cost("W"), sa);

        AssertJUnit.assertEquals("Plains should be tapped for W", 1, countTapped(game, "Plains"));
        AssertJUnit.assertEquals("Karakas should stay untapped", 0, countTapped(game, "Karakas"));
    }

    // {W} with only Karakas: still payable — reserve is soft depriorization, not a hard block.
    @Test
    public void karakasTappedWhenOnlyWhiteSource() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Karakas", p);
        Card spell = addCardToZone("Healing Salve", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        assertProductionPayment(game, p, cost("W"), sa);

        AssertJUnit.assertEquals("Karakas should be tapped when it is the only source", 1, countTapped(game, "Karakas"));
    }

    private void registerBattlefieldTriggers(Game game, Card... cards) {
        for (final Card c : cards) {
            if (c != null) {
                game.getTriggerHandler().registerActiveTrigger(c, false);
            }
        }
    }

    // --- TapsForMana bonuses and conditional mana links (Sprawl, Festival, Gemstone Caverns) ---

    // Forest + Utopia Sprawl (chosen blue): one tap produces {G}{U} via TapsForMana trigger simulation.
    @Test
    public void utopiaSprawlPaysGreenAndChosenColorFromOneForestTap() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        Card forest = addCard("Forest", p);
        Card sprawl = addCard("Utopia Sprawl", p);
        sprawl.setChosenColors(Lists.newArrayList("blue"));
        sprawl.attachToEntity(forest, null);
        registerBattlefieldTriggers(game, sprawl);
        Card spell = addCardToZone("Growth Spiral", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        AssertJUnit.assertTrue(canAutoPay(game, p, cost("G U"), sa));

        CardCollection sources = predictedManaSources(game, p, cost("G U"), sa);
        AssertJUnit.assertEquals("Only the Sprawl'd Forest should be tapped", 1,
                sources.stream().filter(c -> "Forest".equals(c.getName())).count());
        AssertJUnit.assertFalse("Utopia Sprawl is not a mana source host",
                sources.anyMatch(c -> "Utopia Sprawl".equals(c.getName())));

        AssertJUnit.assertTrue("Production auto-pay should match feasibility", prodAutoPay(game, p, cost("G U"), sa));
        AssertJUnit.assertEquals(1, countTapped(game, "Forest"));
    }

    // Forest + Market Festival: one tap produces {G} plus two any ({G}{U}{R} from a single source).
    @Test
    public void marketFestivalProducesThreeManaFromOneForestTap() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        Card forest = addCard("Forest", p);
        Card festival = addCard("Market Festival", p);
        festival.attachToEntity(forest, null);
        registerBattlefieldTriggers(game, festival);
        Card spell = addCardToZone("Temur Charm", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        AssertJUnit.assertTrue("One Festival'd Forest should pay {G}{U}{R}",
                canAutoPay(game, p, cost("G U R"), sa));

        CardCollection sources = predictedManaSources(game, p, cost("G U R"), sa);
        AssertJUnit.assertEquals("Only the Festival'd Forest should be tapped", 1,
                sources.stream().filter(c -> "Forest".equals(c.getName())).count());

        AssertJUnit.assertTrue("Production auto-pay should match feasibility",
                prodAutoPay(game, p, cost("G U R"), sa));
        AssertJUnit.assertEquals(1, countTapped(game, "Forest"));
    }

    // One Festival'd Forest tap yields at most three mana ({G} plus two any); four colored pips is infeasible.
    @Test
    public void marketFestivalCannotPayFourColorsFromOneForest() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        Card forest = addCard("Forest", p);
        Card festival = addCard("Market Festival", p);
        festival.attachToEntity(forest, null);
        registerBattlefieldTriggers(game, festival);
        Card spell = addCardToZone("Lightning Helix", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        AssertJUnit.assertFalse("One Forest tap cannot pay {G}{U}{R}{W}",
                canAutoPay(game, p, cost("G U R W"), sa));
    }

    // Gemstone Caverns with a luck counter: tap adds one mana of any color.
    @Test
    public void gemstoneCavernsWithLuckCounterPaysColoredMana() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        Card caverns = addCard("Gemstone Caverns", p);
        caverns.addCounterInternal(forge.game.card.CounterEnumType.LUCK, 1, p, false, null, null);
        Card spell = addCardToZone("Lightning Bolt", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        AssertJUnit.assertTrue("Luck-counter Gemstone Caverns should pay {R}",
                canAutoPay(game, p, cost("R"), sa));

        CardCollection sources = predictedManaSources(game, p, cost("R"), sa);
        AssertJUnit.assertTrue("Gemstone Caverns should be the mana source",
                sources.anyMatch(c -> "Gemstone Caverns".equals(c.getName())));

        AssertJUnit.assertTrue("Production auto-pay should match feasibility",
                prodAutoPay(game, p, cost("R"), sa));
        AssertJUnit.assertEquals(1, countTapped(game, "Gemstone Caverns"));
    }

    // Gemstone Caverns without a luck counter: tap adds {C} only.
    @Test
    public void gemstoneCavernsWithoutLuckCounterPaysColorlessOnly() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Gemstone Caverns", p);
        Card spell = addCardToZone("Expedition Map", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        AssertJUnit.assertTrue(canAutoPay(game, p, cost("1"), sa));

        CardCollection sources = predictedManaSources(game, p, cost("1"), sa);
        AssertJUnit.assertTrue(sources.anyMatch(c -> "Gemstone Caverns".equals(c.getName())));

        AssertJUnit.assertTrue(prodAutoPay(game, p, cost("1"), sa));
        AssertJUnit.assertEquals(1, countTapped(game, "Gemstone Caverns"));
    }

    // Castability probe: no red sources — skip dry-runs for hand spells that need {R}.
    @Test
    public void castabilityProbeSkipsRedDependentsWhenNoRed() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Plains", p);
        addCard("Forest", p);
        addCard("Llanowar Elves", p);
        addCard("Study Hall", p);
        addCardToZone("Shock", p, ZoneType.Hand);
        addCardToZone("Shock", p, ZoneType.Hand);
        addCardToZone("Shock", p, ZoneType.Hand);
        Card spell = addCardToZone("Healing Salve", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        AssertJUnit.assertTrue(canAutoPay(game, p, cost("W"), sa));
        final int dryRuns = castabilityProbeDryRunsForPaymentPrompt(game, p, cost("W"), sa);
        AssertJUnit.assertEquals("Red spells should be pruned without nested dry-runs", 0, dryRuns);
        assertProductionPayment(game, p, cost("W"), sa);
    }

    // Castability probe: one Mountain — both {R} spells still get full dry-runs (no quantity over-prune).
    @Test
    public void castabilityProbeDoesNotSkipWhenOneRedRemains() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Mountain", p);
        addCard("Plains", p);
        addCard("Boros Signet", p);
        addCardToZone("Shock", p, ZoneType.Hand);
        addCardToZone("Shock", p, ZoneType.Hand);
        Card spell = addCardToZone("Lightning Helix", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        AssertJUnit.assertTrue(canAutoPay(game, p, cost("R W"), sa));
        final int dryRuns = castabilityProbeDryRunsForPaymentPrompt(game, p, cost("R W"), sa);
        AssertJUnit.assertTrue("Both red spells should still be probed when {R} remains available", dryRuns >= 2);
        assertProductionPayment(game, p, cost("R W"), sa);
    }

    // Soft CMC cap: skip dry-run for 5-drop when total mana < CMC; still probe low-CMC spells.
    @Test
    public void castabilityProbeSoftCmcCapSkipsWhenTotalManaInsufficient() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Mountain", p);
        addCard("Plains", p);
        addCard("Boros Signet", p);
        addCardToZone("Air Elemental", p, ZoneType.Hand);
        addCardToZone("Divine Favor", p, ZoneType.Hand);
        Card spell = addCardToZone("Lightning Helix", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        AssertJUnit.assertTrue(canAutoPay(game, p, cost("R W"), sa));
        final int dryRuns = castabilityProbeDryRunsForPaymentPrompt(game, p, cost("R W"), sa);
        AssertJUnit.assertTrue("High-CMC spell should be skipped; low-CMC spell still probed", dryRuns >= 1);
        assertProductionPayment(game, p, cost("R W"), sa);
    }

    // Canopy Vista produces {W} directly; Study Hall should not be used for a lone {W} pip.
    @Test
    public void studyHallDoesNotBeatDualLandForSingleWhite() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Study Hall", p);
        addCard("Canopy Vista", p);
        addCard("Snow-Covered Forest", p);
        Card spell = addCardToZone("Healing Salve", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        AssertJUnit.assertTrue(canAutoPay(game, p, cost("W"), sa));

        CardCollection sources = predictedManaSources(game, p, cost("W"), sa);
        AssertJUnit.assertTrue("Canopy Vista should pay {W} directly",
                sources.anyMatch(c -> "Canopy Vista".equals(c.getName())));
        AssertJUnit.assertFalse("Study Hall should not be used for a single {W}",
                sources.anyMatch(c -> "Study Hall".equals(c.getName())));
    }

    // Quantity gate: clearly short on total mana fails without needing a full shard loop success path.
    @Test
    public void failsWhenTotalManaInsufficient() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCards("Forest", 2, p);
        Card spell = addCardToZone("Omniscience", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        AssertJUnit.assertFalse("2 Forests cannot pay {7}{U}{U}{U}",
                canAutoPay(game, p, cost("7 U U U"), sa));
    }

    // Pure generic: spend colorless land before a colored basic (unless hand needs dedicated {C}).
    @Test
    public void prefersReliquaryTowerOverPlainsForGeneric() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Reliquary Tower", p);
        addCard("Plains", p);
        Card spell = addCardToZone("Sol Ring", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = spell.getFirstSpellAbility();
        AssertJUnit.assertTrue(canAutoPay(game, p, cost("1"), sa));

        CardCollection sources = predictedManaSources(game, p, cost("1"), sa);
        AssertJUnit.assertTrue("Reliquary Tower should pay {1}",
                sources.anyMatch(c -> "Reliquary Tower".equals(c.getName())));
        AssertJUnit.assertFalse("Plains should be saved",
                sources.anyMatch(c -> "Plains".equals(c.getName())));

        AssertJUnit.assertTrue(prodAutoPay(game, p, cost("1"), sa));
        AssertJUnit.assertEquals(1, countTapped(game, "Reliquary Tower"));
        AssertJUnit.assertEquals(0, countTapped(game, "Plains"));
    }
}
