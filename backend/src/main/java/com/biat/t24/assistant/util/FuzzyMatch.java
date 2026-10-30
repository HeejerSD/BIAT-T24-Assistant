package com.biat.t24.assistant.util;

/**
 * Distance de Levenshtein (nombre minimal d'insertions/suppressions/substitutions pour passer d'une chaîne à
 * l'autre), utilisée pour reconnaître un nom de table/colonne T24 mal orthographié (ex: "ACOUNTS.CLOSURE" pour
 * "ACCOUNT.CLOSURE") sans avoir à le taper exactement.
 */
public final class FuzzyMatch {

    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    /**
     * Tolérance proportionnelle à la longueur : jusqu'à ~1 caractère mal orthographié par tranche de 5, avec un
     * minimum de 2 pour rester utile sur des identifiants courts. Volontairement permissif pour de vrais
     * identifiants T24 (souvent longs, ex: "MIN.COMM.AMT.LCCY"), pas pour des mots courts où une faute de frappe
     * ferait trop facilement coïncider deux identifiants différents.
     */
    public static boolean isCloseEnough(String candidate, String reference) {
        int distance = levenshtein(candidate.toUpperCase(), reference.toUpperCase());
        int tolerance = Math.max(2, reference.length() / 5);
        return distance > 0 && distance <= tolerance;
    }

    private FuzzyMatch() {}
}
