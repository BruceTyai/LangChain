package com.localmind.dto;

import java.util.List;

public record ChatResponse(String answer, List<ChatSource> sources) {
}

