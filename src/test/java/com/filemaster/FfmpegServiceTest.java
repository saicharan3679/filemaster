package com.filemaster;

import com.filemaster.exception.FileMasterException;
import com.filemaster.service.FfmpegService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Tests the process handling of FfmpegService with fake "ffmpeg" programs (no real FFmpeg needed). */
class FfmpegServiceTest {

    @TempDir
    Path dir;

    @Test
    void explainsClearlyWhenFfmpegIsNotInstalled() throws IOException {
        FfmpegService service = new FfmpegService("/no/such/ffmpeg", 5, 1, 100);
        Path input = Files.writeString(dir.resolve("input.wav"), "x");

        FileMasterException error = assertThrows(FileMasterException.class,
                () -> service.run(dir, input, dir.resolve("output.mp3"), List.of()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertTrue(error.getMessage().contains("FFmpeg"));
    }

    @Test
    void stopsAConversionThatRunsTooLong() throws IOException {
        assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"), "uses a shell script");
        Path slow = dir.resolve("slow-ffmpeg.sh");
        Files.writeString(slow, "#!/bin/sh\nsleep 30\n");
        assertTrue(slow.toFile().setExecutable(true));

        FfmpegService service = new FfmpegService(slow.toString(), 2, 1, 100);   // 2 second limit
        Path input = Files.writeString(dir.resolve("input.wav"), "x");

        long start = System.currentTimeMillis();
        FileMasterException error = assertThrows(FileMasterException.class,
                () -> service.run(dir, input, dir.resolve("output.mp3"), List.of()));

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, error.getStatus());
        assertTrue(System.currentTimeMillis() - start < 10_000, "must stop after about 2 seconds, not 30");
    }
}
