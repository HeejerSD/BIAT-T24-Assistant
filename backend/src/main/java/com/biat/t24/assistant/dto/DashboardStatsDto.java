package com.biat.t24.assistant.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Statistiques réelles (base de données, aucune valeur inventée) du dashboard super administrateur, sur la
 *  période {@code from}..{@code to} sélectionnée (voir AdminDashboardController). */
public record DashboardStatsDto(
        LocalDate from, LocalDate to,
        long totalUsers, long totalUsersDelta, double totalUsersDeltaPct,
        long activeUsers, long inactiveUsers, double activeUsersPct,
        long totalQuestions, long totalQuestionsDelta, double totalQuestionsDeltaPct,
        List<RecentUser> recentUsers,
        List<DailyCount> dailyActivity,
        List<LabeledCount> topModules,
        List<LabeledCount> topTables,
        List<LabeledCount> topFields,
        List<RecentQuestion> recentQuestions) {

    /** {@code lastActivityAt} : dernière connexion OU dernière question posée, la plus récente des deux (voir
     *  AdminDashboardController#lastActivityByUsername) — jamais seulement la connexion, qui sous-estime
     *  l'activité réelle d'un utilisateur resté connecté avec un jeton encore valide. */
    public record RecentUser(String username, String role, String status, LocalDateTime lastActivityAt) {}

    public record DailyCount(LocalDate date, long count) {}

    public record LabeledCount(String label, long count, double pct) {}

    public record RecentQuestion(String question, String module, String table, String username, LocalDateTime date) {}
}
