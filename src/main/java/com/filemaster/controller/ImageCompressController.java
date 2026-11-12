package com.filemaster.controller;

import com.filemaster.service.ImageCompressionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** POST /api/image/compress  (file, quality = low | medium | high) -> compressed image download. */
@RestController
@RequestMapping("/api/image")
public class ImageCompressController {

    private final ImageCompressionService imageService;

    public ImageCompressController(ImageCompressionService imageService) {
        this.imageService = imageService;
    }

    @PostMapping("/compress")
    public ResponseEntity<byte[]> compress(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "quality", defaultValue = "medium") String quality) {
        return DownloadResponse.of(imageService.compress(file, quality));
    }
}
