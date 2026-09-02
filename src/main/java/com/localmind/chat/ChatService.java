package com.localmind.chat;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
 private final ChatModel chat; private final EmbeddingModel embedding; private final EmbeddingStore<TextSegment> store; private final int max; private final double min;
 public ChatService(ChatModel c,EmbeddingModel e,EmbeddingStore<TextSegment>s,@Value("${app.rag.max-results}")int max,@Value("${app.rag.min-score}")double min){chat=c;embedding=e;store=s;this.max=max;this.min=min;}
 public ChatResponse ask(String question){
   Embedding q=embedding.embed(question).content();
   EmbeddingSearchResult<TextSegment> result=store.search(EmbeddingSearchRequest.builder().queryEmbedding(q).maxResults(max).minScore(min).build());
   List<Source> sources=new ArrayList<>(); StringBuilder context=new StringBuilder(); int i=1;
   for(EmbeddingMatch<TextSegment> m:result.matches()){TextSegment s=m.embedded();String source=s.metadata().getString("source");context.append("\n[资料 ").append(i).append(" · ").append(source).append("]\n").append(s.text()).append('\n');sources.add(new Source(i++,source,m.score(),s.text().substring(0,Math.min(180,s.text().length()))));}
   String prompt="""
你是一个严谨的中文知识库助手。仅依据下方资料回答问题；资料不足时明确说“知识库中没有足够信息”，不要编造。
回答要简洁清晰，并在相关句末用 [资料 1] 的形式标注来源。不要输出思考过程。

用户问题：%s

知识库资料：%s
""".formatted(question,context.length()==0?"（未检索到相关资料）":context);
   return new ChatResponse(chat.chat(prompt),sources);
 }
 public record Source(int index,String name,double score,String excerpt){} public record ChatResponse(String answer,List<Source> sources){}
}
