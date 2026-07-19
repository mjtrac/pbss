/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.scannerui;

import java.awt.*;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Prints a physical "batch sheet" — start notes, end notes, and a
 * human-readable datetime — for the current scan batch. A manual,
 * user-clicked action (shows the system print dialog), distinct from
 * scanner-core's FlagPagePrinter (automatic, off-by-default, one page per
 * individual note). This one combines both notes on a single sheet on
 * demand, regardless of the flag-page setting.
 */
final class BatchSheetPrinter {

    private BatchSheetPrinter() {}

    /** @return null on success, or a message to show the user (cancelled or failed — never throws). */
    static String print(String startNotes, String endNotes) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try {
            PrinterJob job = PrinterJob.getPrinterJob();
            job.setPrintable(new BatchSheet(timestamp, startNotes, endNotes));
            if (!job.printDialog()) return null; // user cancelled — not an error
            job.print();
            return null;
        } catch (PrinterException e) {
            return "Could not print batch sheet: " + e.getMessage();
        }
    }

    private record BatchSheet(String timestamp, String startNotes, String endNotes) implements Printable {
        @Override
        public int print(Graphics graphics, PageFormat pageFormat, int pageIndex) {
            if (pageIndex > 0) return NO_SUCH_PAGE;

            Graphics2D g = (Graphics2D) graphics;
            g.translate(pageFormat.getImageableX(), pageFormat.getImageableY());
            int width = (int) pageFormat.getImageableWidth();

            int y = 50;
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            g.drawString("SCAN BATCH SHEET", 20, y);

            y += 40;
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
            g.drawString(timestamp, 20, y);

            y += 50;
            y = drawSection(g, "Start Notes", startNotes, width, y);
            y += 20;
            drawSection(g, "End Notes", endNotes, width, y);

            return PAGE_EXISTS;
        }

        private static int drawSection(Graphics2D g, String heading, String text, int width, int y) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
            g.drawString(heading + ":", 20, y);
            y += 28;
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
            String body = (text == null || text.isBlank()) ? "(none)" : text;
            for (String line : wrap(g, body, width - 40)) {
                g.drawString(line, 20, y);
                y += 26;
            }
            return y;
        }

        private static List<String> wrap(Graphics2D g, String text, int maxWidth) {
            List<String> lines = new java.util.ArrayList<>();
            for (String paragraph : text.split("\n", -1)) {
                StringBuilder current = new StringBuilder();
                for (String word : paragraph.split("\\s+")) {
                    if (word.isEmpty()) continue;
                    String candidate = current.isEmpty() ? word : current + " " + word;
                    if (g.getFontMetrics().stringWidth(candidate) > maxWidth && !current.isEmpty()) {
                        lines.add(current.toString());
                        current = new StringBuilder(word);
                    } else {
                        current = new StringBuilder(candidate);
                    }
                }
                lines.add(current.toString());
            }
            return lines;
        }
    }
}
