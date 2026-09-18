package com.localmind.dto;

import java.nio.file.Path;

public record DocumentDownload(Path path, String fileName, String contentType, long sizeBytes) {
}
