package com.filemaster.controller;

import com.filemaster.service.OfficeService;
import com.filemaster.service.PopplerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/health -> used by Render (and you) to check the app is alive.
 * "libreOffice": true means Word/PDF files keep their pictures; false means text-only conversion.
 * "poppler": true means PDF to Word gives normal flowing paragraphs (fast); false means LibreOffice is used instead.
 */
@RestController
public class HealthController {

    private final OfficeService officeService;
    private final PopplerService popplerService;

    public HealthController(OfficeService officeService, PopplerService popplerService) {
        this.officeService = officeService;
        this.popplerService = popplerService;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("libreOffice", officeService.isAvailable());
        result.put("poppler", popplerService.isAvailable());
        return result;
    }
}
