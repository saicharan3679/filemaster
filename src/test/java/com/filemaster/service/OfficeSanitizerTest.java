package com.filemaster.service;

import com.filemaster.exception.FileMasterException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Word file must not be able to make the server contact a web address or read a local file. */
class OfficeSanitizerTest {

    private static final String RELS_START =
            "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">";

    @Test
    void removesExternalPicturesAndObjectsButKeepsLinksAndInternalPictures() {
        String xml = RELS_START
                + "<Relationship Id=\"a\" Type=\"http://x/hyperlink\" Target=\"https://example.com\" TargetMode=\"External\"/>"
                + "<Relationship Id=\"b\" Type=\"http://x/image\" Target=\"http://evil.test/x.png\" TargetMode=\"External\"/>"
                + "<Relationship Id=\"c\" Type=\"http://x/image\" Target=\"file:///etc/passwd\"/>"
                + "<Relationship Id=\"d\" Type=\"http://x/oleObject\" Target=\"HTTP://evil.test/o\" TargetMode=\"external\"></Relationship>"
                + "<Relationship Id=\"e\" Type=\"http://x/image\" Target=\"media/image1.png\"/>"
                + "</Relationships>";

        String cleaned = new String(OfficeSanitizer.withoutExternalLinks(xml.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);

        assertTrue(cleaned.contains("example.com"), "a normal clickable hyperlink stays");
        assertTrue(cleaned.contains("media/image1.png"), "a picture stored inside the file stays");
        assertFalse(cleaned.contains("evil.test"), "web pictures and objects are removed");
        assertFalse(cleaned.contains("passwd"), "local file references are removed");
    }

    @Test
    void refusesXmlWithEntityDeclarations() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<Relationships>&x;</Relationships>";
        assertThrows(FileMasterException.class,
                () -> OfficeSanitizer.withoutExternalLinks(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
