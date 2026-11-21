package com.filemaster.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Adds basic browser-side protections to every response. */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Browser must trust our Content-Type and not guess.
        response.setHeader("X-Content-Type-Options", "nosniff");
        // Nobody may embed the site in an iframe (clickjacking).
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        // Only our own scripts and styles may run.
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; img-src 'self' blob: data:; object-src 'none'; frame-ancestors 'none'");
        chain.doFilter(request, response);
    }
}
