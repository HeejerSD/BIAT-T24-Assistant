package com.biat.t24.assistant.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ChatHistoryDto(String messageType, String content, LocalDateTime createdAt,
                              List<SourceFieldDto> sourceFields) {
}
