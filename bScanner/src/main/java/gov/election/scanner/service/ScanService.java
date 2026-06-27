/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package gov.election.scanner.service;

import gov.election.scanner.config.ScannerConfig;
import gov.election.scanner.model.ScanSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ScannerConfig config;
    private final ScanSession   session = new ScanSession();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "scanner-1");
        t.setDaemon(true);
        return t;
    });

    public ScanService(ScannerConfig config) {
        this.config = config;
    }

    public ScanSession getSession() { return session; }

    /**
     * Start a scan asynchronously.
     * Returns immediately; poll getSession() for progress.
     */
    public synchronized void startScan() {
        if (session.scanning) {
            throw new IllegalStateException("Scan already in progress");
        }
        session.reset();
        session.scanning  = true;
        session.startedAt = System.currentTimeMillis();
        executor.submit(this::runScan);
    }

    public synchronized void stopScan() {
        // Mark as stop-requested; the running process will be interrupted
        session.scanning = false;
        session.error    = "Scan stopped by operator";
    }

    // ── Internal scan runner ──────────────────────────────────────────────────

    private void runScan() {
        try {
            // Ensure output directory exists
            Path outDir = Path.of(config.outputDir);
            Files.createDirectories(outDir);

            // Timestamp prefix for this batch
            String ts     = LocalDateTime.now().format(TS);
            String prefix = config.filenamePrefix + ts + "_";

            // Build and run the scanner command
            List<String> cmd = buildCommand(outDir, prefix);
            log.info("Starting scan: {}", String.join(" ", cmd));

            // Snapshot files in output dir BEFORE starting the process
            Set<Path> before = existingImages(outDir);

            // Start monitor thread BEFORE launching the process so fast-exiting
            // commands don't finish before the monitor is ready
            Thread monitor = new Thread(() -> monitorOutput(outDir, before), "scan-monitor");
            monitor.setDaemon(true);
            monitor.start();

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("HOME", System.getProperty("user.home"));
            Process proc = pb.start();

            // Stream process output to log
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[scanner] {}", line);
                }
            }

            int exitCode = proc.waitFor();
            monitor.interrupt();

            // Final count — wait briefly for filesystem to settle
            Thread.sleep(500);
            Set<Path> after = existingImages(outDir);
            after.removeAll(before);
            session.imagesScanned = after.size();

            if (exitCode != 0 && session.error == null) {
                session.error = "Scanner exited with code " + exitCode;
                log.warn("Scanner process exited with code {}", exitCode);
            } else {
                log.info("Scan complete: {} image(s) written to {}",
                    session.imagesScanned, outDir);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error = "Scan interrupted";
        } catch (Exception e) {
            session.error = e.getMessage();
            log.error("Scan failed: {}", e.getMessage(), e);
        } finally {
            session.scanning     = false;
            session.complete     = true;
            session.completedAt  = System.currentTimeMillis();
        }
    }

    private void monitorOutput(Path outDir, Set<Path> before) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Set<Path> current = existingImages(outDir);
                current.removeAll(before);
                int count = current.size();
                if (count != session.imagesScanned) {
                    session.imagesScanned = count;
                    Optional<Path> last = current.stream()
                        .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
                    last.ifPresent(p -> session.lastFile = p.getFileName().toString());
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Set<Path> existingImages(Path dir) {
        try (var s = Files.walk(dir)) {
            Set<Path> result = new HashSet<>();
            s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".png") || n.endsWith(".jpg")
                    || n.endsWith(".tif") || n.endsWith(".tiff");
            }).forEach(result::add);
            return result;
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    // ── Command builders ──────────────────────────────────────────────────────

    private List<String> buildCommand(Path outDir, String prefix) {
        return switch (config.backend.toLowerCase()) {
            case "naps2"      -> buildNaps2Command(outDir, prefix);
            case "scanimage"  -> buildScanimageCommand(outDir, prefix);
            case "command"    -> buildCustomCommand(outDir, prefix);
            default -> throw new IllegalArgumentException(
                "Unknown scanner backend: " + config.backend);
        };
    }

    private List<String> buildNaps2Command(Path outDir, String prefix) {
        // NAPS2 8.x console mode syntax:
        // naps2 console --source duplex --dpi 300 --output /path/prefix_$(nnnn).png
        // Source values: glass | feeder | duplex (duplex implies feeder + both sides)
        List<String> cmd = new ArrayList<>();
        cmd.add(config.naps2Path);
        cmd.add("console");
        // Profile or device selection
        if (config.naps2Profile != null && !config.naps2Profile.isBlank()) {
            // When using a profile, let it control source/dpi — don't override
            cmd.add("--profile");
            cmd.add(config.naps2Profile);
        } else {
            // No profile — pass source and dpi explicitly
            cmd.add("--source");
            if (config.duplex && config.source.equalsIgnoreCase("feeder")) {
                cmd.add("duplex");
            } else {
                cmd.add(config.source.equalsIgnoreCase("feeder") ? "feeder" : "glass");
            }
            cmd.add("--dpi");
            cmd.add(String.valueOf(config.dpi));
            if (config.naps2Device != null && !config.naps2Device.isBlank()) {
                cmd.add("--device");
                cmd.add(config.naps2Device);
            }
        }
        cmd.add("--verbose");
        cmd.add("--output");
        // NAPS2 uses $(nnnn) for zero-padded auto-incrementing page numbers
        cmd.add(outDir.resolve(prefix + "$(nnnn).png").toString());
        return cmd;
    }

    private List<String> buildScanimageCommand(Path outDir, String prefix) {
        // scanimage -d <device> --resolution 300 --format=png --batch=prefix_%04d.png
        List<String> cmd = new ArrayList<>();
        cmd.add(config.scanimagePath);
        cmd.add("--resolution");
        cmd.add(String.valueOf(config.dpi));
        cmd.add("--format=png");
        if (config.source.equalsIgnoreCase("feeder")) {
            cmd.add("--source=ADF");
        }
        cmd.add("--batch=" + outDir.resolve(prefix + "%04d.png"));
        return cmd;
    }

    private List<String> buildCustomCommand(Path outDir, String prefix) {
        if (config.customCommand == null || config.customCommand.isBlank()) {
            throw new IllegalStateException("Custom command not configured");
        }
        String outPath = outDir.resolve(prefix).toString();
        String expanded = config.customCommand.replace("{output}", outPath);
        return List.of("/bin/sh", "-c", expanded);
    }
}
