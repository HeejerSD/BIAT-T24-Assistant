package com.biat.t24.assistant.dto;

import java.time.LocalDateTime;
import java.util.List;

/** {@code tables} : rempli uniquement quand la question demande la liste des tables d'un module (bulles cliquables
 *  côté frontend), vide sinon. {@code sourceFields} : pour la carte "Voir la source", voir {@link SourceFieldDto}.
 *  {@code resolvedModule} : non nul uniquement quand la question nommait explicitement un AUTRE module que celui
 *  transmis par le frontend (ex. "présente-moi le module EB" alors que le module sélectionné était encore "MD") —
 *  le frontend met alors à jour sa propre sélection pour que les questions suivantes restent sur ce nouveau module. */
public record ChatResponseDto(String answer, List<ColumnResultDto> columns, String status,
                                double confidence, LocalDateTime timestamp, Long conversationId,
                                List<TableSummaryDto> tables, List<SourceFieldDto> sourceFields,
                                String resolvedModule) {
}
