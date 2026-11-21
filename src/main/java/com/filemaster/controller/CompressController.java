package com.filemaster.controller;

import com.filemaster.model.EstimateResponse;
import com.filemaster.service.CompressionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * POST /api/compress           one file             -> compressed file
 * POST /api/compress/merge     several files        -> one ZIP archive
 * POST /api/compress/estimate  several files + mode -> real result size for small / medium / high
 */
@RestController
@RequestMapping("/api/compress")
public class CompressController {

    private final CompressionService compressionService;

    public CompressController(CompressionService compressionService) {
        this.compressionService = compressionService;
    }

    @PostMapping
    public ResponseEntity<byte[]> compress(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "preset", defaultValue = "medium") String preset) {
        return DownloadResponse.of(compressionService.compress(file, preset));
    }

    @PostMapping("/merge")
    public ResponseEntity<byte[]> merge(@RequestParam("files") List<MultipartFile> files,
                                        @RequestParam(value = "preset", defaultValue = "medium") String preset) {
        return DownloadResponse.of(compressionService.compressMerged(files, preset));
    }

    @PostMapping("/estimate")
    public EstimateResponse estimate(@RequestParam("files") List<MultipartFile> files,
                                     @RequestParam(value = "mode", defaultValue = "individual") String mode) {
        return compressionService.estimate(files, mode);
    }
}
