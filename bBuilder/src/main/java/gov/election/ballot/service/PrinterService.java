/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.ballot.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.print.*;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.JobName;
import javax.print.attribute.standard.MediaPrintableArea;
import javax.print.attribute.standard.OrientationRequested;
import java.awt.image.BufferedImage;
import java.awt.print.*;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-platform ballot printing service.
 *
 * PRINT STRATEGY — two-tier to guarantee pixel-exact output:
 *
 *   Tier 1 (preferred): Raw PDF stream via DocFlavor.INPUT_STREAM.PDF
 *     The printer receives the PDF unchanged and its built-in PostScript/PDF
 *     interpreter renders it.  No Java margin decisions, no rasterisation,
 *     no driver headers or footers.  Works on any PS/PDF-capable printer
 *     (all modern laser printers).
 *
 *   Tier 2 (fallback): PDFBox rasterisation via PrinterJob
 *     Used when the printer does not advertise PDF support.  Pages are
 *     rendered at 300 DPI and the imageable area is set to the full paper
 *     size (zero margins) to suppress driver-imposed borders.
 *     NOTE: some drivers ignore the zero-margin request.  If exact output
 *     is critical on an image-only printer, verify with a test print.
 *
 * IMPORTANT: printers listed are those visible to the JVM on the SERVER
 * machine, not the end-user's browser printer.
 */
@Service
public class PrinterService {

    private static final Logger log = LoggerFactory.getLogger(PrinterService.class);
    private static final int PRINT_DPI = 300;

    // ── Printer discovery ──────────────────────────────────────────────────

    public List<String> listPrinters() {
        PrintService[] services =
            PrintServiceLookup.lookupPrintServices(null, null);
        List<String> names = new ArrayList<>();
        for (PrintService ps : services) names.add(ps.getName());
        return names;
    }

    public String getDefaultPrinterName() {
        PrintService def = PrintServiceLookup.lookupDefaultPrintService();
        return def != null ? def.getName() : null;
    }

    // ── Printing ───────────────────────────────────────────────────────────

    /**
     * Print the PDF using the best available method for the target printer.
     * Tries raw PDF stream first; falls back to rasterised image printing.
     *
     * @param pdfBytes    raw PDF bytes to print
     * @param printerName exact name from listPrinters(); null = OS default
     * @param jobName     label shown in the printer queue
     */
    public void printPdf(byte[] pdfBytes, String printerName, String jobName)
            throws Exception {

        PrintService service = resolvePrintService(printerName);
        log.info("Printing '{}' to '{}'", jobName, service.getName());

        if (supportsPdfFlavor(service)) {
            printRawPdf(pdfBytes, service, jobName);
        } else {
            log.info("Printer '{}' does not support PDF flavor — " +
                "falling back to rasterised image printing", service.getName());
            printRasterised(pdfBytes, service, jobName);
        }
    }

    // ── Tier 1: raw PDF stream ─────────────────────────────────────────────

    /**
     * Send the PDF bytes directly to the printer without any Java rendering.
     * The printer's own PDF/PS interpreter handles page layout exactly as
     * the PDF specifies — no added margins, headers, or scaling.
     */
    private void printRawPdf(byte[] pdfBytes, PrintService service, String jobName)
            throws PrintException {

        DocFlavor flavor = DocFlavor.INPUT_STREAM.PDF;
        Doc doc = new SimpleDoc(
            new ByteArrayInputStream(pdfBytes), flavor, null);

        PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();
        attrs.add(new JobName(jobName != null ? jobName : "Ballot", null));
        attrs.add(OrientationRequested.PORTRAIT);

        DocPrintJob job = service.createPrintJob();
        job.print(doc, attrs);
        log.info("Raw PDF job '{}' submitted to '{}'", jobName, service.getName());
    }

    private boolean supportsPdfFlavor(PrintService service) {
        try {
            return service.isDocFlavorSupported(DocFlavor.INPUT_STREAM.PDF);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Tier 2: rasterised fallback ───────────────────────────────────────

    /**
     * Rasterise each PDF page at 300 DPI via PDFBox and send as a
     * multi-page PrinterJob.  The imageable area is set to the full paper
     * size to suppress driver margins as much as possible.
     */
    private void printRasterised(byte[] pdfBytes, PrintService service,
                                  String jobName) throws Exception {
        List<BufferedImage> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                pages.add(renderer.renderImageWithDPI(i, PRINT_DPI));
                log.debug("Rasterised page {}/{}", i + 1, doc.getNumberOfPages());
            }
        }
        if (pages.isEmpty())
            throw new IllegalStateException("PDF has no pages to print.");

        PrinterJob job = PrinterJob.getPrinterJob();
        job.setPrintService(service);
        job.setJobName(jobName != null ? jobName : "Ballot");

        // Derive page dimensions from the rasterised image.
        // At PRINT_DPI, pixel size in points = px * 72 / PRINT_DPI.
        BufferedImage first = pages.get(0);
        double pageWPt = first.getWidth()  * 72.0 / PRINT_DPI;
        double pageHPt = first.getHeight() * 72.0 / PRINT_DPI;

        Paper paper = new Paper();
        paper.setSize(pageWPt, pageHPt);
        // Zero imageable margins — tells the driver we want full bleed.
        // Most drivers honour this; a few enforce a minimum hardware margin.
        paper.setImageableArea(0, 0, pageWPt, pageHPt);

        PageFormat pageFormat = new PageFormat();
        pageFormat.setPaper(paper);
        pageFormat.setOrientation(PageFormat.PORTRAIT);

        job.setPageable(new Pageable() {
            @Override public int getNumberOfPages() { return pages.size(); }

            @Override
            public PageFormat getPageFormat(int i) {
                if (i < 0 || i >= pages.size())
                    throw new IndexOutOfBoundsException("pageIndex=" + i);
                return pageFormat;
            }

            @Override
            public Printable getPrintable(int i) {
                if (i < 0 || i >= pages.size())
                    throw new IndexOutOfBoundsException("pageIndex=" + i);
                BufferedImage img = pages.get(i);
                return (g, fmt, idx) -> {
                    if (idx > 0) return Printable.NO_SUCH_PAGE;
                    // Draw image filling the full imageable area.
                    // Since we set the imageable area to full paper, x/y
                    // should be 0,0 and w/h should equal the paper size.
                    int x = (int) fmt.getImageableX();
                    int y = (int) fmt.getImageableY();
                    int w = (int) fmt.getImageableWidth();
                    int h = (int) fmt.getImageableHeight();
                    g.drawImage(img, x, y, w, h, null);
                    return Printable.PAGE_EXISTS;
                };
            }
        });

        PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();
        attrs.add(new JobName(job.getJobName(), null));
        job.print(attrs);
        log.info("Rasterised job '{}' submitted to '{}'", jobName, service.getName());
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private PrintService resolvePrintService(String printerName) {
        if (printerName == null || printerName.isBlank()) {
            PrintService def = PrintServiceLookup.lookupDefaultPrintService();
            if (def == null)
                throw new IllegalArgumentException(
                    "No default printer configured on this system.");
            return def;
        }
        for (PrintService ps : PrintServiceLookup.lookupPrintServices(null, null))
            if (ps.getName().equals(printerName)) return ps;
        for (PrintService ps : PrintServiceLookup.lookupPrintServices(null, null))
            if (ps.getName().equalsIgnoreCase(printerName)) return ps;
        throw new IllegalArgumentException(
            "Printer not found: \"" + printerName + "\". " +
            "Check /print/printers for available printer names.");
    }
}
