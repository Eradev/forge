package forge.ai;

import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.spellability.AbilityStatic;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.IHasForgeLog;

import java.util.List;
import java.util.Locale;

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
        if (!Boolean.getBoolean("forge.debugManaPayment")) {
            return false;
        }
        switch (TraceMode.current()) {
            case PROD:
                return !test;
            case ALL:
                return true;
            case TEST:
            default:
                return test;
        }
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

    static void logResult(final boolean test, final boolean success, final String msg, final ManaPaymentContext ctx) {
        logMain(test, success ? msg : "!! " + msg, ctx);
    }

    static void logTap(final boolean test, final SpellAbility saPayment, final SpellAbility sa,
            final String paidShards, final String manaProduced, final ManaPaymentContext ctx) {
        if (saPayment == null) {
            return;
        }
        logMain(test, "  tap " + formatSourceLabel(saPayment.getHostCard()) + " -> " + manaProduced
                + " (paying " + paidShards + " for " + manaPaymentSpellLabel(sa) + ")", ctx);
    }

    static void logNestedTap(final boolean test, final SpellAbility chosen, final String manaProduced,
            final Card filterHost, final ManaPaymentContext ctx) {
        if (chosen == null) {
            return;
        }
        logMain(test, "    nested tap " + formatSourceLabel(chosen.getHostCard()) + " -> "
                + (manaProduced != null ? manaProduced : "")
                + " for " + formatSourceLabel(filterHost) + " activation", ctx);
    }

    static boolean shouldRecordPlanStep(final SpellAbility sa, final boolean test, final ManaPaymentContext ctx) {
        if (!planEnabled() || sa == null || ctx == null || !ctx.tracePaymentPlan || !ctx.isOutermost()) {
            return false;
        }
        final Card host = sa.getHostCard();
        if (host == null || host.isInZone(ZoneType.Hand)) {
            return false;
        }
        if (host.isInZone(ZoneType.Stack)) {
            return true;
        }
        if (host.isInZone(ZoneType.Battlefield) && isBattlefieldAbilityPayment(sa)) {
            return true;
        }
        return host.isInZone(ZoneType.Command) && isCommandZoneAbilityPayment(sa);
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
                aiLog.info("  {}. {}", step++, formatStep(line));
            }
        }
    }

    private static boolean planEnabled() {
        return Boolean.getBoolean("forge.debugManaPayment.plan");
    }

    /** True when paying mana to activate a non-spell ability (equip, crew, companion ST$, etc.). */
    private static boolean isAbilityManaPayment(final SpellAbility sa) {
        if (sa == null || sa.isSpell() || sa.isManaAbility()) {
            return false;
        }
        final Cost payCosts = sa.getPayCosts();
        if (payCosts == null || !payCosts.hasManaCost()) {
            return false;
        }
        // ST$ scripted abilities (e.g. Companion put-into-hand) are AbilityStatic, not AbilityActivated.
        return sa.isActivatedAbility() || sa.isLandAbility() || sa instanceof AbilityStatic;
    }

    private static boolean isBattlefieldAbilityPayment(final SpellAbility sa) {
        return isAbilityManaPayment(sa);
    }

    private static boolean isCommandZoneAbilityPayment(final SpellAbility sa) {
        return isAbilityManaPayment(sa);
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

    private static String formatStep(final String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("tap ")) {
            s = "Tap " + s.substring(4);
        }
        return s;
    }
}
