package com.filemaster.model;

import java.util.List;

/** JSON returned by POST /api/compress/estimate. */
public record EstimateResponse(long originalSize, List<PresetEstimate> presets) {
}
