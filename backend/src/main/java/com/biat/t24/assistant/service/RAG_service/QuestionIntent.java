package com.biat.t24.assistant.service.RAG_service;

import java.util.regex.Pattern;

/**
 * Type de question posée à l'assistant, déterminé de façon déterministe (sans LLM) : il décide de la stratégie de
 * récupération documentaire (vue d'ensemble d'un module, champs d'une table, contexte du tour précédent...) et de
 * la consigne de rédaction (longueur, structure) donnée au modèle.
 */
public enum QuestionIntent {

    /** Question générale sur un module ("Que trouve-t-on dans la documentation du module LD ?"). */
    MODULE_GENERAL,
    /** Tables d'un module en lien avec un thème précis, avec détail demandé (rôle, champs principaux...) — ex.
     *  "quelles tables contiennent des informations sur les contrats de crédit ? précise leur rôle et leurs
     *  champs principaux". Se distingue de MODULE_GENERAL (aucun filtre thématique) et d'une question sur UNE
     *  table nommée (OTHER, "explique la table X"). */
    TOPIC_TABLES,
    /** Comparaison entre deux éléments ("différence entre A et B"). */
    COMPARE,
    /** Règles de validation, formats, longueurs, valeurs autorisées d'un champ (question autonome). */
    VALIDATION,
    /** Question fermée appelant une réponse brève (oui/non, caractère obligatoire). */
    SIMPLE,
    /** Question de suivi : approfondir ("Explique davantage."). */
    FOLLOWUP_MORE,
    /** Question de suivi : un exemple ("Peux-tu me donner un exemple concret ?"). */
    FOLLOWUP_EXAMPLE,
    /** Question de suivi : valeurs possibles ("Et ses valeurs ?"). */
    FOLLOWUP_VALUES,
    /** Question de suivi : règles de validation ("Et quelles sont les règles de validation ?"). */
    FOLLOWUP_VALIDATION,
    /** Toute autre question (champ, table, liste... : la récupération classique s'applique). */
    OTHER;

    private static final int FOLLOWUP_MAX_WORDS = 11;

