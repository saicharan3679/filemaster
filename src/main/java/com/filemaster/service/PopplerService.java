package com.filemaster.service;

import com.filemaster.exception.FileMasterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads a PDF with the poppler tools ("pdftohtml" and "pdfimages") and builds a normal, flowing Word document
 * from it (see PdfFlowConverter). This is much faster than LibreOffice's PDF import and gives Word real
 * paragraphs instead of one floating text box per line.
 *
 * Safety: started without a shell, with a time limit, inside the request's private temp folder.
 */
@Service
public class PopplerService {

    private static final Logger log = LoggerFactory.getLogger(PopplerService.class);
    private static final int MAX_PAGES = 300;
    private static final double PX_PER_POINT = 2.0;   // pdftohtml -zoom 2: more precise positions

    private final boolean enabled;
    private final long timeoutSeconds;
    private final String configuredPath;
    private volatile Boolean available;
    private volatile Path binDirectory;   // null = the programs are on the PATH

    public PopplerService(@Value("${filemaster.poppler-enabled:true}") boolean enabled,
                          @Value("${filemaster.poppler-timeout-seconds:90}") long timeoutSeconds,
                          @Value("${filemaster.poppler-path:}") String configuredPath) {
        this.enabled = enabled;
        this.timeoutSeconds = timeoutSeconds;
        this.configuredPath = configuredPath == null ? "" : configuredPath.trim();
    }

    /** Log at startup whether the PDF reader was found, so a missing installation is noticed immediately. */
    @EventListener(ApplicationReadyEvent.class)
    public void checkAtStartup() {
        isAvailable();
    }

