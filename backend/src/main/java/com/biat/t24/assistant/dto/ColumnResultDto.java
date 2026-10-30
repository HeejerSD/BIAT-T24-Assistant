package com.biat.t24.assistant.dto;

public record ColumnResultDto(String columnName, String tableName, String module, String dataType,
                                String inputType, boolean mandatory, String description,
                                boolean standardSupported, Integer helperLineNumber) {
}
