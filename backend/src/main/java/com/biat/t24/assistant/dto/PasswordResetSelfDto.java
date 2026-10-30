package com.biat.t24.assistant.dto;

/** Réinitialisation directe du mot de passe oublié par l'utilisateur lui-même (saisi deux fois), sans passer par
 *  une demande à traiter par le super administrateur — voir PasswordResetController#resetSelf. */
public record PasswordResetSelfDto(String username, String newPassword, String confirmPassword) {
}
