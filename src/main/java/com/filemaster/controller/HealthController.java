package com.filemaster.controller;

import com.filemaster.service.OfficeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/health -> used by Render (and you) to check the app is alive.
 * "libreOffice": true means Word/PDF files keep their pictures; false means text-only conversion.
 */
@RestController
public class HealthController {

    private final OfficeService officeService;

    public HealthController(OfficeService officeService) {
        this.officeService = officeService;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("libreOffice", officeService.isAvailable());
        return result;
    }
}
