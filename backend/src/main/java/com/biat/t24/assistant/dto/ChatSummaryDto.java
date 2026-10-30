package com.biat.t24.assistant.dto;

import java.time.LocalDateTime;

public record ChatSummaryDto(Long id, String title, LocalDateTime updatedAt, boolean pinned, String username) {
}
