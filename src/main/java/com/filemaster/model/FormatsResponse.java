package com.filemaster.model;

import java.util.List;
import java.util.Map;

/**
 * JSON returned by GET /api/convert/formats. The frontend uses it to detect a file's
 * format from its extension and to know which targets are possible.
 *   extensions:   "docx" -> "DOCX"
 *   conversions:  "DOCX" -> ["PDF", "TXT"]
 *   mergeFormats: formats whose files can be merged into one output
 */
public record FormatsResponse(Map<String, String> extensions,
                              Map<String, List<String>> conversions,
                              List<String> mergeFormats) {
}
