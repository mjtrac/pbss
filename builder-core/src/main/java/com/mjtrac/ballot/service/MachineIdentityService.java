/*
 * Copyright (C) 2026 Mitch Trachtenberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.mjtrac.ballot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort machine identity for the print audit log: the OS account the
 * app is running under, the machine's hostname, and its hardware serial
 * number if one can be determined -- gathered once at startup (none of
 * these change during a run) so repeated print requests don't repeatedly
 * shell out to platform tools.
 *
 * Every lookup here degrades to null on failure rather than throwing --
 * a ballot print must never be blocked because a serial-number lookup
 * failed. In particular, hardware serial number is frequently unavailable
 * on Linux for a non-root desktop user ({@code /sys/class/dmi/id/
 * product_serial} and {@code dmidecode} both typically require root);
 * that's expected, not a bug -- callers should treat a null serial as
 * "unavailable on this machine/account", not an error.
 */
@Service
public class MachineIdentityService {

    private static final Logger log = LoggerFactory.getLogger(MachineIdentityService.class);

    private final String osUsername;
    private final String hostname;
    private final String machineSerial;

    public MachineIdentityService() {
        this.osUsername = System.getProperty("user.name");
        this.hostname = resolveHostname();
        this.machineSerial = resolveMachineSerial();
        log.info("Machine identity: osUsername={} hostname={} machineSerial={}",
            osUsername, hostname, machineSerial != null ? machineSerial : "(unavailable)");
    }

    /** OS account the JVM process is running under -- same API on all three platforms. */
    public String osUsername() { return osUsername; }

    /** Best-effort machine hostname; null if it truly couldn't be determined. */
    public String hostname() { return hostname; }

    /** Best-effort hardware serial number; null if the platform/permissions don't allow reading it. */
    public String machineSerial() { return machineSerial; }

    // ── Hostname ─────────────────────────────────────────────────────────

    private static String resolveHostname() {
        try {
            String h = InetAddress.getLocalHost().getHostName();
            if (h != null && !h.isBlank()) return h;
        } catch (Exception ignored) {
            // Common on an air-gapped machine with no local hostname resolution configured --
            // this app is explicitly designed to run fully offline (see README).
        }
        String env = System.getenv("COMPUTERNAME"); // Windows
        if (env != null && !env.isBlank()) return env;
        env = System.getenv("HOSTNAME"); // some Unix shells export this; not guaranteed
        if (env != null && !env.isBlank()) return env;
        return runAndCapture(3, "hostname");
    }

    // ── Hardware serial number ──────────────────────────────────────────

    private static String resolveMachineSerial() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac")) {
                return macSerial();
            } else if (os.contains("win")) {
                return windowsSerial();
            } else {
                return linuxSerial();
            }
        } catch (Exception e) {
            log.debug("Machine serial lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private static String macSerial() {
        String out = runAndCapture(3, "ioreg", "-rd1", "-c", "IOPlatformExpertDevice");
        if (out == null) return null;
        Matcher m = Pattern.compile("\"IOPlatformSerialNumber\"\\s*=\\s*\"([^\"]+)\"").matcher(out);
        return m.find() ? m.group(1) : null;
    }

    private static String windowsSerial() {
        // PowerShell, not wmic -- wmic is deprecated (removed on some newer
        // Windows builds) while PowerShell has shipped by default since
        // Vista/Server 2008.
        return runAndCapture(5, "powershell", "-NoProfile", "-NonInteractive", "-Command",
            "(Get-CimInstance -ClassName Win32_BIOS).SerialNumber");
    }

    private static String linuxSerial() {
        // Both of these commonly fail for a normal (non-root) desktop user
        // -- expected, see class Javadoc.
        try {
            String v = Files.readString(Path.of("/sys/class/dmi/id/product_serial")).trim();
            if (!v.isBlank()) return v;
        } catch (Exception ignored) {
        }
        return runAndCapture(3, "dmidecode", "-s", "system-serial-number");
    }

    /** Runs a command with a timeout; returns trimmed stdout, or null on any failure/timeout/non-zero exit. */
    private static String runAndCapture(int timeoutSeconds, String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            try (InputStream is = p.getInputStream()) {
                String out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
                return out.isBlank() ? null : out;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
