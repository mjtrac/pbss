/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.scanner.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes a persistent audit log to the application's working directory —
 * same shape as counter-core's AuditLogService, which bCounter/blCounter
 * already had; scanner-core had no equivalent until a login requirement
 * was added to bScanner/blScanner/scanner and this was needed to actually
 * record it, matching bCounter's existing pattern.
 *
 * Log file: scanner_audit.log (one entry per line, CSV)
 * Columns:  timestamp, event, username, detail
 * Events:   LOGIN, LOGIN_FAILED, LOGOUT
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private static final String LOG_FILE = "scanner_audit.log";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Write one audit log entry.
     *
     * @param event    event type (LOGIN, LOGOUT, LOGIN_FAILED)
     * @param username the user involved
     * @param detail   optional extra detail; may be null
     */
    public synchronized void log(String event, String username, String detail) {
        String ts = LocalDateTime.now().format(FMT);
        String detStr = detail != null ? detail : "";
        String line = String.format("%s,%s,%s,%s%n", ts, event, csv(username), csv(detStr));
        try {
            Files.writeString(Paths.get(LOG_FILE), line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.warn("Audit log write failed: " + ex.getMessage());
        }
    }

    /** Minimal CSV escaping: wrap in quotes if value contains comma or quote. */
    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
