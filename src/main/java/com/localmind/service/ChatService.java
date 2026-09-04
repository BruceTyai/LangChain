package com.localmind.service;

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

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private static final Logger log =
            LoggerFactory.getLogger(ChatService.class);

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final int maxResults;
    private final double minScore;

    public ChatService(
            ChatModel chatModel,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            @Value("${app.rag.max-results}") int maxResults,
            @Value("${app.rag.min-score}") double minScore) {

        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.maxResults = maxResults;
        this.minScore = minScore;
    }

    public ChatResponse ask(String question) {

        // 1. 将问题向量化
        Embedding queryEmbedding =
                embeddingModel.embed(question).content();

        // 2. 向量检索
        EmbeddingSearchResult<TextSegment> result =
                embeddingStore.search(
                        EmbeddingSearchRequest.builder()
                                .queryEmbedding(queryEmbedding)
                                .maxResults(maxResults)
                                .minScore(minScore)
                                .build()
                );

        List<ChatSource> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();

        int index = 1;

        for (EmbeddingMatch<TextSegment> match : result.matches()) {

            TextSegment segment = match.embedded();

            String source =
                    segment.metadata().getString("source");

            log.info(
                    "RAG result [{}] score={}, source={}, text={}",
                    index,
                    match.score(),
                    source,
                    segment.text()
            );

            context.append("\n[资料 ")
                    .append(index)
                    .append(" · ")
                    .append(source)
                    .append("]\n")
                    .append(segment.text())
                    .append('\n');

            sources.add(
                    new ChatSource(
                            index,
                            source,
                            match.score(),
                            segment.text().substring(
                                    0,
                                    Math.min(
                                            180,
                                            segment.text().length()
                                    )
                            )
                    )
            );

            index++;
        }

        // 3. 一个相关资料都没有时，不需要调用大模型
        if (sources.isEmpty()) {
            return new ChatResponse(
                    "知识库中没有足够信息。",
                    sources
            );
        }

        // 4. 真正的 SystemMessage
        String systemPrompt = """
                你是“技术保障部知识库系统”。

                你的任务是严格依据提供的知识库资料回答用户问题。

                必须遵守以下规则：

                1. 用户的问题默认属于技术保障部业务范围。
                   用户没有明确说“技术保障部”时，不得因此认为问题不明确。

                2. 用户可能使用非常简短的提问，例如：
                   “值班电话”
                   “云平台”
                   “网站谁负责”
                   “安全找谁”
                   “采编发负责人”

                3. 只要知识库资料中存在能够直接或明显回答用户问题的信息，
                   就必须根据资料回答。

                4. 如果某条资料已经明确包含答案，
                   禁止回答“知识库中没有足够信息”。

                5. 只有当提供的所有知识库资料确实都没有相关答案时，
                   才允许回答：
                   “知识库中没有足够信息”。

                6. 优先使用与用户问题最直接相关的资料。

                7. 不得因为其他资料中存在无关的电话号码、负责人、
                   系统名称等信息而忽略正确资料。

                8. 电话号码、人名、系统名称等事实信息，
                   必须严格按照知识库原文回答，不得修改、猜测或补充。

                9. 回答必须简洁清晰。

                10. 在相关答案句末使用 [资料 1]、[资料 2] 的形式标明来源。

                11. 不输出分析过程或思考过程，只输出最终答案。
                """;

        // 5. UserMessage 只放知识和当前问题
        String userPrompt = """
                【知识库资料】
                %s

                【用户问题】
                %s

                请直接根据以上知识库资料回答用户问题。
                """.formatted(context, question);

        log.info("""
                
                ================= RAG PROMPT =================
                QUESTION:
                {}

                CONTEXT:
                {}
                ==============================================
                """,
                question,
                context
        );

        // 6. 分开 SystemMessage / UserMessage
        ChatRequest request =
                ChatRequest.builder()
                        .messages(
                                SystemMessage.from(systemPrompt),
                                UserMessage.from(userPrompt)
                        )
                        .build();

        dev.langchain4j.model.chat.response.ChatResponse llmResponse =
                chatModel.chat(request);

        String answer =
                llmResponse.aiMessage().text();

        log.info("LLM answer: {}", answer);

        return new ChatResponse(answer, sources);
    }
}