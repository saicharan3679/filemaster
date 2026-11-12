package com.filemaster.service;

import com.filemaster.exception.FileMasterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Process handling of OfficeService, tested with fake "soffice" programs (real LibreOffice is not needed). */
class OfficeServiceTest {

    @TempDir
    Path dir;

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private Path fake(String script) throws IOException {
        Path file = dir.resolve("fake-soffice.sh");
        Files.writeString(file, "#!/bin/sh\n" + script + "\n");
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

    @Test
    void reportsNotAvailableWhenSwitchedOffOrMissing() {
        assertFalse(new OfficeService(false, "soffice", 10, 1).isAvailable());
        assertFalse(new OfficeService(true, "/no/such/soffice", 10, 1).isAvailable());
    }

    @Test
    void stopsALibreOfficeThatRunsTooLong() throws IOException {
        assumeFalse(windows(), "uses a shell script");
        OfficeService service = new OfficeService(true, fake("sleep 30").toString(), 2, 1);
        Path text = Files.writeString(dir.resolve("input.txt"), "hello");

        long start = System.currentTimeMillis();
        FileMasterException error = assertThrows(FileMasterException.class, () -> service.txtToPdf(dir, text));

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, error.getStatus());
        assertTrue(System.currentTimeMillis() - start < 10_000, "must stop after about 2 seconds");
    }

    @Test
    void explainsAConversionThatProducesNoFile() throws IOException {
        assumeFalse(windows(), "uses a shell script");
        OfficeService service = new OfficeService(true, fake("exit 0").toString(), 10, 1);   // "succeeds" but writes nothing
        Path pdf = Files.writeString(dir.resolve("input.pdf"), "%PDF-1.4");

        FileMasterException error = assertThrows(FileMasterException.class, () -> service.pdfToDocx(dir, pdf));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertTrue(error.getMessage().contains("could not be converted"));
    }

    @Test
    void refusesAFileThatIsNotReallyADocx() throws IOException {
        assumeFalse(windows(), "uses a shell script");
        OfficeService service = new OfficeService(true, fake("exit 0").toString(), 10, 1);
        Path fakeDocx = Files.writeString(dir.resolve("input.docx"), "not a zip file");

        FileMasterException error = assertThrows(FileMasterException.class, () -> service.docxToPdf(dir, fakeDocx));

        assertTrue(error.getMessage().contains("not a valid Word"));
    }

    @Test
    void findsLibreOfficeInsideItsNormalInstallFolder() throws IOException {
        // like "C:\\Program Files\\LibreOffice 7\\program\\soffice.exe" or "/opt/libreoffice7.6/program/soffice"
        Path program = Files.createDirectories(dir.resolve("LibreOffice 7").resolve("program"));
        Path executable = Files.writeString(program.resolve("soffice"), "#!/bin/sh\n");
        assertTrue(executable.toFile().setExecutable(true));
        Files.createDirectories(dir.resolve("SomethingElse").resolve("program"));

        var found = OfficeService.libreOfficeFoldersIn(dir, "soffice");

        assertEquals(1, found.size(), "only the LibreOffice folder is a candidate");
        assertEquals(executable, found.get(0));
    }

    @Test
    void anExplicitPathIsNeverReplacedByGuessing() {
        // the setting names a program that does not exist: do not silently pick another LibreOffice
        assertEquals(null, OfficeService.locate("/no/such/soffice"));
    }
}
