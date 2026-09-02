package com.localmind.document;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    @Mock EmbeddingStore<TextSegment> store;

    @Test
    void deletesEmbeddingsBeforeDeletingReadyDocument() {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setStatus(KnowledgeDocument.Status.READY);
        when(repository.findById(42L)).thenReturn(Optional.of(document));
        DocumentService service = new DocumentService(repository, embeddingModel, store, 700, 100);

        service.delete(42L);

        verify(store).removeAll(any(Filter.class));
        verify(repository).delete(document);
    }

    @Test
    void skipsEmbeddingDeletionForFailedDocument() {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setStatus(KnowledgeDocument.Status.FAILED);
        when(repository.findById(42L)).thenReturn(Optional.of(document));
        DocumentService service = new DocumentService(repository, embeddingModel, store, 700, 100);

        service.delete(42L);

        verify(store, never()).removeAll(any(Filter.class));
        verify(repository).delete(document);
    }
}
