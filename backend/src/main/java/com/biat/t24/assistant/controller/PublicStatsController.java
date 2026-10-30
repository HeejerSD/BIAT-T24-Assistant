package com.biat.t24.assistant.controller;

import com.biat.t24.assistant.repository.ModuleT24Repository;
import com.biat.t24.assistant.repository.TableT24Repository;
import com.biat.t24.assistant.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Compteurs réels de la base, lisibles sans connexion pour la page d'accueil (aucune donnée sensible). */
@RestController
@RequestMapping("/auth")
public class PublicStatsController {

    private final ModuleT24Repository moduleRepository;
    private final TableT24Repository tableRepository;
    private final UserRepository userRepository;

    public PublicStatsController(ModuleT24Repository moduleRepository, TableT24Repository tableRepository,
                                 UserRepository userRepository) {
        this.moduleRepository = moduleRepository;
        this.tableRepository = tableRepository;
        this.userRepository = userRepository;
    }

    @GetMapping("/public-stats")
    public ResponseEntity<Map<String, Long>> publicStats() {
        return ResponseEntity.ok(Map.of(
                "modules", moduleRepository.count(),
                "tables", tableRepository.count(),
                "users", userRepository.count()));
    }
}