    private static final Pattern DOTTED = Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)+");

    private static final Pattern EXAMPLE_PATTERN = Pattern.compile(
            "(?iu)\\b(exemples?|cas concrets?|illustre|illustration|mets? en situation)\\b");

    private static final Pattern VALIDATION_PATTERN = Pattern.compile(
            "(?iu)r[èe]gles?\\s+de\\s+(validation|saisie|contr[ôo]le)|contr[ôo]les?\\s+de\\s+saisie|validation|"
                    + "valeurs?\\s+(autoris[ée]es?|possibles?|accept[ée]es?|permises?)|formats?\\b|longueurs?\\b|"
                    + "combien de caract[èe]res|obligatoire|facultatif|optionnel");

    private static final Pattern VALUES_PATTERN = Pattern.compile("(?iu)\\b(valeurs?|options?|choix|possibilit[ée]s?)\\b");

    private static final Pattern MORE_PATTERN = Pattern.compile(
            "(?iu)\\b(davantage|plus de d[ée]tails?|plus en d[ée]tail|approfondi\\w*|d[ée]veloppe\\w*|continue|"
                    + "en savoir plus|d[ée]taille\\w*|pr[ée]cise\\w*|elabore|[ée]labore)\\b");

    private static final Pattern COMPARE_PATTERN = Pattern.compile(
            "(?iu)\\b(diff[ée]rences?|compar\\w*|versus|par rapport [àa])\\b|\\bvs\\b|\\bentre\\b.+\\bet\\b");

    private static final Pattern MODULE_WORD = Pattern.compile("(?iu)\\bmodule\\b");

    private static final Pattern MODULE_GENERAL_PATTERN = Pattern.compile(
            "(?iu)(quels?\\s+(types?|genres?|sortes?)|que\\s+(peut|puis|pourrait|trouve|contient)|qu'est-ce que|"
                    + "c['’]?\\s*(?:est\\s+)?quoi|"
                    + "[àa]\\s+quoi\\s+sert|pr[ée]sente|d[ée]cri[sv]|explique|parle|r[ôo]le|fonctionnalit|"
                    + "informations?\\s+(disponibles?|document[ée]es?)|contient|couvre|g[èe]re)");

    private static final Pattern CLOSED_PATTERN = Pattern.compile(
            "(?iu)^\\s*(?:et\\s+)?(?:est-ce|est-il|est-elle|y a-t-il|existe)\\b|\\b(?:est-il|est-elle|doit-il|doit-elle|faut-il)\\b");

    private static final Pattern TABLES_WORD_PATTERN = Pattern.compile("(?iu)\\btables?\\b");

    /** Filtre thématique ("[qui] contiennent/gèrent/traitent...", "liées à", "informations sur") ou demande de
     *  détail par table ("rôle", "champs principaux", "pour chacune") : signale une vraie question à traiter par
     *  le LLM (recherche + synthèse), jamais une simple énumération de noms. Le "qui" est optionnel : une question
     *  comme "quelles tables de ce module gèrent la production de relevés de compte ?" (bug trouvé en pratique)
     *  omet "qui" ("tables ... gèrent ...", pas "tables qui gèrent ...") et ratait ce filtre, tombant à tort sur
     *  MODULE_GENERAL (dont le motif large "g[èe]re" matche n'importe où, y compris dans "gèrent") — qui régénère
     *  alors une présentation générique du module entier au lieu de répondre à la question thématique posée. Les
     *  verbes restent conjugués au pluriel (accord avec "tables", le sujet grammatical réel) pour ne pas non plus
     *  matcher une question fermée du type "le module gère-t-il X ?" (toujours au singulier, "gère"). */
    private static final Pattern TOPIC_TABLES_PATTERN = Pattern.compile(
            "(?iu)(?:qui\\s+)?(contiennent|g[èe]rent|traitent|portent|concernent|stockent)|"
                    + "li[ée]es?\\s+[àa]|relatives?\\s+[àa]|informations?\\s+sur|concernant|"
                    + "(r[ôo]le|champs?)\\s+(principaux?|principales?)|pour\\s+chacune|pour\\s+chaque\\s+table|"
                    // "quelle table est responsable de X ?" (bug trouvé en pratique, module MD, "responsable de
                    // balances") : forme au singulier ("table", pas "tables"), déjà couverte par TABLES_WORD_PATTERN
                    // (son "s" est optionnel), mais qu'aucune des alternatives ci-dessus ne reconnaissait.
                    + "responsables?\\s+de|en\\s+charge\\s+de");

    /** Une table nommée explicitement ("la table ACCOUNT") : question OTHER classique sur cette table précise,
     *  jamais une recherche thématique sur plusieurs tables. */
    private static final Pattern SINGLE_TABLE_NAMED_PATTERN = Pattern.compile("(?iu)\\bla\\s+table\\s+\\S");

    /**
     * @param question   texte de la question de l'utilisateur
     * @param hasHistory vrai si la conversation contient déjà des échanges (condition d'une question de suivi)
     */
    public static QuestionIntent classify(String question, boolean hasHistory) {
        if (question == null || question.isBlank()) return OTHER;
        int words = question.trim().split("\\s+").length;
        boolean hasDotted = DOTTED.matcher(question).find();

        if (hasHistory && words <= FOLLOWUP_MAX_WORDS && !hasDotted) {
            if (EXAMPLE_PATTERN.matcher(question).find()) return FOLLOWUP_EXAMPLE;
            if (VALIDATION_PATTERN.matcher(question).find()) return FOLLOWUP_VALIDATION;
            if (VALUES_PATTERN.matcher(question).find()) return FOLLOWUP_VALUES;
            if (MORE_PATTERN.matcher(question).find()) return FOLLOWUP_MORE;
        }
        if (COMPARE_PATTERN.matcher(question).find() && countDotted(question) >= 2) return COMPARE;
        if (!hasDotted && TABLES_WORD_PATTERN.matcher(question).find() && TOPIC_TABLES_PATTERN.matcher(question).find()
                && !SINGLE_TABLE_NAMED_PATTERN.matcher(question).find()) {
            return TOPIC_TABLES;
        }
        if (!hasDotted && MODULE_WORD.matcher(question).find() && MODULE_GENERAL_PATTERN.matcher(question).find()) {
            return MODULE_GENERAL;
        }
        if (CLOSED_PATTERN.matcher(question).find()) return SIMPLE;
        if (VALIDATION_PATTERN.matcher(question).find() && !CLOSED_PATTERN.matcher(question).find() && words >= 4) return VALIDATION;
        return OTHER;
    }

    public boolean isFollowUp() {
        return this == FOLLOWUP_MORE || this == FOLLOWUP_EXAMPLE || this == FOLLOWUP_VALUES
                || this == FOLLOWUP_VALIDATION;
    }

    private static int countDotted(String q) {
        int n = 0;
        var m = DOTTED.matcher(q);
        while (m.find()) n++;
        return n;
    }
}
