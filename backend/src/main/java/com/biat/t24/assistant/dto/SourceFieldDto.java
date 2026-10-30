package com.biat.t24.assistant.dto;

/**
 * Table et colonne source d'une réponse, pour la carte "Voir la source" : {@code mandatoryLabel},
 * {@code description} et {@code validationRule} sont null quand la question n'a pas porté sur cet aspect précis
 * (voir ChatController#buildSourceFields) — la carte n'affiche ainsi que ce qui a été réellement demandé, jamais
 * tout le détail d'un champ pour n'importe quelle question, afin que l'utilisateur voie que le retrieval cible
 * précisément ce qui a été demandé.
 * <p>{@code module} et {@code xmlFile} identifient précisément l'origine documentaire (module T24, nom du fichier
 * HelpText source) : {@code xmlFile} est déduit du nom de table exact selon la même convention que l'import réel
 * (voir T24ImportService — un fichier "NOM.TABLE.xml" produit toujours la table "NOM.TABLE"), jamais un chemin
 * inventé — seul le nom de fichier est reconstruit, pas un chemin disque absolu qui varie selon l'environnement.
 */
public record SourceFieldDto(String tableName, String columnName, String mandatoryLabel, String description,
                              String validationRule, String module, String xmlFile) {
}
