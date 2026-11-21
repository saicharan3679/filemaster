package com.filemaster.service;

import com.filemaster.exception.FileMasterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Safe temporary-file handling.
 *
 * Every request gets its own private folder (created by the JVM in the OS temp
 * directory, so no hard-coded paths). Files inside use fixed names such as
 * "input.png", never the name the user typed, so a malicious file name can't
 * escape the folder. The folder is deleted when the request finishes, and a
 * scheduled job removes any leftovers (for example after a crash).
 */
@Service
public class TempFileService {

    private static final Logger log = LoggerFactory.getLogger(TempFileService.class);
    private static final String PREFIX = "filemaster-";

    /** A piece of work that needs a private temp folder. */
    @FunctionalInterface
    public interface IoTask<T> {
        T run(Path workDir) throws IOException;
    }

    /**
     * Creates a private folder, runs the task, and ALWAYS deletes the folder afterwards
     * (even when the task fails). Services use this so they can never forget to clean up.
     */
    public <T> T withWorkDir(IoTask<T> task) {
        Path dir = null;
        try {
            dir = createWorkDir();
            return task.run(dir);
        } catch (IOException e) {
            log.error("File processing failed", e);
            throw new FileMasterException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The file could not be processed. Please try another file.");
        } finally {
            deleteQuietly(dir);
        }
    }

    public Path createWorkDir() throws IOException {
        return Files.createTempDirectory(PREFIX);
    }

    public Path saveUpload(MultipartFile file, Path dir, String fixedName) throws IOException {
        Path target = dir.resolve(fixedName).normalize();
        if (!target.startsWith(dir)) {
            throw new FileMasterException(HttpStatus.BAD_REQUEST, "Invalid file name.");
        }
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    public void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not delete {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean up {}", dir, e);
        }
    }

    /** Safety net: every 10 minutes remove work folders older than 30 minutes. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void cleanupStaleFolders() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Instant cutoff = Instant.now().minus(30, ChronoUnit.MINUTES);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmp, PREFIX + "*")) {
            for (Path p : stream) {
                if (Files.isDirectory(p) && Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                    log.info("Removing stale temp folder {}", p);
                    deleteQuietly(p);
                }
            }
        } catch (IOException e) {
            log.warn("Temp cleanup failed", e);
        }
    }
}
