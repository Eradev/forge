package forge.ai.controller;

import com.google.common.collect.Lists;
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
import org.testng.annotations.Test;

import java.util.List;

public class AutoPaymentTest extends SimulationTest {

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

    // --- Nested filter execution / mana banking (no CastabilityProbe) ---

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

        GameSimulator sim = createSimulator(game, p);
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
}
