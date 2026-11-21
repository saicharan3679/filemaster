package com.filemaster.service;

import com.filemaster.exception.FileMasterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Document conversion with LibreOffice (the "soffice" program).
 *
 * Why LibreOffice? It keeps everything in a document: pictures, logos, tables, headers,
 * footers, fonts and non-English scripts. Our own code can only copy text.
 *
 * Safety measures (the first two were tested against a real attack file):
 *  - .docx files are cleaned first (OfficeSanitizer removes links to web addresses)
 *  - LibreOffice is started with a dead proxy, so it can not reach any network
 *  - the input format is forced (--infilter), so a file can not pretend to be another type
 *  - started WITHOUT a shell, with a fresh private profile folder inside the temp folder
 *  - time limit, and only a few conversions at the same time (it needs about 200 MB of memory each)
 */
@Service
public class OfficeService {

    private static final Logger log = LoggerFactory.getLogger(OfficeService.class);
    private static final String DEAD_PROXY = "http://127.0.0.1:9";

    private static final String DEFAULT_COMMAND = "soffice";

    private final boolean enabled;
    private final String officePath;          // what the setting says (default: "soffice")
    private final long timeoutSeconds;
    private final BlockingQueue<Integer> profiles;   // one reusable LibreOffice profile per allowed parallel conversion
    private volatile Boolean available;
    private volatile String command;          // the program that is really started (found automatically)

    public OfficeService(@Value("${filemaster.office-enabled:true}") boolean enabled,
                         @Value("${filemaster.office-path:soffice}") String officePath,
                         @Value("${filemaster.office-timeout-seconds:120}") long timeoutSeconds,
                         @Value("${filemaster.office-max-concurrent:1}") int maxConcurrent) {
        this.enabled = enabled;
        this.officePath = officePath;
        this.timeoutSeconds = timeoutSeconds;
        int parallel = Math.max(1, maxConcurrent);
        this.profiles = new ArrayBlockingQueue<>(parallel);
        for (int i = 0; i < parallel; i++) {
            profiles.add(i);
        }
    }

