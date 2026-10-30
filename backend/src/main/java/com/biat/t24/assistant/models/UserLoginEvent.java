package com.biat.t24.assistant.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Une connexion réussie, journalisée pour le graphique "Activité des utilisateurs" du dashboard super
 *  administrateur (nombre d'utilisateurs connectés par jour) — {@code User.lastLoginAt} ne garde que la DERNIÈRE
 *  connexion de chacun, insuffisant pour reconstituer un historique jour par jour. */
@Entity
@Table(name = "USER_LOGIN_EVENT")
public class UserLoginEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(name = "login_at", nullable = false)
    private LocalDateTime loginAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public LocalDateTime getLoginAt() { return loginAt; }
    public void setLoginAt(LocalDateTime loginAt) { this.loginAt = loginAt; }
}
