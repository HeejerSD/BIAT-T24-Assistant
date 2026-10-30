package com.biat.t24.assistant.repository;

import com.biat.t24.assistant.model.ChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {
    List<ChatHistory> findByChat_IdOrderByCreatedAtAsc(Long chatId);
    void deleteByChat_Id(Long chatId);
    long countByMessageTypeIgnoreCase(String messageType);
    long countByChat_UsernameIgnoreCaseAndMessageTypeIgnoreCase(String username, String messageType);
    long countByMessageTypeIgnoreCaseAndCreatedAtBetween(String messageType, LocalDateTime from, LocalDateTime to);

    /** Chat chargé en même temps (JOIN FETCH) pour lire chat.username sans accès paresseux hors session — voir
     *  AdminDashboardController (agrégation modules/tables/champs/questions les plus consultés). */
    @Query("SELECT ch FROM ChatHistory ch JOIN FETCH ch.chat c WHERE ch.createdAt BETWEEN :from AND :to ORDER BY c.id ASC, ch.createdAt ASC")
    List<ChatHistory> findWithChatByCreatedAtBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Dernière question posée par chaque utilisateur (toutes conversations confondues) : un utilisateur déjà
     *  connecté AVANT la mise en place du suivi des connexions (voir User#lastLoginAt) n'a jamais besoin de se
     *  reconnecter tant que son jeton reste valide — son activité réelle (poser des questions) ne se voit donc
     *  qu'ici, pas dans lastLoginAt. Voir AdminDashboardController#lastActivityByUsername, qui combine les deux. */
    @Query("SELECT c.username, MAX(ch.createdAt) FROM ChatHistory ch JOIN ch.chat c WHERE ch.messageType = 'question' GROUP BY c.username")
    List<Object[]> lastQuestionAtByUsername();
}
