/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One row per ballot image file scanned.
 */
@Entity
@Table(name = "ballot_image",
       uniqueConstraints = @UniqueConstraint(columnNames = "image_path"))
public class BallotImage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Full absolute path to the image file at the time of counting. */
    @Column(name = "image_path", nullable = false, length = 2000)
    private String imagePath;

    /** Just the filename (basename) for display. */
    @Column(name = "image_name", nullable = false, length = 500)
    private String imageName;

    /** Datetime this image was counted (set when scanning completes). */
    @Column(name = "counted_at")
    private LocalDateTime countedAt;

    /** DPI detected or assumed for this image. */
    @Column
    private int dpi;

    /** Page number decoded from the barcode. */
    @Column(name = "page_number")
    private int pageNumber;

    /** Whether the image was upside-down and auto-rotated. */
    @Column(name = "was_rotated")
    private boolean wasRotated;

    /** Whether corner marks were successfully detected. */
    @Column(name = "corners_found")
    private boolean cornersFound;

    /** DPI of the canonical (warped) image produced during scanning. */
    @Column(name = "warp_dpi")
    private int warpDpi;

    /** Width of the canonical content area in pixels (warpDpi * contentWidthInches). */
    @Column(name = "canonical_width")
    private int canonicalWidth;

    /** Height of the canonical content area in pixels. */
    @Column(name = "canonical_height")
    private int canonicalHeight;

    /** Corner mark positions in original image pixels: TL_x,TL_y,TR_x,TR_y,BR_x,BR_y,BL_x,BL_y.
     *  Stored as comma-separated string for simplicity. */
    @Column(name = "corner_marks", length = 200)
    private String cornerMarks;

    /** Whether scribble detection flagged this ballot. */
    @Column(name = "scribble_flagged")
    private boolean scribbleFlagged;

    /** Count of dark pixels outside the normative mask. */
    @Column(name = "scribble_pixels")
    private int scribblePixels;

    /** Absolute path to the red-outlined copy of the warped image,
     *  or null if not flagged / outline drawing disabled. */
    @Column(name = "scribble_outline_path", length = 2000)
    private String scribbleOutlinePath;

    /** The barcode record for this image. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "barcode_id")
    private BarcodeRecord barcode;

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public Long            getId()          { return id; }
    public String          getImagePath()   { return imagePath; }
    public void            setImagePath(String v)   { this.imagePath = v; }
    public String          getImageName()   { return imageName; }
    public void            setImageName(String v)   { this.imageName = v; }
    public LocalDateTime   getCountedAt()   { return countedAt; }
    public void            setCountedAt(LocalDateTime v) { this.countedAt = v; }
    public int             getDpi()         { return dpi; }
    public void            setDpi(int v)    { this.dpi = v; }
    public int             getPageNumber()  { return pageNumber; }
    public void            setPageNumber(int v) { this.pageNumber = v; }
    public boolean         isWasRotated()   { return wasRotated; }
    public void            setWasRotated(boolean v) { this.wasRotated = v; }
    public boolean         isCornersFound() { return cornersFound; }
    public void            setCornersFound(boolean v) { this.cornersFound = v; }
    public int             getWarpDpi()     { return warpDpi; }
    public void            setWarpDpi(int v){ this.warpDpi = v; }
    public int             getCanonicalWidth()  { return canonicalWidth; }
    public void            setCanonicalWidth(int v)  { this.canonicalWidth = v; }
    public int             getCanonicalHeight() { return canonicalHeight; }
    public void            setCanonicalHeight(int v) { this.canonicalHeight = v; }
    public String          getCornerMarks() { return cornerMarks; }
    public void            setCornerMarks(String v) { this.cornerMarks = v; }
    public boolean         getScribbleFlagged()      { return scribbleFlagged; }
    public void            setScribbleFlagged(boolean v) { this.scribbleFlagged = v; }
    public int             getScribblePixels()       { return scribblePixels; }
    public void            setScribblePixels(int v)  { this.scribblePixels = v; }
    public String          getScribbleOutlinePath()  { return scribbleOutlinePath; }
    public void            setScribbleOutlinePath(String v) { this.scribbleOutlinePath = v; }
    public BarcodeRecord   getBarcode()     { return barcode; }
    public void            setBarcode(BarcodeRecord v) { this.barcode = v; }
}
