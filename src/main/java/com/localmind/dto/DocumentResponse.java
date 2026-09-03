package com.localmind.dto;

import java.time.Instant;

public record DocumentResponse(
        Long id,
        String name,
        String contentType,
        long sizeBytes,
        String status,
        int chunkCount,
        String errorMessage,
        Instant createdAt,
        boolean confirmable) {
}