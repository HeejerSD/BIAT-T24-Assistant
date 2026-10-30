package com.biat.t24.assistant.controller;

import com.biat.t24.assistant.dto.DashboardStatsDto;
import com.biat.t24.assistant.dto.SourceFieldDto;
import com.biat.t24.assistant.model.Chat;
import com.biat.t24.assistant.model.ChatHistory;
import com.biat.t24.assistant.model.User;
import com.biat.t24.assistant.model.UserLoginEvent;
import com.biat.t24.assistant.repository.ChatHistoryRepository;
import com.biat.t24.assistant.repository.UserLoginEventRepository;
import com.biat.t24.assistant.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dashboard super administrateur : vue d'ensemble de l'utilisation de l'Assistant T24 sur une période choisie
 * (utilisateurs, questions, modules/tables/champs les plus consultés) — entièrement calculé depuis les données
 * réellement en base (APP_USER, CHAT, CHAT_HISTORY), jamais de chiffre inventé. Remplace l'icône de notification
 * "Demandes de réinitialisation" dans la navbar, le flux de réinitialisation étant désormais en libre-service
 * (voir PasswordResetController#resetSelf).
 */
@RestController
@RequestMapping("/admin/dashboard")
public class AdminDashboardController {

    private static final ObjectMapper SOURCE_FIELDS_JSON = new ObjectMapper();
    /** Nombre de modules/tables/champs les plus questionnés retenus pour l'affichage. */
    private static final int TOP_N = 5;
    private static final int RECENT_QUESTIONS_N = 6;

    private final UserRepository userRepository;
    private final ChatHistoryRepository chatHistoryRepository;
    private final UserLoginEventRepository loginEventRepository;
    private final com.biat.t24.assistant.service.UserActivityService userActivityService;

    public AdminDashboardController(UserRepository userRepository, ChatHistoryRepository chatHistoryRepository,
                                     UserLoginEventRepository loginEventRepository,
                                     com.biat.t24.assistant.service.UserActivityService userActivityService) {
        this.userRepository = userRepository;
        this.chatHistoryRepository = chatHistoryRepository;
        this.loginEventRepository = loginEventRepository;
        this.userActivityService = userActivityService;
    }

    @GetMapping
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<DashboardStatsDto> stats(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        LocalDate toDate = to != null && !to.isBlank() ? LocalDate.parse(to) : LocalDate.now();
        LocalDate fromDate = from != null && !from.isBlank() ? LocalDate.parse(from) : toDate.minusDays(29);
        if (fromDate.isAfter(toDate)) { LocalDate tmp = fromDate; fromDate = toDate; toDate = tmp; }
        LocalDateTime fromDt = fromDate.atStartOfDay();
        LocalDateTime toDt = toDate.plusDays(1).atStartOfDay().minusNanos(1);

        long periodDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        LocalDate prevTo = fromDate.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(periodDays - 1);
        LocalDateTime prevFromDt = prevFrom.atStartOfDay();
        LocalDateTime prevToDt = prevTo.plusDays(1).atStartOfDay().minusNanos(1);

        // --- Utilisateurs ---
        // "Actif" = dernière ACTIVITÉ RÉELLE (connexion OU question posée, la plus récente des deux) dans la
        // période — jamais la seule connexion, qui sous-estime un utilisateur resté connecté avec un jeton encore
        // valide (observé en pratique : utilisateur affiché "non actif" alors qu'il venait de poser une question).
        var lastQuestionByUsername = userActivityService.lastQuestionAtByUsername();
        List<User> allUsers = userRepository.findAll();
        Map<User, LocalDateTime> lastActivityByUser = new LinkedHashMap<>();
        for (User u : allUsers) lastActivityByUser.put(u, userActivityService.lastActivityOf(u, lastQuestionByUsername));

        long totalUsers = userRepository.count();
        long totalUsersAsOfPrevEnd = userRepository.countByCreatedAtLessThanEqual(prevToDt);
        long totalUsersDelta = totalUsers - totalUsersAsOfPrevEnd;
        double totalUsersDeltaPct = pct(totalUsersDelta, totalUsersAsOfPrevEnd);

        long activeUsers = lastActivityByUser.values().stream()
                .filter(t -> t != null && !t.isBefore(fromDt) && !t.isAfter(toDt)).count();
        long inactiveUsers = Math.max(0, totalUsers - activeUsers);
        double activeUsersPct = pct(activeUsers, totalUsers);

        List<DashboardStatsDto.RecentUser> recentUsers = lastActivityByUser.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .sorted(Map.Entry.<User, LocalDateTime>comparingByValue().reversed())
                .limit(5)
                .map(e -> {
                    LocalDateTime last = e.getValue();
                    boolean active = !last.isBefore(fromDt) && !last.isAfter(toDt);
                    return new DashboardStatsDto.RecentUser(e.getKey().getUsername(), displayRole(e.getKey().getRole()),
                            active ? "En ligne" : "Hors ligne", last);
                })
                .toList();

        // --- Questions (volume) ---
        long totalQuestions = chatHistoryRepository.countByMessageTypeIgnoreCaseAndCreatedAtBetween("question", fromDt, toDt);
        long prevTotalQuestions = chatHistoryRepository.countByMessageTypeIgnoreCaseAndCreatedAtBetween("question", prevFromDt, prevToDt);
        long totalQuestionsDelta = totalQuestions - prevTotalQuestions;
        double totalQuestionsDeltaPct = pct(totalQuestionsDelta, prevTotalQuestions);

        // --- Activité quotidienne (utilisateurs distincts connectés par jour) ---
        Map<LocalDate, Set<String>> byDay = new TreeMap<>();
        for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) byDay.put(d, new HashSet<>());
        for (UserLoginEvent e : loginEventRepository.findByLoginAtBetweenOrderByLoginAtAsc(fromDt, toDt)) {
            byDay.computeIfAbsent(e.getLoginAt().toLocalDate(), d -> new HashSet<>()).add(e.getUsername());
        }
        List<DashboardStatsDto.DailyCount> dailyActivity = byDay.entrySet().stream()
                .map(e -> new DashboardStatsDto.DailyCount(e.getKey(), e.getValue().size()))
                .toList();

        // --- Modules/tables/champs les plus questionnés + dernières questions (depuis CHAT_HISTORY) ---
        Map<String, Long> moduleCounts = new LinkedHashMap<>();
        Map<String, Long> tableCounts = new LinkedHashMap<>();
        Map<String, Long> fieldCounts = new LinkedHashMap<>();
        List<DashboardStatsDto.RecentQuestion> questions = new ArrayList<>();

        ChatHistory pendingQuestion = null;
        for (ChatHistory h : chatHistoryRepository.findWithChatByCreatedAtBetween(fromDt, toDt)) {
            if ("question".equalsIgnoreCase(h.getMessageType())) {
                pendingQuestion = h;
                continue;
            }
            if (!"reponse".equalsIgnoreCase(h.getMessageType())) continue;
            List<SourceFieldDto> sources = deserializeSourceFields(h.getSourceColumns());
            Set<String> modulesInThisAnswer = new HashSet<>();
            Set<String> tablesInThisAnswer = new HashSet<>();
            for (SourceFieldDto s : sources) {
                if (s.module() != null && !s.module().isBlank() && modulesInThisAnswer.add(s.module().toUpperCase())) {
                    moduleCounts.merge(s.module().toUpperCase(), 1L, Long::sum);
                }
                if (s.tableName() != null && !s.tableName().isBlank() && tablesInThisAnswer.add(s.tableName().toUpperCase())) {
                    tableCounts.merge(s.tableName().toUpperCase(), 1L, Long::sum);
                }
                if (s.columnName() != null && !s.columnName().isBlank()) {
                    fieldCounts.merge(s.columnName().toUpperCase(), 1L, Long::sum);
                }
            }
            if (pendingQuestion != null) {
                Chat chat = h.getChat();
                SourceFieldDto first = sources.stream().filter(s -> s.tableName() != null).findFirst().orElse(null);
                questions.add(new DashboardStatsDto.RecentQuestion(pendingQuestion.getContent(),
                        first != null ? first.module() : null, first != null ? first.tableName() : null,
                        chat != null ? chat.getUsername() : null, h.getCreatedAt()));
                pendingQuestion = null;
            }
        }
        // Tri explicite par date décroissante : les lignes de CHAT_HISTORY sont lues groupées par conversation
        // (voir findWithChatByCreatedAtBetween, triée par chat puis date ASC), jamais dans l'ordre chronologique
        // global entre conversations différentes — un simple "reverse" de la liste donnait donc un ordre erroné
        // dès que plusieurs conversations se chevauchaient dans le temps (bug trouvé en pratique).
        List<DashboardStatsDto.RecentQuestion> recentQuestions = questions.stream()
                .sorted(Comparator.comparing(DashboardStatsDto.RecentQuestion::date).reversed())
                .limit(RECENT_QUESTIONS_N)
                .toList();

        // Toujours recalculé depuis la base à chaque appel (aucun cache applicatif), mais sans l'en-tête
        // Cache-Control ci-dessous, le navigateur pouvait réafficher une réponse mise en cache localement après un
        // retour en arrière/ré-ouverture d'onglet — observé en pratique, un dashboard qui ne reflétait plus les
        // utilisateurs supprimés directement en base tant que la page n'était pas rechargée.
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new DashboardStatsDto(fromDate, toDate,
                        totalUsers, totalUsersDelta, totalUsersDeltaPct,
                        activeUsers, inactiveUsers, activeUsersPct,
                        totalQuestions, totalQuestionsDelta, totalQuestionsDeltaPct,
                        recentUsers, dailyActivity,
                        topN(moduleCounts), topN(tableCounts), topN(fieldCounts),
                        recentQuestions));
    }

    private static double pct(long delta, long base) {
        if (base > 0) return Math.round(delta * 1000.0 / base) / 10.0;
        return delta > 0 ? 100.0 : 0.0;
    }

    private static List<DashboardStatsDto.LabeledCount> topN(Map<String, Long> counts) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(TOP_N)
                .map(e -> new DashboardStatsDto.LabeledCount(e.getKey(), e.getValue(),
                        total > 0 ? Math.round(e.getValue() * 1000.0 / total) / 10.0 : 0.0))
                .toList();
    }

    private static String displayRole(String role) {
        if (role == null) return "";
        return switch (role.toLowerCase()) {
            case "superadmin" -> "Super Admin";
            case "admin" -> "Admin";
            case "consultant" -> "Consultant";
            default -> role;
        };
    }

    private static List<SourceFieldDto> deserializeSourceFields(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return SOURCE_FIELDS_JSON.readValue(json, new TypeReference<List<SourceFieldDto>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
