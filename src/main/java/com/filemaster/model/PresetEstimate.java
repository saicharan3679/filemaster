package com.filemaster.model;

/** The real result size if the files were compressed with one preset. */
public record PresetEstimate(String preset, long size, double percentSaved) {
}