    /** True when switched on and both poppler programs were found. */
    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        if (available == null) {
            synchronized (this) {
                if (available == null) {
                    available = locate();
                    if (available) {
                        log.info("Poppler (PDF reader) found{}", binDirectory == null ? " on the PATH" : ": " + binDirectory);
                    } else {
                        log.warn("Poppler (PDF reader) was NOT found, so PDF to Word uses LibreOffice (text boxes) or text only. "
                                + "Install poppler (Windows: choco install poppler, macOS: brew install poppler, Linux: apt install poppler-utils) "
                                + "and restart FileMaster. If it is installed in an unusual place, set the folder with pdftohtml in the "
                                + "environment variable FILEMASTER_POPPLER_PATH.");
                    }
                }
            }
        }
        return available;
    }

    private boolean locate() {
        if (!configuredPath.isEmpty()) {                       // an explicit setting is used exactly as given
            Path configured = Path.of(configuredPath);
            Path dir = Files.isDirectory(configured) ? configured : configured.getParent();
            if (dir != null && hasBoth(dir)) {
                binDirectory = dir;
                return true;
            }
            return false;
        }
        if (OfficeService.isInstalled("pdftohtml") && OfficeService.isInstalled("pdfimages")) {
            binDirectory = null;
            return true;
        }
        for (Path dir : standardFolders()) {
            if (hasBoth(dir)) {
                binDirectory = dir;
                return true;
            }
        }
        return false;
    }

    private static boolean hasBoth(Path dir) {
        return isProgram(dir, "pdftohtml") && isProgram(dir, "pdfimages");
    }

    private static boolean isProgram(Path dir, String name) {
        return Files.isExecutable(dir.resolve(name)) || Files.isExecutable(dir.resolve(name + ".exe"));
    }

    /** Where poppler usually is when it is not on the PATH (chocolatey, scoop, a downloaded zip, Homebrew). */
    static List<Path> standardFolders() {
        List<Path> folders = new ArrayList<>();
        if (OfficeService.isWindows()) {
            List<Path> roots = new ArrayList<>();
            for (String variable : List.of("ProgramFiles", "ProgramFiles(x86)", "ProgramW6432", "LOCALAPPDATA")) {
                String value = System.getenv(variable);
                if (value != null) {
                    roots.add(Path.of(value));
                }
            }
            String data = System.getenv("ProgramData");
            if (data != null) {
                roots.add(Path.of(data, "chocolatey", "lib"));
            }
            String user = System.getenv("USERPROFILE");
            if (user != null) {
                roots.add(Path.of(user, "scoop", "apps"));
                roots.add(Path.of(user, "Downloads"));
            }
            roots.add(Path.of("C:\\"));
            for (Path root : roots) {
                folders.addAll(popplerFoldersIn(root));
            }
        } else {
            folders.add(Path.of("/opt/homebrew/bin"));
            folders.add(Path.of("/usr/local/bin"));
            folders.add(Path.of("/usr/bin"));
        }
        return folders;
    }

    /** Looks inside folders named "poppler..." (such as poppler-24.08.0\Library\bin) for the programs. */
    static List<Path> popplerFoldersIn(Path root) {
        List<Path> found = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                if (Files.isDirectory(child) && child.getFileName().toString().toLowerCase(java.util.Locale.ROOT).startsWith("poppler")) {
                    try (java.util.stream.Stream<Path> walk = Files.walk(child, 5)) {
                        walk.filter(f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).matches("pdftohtml(\\.exe)?"))
                                .forEach(f -> found.add(f.getParent()));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // folder missing or not readable: nothing to add
        }
        return found;
    }

    private String program(String name) {
        Path dir = binDirectory;
        if (dir == null) {
            return name;
        }
        Path exe = dir.resolve(name + ".exe");
        return Files.isExecutable(exe) ? exe.toString() : dir.resolve(name).toString();
    }

    /** Converts a PDF to a flowing .docx inside workDir and returns the new file. */
    public Path pdfToDocx(Path workDir, Path pdf) throws IOException {
        Path dir = Files.createTempDirectory(workDir, "poppler-");
        Path xml = dir.resolve("out.xml");

        // 1) text with positions and fonts, plus the pictures as files
        run(List.of(program("pdftohtml"), "-xml", "-q", "-zoom", String.valueOf((int) PX_PER_POINT), "-l", String.valueOf(MAX_PAGES + 1),
                pdf.toString(), "out"), dir, null);
        if (!Files.exists(xml)) {
            throw FileMasterException.badRequest("This PDF could not be read. It may be damaged or password-protected.");
        }

        // 2) pictures that have a transparency mask: put mask and picture back together
        Path listing = dir.resolve("list.txt");
        run(List.of(program("pdfimages"), "-list", "-l", String.valueOf(MAX_PAGES + 1), pdf.toString()), dir, listing);
        String listText = Files.exists(listing) ? Files.readString(listing, StandardCharsets.UTF_8) : "";
        List<Integer> maskPages = PdfImageMasks.pagesWithSoftMasks(listText);
        if (!maskPages.isEmpty()) {
            // writing every picture as PNG is slow, so only the pages that really have a mask are exported
            for (int[] range : ranges(maskPages)) {
                run(List.of(program("pdfimages"), "-png", "-p", "-f", String.valueOf(range[0]), "-l", String.valueOf(range[1]),
                        pdf.toString(), "img"), dir, null);
            }
            PdfImageMasks.apply(xml, dir, listText);
        }

        // 3) rebuild paragraphs
        Path docx = Files.createTempFile(workDir, "flow-", ".docx");
        PdfFlowConverter.Result result = PdfFlowConverter.convert(xml, dir, PX_PER_POINT, docx);
        if (result.pages() > MAX_PAGES) {
            throw new FileMasterException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "This PDF has too many pages. The limit is " + MAX_PAGES + " pages.");
        }
        return docx;
    }

    /** [1,2,3,7,8,20] -> [1-3], [7-8], [20-20]. With very many ranges one big range is used instead. */
    static List<int[]> ranges(List<Integer> pages) {
        List<int[]> result = new ArrayList<>();
        int start = pages.get(0);
        int previous = start;
        for (int i = 1; i < pages.size(); i++) {
            int page = pages.get(i);
            if (page != previous + 1) {
                result.add(new int[]{start, previous});
                start = page;
            }
            previous = page;
        }
        result.add(new int[]{start, previous});
        if (result.size() > 15) {
            return List.of(new int[]{pages.get(0), pages.get(pages.size() - 1)});
        }
        return result;
    }

    private void run(List<String> command, Path workDir, Path stdoutFile) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(false);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        if (stdoutFile != null) {
            builder.redirectOutput(stdoutFile.toFile());
        } else {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        builder.environment().put("HOME", workDir.toString());
        builder.environment().put("XDG_CACHE_HOME", workDir.resolve("cache").toString());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            log.error("Could not start {}", command.get(0), e);
            throw new FileMasterException(HttpStatus.SERVICE_UNAVAILABLE, "The PDF reader (poppler) is not available on this server.");
        }
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new FileMasterException(HttpStatus.GATEWAY_TIMEOUT,
                        "Reading the PDF took too long and was stopped. Try a smaller PDF.");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new FileMasterException(HttpStatus.INTERNAL_SERVER_ERROR, "Processing was interrupted.");
        }
    }
}
