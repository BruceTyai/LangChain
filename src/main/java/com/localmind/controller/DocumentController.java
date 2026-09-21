package com.localmind.controller;

import com.localmind.dto.DocumentDownload;
import com.localmind.dto.DocumentPageResponse;
import com.localmind.dto.DocumentResponse;
import com.localmind.dto.DocumentUploadCommand;
import com.localmind.service.DocumentService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final long MAX_UPLOAD_BYTES = 30L * 1024 * 1024;
    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping
    public DocumentPageResponse list(@RequestParam(defaultValue = "0") int page) {
        if (page < 0) throw new IllegalArgumentException("Page must not be negative");
        return documentService.page(page, 10);
    }

    @GetMapping("/confirmable")
    public List<DocumentResponse> confirmable() {
        return documentService.confirmable();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable long id) {
        DocumentDownload document = documentService.download(id);
        MediaType contentType = MediaType.APPLICATION_OCTET_STREAM;
        if (document.contentType() != null && !document.contentType().isBlank()) {
            try { contentType = MediaType.parseMediaType(document.contentType()); }
            catch (IllegalArgumentException ignored) { }
        }
        return ResponseEntity.ok().contentType(contentType).contentLength(document.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(document.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(document.path()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<DocumentResponse> upload(@RequestParam("file") List<MultipartFile> files) throws IOException {
        long totalSize = files.stream().mapToLong(MultipartFile::getSize).sum();
        if (totalSize > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("单次上传文件总大小不能超过 30MB");
        List<DocumentUploadCommand> commands = new java.util.ArrayList<>();
        for (MultipartFile file : files) {
            commands.add(new DocumentUploadCommand(file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        }
        return documentService.stageAll(commands);
    }

    @PostMapping("/{id}/confirm")
    public DocumentResponse confirm(@PathVariable long id) {
        return documentService.confirm(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        documentService.delete(id);
    }
}