package com.filemaster.controller;

import com.filemaster.model.FormatsResponse;
import com.filemaster.service.MediaConversionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * GET  /api/audio/formats   POST /api/audio/convert   (file, targetFormat)
 * GET  /api/video/formats   POST /api/video/convert   (file, targetFormat)
 *
 * Video targets include MP3, WAV and M4A for audio extraction.
 */
@RestController
@RequestMapping("/api")
public class MediaController {

    private final MediaConversionService mediaService;

    public MediaController(MediaConversionService mediaService) {
        this.mediaService = mediaService;
    }

    @GetMapping("/audio/formats")
    public FormatsResponse audioFormats() {
        return mediaService.audioFormats();
    }

    @PostMapping("/audio/convert")
    public ResponseEntity<byte[]> convertAudio(@RequestParam("file") MultipartFile file,
                                               @RequestParam("targetFormat") String targetFormat) {
        return DownloadResponse.of(mediaService.convertAudio(file, targetFormat));
    }

    @GetMapping("/video/formats")
    public FormatsResponse videoFormats() {
        return mediaService.videoFormats();
    }

    @PostMapping("/video/convert")
    public ResponseEntity<byte[]> convertVideo(@RequestParam("file") MultipartFile file,
                                               @RequestParam("targetFormat") String targetFormat) {
        return DownloadResponse.of(mediaService.convertVideo(file, targetFormat));
    }
}
