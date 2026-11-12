package com.filemaster.controller;

import com.filemaster.model.FormatsResponse;
import com.filemaster.service.ConversionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * GET  /api/convert/formats  -> supported formats and conversions
 * POST /api/convert          one file                -> converted file
 * POST /api/convert/merge    several files + target  -> ONE converted file
 *
 * Converting several files one by one is done by the browser calling POST /api/convert once per file.
 */
@RestController
@RequestMapping("/api/convert")
public class ConvertController {

    private final ConversionService conversionService;

    public ConvertController(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    @GetMapping("/formats")
    public FormatsResponse formats() {
        return conversionService.formats();
    }

    @PostMapping
    public ResponseEntity<byte[]> convert(@RequestParam("file") MultipartFile file,
                                          @RequestParam("targetFormat") String targetFormat) {
        return DownloadResponse.of(conversionService.convert(file, targetFormat));
    }

    @PostMapping("/merge")
    public ResponseEntity<byte[]> merge(@RequestParam("files") List<MultipartFile> files,
                                        @RequestParam("targetFormat") String targetFormat) {
        return DownloadResponse.of(conversionService.convertMerged(files, targetFormat));
    }
}
