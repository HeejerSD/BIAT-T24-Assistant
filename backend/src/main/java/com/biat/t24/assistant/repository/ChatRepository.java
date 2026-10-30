package com.biat.t24.assistant.repository;

import com.biat.t24.assistant.model.Chat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatRepository extends JpaRepository<Chat, Long> {
    List<Chat> findByUsernameOrderByPinnedDescUpdatedAtDescIdDesc(String username);
    Optional<Chat> findByIdAndUsername(Long id, String username);
    List<Chat> findByUsernameAndTitleContainingIgnoreCaseOrderByPinnedDescUpdatedAtDescIdDesc(String username, String title);

    /** Réservé au superAdmin : historique de tous les utilisateurs, pas seulement le sien. */
    List<Chat> findAllByOrderByPinnedDescUpdatedAtDescIdDesc();
    List<Chat> findByTitleContainingIgnoreCaseOrderByPinnedDescUpdatedAtDescIdDesc(String title);
}
