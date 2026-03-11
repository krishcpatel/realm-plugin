package com.krishcpatel.realm.jobs.model;

import java.util.Locale;

/**
 * Configured reward for a single job action selector.
 *
 * @param selector exact target, group selector, or {@code ANY}
 * @param moneyMin minimum money payout
 * @param moneyMax maximum money payout
 * @param xpMin minimum xp payout
 * @param xpMax maximum xp payout
 * @param dailyMoneyCap max money that can be earned from this rule per day, {@code 0} for unlimited
 * @param dailyXpCap max xp that can be earned from this rule per day, {@code 0} for unlimited
 */
public record RewardRule(
        String selector,
        long moneyMin,
        long moneyMax,
        long xpMin,
        long xpMax,
        long dailyMoneyCap,
        long dailyXpCap
) {
    /**
     * Creates a reward rule and normalizes its selector for case-insensitive matching.
     */
    public RewardRule {
        selector = normalizeSelector(selector);
    }

    /**
     * Returns a relative match score for the given action.
     *
     * @param action player action
     * @return 2 for exact match, 1 for group match, 0 for ANY, -1 for no match
     */
    public int matchScore(JobActionContext action) {
        String normalizedTarget = normalizeSelector(action.target());
        if (selector.equals(normalizedTarget)) {
            return 2;
        }
        if ("ANY".equals(selector)) {
            return 0;
        }
        for (String group : action.groups()) {
            if (selector.equals(normalizeSelector(group))) {
                return 1;
            }
        }
        return -1;
    }

    /**
     * Normalizes selectors so config and runtime matching are case-insensitive.
     *
     * @param raw selector
     * @return normalized selector
     */
    public static String normalizeSelector(String raw) {
        if (raw == null || raw.isBlank()) {
            return "ANY";
        }
        return raw.trim()
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }
}
