package com.localmind.dto;

public record DocumentUploadCommand(
        String fileName,
        String contentType,
        byte[] content) {
}
