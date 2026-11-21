# ---------- Stage 1: build the JAR with Maven (Java 21) ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom.xml first so Docker can cache the downloaded dependencies
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are run locally with "mvn test"; skipping them keeps the cloud build fast.
RUN mvn -B -q clean package -Dmaven.test.skip=true

# ---------- Stage 2: small runtime image ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# FFmpeg: audio, video, PNG-palette and WebP work.
# LibreOffice: converts Word and text files WITHOUT losing pictures, logos, tables or fonts.
# poppler-utils (pdftohtml, pdfimages): reads PDFs so PDF -> Word gives normal paragraphs with pictures in place.
# Fonts: Liberation/Carlito/Caladea replace Arial/Times/Calibri/Cambria with the same letter widths,
#        Noto covers Telugu, Hindi, Tamil, Arabic and many other scripts.
#        (For Chinese/Japanese/Korean text also add: fonts-noto-cjk)
# (This image is Ubuntu 24.04. The first build takes several minutes because of the size of LibreOffice.)
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
        libreoffice-writer libreoffice-draw \
        poppler-utils \
        fonts-liberation fonts-dejavu-core fonts-crosextra-carlito fonts-crosextra-caladea fonts-noto-core \
    && rm -rf /var/lib/apt/lists/* \
    && ffmpeg -version | head -n 1

# Build-time check AND warm-up: LibreOffice converts a file once here. If that fails, the build fails now (not silently
# later). The profile and font cache it creates are kept in /tmp/fm-lo-profile-0, which the app reuses, so the first
# document conversion after a deploy is not slow. (The same folder name is used by OfficeService.)
RUN mkdir -p /tmp/fm-lo-profile-0/cache \
    && printf 'build check' > /tmp/check.txt \
    && XDG_CACHE_HOME=/tmp/fm-lo-profile-0/cache HOME=/tmp/fm-lo-profile-0 \
       soffice --headless --norestore --nologo --nodefault -env:UserInstallation=file:///tmp/fm-lo-profile-0 \
        --convert-to pdf --outdir /tmp /tmp/check.txt \
    && test -s /tmp/check.pdf \
    && rm -f /tmp/check.txt /tmp/check.pdf \
    && pdftohtml -v 2>&1 | head -n 1

# Do not run the app as root
RUN useradd --system --uid 1001 appuser
COPY --from=build /app/target/filemaster.jar app.jar
RUN chown -R appuser /tmp/fm-lo-profile-0
USER appuser

# Memory settings suited to small hosts (Render free tier has 512 MB).
# Java may use 55% of the memory; the rest is left for FFmpeg, LibreOffice (about 200 MB per document) and the system.
# TieredStopAtLevel=1 uses the simple compiler only: less CPU and a faster start on very small servers.
# LibreOffice needs a server with at least 1 GB of memory. On less, set FILEMASTER_OFFICE_ENABLED=false.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djava.awt.headless=true"

# Render sets PORT; application.properties reads it with server.port=${PORT:8080}
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
