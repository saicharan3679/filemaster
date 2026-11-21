package com.filemaster.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables @Scheduled methods (used to sweep leftover temp folders). */
@Configuration
@EnableScheduling
public class AppConfig {
}
