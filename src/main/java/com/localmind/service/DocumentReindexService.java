package com.localmind.service;

import com.localmind.dto.DocumentReindexJobResponse;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
public class DocumentReindexService {

    private final DocumentService documentService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "document-reindex");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile JobState latestJob;

    public DocumentReindexService(DocumentService documentService) {
        this.documentService = documentService;
    }

    public DocumentReindexJobResponse startSingle(long documentId) {
        documentService.ensureReindexable(documentId);
        return start("SINGLE", List.of(documentId), 0);
    }

    public DocumentReindexJobResponse startAll() {
        DocumentService.ReindexTargets targets = documentService.reindexTargets();
        return start("ALL", targets.documentIds(), targets.skipped());
    }

    public DocumentReindexJobResponse current() {
        JobState job = latestJob;
        if (job == null) {
            throw new IllegalArgumentException("No document re-index job has been started");
        }
        return job.snapshot();
    }

    private DocumentReindexJobResponse start(String scope, List<Long> ids, int skipped) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("A document re-index job is already running");
        }
        JobState job = new JobState(UUID.randomUUID().toString(), scope, ids.size(), skipped);
        latestJob = job;
        if (ids.isEmpty()) {
            job.complete();
            running.set(false);
            return job.snapshot();
        }
        executor.execute(() -> run(job, ids));
        return job.snapshot();
    }

    private void run(JobState job, List<Long> ids) {
        try {
            for (Long id : ids) {
                try {
                    job.startDocument(id);
                    documentService.reindex(id);
                    job.succeed();
                } catch (Exception exception) {
                    job.fail(id, exception.getMessage());
                }
            }
            job.complete();
        } finally {
            running.set(false);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static final class JobState {
        private final String id;
        private final String scope;
        private final int total;
        private final int skipped;
        private final Instant startedAt = Instant.now();
        private final List<String> failures = new ArrayList<>();
        private int completed;
        private int succeeded;
        private int failed;
        private Long currentDocumentId;
        private String currentDocumentName;
        private String status = "RUNNING";
        private Instant finishedAt;

        private JobState(String id, String scope, int total, int skipped) {
            this.id = id;
            this.scope = scope;
            this.total = total;
            this.skipped = skipped;
        }

        synchronized void startDocument(long documentId) {
            currentDocumentId = documentId;
            currentDocumentName = "Document " + documentId;
        }

        synchronized void succeed() {
            completed++;
            succeeded++;
        }

        synchronized void fail(long documentId, String message) {
            completed++;
            failed++;
            failures.add("Document " + documentId + ": " + (message == null ? "Unknown error" : message));
        }

        synchronized void complete() {
            status = failed == 0 ? "COMPLETED" : "COMPLETED_WITH_FAILURES";
            currentDocumentId = null;
            currentDocumentName = null;
            finishedAt = Instant.now();
        }

        synchronized DocumentReindexJobResponse snapshot() {
            return new DocumentReindexJobResponse(id, scope, status, total, completed, succeeded, failed,
                    skipped, currentDocumentId, currentDocumentName, List.copyOf(failures), startedAt, finishedAt);
        }
    }
}