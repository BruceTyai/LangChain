package com.localmind.dto;

import java.util.List;

public record ChatResponse(
        String answer,
        AnswerType answerType,
        List<ChatSource> sources) {
}
