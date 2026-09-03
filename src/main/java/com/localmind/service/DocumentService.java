package com.localmind.service;

import com.localmind.dao.entity.KnowledgeDocument;
import com.localmind.dao.repository.KnowledgeDocumentRepository;
import com.localmind.dto.DocumentResponse;
import com.localmind.dto.DocumentUploadCommand;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final Duration ORPHAN_GRACE_PERIOD = Duration.ofHours(1);

    private final KnowledgeDocumentRepository repository;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final int segmentSize;
    private final int overlap;
    private final Path uploadDirectory;

    public DocumentService(
            KnowledgeDocumentRepository repository,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            @Value("${app.rag.segment-size}") int segmentSize,
            @Value("${app.rag.overlap}") int overlap,
            @Value("${app.upload.directory}") String uploadDirectory) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.segmentSize = segmentSize;
        this.overlap = overlap;
        this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> list() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public DocumentResponse stage(DocumentUploadCommand command) {
        if (command.content() == null || command.content().length == 0) {
            throw new IllegalArgumentException("文件不能为空");
        }

        String stagedFile = UUID.randomUUID() + ".upload";
        Path stagedPath = resolveStagedFile(stagedFile);
        try {
            Files.createDirectories(uploadDirectory);
            Files.write(stagedPath, command.content(), StandardOpenOption.CREATE_NEW);
        } catch (IOException exception) {
            throw new IllegalStateException("暂存上传文件失败", exception);
        }
        registerRollbackCleanup(stagedPath);

        KnowledgeDocument document = new KnowledgeDocument();
        document.setName(command.fileName() == null || command.fileName().isBlank()
                ? "未命名文档" : command.fileName());
        document.setContentType(command.contentType());
        document.setSizeBytes(command.content().length);
        document.setStagedFile(stagedFile);
        return toResponse(repository.save(document));
    }

    @Transactional
    public List<DocumentResponse> stageAll(List<DocumentUploadCommand> commands) {
        return commands.stream().map(this::stage).toList();
    }

    @Transactional
    public DocumentResponse confirm(long id) {
        KnowledgeDocument document = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        if (document.getStatus() != KnowledgeDocument.Status.PENDING
                && document.getStatus() != KnowledgeDocument.Status.FAILED) {
            throw new IllegalArgumentException("只有待确认或解析失败的文档可以开始解析");
        }

        removeEmbeddings(document.getId());

        Path stagedPath = resolveStagedFile(document.getStagedFile());
        document.setStatus(KnowledgeDocument.Status.PROCESSING);
        document.setErrorMessage(null);
        repository.saveAndFlush(document);

        try (InputStream input = Files.newInputStream(stagedPath)) {
            DocumentParser parser = new ApacheTikaDocumentParser();
            Document parsedDocument = parser.parse(input);
            List<TextSegment> segments = DocumentSplitters.recursive(segmentSize, overlap).split(parsedDocument);
            segments.forEach(segment -> {
                segment.metadata().put("documentId", document.getId().toString());
                segment.metadata().put("source", document.getName());
            });
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(embeddings, segments);
            document.setChunkCount(segments.size());
            document.setStatus(KnowledgeDocument.Status.READY);
            document.setStagedFile(null);
            registerCommitCleanup(stagedPath);
        } catch (Exception exception) {
            String cleanupError = tryRemoveEmbeddings(document.getId());
            document.setStatus(KnowledgeDocument.Status.FAILED);
            document.setErrorMessage(limitError(exception, cleanupError));
        }
        return toResponse(repository.save(document));
    }

    @Transactional
    public void delete(long id) {
        KnowledgeDocument document = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        if (document.getStatus() != KnowledgeDocument.Status.PENDING) {
            removeEmbeddings(id);
        }
        if (document.getStagedFile() != null) {
            registerCommitCleanup(resolveStagedFile(document.getStagedFile()));
        }
        repository.delete(document);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOrphanedStagedFiles() {
        if (!Files.isDirectory(uploadDirectory)) {
            return;
        }
        Instant orphanCutoff = Instant.now().minus(ORPHAN_GRACE_PERIOD);
        Set<String> referencedFiles = new HashSet<>();
        repository.findAll().stream()
                .map(KnowledgeDocument::getStagedFile)
                .filter(file -> file != null && !file.isBlank())
                .forEach(referencedFiles::add);
        try (var files = Files.list(uploadDirectory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".upload"))
                    .filter(path -> !referencedFiles.contains(path.getFileName().toString()))
                    .filter(path -> isOlderThan(path, orphanCutoff))
                    .forEach(this::deleteWithWarning);
        } catch (IOException exception) {
            log.warn("扫描孤儿暂存文件失败: {}", uploadDirectory, exception);
        }
    }

    private void removeEmbeddings(long documentId) {
        embeddingStore.removeAll(
                MetadataFilterBuilder.metadataKey("documentId").isEqualTo(Long.toString(documentId)));
    }

    private String tryRemoveEmbeddings(long documentId) {
        try {
            removeEmbeddings(documentId);
            return null;
        } catch (Exception cleanupException) {
            return cleanupException.getMessage() == null ? "向量清理失败" : cleanupException.getMessage();
        }
    }

    private void registerRollbackCleanup(Path path) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteWithWarning(path);
                }
            }
        });
    }

    private void registerCommitCleanup(Path path) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteWithWarning(path);
            }
        });
    }

    private boolean isOlderThan(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (IOException exception) {
            log.warn("读取暂存文件时间失败，跳过清理: {}", path, exception);
            return false;
        }
    }

    private void deleteWithWarning(Path path) {
        if (!deleteQuietly(path)) {
            log.warn("删除暂存文件失败，将在应用下次启动时重试: {}", path);
        }
    }

    private Path resolveStagedFile(String stagedFile) {
        if (stagedFile == null || stagedFile.isBlank()) {
            throw new IllegalStateException("暂存文件不存在");
        }
        Path resolved = uploadDirectory.resolve(stagedFile).normalize();
        if (!resolved.startsWith(uploadDirectory)) {
            throw new IllegalStateException("非法暂存文件路径");
        }
        return resolved;
    }

    private boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path) || !Files.exists(path);
        } catch (IOException exception) {
            return false;
        }
    }

    private String limitError(Exception exception, String cleanupError) {
        String message = exception.getMessage() == null ? "文档解析失败" : exception.getMessage();
        if (cleanupError != null) {
            message += "；" + cleanupError;
        }
        return message.substring(0, Math.min(message.length(), 1000));
    }

    private DocumentResponse toResponse(KnowledgeDocument document) {
        boolean confirmable = document.getStagedFile() != null
                && (document.getStatus() == KnowledgeDocument.Status.PENDING
                || document.getStatus() == KnowledgeDocument.Status.FAILED);
        return new DocumentResponse(
                document.getId(),
                document.getName(),
                document.getContentType(),
                document.getSizeBytes(),
                document.getStatus().name(),
                document.getChunkCount(),
                document.getErrorMessage(),
                document.getCreatedAt(),
                confirmable);
    }
}