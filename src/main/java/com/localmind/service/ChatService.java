package com.localmind.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localmind.dto.AnswerType;
import com.localmind.dto.ChatResponse;
import com.localmind.dto.ChatSource;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss EEEE");

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ObjectMapper objectMapper;
    private final int maxResults;
    private final double minScore;

    public ChatService(
            ChatModel chatModel,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            ObjectMapper objectMapper,
            @Value("${app.rag.max-results}") int maxResults,
            @Value("${app.rag.min-score}") double minScore) {

        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.objectMapper = objectMapper;
        this.maxResults = maxResults;
        this.minScore = minScore;
    }

    public ChatResponse ask(String question) {
        List<RetrievedSource> retrievedSources = retrieveSources(question);
        List<RetrievedSource> usableSources = selectUsableSources(question, retrievedSources);
        AnswerType answerType = usableSources.isEmpty()
                ? AnswerType.MODEL_FALLBACK
                : AnswerType.KNOWLEDGE_BASE;

        String currentDateTime = ZonedDateTime.now(SHANGHAI_ZONE)
                .format(DATE_TIME_FORMATTER);
        String context = answerType == AnswerType.KNOWLEDGE_BASE
                ? buildContext(usableSources)
                : "无可用知识库资料";

        String answer = chat(
                buildAnswerSystemPrompt(currentDateTime, answerType),
                buildAnswerUserPrompt(context, question, answerType)
        );
        List<ChatSource> responseSources = answerType == AnswerType.KNOWLEDGE_BASE
                ? usableSources.stream().map(RetrievedSource::source).toList()
                : List.of();

        log.info("LLM answer type: {}, answer: {}", answerType, answer);
        return new ChatResponse(answer, answerType, responseSources);
    }

    private List<RetrievedSource> retrieveSources(String question) {
        Embedding queryEmbedding = embeddingModel.embed(question).content();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(maxResults)
                        .minScore(minScore)
                        .build()
        );

        List<RetrievedSource> sources = new ArrayList<>();
        int index = 1;
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            TextSegment segment = match.embedded();
            String sourceName = segment.metadata().getString("source");
            String text = segment.text();
            ChatSource source = new ChatSource(
                    index,
                    sourceName,
                    match.score(),
                    text.substring(0, Math.min(180, text.length()))
            );
            sources.add(new RetrievedSource(source, text));
            log.info("RAG result [{}] score={}, source={}, text={}",
                    index, match.score(), sourceName, text);
            index++;
        }
        return sources;
    }

    private List<RetrievedSource> selectUsableSources(
            String question, List<RetrievedSource> retrievedSources) {
        if (retrievedSources.isEmpty()) {
            return List.of();
        }

        String decision = chat("""
                你是知识库资料判定器。判断给定资料是否足以直接回答用户问题。
                只能输出一行 JSON，禁止 Markdown、解释和其他文字：
                {"answerType":"KNOWLEDGE_BASE","sourceIndexes":[1,2]}
                或
                {"answerType":"MODEL_FALLBACK","sourceIndexes":[]}
                仅当资料包含问题的实际答案时，才返回 KNOWLEDGE_BASE；
                sourceIndexes 只能填写实际用于回答的资料编号。
                """, """
                【知识库资料】
                %s

                【用户问题】
                %s
                """.formatted(buildContext(retrievedSources), question));

        KnowledgeDecision parsed = parseDecision(decision);
        if (parsed == null || parsed.answerType() != AnswerType.KNOWLEDGE_BASE
                || parsed.sourceIndexes() == null || parsed.sourceIndexes().isEmpty()) {
            return List.of();
        }

        Set<Integer> requestedIndexes = new HashSet<>(parsed.sourceIndexes());
        if (requestedIndexes.size() != parsed.sourceIndexes().size()) {
            return List.of();
        }
        List<RetrievedSource> selected = retrievedSources.stream()
                .filter(source -> requestedIndexes.contains(source.source().index()))
                .toList();
        if (selected.size() != requestedIndexes.size()) {
            return List.of();
        }
        return selected;
    }

    private KnowledgeDecision parseDecision(String decision) {
        String json = extractJsonObject(decision);
        if (json == null) {
            log.warn("Knowledge-base decision contains no JSON object; using model fallback. response={}", decision);
            return null;
        }
        try {
            return objectMapper.readValue(json, KnowledgeDecision.class);
        } catch (JsonProcessingException exception) {
            log.warn("Knowledge-base decision JSON could not be parsed; using model fallback. json={}", json);
            return null;
        }
    }

    private String extractJsonObject(String response) {
        if (response == null) {
            return null;
        }
        String normalized = response.trim()
                .replace("```json", "")
                .replace("```JSON", "")
                .replace("```", "")
                .trim();
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start < 0 || end < start) {
            return null;
        }
        return normalized.substring(start, end + 1);
    }

    private String buildContext(List<RetrievedSource> sources) {
        StringBuilder context = new StringBuilder();
        for (RetrievedSource source : sources) {
            context.append("\n[资料 ")
                    .append(source.source().index())
                    .append(" · ")
                    .append(source.source().name())
                    .append("]\n")
                    .append(source.text())
                    .append('\n');
        }
        return context.toString();
    }

    private String buildAnswerSystemPrompt(String currentDateTime, AnswerType answerType) {
        String modeInstruction = answerType == AnswerType.KNOWLEDGE_BASE
                ? "只能根据提供的知识库资料回答；资料外的信息不得补充或猜测。"
                : "可以使用模型通用知识回答，并明确说明该回答并非来自知识库。";
        return """
                你是技术保障部知识库系统。

                当前日期和时间是：%s。
                当用户询问当前日期、时间或星期时，必须直接根据上述时间回答。

                %s
                电话号码、人名、系统名称、地址、编号等具体信息必须原样输出，不得遗漏。
                不要输出分析过程。
                """.formatted(currentDateTime, modeInstruction);
    }

    private String buildAnswerUserPrompt(String context, String question, AnswerType answerType) {
        String instruction = answerType == AnswerType.KNOWLEDGE_BASE
                ? "请直接输出资料中的实际答案。"
                : "无需标注引用来源。";
        return """
                【知识库资料】
                %s

                【用户问题】
                %s

                %s
                """.formatted(context, question, instruction);
    }

    private String chat(String systemPrompt, String userPrompt) {
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt))
                .build();
        return chatModel.chat(request).aiMessage().text();
    }

    private record RetrievedSource(ChatSource source, String text) {
    }

    private record KnowledgeDecision(AnswerType answerType, List<Integer> sourceIndexes) {
    }
}
