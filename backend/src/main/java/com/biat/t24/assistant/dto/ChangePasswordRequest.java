package com.biat.t24.assistant.dto;

public record ChangePasswordRequest(String currentPassword, String newPassword) {
}
