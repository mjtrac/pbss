/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.scanner.service;

import com.mjtrac.scanner.config.ScannerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.util.List;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;

/**
 * Optionally prints a start/end note as a physical "flag page" on an
 * attached printer — meant to be pulled out and inserted into the paper
 * ballot stack at the point a misfeed/doublefeed was flagged for manual
 * correction, not scanned by the ballot scanner itself.
 *
 * Off by default (ScannerConfig.printFlagPages). Every failure here is
 * caught and logged, never propagated — a missing/broken office printer
 * must never stop a scan batch.
 */
final class FlagPagePrinter {

    private static final Logger log = LoggerFactory.getLogger(FlagPagePrinter.class);

    private FlagPagePrinter() {}

    static void printIfEnabled(ScannerConfig config, String label, String timestamp, String note) {
        if (!config.printFlagPages) return;
        try {
            print(config, label, timestamp, note);
        } catch (Exception e) {
            // Intentionally broad: any printing failure (no printer attached,
            // driver error, permission issue, etc.) must not stop scanning.
            log.warn("Could not print flag page ({}): {}", label, e.getMessage());
        }
    }

    private static void print(ScannerConfig config, String label, String timestamp, String note)
            throws PrinterException {
        PrinterJob job = PrinterJob.getPrinterJob();

        if (config.printerName != null && !config.printerName.isBlank()) {
            PrintService svc = findPrintService(config.printerName);
            if (svc == null) {
                log.warn("Configured printer '{}' not found; falling back to system default", config.printerName);
            } else {
                job.setPrintService(svc);
            }
        }

        job.setPrintable(new FlagPage(label, timestamp, note));
        job.print();
        log.info("Flag page printed: {} @ {}", label, timestamp);
    }

    private static PrintService findPrintService(String name) {
        for (PrintService svc : PrintServiceLookup.lookupPrintServices(null, null)) {
            if (svc.getName().equalsIgnoreCase(name)) return svc;
        }
        return null;
    }

    /** One large, unmistakable page: label, timestamp, then the note text, word-wrapped. */
    private record FlagPage(String label, String timestamp, String note) implements Printable {
        @Override
        public int print(Graphics graphics, PageFormat pageFormat, int pageIndex) {
            if (pageIndex > 0) return NO_SUCH_PAGE;

            Graphics2D g = (Graphics2D) graphics;
            g.translate(pageFormat.getImageableX(), pageFormat.getImageableY());
            int width = (int) pageFormat.getImageableWidth();

            int y = 60;
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36));
            g.drawString("*** " + label.toUpperCase() + " ***", 20, y);

            y += 50;
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
            g.drawString(timestamp, 20, y);

            y += 50;
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
            for (String line : wrap(g, note, width - 40)) {
                g.drawString(line, 20, y);
                y += 28;
            }

            return PAGE_EXISTS;
        }

        private static List<String> wrap(Graphics2D g, String text, int maxWidth) {
            List<String> lines = new java.util.ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : text.split("\\s+")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (g.getFontMetrics().stringWidth(candidate) > maxWidth && !current.isEmpty()) {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(candidate);
                }
            }
            if (!current.isEmpty()) lines.add(current.toString());
            return lines;
        }
    }
}
