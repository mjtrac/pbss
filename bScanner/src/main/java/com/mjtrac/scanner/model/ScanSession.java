/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3.
 */
package com.mjtrac.scanner.model;

/**
 * In-memory state for the current scan session.
 * One instance per application (singleton via ScanService).
 */
public class ScanSession {
    public volatile boolean scanning      = false;
    public volatile boolean complete      = false;
    public volatile int     imagesScanned = 0;
    public volatile String  error         = null;
    public volatile String  lastFile      = null;
    public volatile long    startedAt     = 0;
    public volatile long    completedAt   = 0;
    /** Operator comment for the current batch; cleared after scan completes. */
    public volatile String  comment       = "";

    public void reset() {
        scanning      = false;
        complete      = false;
        imagesScanned = 0;
        error         = null;
        lastFile      = null;
        startedAt     = 0;
        completedAt   = 0;
        comment       = "";
    }
}
