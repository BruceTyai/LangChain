package com.localmind.dto;

import java.time.Instant;
import java.util.List;

public record DocumentReindexJobResponse(
        String id,
        String scope,
        String status,
        int total,
        int completed,
        int succeeded,
        int failed,
        int skipped,
        Long currentDocumentId,
        String currentDocumentName,
        List<String> failures,
        Instant startedAt,
        Instant finishedAt) {
}