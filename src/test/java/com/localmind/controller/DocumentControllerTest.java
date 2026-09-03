package com.localmind.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.localmind.service.DocumentService;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class DocumentControllerTest {

    @Test
    void rejectsFilesWhoseCombinedSizeExceedsThirtyMegabytesBeforeReadingContent() throws IOException {
        DocumentService service = mock(DocumentService.class);
        MultipartFile first = mock(MultipartFile.class);
        MultipartFile second = mock(MultipartFile.class);
        when(first.getSize()).thenReturn(20L * 1024 * 1024);
        when(second.getSize()).thenReturn(10L * 1024 * 1024 + 1);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new DocumentController(service).upload(List.of(first, second)));

        assertEquals("单次上传文件总大小不能超过 30MB", exception.getMessage());
        verify(first, never()).getBytes();
        verify(second, never()).getBytes();
        verify(service, never()).stageAll(anyList());
    }

    @Test
    void acceptsFilesWhoseCombinedSizeIsExactlyThirtyMegabytes() throws IOException {
        DocumentService service = mock(DocumentService.class);
        MultipartFile first = mock(MultipartFile.class);
        MultipartFile second = mock(MultipartFile.class);
        when(first.getSize()).thenReturn(20L * 1024 * 1024);
        when(second.getSize()).thenReturn(10L * 1024 * 1024);
        when(first.getBytes()).thenReturn(new byte[] {1});
        when(second.getBytes()).thenReturn(new byte[] {2});
        when(service.stageAll(anyList())).thenReturn(List.of());

        List<?> responses = new DocumentController(service).upload(List.of(first, second));

        assertEquals(0, responses.size());
        verify(service).stageAll(anyList());
    }
}
