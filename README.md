<<<<<<< HEAD
# FileMaster – File Compressor & File Converter

A small full-stack web app built with **Java 21, Spring Boot, Maven** and plain **HTML/CSS/JavaScript**.
No database, no frameworks on the frontend. Deployable to Render with Docker.

## Features

**File Compressor**
- Add up to **8 files** (20 MB each, 60 MB total) by drag & drop or browsing
- Images (JPG/PNG): genuinely re-encoded and resized. Other files (PDF, Office, TXT, CSV, JSON, XML, HTML, MD...): packed into a ZIP with Deflate
- **Small / Medium / High** levels. The app really compresses your files with every level and shows the **exact resulting size** on each card, so you can choose before downloading
- With several files you choose: **compress each file separately** (one download per file) or **merge into one ZIP**

**File Converter**

| From | To |
|---|---|
| DOCX | PDF, TXT |
| DOC (old Word) | PDF, TXT, DOCX |
| PDF | DOCX |
| TXT | PDF, DOCX |
| JPG | PNG |
| PNG | JPG |

- Real conversion: documents are read into paragraphs and written in the new format; images are decoded and re-encoded. Never a renamed extension
- With several files you choose: **convert each file** (pick a format per file) or **merge into one file** (for example three TXT/DOCX files into a single PDF). Merge order = the order shown (use the arrows to reorder)
- **Nothing is dropped.** With LibreOffice installed (the Docker image includes it), DOCX/DOC → PDF, PDF → DOCX and TXT → PDF keep pictures, logos, tables, headers and footers, fonts and non-English text (Telugu, Hindi, ...). Merging converts every file to PDF first and joins the PDFs, so pictures survive merging too
- Things that genuinely cannot be kept are **reported on the result screen**: Word → TXT says how many pictures a plain text file cannot hold, PNG → JPG says that transparency becomes white, PDF → DOCX says that text sits in boxes
- Without LibreOffice (for example on your own PC before you install it), documents are converted text-only and the result screen says so

