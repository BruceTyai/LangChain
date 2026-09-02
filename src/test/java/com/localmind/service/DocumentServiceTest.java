package com.localmind.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.localmind.dao.entity.KnowledgeDocument;
import com.localmind.dao.repository.KnowledgeDocumentRepository;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock KnowledgeDocumentRepository repository;
    @Mock EmbeddingModel embeddingModel;
    @Mock EmbeddingStore<TextSegment> embeddingStore;

    @Test
    void deletesEmbeddingsAndReadyDocument() {
        KnowledgeDocument document = documentWithStatus(KnowledgeDocument.Status.READY);
        when(repository.findById(42L)).thenReturn(Optional.of(document));
        DocumentService service = service();

        service.delete(42L);

        verify(embeddingStore).removeAll(any(Filter.class));
        verify(repository).delete(document);
    }

    @Test
    void alsoCleansPotentialEmbeddingsForFailedDocument() {
        KnowledgeDocument document = documentWithStatus(KnowledgeDocument.Status.FAILED);
        when(repository.findById(42L)).thenReturn(Optional.of(document));
        DocumentService service = service();

        service.delete(42L);

        verify(embeddingStore).removeAll(any(Filter.class));
        verify(repository).delete(document);
    }

    private DocumentService service() {
        return new DocumentService(repository, embeddingModel, embeddingStore, 700, 100);
    }

    private static KnowledgeDocument documentWithStatus(KnowledgeDocument.Status status) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setStatus(status);
        return document;
    }
}
