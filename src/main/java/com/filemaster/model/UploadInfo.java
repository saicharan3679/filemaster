package com.filemaster.model;

/** Result of validating an upload: a safe base name and a lower-case extension. */
public record UploadInfo(String baseName, String extension) {
}
