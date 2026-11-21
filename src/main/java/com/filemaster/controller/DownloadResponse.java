package com.filemaster.controller;

import com.filemaster.model.FileResult;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Builds the HTTP response that makes the browser download a file.
 * Size information travels in custom X-... headers so the frontend can show it.
 */
final class DownloadResponse {

    private DownloadResponse() {
    }

    static ResponseEntity<byte[]> of(FileResult result) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(result.mimeType()));
        headers.setContentLength(result.data().length);
        headers.setContentDisposition(ContentDisposition.attachment().filename(result.fileName()).build());
        headers.setCacheControl(CacheControl.noStore());
        headers.set("X-Original-Size", String.valueOf(result.originalSize()));
        headers.set("X-Result-Size", String.valueOf(result.resultSize()));
        headers.set("X-Saved-Percent", String.valueOf(result.percentSaved()));
        if (result.note() != null) {
            headers.set("X-Result-Note", result.note());
        }
        return ResponseEntity.ok().headers(headers).body(result.data());
    }
}
