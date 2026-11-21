package com.filemaster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. @SpringBootApplication turns on auto-configuration and scans
 * the com.filemaster package for controllers, services and config classes.
 */
@SpringBootApplication
public class FileMasterApplication {

    public static void main(String[] args) {
        // Servers have no screen; image code must run in "headless" mode.
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(FileMasterApplication.class, args);
    }
}
