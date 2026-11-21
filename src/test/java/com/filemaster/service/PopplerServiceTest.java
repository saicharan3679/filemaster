package com.filemaster.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finding the poppler programs: in an explicit folder, and inside a downloaded "poppler-..." folder. */
class PopplerServiceTest {

    @TempDir
    Path dir;

    private Path fakePopplerBin(Path parent) throws IOException {
        Path bin = Files.createDirectories(parent.resolve("poppler-24.08.0").resolve("Library").resolve("bin"));
        for (String name : List.of("pdftohtml", "pdfimages")) {
            Path program = Files.writeString(bin.resolve(name), "#!/bin/sh\n");
            assertTrue(program.toFile().setExecutable(true));
        }
        return bin;
    }

    @Test
    void findsTheProgramsInsideADownloadedPopplerFolder() throws IOException {
        Path bin = fakePopplerBin(dir);
        List<Path> found = PopplerService.popplerFoldersIn(dir);
        assertEquals(List.of(bin), found);
    }

    @Test
    void usesAnExplicitFolderExactlyAsGiven() throws IOException {
        Path bin = fakePopplerBin(dir);
        assertTrue(new PopplerService(true, 90, bin.toString()).isAvailable());
        assertFalse(new PopplerService(true, 90, dir.resolve("no-such-folder").toString()).isAvailable(),
                "a wrong explicit setting is not replaced by guessing");
    }

    @Test
    void canBeSwitchedOff() throws IOException {
        Path bin = fakePopplerBin(dir);
        assertFalse(new PopplerService(false, 90, bin.toString()).isAvailable());
    }
}