**Image Compressor** (JPG, JPEG, PNG, WebP)
- Low / Medium / High quality, up to 8 images at once, each processed separately
- Width and height stay the same: JPG and WebP are re-encoded at a lower quality, PNG gets a smaller colour palette (transparency is kept)
- Shows original size, compressed size and percentage saved, with a download for every image
- If an image cannot be made smaller (for example High on an already light JPG), you get the original back with a note
- Animated WebP is not supported (the server's FFmpeg cannot read it) and is refused with a clear message

**Audio Converter** (FFmpeg)
- MP3, WAV, M4A, AAC, OGG, FLAC, WMA: any of them to any other

**Video Converter** (FFmpeg)
- MP4, MOV, MKV, AVI, WEBM, WMV, FLV, M4V: any of them to any other
- Audio extraction: MP4, MOV, MKV and WEBM can also become MP3, WAV or M4A
- One file at a time, up to 50 MB. Results larger than 100 MB are refused. A conversion that runs longer than 4 minutes is stopped.

**Safety**
- Extension allow-list + "magic byte" check (a program renamed to `photo.jpg` is rejected)
- File names are sanitized; uploads are stored under fixed names (`input-0.png`) in a private temp folder
- Temp folders are deleted after every request, plus a scheduled sweep for leftovers
- Size, file-count and image pixel limits, zip-bomb limit for Word files, security headers, clean JSON errors

## How it works

```
Browser (static/index.html + js/app.js)
   │  XMLHttpRequest, multipart/form-data
   ▼
Controller   receives the request, returns the download
   ▼
Service      validates, converts/compresses inside a temp folder
   ▼
Response     file bytes + headers (Content-Disposition, X-Original-Size, ...)
```

## Project structure

```
src/main/java/com/filemaster/
├── FileMasterApplication.java        starts the app
├── config/
│   ├── AppConfig.java                enables scheduled jobs
│   ├── FileTypes.java                allowed extensions per feature
│   └── SecurityHeadersFilter.java    adds browser security headers
├── controller/
│   ├── CompressController.java       POST /api/compress, /merge, /estimate
│   ├── ImageCompressController.java  POST /api/image/compress
│   ├── MediaController.java          /api/audio/... and /api/video/...
│   ├── ConvertController.java        GET /api/convert/formats, POST /api/convert, POST /api/convert/merge
│   ├── DownloadResponse.java         builds the file-download response
│   └── HealthController.java         GET /api/health
├── service/
│   ├── CompressionService.java       ZIP + image compression, merge, size estimates
│   ├── ConversionService.java        image + document conversions, merge
│   ├── FileValidationService.java    all upload checks (one or many files)
│   ├── TempFileService.java          safe temp folders + cleanup
│   ├── ImageHelper.java              ImageIO read/scale/write
│   ├── DocumentHelper.java           read TXT/DOC/DOCX/PDF, write DOCX/TXT
│   ├── FfmpegService.java            starts FFmpeg safely (timeout, limits, error messages)
│   ├── MediaCommands.java            the FFmpeg options for every output format
│   ├── MediaConversionService.java   audio + video conversion tables and logic
│   ├── MediaSignatures.java          "magic bytes" of audio/video/WebP files
│   ├── ImageCompressionService.java  Low / Medium / High image compression
│   └── PdfHelper.java                PDFBox paragraphs → PDF
├── model/                            small data classes (records, enum)
└── exception/                        FileMasterException + GlobalExceptionHandler
src/main/resources/
├── application.properties
└── static/                           index.html, css/style.css, js/app.js, favicon.svg
src/test/java/...                     end-to-end tests
```

## Run locally

Requirements: **JDK 21**, **Maven 3.9+** (`java -version`, `mvn -version`) and **FFmpeg**.

FFmpeg is needed for the Audio Converter, Video Converter, PNG compression and WebP. Everything else (documents, JPG, ZIP) works without it. Install it once and check with `ffmpeg -version`:

| System | Command |
|---|---|
| Windows | `winget install Gyan.FFmpeg` (then open a **new** terminal) |
| macOS | `brew install ffmpeg` |
| Ubuntu / Debian | `sudo apt install ffmpeg` |

Documents keep their pictures and layout only if **LibreOffice** is installed too (the Docker image already has it). FileMaster finds it by itself in its normal install folder (Windows `C:\Program Files\LibreOffice`, macOS `/Applications`, Linux `/usr/bin`), so you only have to install it and **restart FileMaster**:

| System | Command |
|---|---|
| Windows | `winget install TheDocumentFoundation.LibreOffice` (or download the installer from libreoffice.org) |
| macOS | `brew install --cask libreoffice` |
| Ubuntu / Debian | `sudo apt install libreoffice-writer libreoffice-draw` |

Check it: open http://localhost:8080/api/health. `"libreOffice": true` means pictures are kept; `false` means documents are converted as text only.

**"Only the text was converted ... LibreOffice was not found"?**
1. Install LibreOffice (table above), then stop and start FileMaster again (it looks for LibreOffice once, at the first document conversion).
2. Open `/api/health`. Still `false`? LibreOffice is installed in an unusual place: set its full path, for example on Windows `setx FILEMASTER_OFFICE_PATH "D:\Apps\LibreOffice\program\soffice.exe"` and open a **new** terminal (macOS/Linux: `export FILEMASTER_OFFICE_PATH=/path/to/soffice`).
3. `false` although it is installed and `FILEMASTER_OFFICE_ENABLED=false` is set? Remove that setting.

If FFmpeg is missing, those features answer "The conversion engine (FFmpeg) is not available" and the tests that need it are skipped. Nothing else breaks.

```bash
mvn test                  # run the automated tests
mvn spring-boot:run       # start the app
```

Open http://localhost:8080

Port busy? `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=9090`

Build the JAR yourself: `mvn clean package` then `java -jar target/filemaster.jar`.

## API

| Method | URL | Form fields | Returns |
|---|---|---|---|
| POST | `/api/compress` | `file`, `preset` = small / medium / high | compressed file |
| POST | `/api/compress/merge` | `files` (several), `preset` | one ZIP |
| POST | `/api/compress/estimate` | `files`, `mode` = individual / merge | JSON with the real size for each level |
| GET | `/api/convert/formats` | – | JSON: extensions, conversions, mergeable formats |
| POST | `/api/convert` | `file`, `targetFormat` (e.g. `PDF`) | converted file |
| POST | `/api/convert/merge` | `files` (several), `targetFormat` | one merged file |
| POST | `/api/image/compress` | `file`, `quality` = low / medium / high | compressed image |
| GET | `/api/audio/formats` | – | JSON: extensions and possible targets |
| POST | `/api/audio/convert` | `file`, `targetFormat` (e.g. `MP3`) | converted audio |
| GET | `/api/video/formats` | – | JSON: extensions and possible targets (video + audio extraction) |
| POST | `/api/video/convert` | `file`, `targetFormat` (e.g. `WEBM` or `MP3`) | converted video / extracted audio |
| GET | `/api/health` | – | `{"status":"UP"}` |

"Each file separately" is done by the browser calling the single-file endpoint once per file.
Errors always look like `{"error": "readable message"}` with a proper HTTP status (400, 413, 415, 500).

## Run with Docker

```bash
docker build -t filemaster .
docker run --rm -p 8080:8080 filemaster
# or with a custom port, like Render does:
docker run --rm -e PORT=10000 -p 10000:10000 filemaster
```

The Dockerfile installs FFmpeg (`apt-get install ffmpeg`), so the first build takes a few minutes longer. You do not need Docker or FFmpeg on your own computer to deploy: Render builds the image from your GitHub repository.

## Upload to GitHub

1. Create an empty repository on github.com (no README, no .gitignore), e.g. `filemaster`.
2. In the project folder:

```bash
git init
git add .
git commit -m "FileMaster: file compressor and converter"
git branch -M main
git remote add origin https://github.com/<your-username>/filemaster.git
git push -u origin main
```

## Deploy on Render

1. Sign in at https://render.com with your GitHub account.
2. **New +** → **Web Service** → pick your `filemaster` repository.
3. Settings:
   - **Language / Runtime:** Docker (Render finds the `Dockerfile` automatically)
   - **Branch:** `main`
   - **Instance type:** Free
   - **Health Check Path:** `/api/health`
4. Click **Create Web Service**. The first build takes a few minutes (Maven runs inside Docker).
5. Open the `https://<name>.onrender.com` link when the log says the service is live.

Notes:
- Render sets the `PORT` variable; `server.port=${PORT:8080}` reads it, so no extra setup is needed.
- On the free plan the service sleeps after ~15 minutes of inactivity; the first request afterwards takes about 30–60 seconds.
- Every `git push` to `main` redeploys automatically.
- The free plan has 512 MB RAM and very little CPU, which is why uploads are limited (20 MB documents, 50 MB audio/video), only one FFmpeg conversion runs at a time, and images are limited to 20 megapixels.
- **LibreOffice needs about 200 MB of memory per document conversion** (measured), on top of Java and FFmpeg. Use a Render instance with at least 1 GB. On the 512 MB free plan set the environment variable `FILEMASTER_OFFICE_ENABLED=false`: documents are then converted text-only (and the result screen says so) instead of risking a crash.
- Video conversion is CPU heavy. On the free plan a large video can take minutes or hit the 4 minute limit; short clips work best. I have not verified Render's own request time limit, so check Render's documentation if long conversions fail.

## Privacy: what the homepage says and why it is true

The homepage only makes claims the code backs up:

| Claim | How it is implemented |
|---|---|
| Files are processed temporarily | Every request gets its own folder from `TempFileService.withWorkDir(...)`. |
| Temporary files are automatically deleted after processing | The folder is deleted in a `finally` block, so it is removed after success **and** after errors. A scheduled job (`cleanupStaleFolders`) also removes any `filemaster-*` folder older than 30 minutes, for example after a crash. |
| We do not permanently store your files | There is no database and no user accounts. The result is sent back in the response and the server keeps no copy. |
| We do not share your files | The code never sends a file to any other service. FFmpeg runs on the same server. |
| Files are checked before processing | `FileValidationService`: size limit, allow-listed extension, and a "magic byte" check that the content really is that type. |
| Uploaded Word files cannot make the server contact the internet | A Word file can link a picture to a web address, and LibreOffice would download it (this was tested). `OfficeSanitizer` removes every external link from `.docx` files, LibreOffice is started with a dead proxy, the input type is forced, and a file that is not really a `.docx` is refused. |
| Safe file names | The name you upload is cleaned (letters, digits, `.`, `-`, `_`) and is only used for the download name. On disk the file is always called `input.<ext>`. FFmpeg is started without a shell and may only read local files. |

What the code cannot promise: the connection is only encrypted when the site is opened with `https://` (Render provides this; plain `http://localhost` is not encrypted), and the hosting platform itself is outside this project. Spring and Tomcat keep their own short-lived copy of an upload while the request runs and remove it when the request ends (standard behaviour, not covered by a test here). A test (`temporaryFoldersAreDeletedAfterEveryRequest`) checks that no `filemaster-*` folder is left behind, also when FFmpeg fails.

## Add a new conversion later

Image example (PNG → GIF):
1. `ConversionService.SUPPORTED` and `EXTENSIONS`: add the pair and the `gif` extension.
2. `ConversionService.convert()`: add a `case "PNG_GIF"` and a private method (ImageIO already writes GIF/BMP).
3. Add the extension to `FileTypes.CONVERTIBLE`.
4. Add a test in `FileMasterApplicationTests`. The frontend reads `/api/convert/formats`, so it needs no change.

Document example (new target format): add it to `SUPPORTED`, then add a `case` in `ConversionService.convertDocuments()` that writes the paragraphs.

## Limitations (good to mention in interviews)

- PNG is lossless, so PNG compression works by reducing the image size (dimensions). JPG compression lowers quality and size.
- PDF → DOCX rebuilds the layout with positioned text boxes (that is how everything stays where it was). It is accurate to look at, but editing is easier in the original Word file. Scanned PDFs keep their page pictures, but the text in them is not recognised (no OCR).
- Upload limits: 8 files at once (Tomcat allows 10 parts per upload, including form fields), 20 MB per file, 60 MB total.
- The size shown for each compression level is exact, but it needs a second upload of the files (one to calculate, one to compress).
- Already-compressed formats (DOCX, PDF, JPG inside a ZIP) cannot shrink much; the app tells the user.
- TXT → PDF uses the built-in Helvetica font, so characters outside Western European text show as `?`.
- Photos: the picture's rotation note (EXIF) is applied, but the embedded colour profile and camera details are not copied to compressed or converted JPEGs. Audio/video: subtitle and data tracks and embedded cover art are not copied.
- Files are processed in memory/temp folders and not stored. There is no user login, rate limiting or virus scanning; a production system would add them.

- Audio/video: conversions re-encode, so some quality is lost (especially WMV/AVI/FLV targets). Subtitle and data tracks are dropped. Videos with odd sizes are cropped by one pixel to make the size even (required by H.264).
- WMA, WMV, AVI and FLV outputs use older codecs on purpose for compatibility; MP4/MOV/MKV/M4V use H.264 + AAC, WEBM uses VP8 + Vorbis.
- PNG compression reduces the number of colours, so photos saved as PNG can show slight banding at the Low setting. Soft transparency (shadows) can become sharper.

## Interview talking points

- **Why a service layer?** Controllers only handle HTTP; services hold the logic, so it is easy to test and reuse.
- **Why check magic bytes?** File extensions can be faked; the first bytes of a file reveal its real type.
- **Why fixed temp file names?** A user-supplied name could contain `../` and write outside the temp folder (path traversal).
- **How does merging work?** Every document is read into a list of paragraphs (`Para`), the lists are joined, and one writer produces the PDF/DOCX/TXT. Adding a format means writing one reader or one writer, not every pair.
- **Why a multi-stage Docker build?** Stage 1 has Maven and the JDK to build; stage 2 only has the JRE and the JAR, so the final image is smaller and safer.
- **How do you run FFmpeg safely?** `ProcessBuilder` with a list of arguments (no shell, so no command injection), fixed file names inside a private temp folder, `-protocol_whitelist file` so FFmpeg cannot open URLs or playlists, a time limit that kills stuck processes, an output size limit (`-fs`), and a semaphore so only one conversion runs at a time on a small server.
- **Why is MediaCommands a separate class?** It only builds lists of text, so it has no Spring code and is easy to read and test.
- **Why `${PORT:8080}`?** Render assigns the port at runtime; locally the default 8080 is used.
=======
# filemaster
>>>>>>> a95757c367d0712f1351039f32515b63bcfae680
