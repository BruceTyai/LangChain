package com.localmind.document;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import java.io.InputStream;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
    private final KnowledgeDocumentRepository repository; private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> store; private final int size; private final int overlap;
    public DocumentService(KnowledgeDocumentRepository r, EmbeddingModel e, EmbeddingStore<TextSegment> s,
            @Value("${app.rag.segment-size}") int size,@Value("${app.rag.overlap}") int overlap){this.repository=r;this.embeddingModel=e;this.store=s;this.size=size;this.overlap=overlap;}
    public List<KnowledgeDocument> list(){return repository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,"createdAt"));}
    @Transactional
    public KnowledgeDocument ingest(MultipartFile file){
        if(file.isEmpty()) throw new IllegalArgumentException("文件不能为空");
        KnowledgeDocument row=new KnowledgeDocument(); row.setName(file.getOriginalFilename()==null?"未命名文档":file.getOriginalFilename());
        row.setContentType(file.getContentType()); row.setSizeBytes(file.getSize()); row=repository.save(row);
        final KnowledgeDocument documentRow = row;
        try(InputStream in=file.getInputStream()){
            DocumentParser parser=new ApacheTikaDocumentParser(); Document doc=parser.parse(in);
            List<TextSegment> segments=DocumentSplitters.recursive(size,overlap).split(doc);
            segments.forEach(s->{s.metadata().put("documentId",documentRow.getId().toString());s.metadata().put("source",documentRow.getName());});
            List<Embedding> embeddings=embeddingModel.embedAll(segments).content(); store.addAll(embeddings,segments);
            row.setChunkCount(segments.size()); row.setStatus(KnowledgeDocument.Status.READY);
        }catch(Exception ex){row.setStatus(KnowledgeDocument.Status.FAILED);row.setErrorMessage(ex.getMessage());}
        return repository.save(row);
    }
    @Transactional
    public void delete(long id) {
        KnowledgeDocument document = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        if (document.getStatus() == KnowledgeDocument.Status.READY) {
            store.removeAll(MetadataFilterBuilder.metadataKey("documentId").isEqualTo(Long.toString(id)));
        }
        repository.delete(document);
    }
}
