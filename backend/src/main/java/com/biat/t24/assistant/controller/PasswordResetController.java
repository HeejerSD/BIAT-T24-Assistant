package com.biat.t24.assistant.controller;

import com.biat.t24.assistant.dto.PasswordResetCreateDto;
import com.biat.t24.assistant.dto.PasswordResetSelfDto;
import com.biat.t24.assistant.model.PasswordResetRequest;
import com.biat.t24.assistant.model.User;
import com.biat.t24.assistant.repository.PasswordResetRequestRepository;
import com.biat.t24.assistant.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/** Mot de passe oublié : l'utilisateur le redéfinit lui-même directement (en libre-service, voir {@link
 *  #resetSelf}), sans passer par une demande qu'un super administrateur devrait approuver manuellement — l'ancien
 *  flux d'approbation (liste des demandes, résolution avec mot de passe aléatoire envoyé par email) a été retiré,
 *  son utilité ayant disparu avec le libre-service. */
@RestController
@RequestMapping("/auth/password-reset")
@CrossOrigin(origins = "http://localhost:4200")
public class PasswordResetController {

    private final PasswordResetRequestRepository repository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public PasswordResetController(PasswordResetRequestRepository repository, UserRepository userRepository,
                                    PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** Public (aucune authentification) : conservé pour compatibilité, non utilisé par l'écran de connexion
     *  actuel (voir {@link #resetSelf}, qui redéfinit le mot de passe directement). */
    @PostMapping
    public ResponseEntity<?> requestReset(@RequestBody PasswordResetCreateDto dto) {
        // Réponse volontairement identique que le compte existe ou non, pour ne pas permettre l'énumération de comptes.
        Map<String, String> genericResponse = Map.of("message", "Si ce compte existe, une demande a été transmise au super administrateur.");
        if (dto.username() == null || dto.username().isBlank()) {
            return ResponseEntity.ok(genericResponse);
        }
        userRepository.findByUsernameIgnoreCase(dto.username().trim()).ifPresent(user -> {
            PasswordResetRequest req = new PasswordResetRequest();
            req.setUsername(user.getUsername());
            repository.save(req);
        });
        return ResponseEntity.ok(genericResponse);
    }

    /** Public (aucune authentification) : l'utilisateur ayant oublié son mot de passe le redéfinit lui-même
     *  directement (saisi deux fois), sans attendre qu'un super administrateur traite une demande. La demande est
     *  quand même tracée (résolue immédiatement, "resolvedBy" = l'utilisateur lui-même) pour garder un historique
     *  en base, même si plus aucune page d'administration ne l'affiche. */
    @PostMapping("/self")
    @Transactional
    public ResponseEntity<?> resetSelf(@RequestBody PasswordResetSelfDto dto) {
        if (dto.username() == null || dto.username().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Nom d'utilisateur requis"));
        }
        if (dto.newPassword() == null || dto.newPassword().length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("message", "Le nouveau mot de passe doit contenir au moins 6 caractères"));
        }
        if (!dto.newPassword().equals(dto.confirmPassword())) {
            return ResponseEntity.badRequest().body(Map.of("message", "La confirmation ne correspond pas au mot de passe"));
        }
        User user = userRepository.findByUsernameIgnoreCase(dto.username().trim()).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Aucun compte ne correspond à ce nom d'utilisateur"));
        }

        user.setPassword(passwordEncoder.encode(dto.newPassword()));
        userRepository.save(user);

        PasswordResetRequest req = new PasswordResetRequest();
        req.setUsername(user.getUsername());
        req.setResolved(true);
        req.setResolvedAt(LocalDateTime.now());
        req.setResolvedBy(user.getUsername() + " (auto-service)");
        repository.save(req);

        return ResponseEntity.ok(Map.of("message", "Mot de passe mis à jour. Vous pouvez vous connecter."));
    }
}
