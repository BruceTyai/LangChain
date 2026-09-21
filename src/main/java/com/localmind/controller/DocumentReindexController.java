package com.localmind.controller;

import com.localmind.dto.DocumentReindexJobResponse;
import com.localmind.service.DocumentReindexService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DocumentReindexController {

    private final DocumentReindexService service;

    public DocumentReindexController(DocumentReindexService service) {
        this.service = service;
    }

    @PostMapping("/api/documents/{id}/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentReindexJobResponse reindex(@PathVariable long id) {
        return service.startSingle(id);
    }

    @PostMapping("/api/documents/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentReindexJobResponse reindexAll() {
        return service.startAll();
    }

    @GetMapping("/api/document-reindex-jobs/current")
    public DocumentReindexJobResponse current() {
        return service.current();
    }
}