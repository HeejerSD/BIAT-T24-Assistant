package com.biat.t24.assistant.config;

import com.biat.t24.assistant.model.User;
import com.biat.t24.assistant.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.existsByUsernameIgnoreCase("admin")) return;

        User admin = new User();
        admin.setUsername("admin");
        admin.setEmail("admin@biat.tn");
        admin.setPassword(passwordEncoder.encode("Admin@2024"));
        admin.setRole("superAdmin");
        userRepository.save(admin);
        log.info("Utilisateur admin créé (username: admin / mot de passe: Admin@2024) — à changer après la première connexion.");
    }
}
