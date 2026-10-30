package com.biat.t24.assistant.service;

import com.biat.t24.assistant.model.User;
import com.biat.t24.assistant.repository.ChatHistoryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Dernière activité réelle d'un utilisateur : la plus récente entre sa dernière connexion ({@link
 * User#getLastLoginAt()}) et la dernière question qu'il a posée dans le chat. La seule connexion sous-estime
 * l'activité d'un utilisateur resté connecté avec un jeton encore valide, qui continue à utiliser l'assistant
 * sans jamais repasser par /auth/login (observé en pratique : un utilisateur affiché "non actif" alors qu'il
 * venait de poser une question quelques minutes plus tôt). Utilisé par AuthController#listUsers et
 * AdminDashboardController, pour une définition cohérente de "actif" partout dans l'administration.
 */
@Service
public class UserActivityService {

    private final ChatHistoryRepository chatHistoryRepository;

    public UserActivityService(ChatHistoryRepository chatHistoryRepository) {
        this.chatHistoryRepository = chatHistoryRepository;
    }

    /** Nom d'utilisateur (tel que stocké) -> date/heure de sa dernière question, toutes conversations confondues. */
    public Map<String, LocalDateTime> lastQuestionAtByUsername() {
        Map<String, LocalDateTime> map = new HashMap<>();
        for (Object[] row : chatHistoryRepository.lastQuestionAtByUsername()) {
            map.put((String) row[0], (LocalDateTime) row[1]);
        }
        return map;
    }

    /** La plus récente entre {@code user.getLastLoginAt()} et sa dernière question (voir {@link
     *  #lastQuestionAtByUsername()}) ; {@code null} si aucune des deux n'est connue. */
    public LocalDateTime lastActivityOf(User user, Map<String, LocalDateTime> lastQuestionByUsername) {
        LocalDateTime login = user.getLastLoginAt();
        LocalDateTime question = lastQuestionByUsername.get(user.getUsername());
        if (login == null) return question;
        if (question == null) return login;
        return login.isAfter(question) ? login : question;
    }
}
