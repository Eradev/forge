package forge.ai;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostSacrifice;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.IHasForgeLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Debug tracing for AI mana payment ({@code -Dforge.debugManaPayment*}).
 * <ul>
 *   <li>Numbered plan at payment prompt: {@code -Dforge.debugManaPayment.plan=true} → {@link #aiLog} info</li>
 *   <li>Verbose shard trace: {@code -Dforge.debugManaPayment=true} → {@link #aiLog} debug</li>
 * </ul>
 */
final class ManaPaymentTracer implements IHasForgeLog {
    private ManaPaymentTracer() {
    }

    /**
     * Verbose flag and trace mode are JVM startup switches; cache them so the hot payment loop does not
     * hit the synchronized system-properties table on every trace line. {@link #refreshFlags()} re-reads.
     */
    private static volatile Boolean verboseFlag;
    private static volatile TraceMode traceMode;

    /** Re-read {@code -Dforge.debugManaPayment*} switches (tests / runtime toggles). */
    static void refreshFlags() {
        verboseFlag = null;
        traceMode = null;
    }

    private static boolean verboseFlag() {
        Boolean v = verboseFlag;
        if (v == null) {
            v = Boolean.getBoolean("forge.debugManaPayment");
            verboseFlag = v;
        }
        return v;
    }

    private static TraceMode traceMode() {
        TraceMode m = traceMode;
        if (m == null) {
            m = TraceMode.current();
            traceMode = m;
        }
        return m;
    }

    private enum TraceMode {
        TEST, PROD, ALL;

        static TraceMode current() {
            if (Boolean.getBoolean("forge.debugManaPayment.prodOnly")) {
                return PROD;
            }
            final String trace = System.getProperty("forge.debugManaPayment.trace");
            if (trace != null) {
                switch (trace.toLowerCase(Locale.ROOT)) {
                    case "prod":
                        return PROD;
                    case "all":
                        return ALL;
                    case "test":
                        return TEST;
                    default:
                        break;
                }
            }
            if (!Boolean.parseBoolean(System.getProperty("forge.debugManaPayment.testOnly", "true"))) {
                return ALL;
            }
            return TEST;
        }
    }

    /** {@code Plains (12)} — card name plus in-game entity id for plan/debug lines. */
    static String formatSourceLabel(final Card card) {
        if (card == null) {
            return "?";
        }
        return card.getName() + " (" + card.getId() + ")";
    }

    /**
     * Human-readable action for a mana source in the payment plan:
     * {@code Sacrifice Lotus Petal (103)},
     * {@code Tap Phyrexian Tower (5), Sacrifice Grizzly Bears (8)},
     * {@code Activate Ashnod's Altar (3), Sacrifice Soldier Token (4)},
     * or {@code Tap Forest (1)}.
     */
    static String formatSourceAction(final SpellAbility ma) {
        return formatSourceAction(ma, ma != null ? ma.getActivatingPlayer() : null);
    }

    static String formatSourceAction(final SpellAbility ma, final Player ai) {
        if (ma == null || ma.getHostCard() == null) {
            return "Activate ?";
        }
        final Card host = ma.getHostCard();
        final String label = formatSourceLabel(host);
        final Cost payCosts = ma.getPayCosts();
        if (ComputerUtilCost.isSacrificeSelfCost(payCosts)) {
            return "Sacrifice " + label;
        }
        final String sacTargets = formatExternalSacrificeTargets(ma, ai);
        final String activate;
        if (payCosts != null && payCosts.hasTapCost()) {
            activate = "Tap " + label;
        } else {
            activate = "Activate " + label;
        }
        if (sacTargets != null) {
            return activate + ", Sacrifice " + sacTargets;
        }
        if (ManaFilterConsolidation.sacrificesOtherPermanentsForMana(ma)) {
            return activate + " (sacrifice a creature)";
        }
        return activate;
    }

    /**
     * Names the permanent(s) the AI would sacrifice for outlets like Ashnod's Altar / Phyrexian Tower.
     * Prefers cards already reserved in {@link MemorySet#PAYS_SAC_COST}; otherwise asks the AI chooser
     * (display-only, does not reserve).
     */
    private static String formatExternalSacrificeTargets(final SpellAbility ma, final Player ai) {
        if (!ManaFilterConsolidation.sacrificesOtherPermanentsForMana(ma)) {
            return null;
        }
        final Cost payCosts = ma.getPayCosts();
        if (payCosts == null) {
            return null;
        }
        final Card host = ma.getHostCard();
        final List<String> labels = new ArrayList<>();
        for (final CostPart part : payCosts.getCostParts()) {
            if (!(part instanceof CostSacrifice) || part.payCostFromSource()) {
                continue;
            }
            final List<Card> chosen = resolveExternalSacrificeTargets(ma, ai, (CostSacrifice) part);
            for (final Card c : chosen) {
                if (c != null && c != host) {
                    labels.add(formatSourceLabel(c));
                }
            }
        }
        if (labels.isEmpty()) {
            return null;
        }
        return String.join(" and ", labels);
    }

    private static List<Card> resolveExternalSacrificeTargets(final SpellAbility ma, final Player ai,
            final CostSacrifice part) {
        final List<Card> result = new ArrayList<>();
        if (ai == null) {
            return result;
        }
        final Card host = ma.getHostCard();
        final Set<Card> remembered = AiCardMemory.getMemorySet(ai, AiCardMemory.MemorySet.PAYS_SAC_COST);
        if (remembered != null) {
            for (final Card c : remembered) {
                if (c == null || c == host) {
                    continue;
                }
                if (c.isValid(part.getType().split(";"), ai, host, ma)) {
                    result.add(c);
                    if (result.size() >= Math.max(1, part.getAbilityAmount(ma))) {
                        return result;
                    }
                }
            }
        }
        if (!result.isEmpty()) {
            return result;
        }
        if (!ai.isAI() || !(ai.getController() instanceof PlayerControllerAi)) {
            return result;
        }
        try {
            final int amount = Math.max(1, part.getAbilityAmount(ma));
            final CardCollection exclude = new CardCollection();
            if (remembered != null) {
                exclude.addAll(remembered);
            }
            final AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
            final CardCollectionView choices = aic.chooseSacrificeType(part.getType(), ma, ma.isTrigger(), amount, exclude);
            if (choices != null) {
                for (final Card c : choices) {
                    result.add(c);
                }
            }
        } catch (final RuntimeException ignored) {
            // Plan text should never fail payment; fall back to generic wording.
        }
        return result;
    }

    /** Spell name for payment traces; zone/ability kind tagged when not a hand spell. */
    static String manaPaymentSpellLabel(final SpellAbility sa) {
        if (sa == null || sa.getHostCard() == null) {
            return "?";
        }
        final Card host = sa.getHostCard();
        final StringBuilder label = new StringBuilder(host.getName());
        if (host.isInZone(ZoneType.Command)) {
            label.append(" [").append(commandZoneAbilityPaymentKind(sa)).append("]");
        } else if (host.isInZone(ZoneType.Stack)) {
            label.append(" [stack]");
        } else if (host.isInZone(ZoneType.Battlefield)) {
            label.append(" [").append(battlefieldAbilityPaymentKind(sa)).append("]");
        }
        return label.toString();
    }

    static boolean verboseEnabled(final boolean test) {
        if (!verboseFlag()) {
            return false;
        }
        switch (traceMode()) {
            case PROD:
                return !test;
            case ALL:
                return true;
            case TEST:
            default:
                return test;
        }
    }

    /** True when a main-trace line for this context would actually be emitted. */
    static boolean mainEnabled(final boolean test, final ManaPaymentContext ctx) {
        return (ctx == null || ctx.shouldLogMain()) && verboseEnabled(test);
    }

    /** True when a tap line should be built: either verbose trace or plan recording is active. */
    static boolean tapTraceEnabled(final boolean test, final ManaPaymentContext ctx) {
        return mainEnabled(test, ctx) || (ctx != null && ctx.planSteps != null);
    }

    static void log(final boolean test, final String msg) {
        if (verboseEnabled(test) && msg != null) {
            aiLog.debug("MANA_PAYMENT [{}] {}", test ? "test" : "prod", msg);
        }
    }

    static void logMain(final boolean test, final String msg, final ManaPaymentContext ctx) {
        if (ctx == null || ctx.shouldLogMain()) {
            log(test, msg);
        }
    }

    /** Lazy variant: the message is only built when the line will be emitted. */
    static void logMain(final boolean test, final Supplier<String> msg, final ManaPaymentContext ctx) {
        if (mainEnabled(test, ctx)) {
            log(test, msg.get());
        }
    }

    static void logResult(final boolean test, final boolean success, final String msg, final ManaPaymentContext ctx) {
        logMain(test, success ? msg : "!! " + msg, ctx);
    }

    static void logResult(final boolean test, final boolean success, final Supplier<String> msg,
            final ManaPaymentContext ctx) {
        if (mainEnabled(test, ctx)) {
            final String m = msg.get();
            log(test, success ? m : "!! " + m);
        }
    }

    static void logTap(final boolean test, final SpellAbility saPayment, final SpellAbility sa,
            final String paidShards, final String manaProduced, final ManaPaymentContext ctx) {
        if (saPayment == null || !tapTraceEnabled(test, ctx)) {
            return;
        }
        final String msg = "  " + formatSourceAction(saPayment, saPayment.getActivatingPlayer() != null
                ? saPayment.getActivatingPlayer()
                : (sa != null ? sa.getActivatingPlayer() : null))
                + " -> " + manaProduced
                + " (paying " + paidShards + " for " + manaPaymentSpellLabel(sa) + ")";
        logMain(test, msg, ctx);
        if (ctx != null) {
            ctx.recordStep(sa, test, msg);
        }
    }

    /**
     * Nested free source paying a filter/signet activation cost (e.g. sac Petal for Signet {1}).
     * Included in {@code MANA_PAYMENT_PLAN} so the user sees the play-by-play before the filter tap.
     */
    static void logNestedTap(final boolean test, final SpellAbility chosen, final String manaProduced,
            final Card filterHost, final SpellAbility paidFor, final ManaPaymentContext ctx) {
        if (chosen == null || !tapTraceEnabled(test, ctx)) {
            return;
        }
        final String produced = manaProduced != null ? manaProduced : "";
        final Player ai = chosen.getActivatingPlayer() != null
                ? chosen.getActivatingPlayer()
                : (paidFor != null ? paidFor.getActivatingPlayer() : null);
        final String msg = "  " + formatSourceAction(chosen, ai) + " -> " + produced
                + " (paying activation of " + formatSourceLabel(filterHost) + ")";
        logMain(test, msg, ctx);
        if (ctx != null && paidFor != null) {
            ctx.recordStep(paidFor, test, msg);
        }
    }

    static boolean shouldRecordPlanStep(final SpellAbility sa, final boolean test, final ManaPaymentContext ctx) {
        if (!planEnabled() || sa == null || ctx == null || !ctx.tracePaymentPlan || !ctx.isOutermost()) {
            return false;
        }
        // Never plan the mana abilities used as payment sources — only the spell/ability being paid for.
        if (sa.isManaAbility()) {
            return false;
        }
        final Card host = sa.getHostCard();
        if (host == null) {
            return false;
        }
        final Cost payCosts = sa.getPayCosts();
        if (payCosts == null || !payCosts.hasManaCost()) {
            return false;
        }
        // Payment-prompt only (tracePaymentPlan). Include spells (stack/hand) and abilities
        // (battlefield/command/exile/graveyard flashback host before zone change, etc.).
        // Do not require isActivatedAbility() — that missed some AbilityStatic / edge SA types.
        return true;
    }

    static void logPaymentPlan(final boolean test, final String costLabel,
            final SpellAbility sa, final List<String> rawSteps, final boolean paid) {
        if (rawSteps == null || rawSteps.isEmpty()) {
            return;
        }
        final String spellName = manaPaymentSpellLabel(sa);
        aiLog.info("MANA_PAYMENT_PLAN [{}] {} for {}{}",
                test ? "test" : "prod", costLabel, spellName, paid ? "" : " (unpaid)");
        int step = 1;
        for (final String line : rawSteps) {
            if (line != null) {
                aiLog.info("  {}. {}", step++, line.trim());
            }
        }
    }

    /** Read dynamically: tests toggle this property at runtime. Only reached when a plan is being recorded. */
    /** Read live (not cached): tests toggle the property at runtime. */
    static boolean planEnabled() {
        return Boolean.getBoolean("forge.debugManaPayment.plan");
    }

    private static String battlefieldAbilityPaymentKind(final SpellAbility sa) {
        if (sa.isEquip()) {
            return "equip";
        }
        if (sa.isCrew()) {
            return "crew";
        }
        if (sa.isPwAbility()) {
            return "loyalty";
        }
        if (sa.isLandAbility()) {
            return "land ability";
        }
        if (sa.isActivatedAbility()) {
            return "ability";
        }
        return "battlefield";
    }

    private static String commandZoneAbilityPaymentKind(final SpellAbility sa) {
        final String desc = sa.getDescription();
        if (desc != null && desc.contains("Companion")) {
            return "companion";
        }
        return "command";
    }
}
