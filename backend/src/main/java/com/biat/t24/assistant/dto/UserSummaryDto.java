package com.biat.t24.assistant.dto;

import java.time.LocalDateTime;

/** Un utilisateur tel que listé sur la page "Utilisateurs" du super administrateur — jamais le mot de passe
 *  (même haché). {@code lastActivityAt} : dernière connexion OU dernière question posée, la plus récente des
 *  deux (voir AuthController#listUsers) — jamais seulement la connexion, qui sous-estime l'activité réelle d'un
 *  utilisateur resté connecté avec un jeton encore valide sans s'être reconnecté depuis. */
public record UserSummaryDto(String username, String email, String role, boolean active,
                              LocalDateTime createdAt, LocalDateTime lastActivityAt) {
}
