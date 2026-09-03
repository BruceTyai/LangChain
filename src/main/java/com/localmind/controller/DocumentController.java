package com.localmind.controller;

import com.localmind.dto.DocumentResponse;
import com.localmind.dto.DocumentUploadCommand;
import com.localmind.service.DocumentService;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
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
    public List<DocumentResponse> list() {
        return documentService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<DocumentResponse> upload(@RequestParam("file") List<MultipartFile> files) throws IOException {
        long totalSize = files.stream().mapToLong(MultipartFile::getSize).sum();
        if (totalSize > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("单次上传文件总大小不能超过 30MB");
        }
        List<DocumentUploadCommand> commands = new java.util.ArrayList<>();
        for (MultipartFile file : files) {
            commands.add(new DocumentUploadCommand(
                    file.getOriginalFilename(), file.getContentType(), file.getBytes()));
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
