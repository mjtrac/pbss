/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.scannerui;

import java.io.InputStream;
import java.util.Properties;

/** Plain entry point handling --version before Spring/Swing start. */
public class Launcher {
    public static void main(String[] args) {
        if (args.length > 0 && ("--version".equals(args[0]) || "-version".equals(args[0]))) {
            System.out.println(readName() + " " + readVersion());
            return;
        }
        ScannerApp.main(args);
    }

    private static String readName() {
        return readProperty("name", "scanner");
    }

    private static String readVersion() {
        return readProperty("version", "unknown");
    }

    private static String readProperty(String key, String fallback) {
        try (InputStream in = Launcher.class.getResourceAsStream("/version.properties")) {
            if (in == null) return fallback;
            Properties props = new Properties();
            props.load(in);
            return props.getProperty(key, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }
}
