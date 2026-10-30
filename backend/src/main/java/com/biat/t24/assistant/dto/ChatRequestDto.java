package com.biat.t24.assistant.dto;

public record ChatRequestDto(String question, String module, String tableName, Long conversationId) {
}
