package com.biat.t24.assistant.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Un message (question ou réponse) d'un {@link Chat}. */
@Entity
@Table(name = "CHAT_HISTORY")
public class ChatHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id", nullable = false)
    private Chat chat;

    /** "question" ou "reponse". */
    @Column(name = "message_type", nullable = false, length = 10)
    private String messageType;

    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String content;

    /** Colonnes/tables sources de cette réponse (ex: [{"tableName":"LD.LOANS.AND.DEPOSITS","columnName":"..."}]),
     *  sérialisées en JSON — null pour une question ou un message sans source documentaire (small talk, repli
     *  connaissances générales). Persisté pour que le bouton "Voir la source" reste disponible après un
     *  rafraîchissement de page ou un changement de conversation, sans quoi cette info (jamais stockée ailleurs)
     *  serait perdue au rechargement de l'historique. */
    @Lob
    @Column(name = "source_columns", columnDefinition = "CLOB")
    private String sourceColumns;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Chat getChat() { return chat; }
    public void setChat(Chat chat) { this.chat = chat; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSourceColumns() { return sourceColumns; }
    public void setSourceColumns(String sourceColumns) { this.sourceColumns = sourceColumns; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
