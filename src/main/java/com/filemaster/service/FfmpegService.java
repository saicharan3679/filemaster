package com.filemaster.service;

import com.filemaster.exception.FileMasterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Runs the FFmpeg program for real conversions.
 *
 * Safety measures:
 *  - the command is a list of arguments started WITHOUT a shell (no command injection)
 *  - input and output file names are fixed names inside a private temp folder
 *  - "-protocol_whitelist file": FFmpeg may only read local files, never URLs or playlists
 *  - a time limit: a stuck or very slow conversion is killed
 *  - an output size limit (-fs) so a result can never fill the disk
 *  - only a few conversions run at the same time (small servers have little memory)
 */
@Service
public class FfmpegService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegService.class);

    private final String ffmpegPath;
    private final long timeoutSeconds;
    private final long maxOutputBytes;
    private final long maxOutputMb;
    private final Semaphore slots;

    public FfmpegService(@Value("${filemaster.ffmpeg-path:ffmpeg}") String ffmpegPath,
                         @Value("${filemaster.ffmpeg-timeout-seconds:240}") long timeoutSeconds,
                         @Value("${filemaster.ffmpeg-max-concurrent:1}") int maxConcurrent,
                         @Value("${filemaster.ffmpeg-max-output-mb:100}") long maxOutputMb) {
        this.ffmpegPath = ffmpegPath;
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputMb = maxOutputMb;
        this.maxOutputBytes = maxOutputMb * 1024 * 1024;
        this.slots = new Semaphore(Math.max(1, maxConcurrent));
    }

    /** Converts input to output using the given FFmpeg output options. */
    public void run(Path workDir, Path input, Path output, List<String> outputArgs) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                ffmpegPath, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-protocol_whitelist", "file", "-i", input.toString()));
        command.addAll(outputArgs);
        command.addAll(List.of("-threads", "2", "-fs", String.valueOf(maxOutputBytes), output.toString()));

        Path logFile = workDir.resolve("ffmpeg.log");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());

        acquireSlot();
        try {
            Process process = start(builder);
            closeQuietly(process.getOutputStream());

            boolean finished = waitFor(process);
            if (!finished) {
                kill(process);
                throw new FileMasterException(HttpStatus.GATEWAY_TIMEOUT,
                        "Processing took too long and was stopped (limit: " + timeoutSeconds
                                + " seconds). Try a shorter or smaller file.");
            }
            if (process.exitValue() != 0) {
                throw failure(readTail(logFile));
            }
        } finally {
            slots.release();
        }

        long size = Files.exists(output) ? Files.size(output) : 0;
        if (size == 0) {
            throw new FileMasterException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The conversion produced no output. The file may be damaged.");
        }
        if (size >= maxOutputBytes) {
            throw new FileMasterException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The result would be larger than " + maxOutputMb
                            + " MB. Try a shorter file or a more compressed format.");
        }
    }

    // ---------- helpers ----------

    private void acquireSlot() {
        try {
            if (!slots.tryAcquire(30, TimeUnit.SECONDS)) {
                throw new FileMasterException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The server is busy converting other files. Please try again in a minute.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FileMasterException(HttpStatus.INTERNAL_SERVER_ERROR, "Processing was interrupted.");
        }
    }

    private Process start(ProcessBuilder builder) {
        try {
            return builder.start();
        } catch (IOException e) {
            log.error("Could not start FFmpeg ('{}'). Is it installed?", ffmpegPath, e);
            throw new FileMasterException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The conversion engine (FFmpeg) is not available on this server.");
        }
    }

    private boolean waitFor(Process process) {
        try {
            return process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new FileMasterException(HttpStatus.INTERNAL_SERVER_ERROR, "Processing was interrupted.");
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    /** The last part of FFmpeg's error output (it is small because the log level is "error"). */
    private static String readTail(Path logFile) {
        try (InputStream in = Files.newInputStream(logFile)) {
            in.skipNBytes(Math.max(0, Files.size(logFile) - 4000));
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Turns FFmpeg's technical error text into a message a user can understand. */
    private static FileMasterException failure(String ffmpegOutput) {
        log.warn("FFmpeg failed: {}", ffmpegOutput);
        String text = ffmpegOutput.toLowerCase(Locale.ROOT);

        if (text.contains("does not contain any stream")) {
            return FileMasterException.badRequest("No audio track was found in this file.");
        }
        if (text.contains("invalid data found") || text.contains("moov atom not found")) {
            return FileMasterException.badRequest("This file is damaged or is not a valid media file.");
        }
        if (text.contains("unknown encoder") || text.contains("encoder not found")) {
            return new FileMasterException(HttpStatus.SERVICE_UNAVAILABLE,
                    "This server cannot create that format right now.");
        }
        return FileMasterException.badRequest(
                "The file could not be converted. It may be damaged or use an unsupported codec.");
    }
}
