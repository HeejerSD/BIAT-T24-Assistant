package com.biat.t24.assistant.service.RAG_service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classification dynamique du caractère obligatoire d'un champ T24, recalculée à partir de sa Description à
 * chaque utilisation (affichage des cartes, contexte donné au LLM) — jamais depuis la ligne "Obligatoire : "
 * telle qu'écrite en base au moment de l'import, qui peut devenir obsolète si cette logique est améliorée après
 * coup. Toute évolution de cette classe s'applique donc immédiatement à tous les champs déjà importés, sans
 * réindexation ni recalcul d'embedding.
 *
 * Le HelpText T24 commence conventionnellement la description par "Mandatory."/"Optional."/"No input field..." :
 * on se base sur ce début plutôt que sur une simple recherche de "mandatory" n'importe où dans le texte, qui
 * donnerait un faux positif pour un champ optionnel par défaut mais conditionnellement obligatoire (ex: "Optional.
 * Becomes mandatory if..." serait à tort classé "OUI" alors que la vraie réponse est "NON, sauf dans ce cas précis").
 */
public final class MandatoryClassifier {

    private static final Pattern OBLIGATOIRE_LINE = Pattern.compile("(?m)^Obligatoire : .*$");
    private static final String DESCRIPTION_LABEL = "Description : ";

    public static String classify(String description) {
        if (description == null || description.isBlank()) return "NON SPÉCIFIÉ";
        String d = description.trim().toUpperCase();
        if (d.startsWith("NO INPUT FIELD") || d.startsWith("SYSTEM GENERATED")) return "NON (auto-calculé)";
        if (d.startsWith("OPTIONAL")) return "NON";
        if (d.startsWith("MANDATORY")) return "OUI";
        if (d.contains("NO INPUT FIELD") || d.contains("SYSTEM GENERATED")) return "NON (auto-calculé)";
        if (d.contains("MANDATORY")) return "OUI";
        if (d.contains("OPTIONAL")) return "NON";
        return "NON SPÉCIFIÉ";
    }

    /**
     * Réécrit la ligne "Obligatoire : " d'un chunk (format produit par DocumentServiceImpl#buildChunkText) avec
     * la classification fraîchement recalculée depuis sa propre Description, pour que le contexte donné au LLM
     * ne s'appuie jamais sur une valeur potentiellement obsolète stockée en base.
     */
    public static String withFreshMandatoryLine(String chunkContent) {
        if (chunkContent == null) return null;
        String fresh = classify(extractDescription(chunkContent));
        return OBLIGATOIRE_LINE.matcher(chunkContent).replaceFirst("Obligatoire : " + Matcher.quoteReplacement(fresh));
    }

    private static String extractDescription(String content) {
        int start = content.indexOf(DESCRIPTION_LABEL);
        return start < 0 ? "" : content.substring(start + DESCRIPTION_LABEL.length()).trim();
    }

    private MandatoryClassifier() {}
}
