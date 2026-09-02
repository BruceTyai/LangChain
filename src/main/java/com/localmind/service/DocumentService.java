package com.localmind.service;

import com.localmind.dao.entity.KnowledgeDocument;
import com.localmind.dao.repository.KnowledgeDocumentRepository;
import com.localmind.dto.DocumentResponse;
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
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {

    private final KnowledgeDocumentRepository repository;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final int segmentSize;
    private final int overlap;

    public DocumentService(
            KnowledgeDocumentRepository repository,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            @Value("${app.rag.segment-size}") int segmentSize,
            @Value("${app.rag.overlap}") int overlap) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.segmentSize = segmentSize;
        this.overlap = overlap;
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> list() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(DocumentResponse::from)
                .toList();
    }

    @Transactional
    public DocumentResponse ingest(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        KnowledgeDocument document = new KnowledgeDocument();
        document.setName(file.getOriginalFilename() == null ? "未命名文档" : file.getOriginalFilename());
        document.setContentType(file.getContentType());
        document.setSizeBytes(file.getSize());
        document = repository.save(document);
        final KnowledgeDocument savedDocument = document;

        try (InputStream input = file.getInputStream()) {
            DocumentParser parser = new ApacheTikaDocumentParser();
            Document parsedDocument = parser.parse(input);
            List<TextSegment> segments = DocumentSplitters.recursive(segmentSize, overlap).split(parsedDocument);
            segments.forEach(segment -> {
                segment.metadata().put("documentId", savedDocument.getId().toString());
                segment.metadata().put("source", savedDocument.getName());
            });
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(embeddings, segments);
            document.setChunkCount(segments.size());
            document.setStatus(KnowledgeDocument.Status.READY);
        } catch (Exception exception) {
            document.setStatus(KnowledgeDocument.Status.FAILED);
            document.setErrorMessage(exception.getMessage());
        }
        return DocumentResponse.from(repository.save(document));
    }

    @Transactional
    public void delete(long id) {
        KnowledgeDocument document = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        embeddingStore.removeAll(
                MetadataFilterBuilder.metadataKey("documentId").isEqualTo(Long.toString(id)));
        repository.delete(document);
    }
}

