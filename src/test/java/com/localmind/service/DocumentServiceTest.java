package com.localmind.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.localmind.dao.entity.KnowledgeDocument;
import com.localmind.dao.repository.KnowledgeDocumentRepository;
import com.localmind.dto.DocumentPageResponse;
import com.localmind.dto.DocumentResponse;
import com.localmind.dto.DocumentUploadCommand;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock KnowledgeDocumentRepository repository;
    @Mock EmbeddingModel embeddingModel;
    @Mock EmbeddingStore<TextSegment> embeddingStore;
    @TempDir Path uploadDirectory;

    @Test
    void pageReturnsOnlyRequestedDocumentsAndGlobalMetadata() {
        KnowledgeDocument document = documentWithStatus(KnowledgeDocument.Status.PENDING);
        document.setStagedFile("pending.upload");
        when(repository.findAll(any(Pageable.class))).thenReturn(
                new PageImpl<>(List.of(document), PageRequest.of(1, 10), 21));
        when(repository.countByStatusInAndStagedFileIsNotNull(any())).thenReturn(3L);

        DocumentPageResponse response = service().page(1, 10);

        assertEquals(1, response.content().size());
        assertEquals(21, response.totalElements());
        assertEquals(3, response.totalPages());
        assertEquals(1, response.page());
        assertEquals(3, response.confirmableElements());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(pageable.capture());
        assertEquals(1, pageable.getValue().getPageNumber());
        assertEquals(10, pageable.getValue().getPageSize());
    }

    @Test
    void uploadOnlyStagesFileWithoutParsingOrEmbedding() throws IOException {
        saveReturnsArgument();
        DocumentResponse response = service().stage(
                new DocumentUploadCommand("notes.txt", "text/plain", "待解析内容".getBytes()));

        assertEquals("PENDING", response.status());
        assertEquals(1, stagedFileCount());
        verifyNoInteractions(embeddingModel, embeddingStore);
    }

    @Test
    void transactionRollbackRemovesNewlyStagedFile() throws IOException {
        saveReturnsArgument();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service().stage(new DocumentUploadCommand("notes.txt", "text/plain", "内容".getBytes()));
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_ROLLED_BACK));
            assertEquals(0, stagedFileCount());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void batchRollbackRemovesEveryStagedFile() throws IOException {
        saveReturnsArgument();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service().stageAll(List.of(
                    new DocumentUploadCommand("first.txt", "text/plain", "first".getBytes()),
                    new DocumentUploadCommand("second.txt", "text/plain", "second".getBytes())));
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_ROLLED_BACK));
            assertEquals(0, stagedFileCount());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void cleanupFailureKeepsFailedDocumentRetryable() throws IOException {
        Path stagedFile = uploadDirectory.resolve("retry.upload");
        Files.writeString(stagedFile, "待解析内容");
        KnowledgeDocument document = documentWithStatus(KnowledgeDocument.Status.PENDING);
        document.setStagedFile(stagedFile.getFileName().toString());
        ReflectionTestUtils.setField(document, "id", 42L);
        when(repository.findByIdForUpdate(42L)).thenReturn(Optional.of(document));
        saveReturnsArgument();
        org.mockito.Mockito.doNothing()
                .doThrow(new RuntimeException("Chroma unavailable"))
                .when(embeddingStore).removeAll(any(Filter.class));

        DocumentResponse response = service().confirm(42L);

        assertEquals("FAILED", response.status());
        assertTrue(response.confirmable());
        assertTrue(Files.exists(stagedFile));
        assertTrue(response.errorMessage().contains("Chroma unavailable"));
    }

    @Test
    void pendingDocumentStopsBeforeParsingWhenPotentialOldEmbeddingsCannotBeRemoved() throws IOException {
        Path stagedFile = uploadDirectory.resolve("pending.upload");
        Files.writeString(stagedFile, "待解析内容");
        KnowledgeDocument document = documentWithStatus(KnowledgeDocument.Status.PENDING);
        document.setStagedFile(stagedFile.getFileName().toString());
        ReflectionTestUtils.setField(document, "id", 42L);
        when(repository.findByIdForUpdate(42L)).thenReturn(Optional.of(document));
        doThrow(new RuntimeException("Chroma unavailable"))
                .when(embeddingStore).removeAll(any(Filter.class));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> service().confirm(42L));

        assertEquals("Chroma unavailable", exception.getMessage());
        assertEquals(KnowledgeDocument.Status.PENDING, document.getStatus());
        assertTrue(Files.exists(stagedFile));
        verifyNoInteractions(embeddingModel);
    }

    @Test
    void failedDocumentRetryStopsBeforeParsingWhenOldEmbeddingsCannotBeRemoved() throws IOException {
        Path stagedFile = uploadDirectory.resolve("retry.upload");
        Files.writeString(stagedFile, "待解析内容");
        KnowledgeDocument document = documentWithStatus(KnowledgeDocument.Status.FAILED);
        document.setStagedFile(stagedFile.getFileName().toString());
        ReflectionTestUtils.setField(document, "id", 42L);
        when(repository.findByIdForUpdate(42L)).thenReturn(Optional.of(document));
        doThrow(new RuntimeException("Chroma unavailable"))
                .when(embeddingStore).removeAll(any(Filter.class));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> service().confirm(42L));

        assertEquals("Chroma unavailable", exception.getMessage());
        assertEquals(KnowledgeDocument.Status.FAILED, document.getStatus());
        assertTrue(Files.exists(stagedFile));
        verifyNoInteractions(embeddingModel);
    }

    @Test
    void deletingPendingDocumentDoesNotRequireChroma() {
        KnowledgeDocument document = documentWithStatus(KnowledgeDocument.Status.PENDING);
        when(repository.findByIdForUpdate(42L)).thenReturn(Optional.of(document));

        service().delete(42L);

        verify(embeddingStore, never()).removeAll(any(Filter.class));
        verify(repository).delete(document);
    }

    @Test
    void deletingPendingDocumentKeepsStagedFileOnRollback() throws IOException {
        Path stagedFile = uploadDirectory.resolve("pending.upload");
        Files.writeString(stagedFile, "pending");
        KnowledgeDocument document = documentWithStatus(KnowledgeDocument.Status.PENDING);
        document.setStagedFile(stagedFile.getFileName().toString());
        when(repository.findByIdForUpdate(42L)).thenReturn(Optional.of(document));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service().delete(42L);
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_ROLLED_BACK));
            assertTrue(Files.exists(stagedFile));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deletingPendingDocumentRemovesStagedFileAfterCommit() throws IOException {
        Path stagedFile = uploadDirectory.resolve("pending.upload");
        Files.writeString(stagedFile, "pending");
        KnowledgeDocument document = documentWithStatus(KnowledgeDocument.Status.PENDING);
        document.setStagedFile(stagedFile.getFileName().toString());
        when(repository.findByIdForUpdate(42L)).thenReturn(Optional.of(document));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service().delete(42L);
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    TransactionSynchronization::afterCommit);
            assertTrue(Files.notExists(stagedFile));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void startupCleanupOnlyRemovesUnreferencedStagedFiles() throws IOException {
        Path referencedFile = uploadDirectory.resolve("referenced.upload");
        Path orphanedFile = uploadDirectory.resolve("orphaned.upload");
        Path unrelatedFile = uploadDirectory.resolve("keep.txt");
        Files.writeString(referencedFile, "referenced");
        Files.writeString(orphanedFile, "orphaned");
        Files.setLastModifiedTime(orphanedFile, FileTime.from(Instant.now().minusSeconds(7200)));
        Files.writeString(unrelatedFile, "unrelated");
        KnowledgeDocument document = documentWithStatus(KnowledgeDocument.Status.PENDING);
        document.setStagedFile(referencedFile.getFileName().toString());
        when(repository.findAll()).thenReturn(List.of(document));

        service().cleanupOrphanedStagedFiles();

        assertTrue(Files.exists(referencedFile));
        assertTrue(Files.notExists(orphanedFile));
        assertTrue(Files.exists(unrelatedFile));
    }

    @Test
    void deletingFailedDocumentCleansPotentialEmbeddings() {
        KnowledgeDocument document = documentWithStatus(KnowledgeDocument.Status.FAILED);
        when(repository.findByIdForUpdate(42L)).thenReturn(Optional.of(document));

        service().delete(42L);

        verify(embeddingStore).removeAll(any(Filter.class));
        verify(repository).delete(document);
    }

    private DocumentService service() {
        return new DocumentService(repository, embeddingModel, embeddingStore, 700, 100,
                uploadDirectory.toString());
    }

    private void saveReturnsArgument() {
        when(repository.save(any(KnowledgeDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private long stagedFileCount() throws IOException {
        try (Stream<Path> files = Files.list(uploadDirectory)) {
            return files.count();
        }
    }

    private static KnowledgeDocument documentWithStatus(KnowledgeDocument.Status status) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setStatus(status);
        return document;
    }
}