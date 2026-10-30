package com.biat.t24.assistant.controller;

import com.biat.t24.assistant.dto.ChangePasswordRequest;
import com.biat.t24.assistant.dto.LoginRequest;
import com.biat.t24.assistant.dto.LoginResponse;
import com.biat.t24.assistant.dto.PublicRegisterRequest;
import com.biat.t24.assistant.dto.RegisterRequest;
import com.biat.t24.assistant.dto.UserSummaryDto;
import com.biat.t24.assistant.model.User;
import com.biat.t24.assistant.model.UserLoginEvent;
import com.biat.t24.assistant.repository.UserLoginEventRepository;
import com.biat.t24.assistant.repository.UserRepository;
import com.biat.t24.assistant.security.JwtService;
import com.biat.t24.assistant.service.UserActivityService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@CrossOrigin(origins = "http://localhost:4200")
public class AuthController {

    /** Un superAdmin ne peut créer que des consultants ou des admins — jamais un autre superAdmin. */
    private static final List<String> CREATABLE_ROLES = List.of("consultant", "admin");

    private final UserRepository userRepository;
    private final UserLoginEventRepository loginEventRepository;
    private final UserActivityService userActivityService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserRepository userRepository, UserLoginEventRepository loginEventRepository,
                           UserActivityService userActivityService, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.loginEventRepository = loginEventRepository;
        this.userActivityService = userActivityService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        User user = userRepository.findByUsernameIgnoreCase(req.getUsername()).orElse(null);
        if (user == null || !user.isActive() || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            return ResponseEntity.status(401).body(Map.of("message", "Identifiants incorrects"));
        }
        // Alimente le dashboard super administrateur (utilisateurs actifs/non actifs, connexions récentes,
        // activité quotidienne) — voir User#lastLoginAt et UserLoginEvent.
        var now = java.time.LocalDateTime.now();
        user.setLastLoginAt(now);
        userRepository.save(user);
        UserLoginEvent event = new UserLoginEvent();
        event.setUsername(user.getUsername());
        event.setLoginAt(now);
        loginEventRepository.save(event);

        String token = jwtService.generateToken(user.getUsername(), user.getRole());
        return ResponseEntity.ok(new LoginResponse(token, user.getUsername(), user.getRole()));
    }

    @PostMapping("/register")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        if (CREATABLE_ROLES.stream().noneMatch(r -> r.equalsIgnoreCase(req.getRole()))) {
            return ResponseEntity.badRequest().body(Map.of("message", "Rôle invalide (consultant ou admin uniquement — un superAdmin ne peut pas en créer un autre)"));
        }
        if (userRepository.existsByUsernameIgnoreCase(req.getUsername())) {
            return ResponseEntity.status(409).body(Map.of("message", "Ce nom d'utilisateur existe déjà"));
        }

        User user = new User();
        user.setUsername(req.getUsername());
        user.setEmail(req.getEmail());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRole(req.getRole());
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("username", user.getUsername(), "role", user.getRole()));
    }

    /** Inscription publique depuis la page de login (aucune authentification requise). Le rôle est toujours
     *  "consultant" — imposé côté serveur, jamais lu depuis la requête — pour qu'un visiteur ne puisse pas
     *  s'auto-attribuer un rôle admin/superAdmin. */
    @PostMapping("/register-public")
    public ResponseEntity<?> registerPublic(@Valid @RequestBody PublicRegisterRequest req) {
        if (userRepository.existsByUsernameIgnoreCase(req.getUsername())) {
            return ResponseEntity.status(409).body(Map.of("message", "Ce nom d'utilisateur existe déjà"));
        }

        User user = new User();
        user.setUsername(req.getUsername());
        user.setEmail(req.getEmail());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRole("consultant");
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("username", user.getUsername(), "role", user.getRole()));
    }

    /** Liste de tous les utilisateurs (page "Utilisateurs" du super administrateur) — jamais le mot de passe,
     *  même haché. Triée par nom d'utilisateur pour un affichage stable. */
    @GetMapping("/users")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<List<UserSummaryDto>> listUsers() {
        var lastQuestionByUsername = userActivityService.lastQuestionAtByUsername();
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(userRepository.findAll().stream()
                        .sorted((a, b) -> a.getUsername().compareToIgnoreCase(b.getUsername()))
                        .map(u -> new UserSummaryDto(u.getUsername(), u.getEmail(), u.getRole(), u.isActive(),
                                u.getCreatedAt(), userActivityService.lastActivityOf(u, lastQuestionByUsername)))
                        .toList());
    }

    /** Auto-service : n'importe quel utilisateur connecté (superAdmin y compris) change son propre mot de passe,
     *  sans passer par une demande de réinitialisation — indispensable pour le superAdmin, qui n'a personne
     *  au-dessus de lui pour approuver une telle demande. */
    @PutMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest req, Authentication auth) {
        User user = userRepository.findByUsernameIgnoreCase(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Utilisateur introuvable : " + auth.getName()));

        if (req.currentPassword() == null || !passwordEncoder.matches(req.currentPassword(), user.getPassword())) {
            return ResponseEntity.status(401).body(Map.of("message", "Mot de passe actuel incorrect"));
        }
        if (req.newPassword() == null || req.newPassword().length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("message", "Le nouveau mot de passe doit contenir au moins 6 caractères"));
        }

        user.setPassword(passwordEncoder.encode(req.newPassword()));
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }
}
