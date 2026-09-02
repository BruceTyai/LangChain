package com.localmind.dto;

import com.localmind.dao.entity.KnowledgeDocument;
import java.time.Instant;

public record DocumentResponse(
        Long id,
        String name,
        String contentType,
        long sizeBytes,
        String status,
        int chunkCount,
        String errorMessage,
        Instant createdAt) {

    public static DocumentResponse from(KnowledgeDocument document) {
        return new DocumentResponse(
                document.getId(),
                document.getName(),
                document.getContentType(),
                document.getSizeBytes(),
                document.getStatus().name(),
                document.getChunkCount(),
                document.getErrorMessage(),
                document.getCreatedAt());
    }
}

