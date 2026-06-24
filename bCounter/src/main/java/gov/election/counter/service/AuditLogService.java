/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 */
package gov.election.counter.service;

import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a persistent audit log to the application's working directory.
 *
 * Log file: counter_audit.log  (one entry per line, CSV)
 *
 * Columns:
 *   timestamp, event, username, detail
 *
 * Events logged:
 *   LOGIN          — successful login
 *   LOGIN_FAILED   — failed login attempt
 *   LOGOUT         — user logged out
 *   SCAN           — ballot image scanned (detail = image filename)
 */
@Service
public class AuditLogService {

    private static final Logger log =
        LoggerFactory.getLogger(AuditLogService.class);

    private static final String LOG_FILE = "counter_audit.log";
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Write one audit log entry.
     *
     * @param event    event type (LOGIN, LOGOUT, LOGIN_FAILED, SCAN)
     * @param username the user involved
     * @param detail   optional extra detail (e.g. filename); may be null
     */
    public synchronized void log(String event, String username, String detail) {
        String ts     = LocalDateTime.now().format(FMT);
        String detStr = detail != null ? detail : "";
        String line   = String.format("%s,%s,%s,%s%n", ts, event, csv(username), csv(detStr));
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
