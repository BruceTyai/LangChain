package com.localmind.dto;

import java.util.List;

public record DocumentPageResponse(
        List<DocumentResponse> content,
        long totalElements,
        int totalPages,
        int page,
        long confirmableElements) {
}