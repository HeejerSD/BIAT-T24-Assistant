package com.biat.t24.assistant.repository;

import com.biat.t24.assistant.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCase(String username);
    long countByCreatedAtLessThanEqual(LocalDateTime dt);
}