    /** True when the feature is switched on AND LibreOffice was found on this computer. */
    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        if (available == null) {
            synchronized (this) {
                if (available == null) {
                    String found = locate(officePath);
                    command = found;
                    available = found != null;
                    if (found != null) {
                        log.info("LibreOffice found: {}", found);
                    } else {
                        log.warn("LibreOffice was NOT found, so Word files are converted with text and simple pictures only. "
                                + "Install it from https://www.libreoffice.org/download and restart FileMaster. "
                                + "If it is already installed, set the full path of soffice (soffice.exe on Windows) in the "
                                + "environment variable FILEMASTER_OFFICE_PATH. Searched: {}", describeSearch());
                    }
                }
            }
        }
        return available;
    }

    // ---------- the conversions ----------

    public Path docxToPdf(Path workDir, Path docx) throws IOException {
        Path clean = Files.createTempFile(workDir, "clean-", ".docx");
        OfficeSanitizer.cleanDocx(docx, clean);
        return run(workDir, clean, "pdf", "pdf:writer_pdf_Export", "MS Word 2007 XML");
    }

    public Path docToPdf(Path workDir, Path doc) throws IOException {
        return run(workDir, doc, "pdf", "pdf:writer_pdf_Export", "MS Word 97");
    }

    public Path docToDocx(Path workDir, Path doc) throws IOException {
        return run(workDir, doc, "docx", "docx:MS Word 2007 XML", "MS Word 97");
    }

    /** The text file must already be UTF-8 (the caller makes sure of that). */
    public Path txtToPdf(Path workDir, Path utf8Text) throws IOException {
        return run(workDir, utf8Text, "pdf", "pdf:writer_pdf_Export", "Text (encoded):UTF8,LF,,,,,,true");
    }

    public Path pdfToDocx(Path workDir, Path pdf) throws IOException {
        return run(workDir, pdf, "docx", "docx:MS Word 2007 XML", "writer_pdf_import");
    }

    // ---------- running the program ----------

    private Path run(Path workDir, Path input, String outExtension, String convertTo, String inputFilter)
            throws IOException {
        if (!isAvailable()) {
            throw new FileMasterException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The document engine (LibreOffice) is not available on this server.");
        }
        Path jobDir = Files.createTempDirectory(workDir, "office-");
        Path outDir = Files.createDirectory(jobDir.resolve("out"));
        Path logFile = jobDir.resolve("office.log");

        // A LibreOffice profile is slow to create (about as slow as the conversion itself on a small server),
        // so a few profiles are kept and reused. Only one conversion may use a profile at a time.
        int profileId = acquireProfile();
        Path profile = profileDirectory(profileId);
        boolean damaged = false;
        try {
            List<String> arguments = List.of(command != null ? command : officePath,
                    "--headless", "--norestore", "--nologo", "--nodefault", "--nolockcheck",
                    "-env:UserInstallation=" + profile.toUri(),
                    "--infilter=" + inputFilter,
                    "--convert-to", convertTo,
                    "--outdir", outDir.toString(),
                    input.toString());

            ProcessBuilder builder = new ProcessBuilder(arguments)
                    .directory(jobDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile());
            Map<String, String> environment = builder.environment();
            for (String name : List.of("http_proxy", "https_proxy", "ftp_proxy", "all_proxy",
                    "HTTP_PROXY", "HTTPS_PROXY", "FTP_PROXY", "ALL_PROXY")) {
                environment.put(name, DEAD_PROXY);       // every web request goes nowhere
            }
            environment.put("no_proxy", "");
            environment.put("NO_PROXY", "");
            environment.put("HOME", jobDir.toString());   // the server user may not have a home folder
            environment.put("XDG_CACHE_HOME", profile.resolve("cache").toString());   // the font cache is kept, too
            environment.put("XDG_CONFIG_HOME", jobDir.resolve("config").toString());

            Process process = start(builder);
            closeQuietly(process.getOutputStream());
            boolean finished;
            try {
                finished = waitFor(process);
            } catch (RuntimeException e) {
                damaged = true;
                throw e;
            }
            if (!finished) {
                kill(process);
                damaged = true;                           // a killed LibreOffice can leave a broken profile behind
                throw new FileMasterException(HttpStatus.GATEWAY_TIMEOUT,
                        "The document took too long to convert and was stopped (limit: " + timeoutSeconds
                                + " seconds). Try a smaller document.");
            }

            String stem = input.getFileName().toString();
            stem = stem.substring(0, stem.lastIndexOf('.'));
            Path output = outDir.resolve(stem + "." + outExtension);
            if (!Files.exists(output) || Files.size(output) == 0) {
                waitForFile(output, jobDir, 60);
            }
            if (!Files.exists(output) || Files.size(output) == 0) {
                log.warn("LibreOffice produced no output. Log: {}", readTail(logFile));
                throw FileMasterException.badRequest(
                        "This document could not be converted. It may be damaged, password-protected or in an unsupported format.");
            }
            return output;
        } finally {
            if (damaged) {
                deleteQuietly(profile);
            }
            profiles.add(profileId);                       // give the profile back
        }
    }

    private int acquireProfile() {
        try {
            Integer id = profiles.poll(60, TimeUnit.SECONDS);
            if (id == null) {
                throw new FileMasterException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The server is busy converting other documents. Please try again in a minute.");
            }
            return id;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FileMasterException(HttpStatus.INTERNAL_SERVER_ERROR, "Processing was interrupted.");
        }
    }

    /** Outside the per-request temp folders, so it survives between conversions (and the cleanup job leaves it alone). */
    private static Path profileDirectory(int id) {
        return Path.of(System.getProperty("java.io.tmpdir"), "fm-lo-profile-" + id);
    }

    // ---------- warm-up ----------

    /** Right after the server has started, start LibreOffice once in the background so the first visitor is not slow. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpInBackground() {
        if (!enabled) {
            return;
        }
        Thread thread = new Thread(this::warmUp, "libreoffice-warm-up");
        thread.setDaemon(true);
        thread.start();
    }

    void warmUp() {
        if (!isAvailable()) {
            return;
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("filemaster-warmup-");
            Path text = Files.writeString(dir.resolve("warmup.txt"), "warm up");
            long start = System.nanoTime();
            txtToPdf(dir, text);
            log.info("LibreOffice is warmed up ({} ms)", (System.nanoTime() - start) / 1_000_000);
        } catch (IOException | RuntimeException e) {
            log.warn("LibreOffice warm-up did not work: {}", e.toString());
        } finally {
            deleteQuietly(dir);
        }
    }

    private static void deleteQuietly(Path folder) {
        if (folder == null) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }

    private Process start(ProcessBuilder builder) {
        try {
            return builder.start();
        } catch (IOException e) {
            log.error("Could not start LibreOffice ('{}')", command, e);
            throw new FileMasterException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The document engine (LibreOffice) is not available on this server.");
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

    /** "soffice" starts a second program (soffice.bin), so the whole family is stopped. */
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

    private static String readTail(Path logFile) {
        try (InputStream in = Files.newInputStream(logFile)) {
            in.skipNBytes(Math.max(0, Files.size(logFile) - 2000));
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Waits until the output file exists and has stopped growing. Only used when the file is not there
     * yet: on Windows the starter program can finish BEFORE LibreOffice has written the file.
     * We keep waiting while a LibreOffice process of THIS job is still running (its command line contains
     * the job folder). If nothing is running and nothing has appeared after 5 seconds, the conversion failed.
     */
    private static void waitForFile(Path file, Path jobDir, long maxSeconds) throws IOException {
        long start = System.nanoTime();
        long end = start + TimeUnit.SECONDS.toNanos(maxSeconds);
        long lastSize = -1;
        while (System.nanoTime() < end) {
            long size = Files.exists(file) ? Files.size(file) : -1;
            if (size > 0 && size == lastSize) {
                return;                                   // it exists and has stopped growing
            }
            lastSize = size;
            boolean nothingRunning = !isRunningFor(jobDir);
            if (size < 0 && nothingRunning && System.nanoTime() - start > TimeUnit.SECONDS.toNanos(5)) {
                return;                                   // nothing is working on it any more: it failed
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** True while any process on this computer has our job folder in its command line. */
    private static boolean isRunningFor(Path jobDir) {
        String key = jobDir.toString();
        try {
            return ProcessHandle.allProcesses()
                    .anyMatch(p -> p.info().commandLine().map(line -> line.contains(key)).orElse(false));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Finds LibreOffice. Order: the program name on the PATH, then the usual install folders of
     * Windows, macOS and Linux. If you set filemaster.office-path to something other than the default,
     * exactly that path is used and nothing else is guessed.
     */
    static String locate(String configured) {
        if (isInstalled(configured)) {
            return configured;
        }
        if (!DEFAULT_COMMAND.equals(configured)) {
            return null;
        }
        if (isInstalled("libreoffice")) {
            return "libreoffice";
        }
        for (Path candidate : standardLocations()) {
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        for (Path candidate : registryLocations()) {          // Windows: wherever the installer put it
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Windows remembers where LibreOffice is installed. Asks the registry (the "reg" program) for it. */
    static List<Path> registryLocations() {
        List<Path> found = new ArrayList<>();
        if (!isWindows()) {
            return found;
        }
        for (String key : List.of(
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\soffice.exe",   // the full path of soffice.exe
                "HKLM\\SOFTWARE\\LibreOffice\\UNO\\InstallPath",                                  // the "program" folder
                "HKLM\\SOFTWARE\\WOW6432Node\\LibreOffice\\UNO\\InstallPath",
                "HKCU\\SOFTWARE\\LibreOffice\\UNO\\InstallPath")) {
            String value = queryRegistryDefault(key);
            if (value != null) {
                Path path = Path.of(value);
                found.add(key.endsWith("soffice.exe") ? path : path.resolve("soffice.exe"));
            }
        }
        return found;
    }

    private static String queryRegistryDefault(String key) {
        try {
            Process process = new ProcessBuilder("reg", "query", key, "/ve").redirectErrorStream(true).start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return parseRegistryDefault(output);
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /** Picks the value out of the text "reg query" prints:  (Default)    REG_SZ    C:\Program Files\LibreOffice\program */
    static String parseRegistryDefault(String output) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("REG_(?:EXPAND_)?SZ\\s+(.+?)\\s*$");
        for (String line : output.split("\\R")) {
            java.util.regex.Matcher matcher = pattern.matcher(line);
            if (matcher.find() && !matcher.group(1).isBlank()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }

    /** A short description of where we looked, for the startup message. */
    private static String describeSearch() {
        StringBuilder sb = new StringBuilder("the PATH");
        int shown = 0;
        for (Path place : standardLocations()) {
            if (shown++ < 4) {
                sb.append(", ").append(place);
            }
        }
        return sb.append(isWindows() ? ", the Windows registry" : "").toString();
    }

    static List<Path> standardLocations() {
        List<Path> places = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (isWindows()) {
            for (String variable : List.of("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)")) {
                String root = System.getenv(variable);
                if (root != null) {
                    places.addAll(libreOfficeFoldersIn(Path.of(root), "soffice.exe"));
                }
            }
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) {
                places.addAll(libreOfficeFoldersIn(Path.of(local, "Programs"), "soffice.exe"));
            }
            places.add(Path.of("C:\\Program Files\\LibreOffice\\program\\soffice.exe"));
            places.add(Path.of("C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe"));
        } else if (os.contains("mac")) {
            places.add(Path.of("/Applications/LibreOffice.app/Contents/MacOS/soffice"));
        } else {
            places.add(Path.of("/usr/bin/soffice"));
            places.add(Path.of("/usr/local/bin/soffice"));
            places.add(Path.of("/usr/lib/libreoffice/program/soffice"));
            places.add(Path.of("/usr/lib64/libreoffice/program/soffice"));
            places.addAll(libreOfficeFoldersIn(Path.of("/opt"), "soffice"));
        }
        return places;
    }

    /** Folders such as "LibreOffice", "LibreOffice 7" or "libreoffice7.6" inside a parent folder. */
    static List<Path> libreOfficeFoldersIn(Path parent, String executable) {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(parent)) {
            for (Path folder : folders) {
                if (Files.isDirectory(folder)
                        && folder.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("libreoffice")) {
                    found.add(folder.resolve("program").resolve(executable));
                }
            }
        } catch (IOException | RuntimeException e) {
            // folder missing or not readable: nothing to add
        }
        return found;
    }

    /** Looks for the program on the PATH (or checks the full path), without starting it. */
    static boolean isInstalled(String command) {
        Path direct = Path.of(command);
        if (direct.isAbsolute() || direct.getNameCount() > 1) {
            return Files.isExecutable(direct);
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String folder : path.split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(folder, command)) || Files.isExecutable(Path.of(folder, command + ".exe"))) {
                return true;
            }
        }
        return false;
    }
}
