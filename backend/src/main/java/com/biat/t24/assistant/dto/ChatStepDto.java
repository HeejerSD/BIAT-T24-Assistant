package com.biat.t24.assistant.dto;

/** Un échange du dialogue guidé (choix de module/table) à persister sans passer par la recherche/LLM. */
public record ChatStepDto(Long conversationId, String userMessage, String botMessage) {
}
