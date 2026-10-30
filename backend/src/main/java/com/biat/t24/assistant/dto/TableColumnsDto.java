package com.biat.t24.assistant.dto;

import java.util.List;

public record TableColumnsDto(String tableName, List<String> columns) {
}
