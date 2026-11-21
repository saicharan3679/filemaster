package com.filemaster;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the Audio Converter, Video Converter and Image Compressor.
 *
 * Tests that need the FFmpeg program are SKIPPED (not failed) when FFmpeg is not installed
 * on your computer. Install FFmpeg (see README) to run all of them:  mvn test
 */
@SpringBootTest
@AutoConfigureMockMvc
class MediaFeaturesTests {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Autowired
    private MockMvc mockMvc;

    // ---------- helpers ----------

    private static boolean ffmpegInstalled() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** A real WAV file: a 440 Hz tone, mono, 8000 Hz, 16 bit. */
    private static byte[] wav(int seconds) {
        int rate = 8000;
        int samples = rate * seconds;
        ByteBuffer b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples * 2).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
        for (int i = 0; i < samples; i++) {
            b.putShort((short) (Math.sin(i * 2 * Math.PI * 440 / rate) * 12000));
        }
        return b.array();
    }

    /** A real 1-second MP4 video with sound, made by FFmpeg itself. Returns null if FFmpeg cannot make it. */
    private static byte[] mp4() throws Exception {
        Path file = Files.createTempFile("filemaster-test-", ".mp4");
        try {
            Process p = new ProcessBuilder("ffmpeg", "-nostdin", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "testsrc=duration=1:size=160x120:rate=10",
                    "-f", "lavfi", "-i", "sine=duration=1",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", file.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0 ? Files.readAllBytes(file) : null;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static byte[] noisyImage(String format) throws IOException {
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static MockMultipartFile upload(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    private byte[] convert(String api, String name, byte[] data, String target) throws Exception {
        return mockMvc.perform(multipart("/api/" + api + "/convert").file(upload(name, data)).param("targetFormat", target))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static String ascii(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }

    private static Set<String> tempFolders() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("filemaster-") && !name.startsWith("filemaster-test-"))
                    .collect(Collectors.toSet());
        }
    }

    // ---------- what is supported ----------

    @Test
    void audioFormatsListEveryConversion() throws Exception {
        mockMvc.perform(get("/api/audio/formats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extensions.flac").value("FLAC"))
                .andExpect(jsonPath("$.extensions.wma").value("WMA"))
                .andExpect(jsonPath("$.conversions.MP3.length()").value(6));
    }

    @Test
    void onlyMp4MovMkvAndWebmCanExtractAudio() throws Exception {
        mockMvc.perform(get("/api/video/formats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversions.MP4.length()").value(10))   // 7 videos + MP3, WAV, M4A
                .andExpect(jsonPath("$.conversions.WEBM.length()").value(10))
                .andExpect(jsonPath("$.conversions.AVI.length()").value(7))    // 7 videos, no audio extraction
                .andExpect(jsonPath("$.conversions.FLV.length()").value(7));
    }

    // ---------- validation and security (no FFmpeg needed) ----------

    @Test
    void rejectsTextFileRenamedToMp3() throws Exception {
        mockMvc.perform(multipart("/api/audio/convert")
                        .file(upload("song.mp3", "I am only text".getBytes(StandardCharsets.UTF_8)))
                        .param("targetFormat", "WAV"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error", containsString("does not match")));
    }

    @Test
    void rejectsVideoFileSentToAudioConverter() throws Exception {
        mockMvc.perform(multipart("/api/audio/convert")
                        .file(upload("clip.mp4", "x".getBytes(StandardCharsets.UTF_8)))
                        .param("targetFormat", "MP3"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void rejectsExecutableFiles() throws Exception {
        mockMvc.perform(multipart("/api/video/convert")
                        .file(upload("setup.exe", "MZ".getBytes(StandardCharsets.UTF_8)))
                        .param("targetFormat", "MP4"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void rejectsConversionToTheSameOrAnUnknownFormat() throws Exception {
        mockMvc.perform(multipart("/api/audio/convert").file(upload("a.wav", wav(1))).param("targetFormat", "WAV"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/audio/convert").file(upload("a.wav", wav(1))).param("targetFormat", "EXE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aviCannotBeConvertedToAudio() throws Exception {
        byte[] fakeAvi = new byte[64];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, fakeAvi, 0, 4);
        System.arraycopy("AVI ".getBytes(StandardCharsets.US_ASCII), 0, fakeAvi, 8, 4);
        mockMvc.perform(multipart("/api/video/convert").file(upload("old.avi", fakeAvi)).param("targetFormat", "MP3"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnimatedWebp() throws Exception {
        ByteBuffer b = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(22).put("WEBP".getBytes(StandardCharsets.US_ASCII));
        b.put("VP8X".getBytes(StandardCharsets.US_ASCII)).putInt(10).put((byte) 0x02);   // 0x02 = "animation" flag
        mockMvc.perform(multipart("/api/image/compress").file(upload("anim.webp", b.array())).param("quality", "low"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Animated WebP")));
    }

    @Test
    void rejectsUnknownImageQualityAndWrongImageType() throws Exception {
        mockMvc.perform(multipart("/api/image/compress").file(upload("p.jpg", noisyImage("jpg"))).param("quality", "ultra"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/image/compress").file(upload("doc.pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnsupportedMediaType());
    }

    // ---------- Image Compressor ----------

    @Test
    void jpgGetsSmallerAndLowIsSmallerThanMedium() throws Exception {
        byte[] original = noisyImage("jpg");   // saved by Java at quality 0.75
        long[] sizes = new long[2];
        String[] levels = {"low", "medium"};
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = mockMvc.perform(multipart("/api/image/compress")
                            .file(upload("photo.jpg", original)).param("quality", levels[i]))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", containsString("photo-compressed.jpg")))
                    .andReturn().getResponse();
            byte[] body = response.getContentAsByteArray();
            sizes[i] = body.length;
            assertNotNull(ImageIO.read(new ByteArrayInputStream(body)), "result must be a readable image");
            assertEquals(String.valueOf(original.length), response.getHeader("X-Original-Size"));
            assertTrue(Double.parseDouble(response.getHeader("X-Saved-Percent")) > 0);
        }
        assertTrue(sizes[0] < sizes[1] && sizes[1] < original.length, "low < medium < original, got " + sizes[0] + ", " + sizes[1]);
    }

    @Test
    void pngColourPaletteMakesTheFileSmaller() throws Exception {
        assumeTrue(ffmpegInstalled(), "FFmpeg is not installed");
        byte[] original = noisyImage("png");
        MockHttpServletResponse response = mockMvc.perform(multipart("/api/image/compress")
                        .file(upload("noise.png", original)).param("quality", "low"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("noise-compressed.png")))
                .andReturn().getResponse();
        byte[] body = response.getContentAsByteArray();
        assertTrue(body.length < original.length, "PNG must get smaller");
        assertEquals(0x89, body[0] & 0xFF);
        assertNotNull(ImageIO.read(new ByteArrayInputStream(body)));
    }

    @Test
    void webpIsRecompressed() throws Exception {
        assumeTrue(ffmpegInstalled(), "FFmpeg is not installed");
        Path in = Files.createTempFile("filemaster-test-", ".jpg");
        Path out = Files.createTempFile("filemaster-test-", ".webp");
        try {
            Files.write(in, noisyImage("jpg"));
            Process p = new ProcessBuilder("ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", in.toString(),
                    "-c:v", "libwebp", "-quality", "95", out.toString()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            assumeTrue(p.waitFor() == 0, "this FFmpeg cannot write WebP");
            byte[] original = Files.readAllBytes(out);

            MockHttpServletResponse response = mockMvc.perform(multipart("/api/image/compress")
                            .file(upload("pic.webp", original)).param("quality", "low"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", containsString("pic-compressed.webp")))
                    .andReturn().getResponse();
            byte[] body = response.getContentAsByteArray();
            assertTrue(body.length < original.length);
            assertEquals("RIFF", ascii(body, 0, 4));
            assertEquals("WEBP", ascii(body, 8, 4));
        } finally {
            Files.deleteIfExists(in);
            Files.deleteIfExists(out);
        }
    }

    // ---------- Audio Converter (needs FFmpeg) ----------

    @Test
    void convertsWavToEveryAudioFormat() throws Exception {
        assumeTrue(ffmpegInstalled(), "FFmpeg is not installed");
        byte[] wav = wav(2);

        byte[] mp3 = convert("audio", "tone.wav", wav, "MP3");
        assertTrue(ascii(mp3, 0, 3).equals("ID3") || (mp3[0] & 0xFF) == 0xFF, "MP3 signature");
        assertEquals("fLaC", ascii(convert("audio", "tone.wav", wav, "FLAC"), 0, 4));
        assertEquals("OggS", ascii(convert("audio", "tone.wav", wav, "OGG"), 0, 4));
        assertEquals("ftyp", ascii(convert("audio", "tone.wav", wav, "M4A"), 4, 4));
        byte[] aac = convert("audio", "tone.wav", wav, "AAC");
        assertEquals(0xFF, aac[0] & 0xFF);
        assertEquals(0x30, convert("audio", "tone.wav", wav, "WMA")[0] & 0xFF);   // ASF header
    }

    @Test
    void convertedAudioKeepsItsNameAndMimeType() throws Exception {
        assumeTrue(ffmpegInstalled(), "FFmpeg is not installed");
        mockMvc.perform(multipart("/api/audio/convert").file(upload("my song.wav", wav(1))).param("targetFormat", "MP3"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("my_song.mp3")))   // unsafe characters replaced
                .andExpect(header().string("Content-Type", "audio/mpeg"));
    }

    @Test
    void audioConversionAlsoWorksFromFlacBackToMp3() throws Exception {
        assumeTrue(ffmpegInstalled(), "FFmpeg is not installed");
        byte[] flac = convert("audio", "tone.wav", wav(1), "FLAC");
        byte[] mp3 = convert("audio", "tone.flac", flac, "MP3");
        assertTrue(mp3.length > 0);
    }

    // ---------- Video Converter (needs FFmpeg) ----------

    @Test
    void convertsMp4ToOtherVideoFormats() throws Exception {
        assumeTrue(ffmpegInstalled(), "FFmpeg is not installed");
        byte[] video = mp4();
        assumeTrue(video != null, "this FFmpeg cannot create a test video");

        byte[] webm = convert("video", "clip.mp4", video, "WEBM");
        assertEquals(0x1A, webm[0] & 0xFF);                       // Matroska/WebM header
        byte[] avi = convert("video", "clip.mp4", video, "AVI");
        assertEquals("RIFF", ascii(avi, 0, 4));
        assertEquals("AVI ", ascii(avi, 8, 4));
        assertEquals("FLV", ascii(convert("video", "clip.mp4", video, "FLV"), 0, 3));
        assertEquals("ftyp", ascii(convert("video", "clip.mp4", video, "M4V"), 4, 4));
        assertEquals(0x30, convert("video", "clip.mp4", video, "WMV")[0] & 0xFF);   // ASF header
    }

    @Test
    void extractsAudioFromVideo() throws Exception {
        assumeTrue(ffmpegInstalled(), "FFmpeg is not installed");
        byte[] video = mp4();
        assumeTrue(video != null, "this FFmpeg cannot create a test video");

        assertEquals("RIFF", ascii(convert("video", "clip.mp4", video, "WAV"), 0, 4));
        assertEquals("ftyp", ascii(convert("video", "clip.mp4", video, "M4A"), 4, 4));
        assertTrue(convert("video", "clip.mp4", video, "MP3").length > 0);
    }

    // ---------- temporary files are cleaned up ----------

    @Test
    void temporaryFoldersAreDeletedAfterEveryRequest() throws Exception {
        Set<String> before = tempFolders();

        // a successful request
        mockMvc.perform(multipart("/api/image/compress").file(upload("photo.jpg", noisyImage("jpg"))).param("quality", "low"))
                .andExpect(status().isOk());
        // a request rejected by validation
        mockMvc.perform(multipart("/api/audio/convert").file(upload("fake.mp3", "text".getBytes(StandardCharsets.UTF_8)))
                .param("targetFormat", "WAV")).andExpect(status().isUnsupportedMediaType());

        assertEquals(before, tempFolders(), "no filemaster-* folder may be left behind");
    }

    @Test
    void temporaryFoldersAreDeletedEvenWhenFfmpegFails() throws Exception {
        assumeTrue(ffmpegInstalled(), "FFmpeg is not installed");
        // looks like an MP4 at the start (passes validation) but is damaged, so FFmpeg fails
        byte[] damaged = new byte[2000];
        System.arraycopy("ftypmp42".getBytes(StandardCharsets.US_ASCII), 0, damaged, 4, 8);
        byte[] noise = new byte[1900];
        new Random(1).nextBytes(noise);
        System.arraycopy(noise, 0, damaged, 16, noise.length);

        Set<String> before = tempFolders();
        mockMvc.perform(multipart("/api/video/convert").file(upload("broken.mp4", damaged)).param("targetFormat", "WEBM"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        assertEquals(before, tempFolders(), "no filemaster-* folder may be left behind");
    }
}
