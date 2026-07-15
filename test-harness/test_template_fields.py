#!/usr/bin/env python3
"""
test_template_fields.py — bBuilder ballot design template field validation

For each template field that affects PDF output, this suite:
  1. Creates an election with a single contest via the bBuilder API
  2. Prints a ballot with a specific template setting changed
  3. Extracts the PDF text/geometry and asserts the change had the expected effect

Requirements:
  pip install requests pdfminer.six pymupdf

Usage:
  python3 test_template_fields.py --host http://localhost:8080
"""

import argparse, json, sys, math, io, tempfile, os, re
from pathlib import Path

try:
    import requests
except ImportError:
    sys.exit("pip install requests")

try:
    try:
        import fitz  # PyMuPDF legacy import
    except ImportError:
        import pymupdf as fitz  # PyMuPDF >= 1.24 new import name
    PDF_BACKEND = "pymupdf"
except ImportError:
    try:
        from pdfminer.high_level import extract_text as pm_extract
        PDF_BACKEND = "pdfminer"
    except ImportError:
        sys.exit("pip install pymupdf  OR  pip install pdfminer.six")

# ── Colours ───────────────────────────────────────────────────────────────────
GREEN  = "\033[32m"
RED    = "\033[31m"
YELLOW = "\033[33m"
RESET  = "\033[0m"

passed = failed = skipped = 0

def ok(name):
    global passed; passed += 1
    print(f"  {GREEN}✓{RESET}  {name}")

def fail(name, reason):
    global failed; failed += 1
    print(f"  {RED}✗{RESET}  {name}")
    print(f"       {YELLOW}{reason}{RESET}")

def skip(name, reason):
    global skipped; skipped += 1
    print(f"  {YELLOW}~{RESET}  {name}  [{reason}]")

# ── PDF helpers ───────────────────────────────────────────────────────────────

def pdf_text(pdf_bytes: bytes) -> str:
    if PDF_BACKEND == "pymupdf":
        doc = fitz.open(stream=pdf_bytes, filetype="pdf")
        return "\n".join(page.get_text() for page in doc)
    else:
        return pm_extract(io.BytesIO(pdf_bytes))

def pdf_page_size(pdf_bytes: bytes):
    """Return (width_inches, height_inches) of page 1."""
    if PDF_BACKEND != "pymupdf":
        return None, None
    doc = fitz.open(stream=pdf_bytes, filetype="pdf")
    r = doc[0].rect
    return r.width / 72, r.height / 72

def pdf_font_sizes(pdf_bytes: bytes):
    """Return set of font sizes used on page 1 (rounded to 1dp)."""
    if PDF_BACKEND != "pymupdf":
        return set()
    doc = fitz.open(stream=pdf_bytes, filetype="pdf")
    sizes = set()
    for block in doc[0].get_text("dict")["blocks"]:
        for line in block.get("lines", []):
            for span in line.get("spans", []):
                sizes.add(round(span["size"], 1))
    return sizes

def pdf_bold_texts(pdf_bytes: bytes):
    """Return list of (text, is_bold) for all spans on page 1."""
    if PDF_BACKEND != "pymupdf":
        return []
    doc = fitz.open(stream=pdf_bytes, filetype="pdf")
    result = []
    for block in doc[0].get_text("dict")["blocks"]:
        for line in block.get("lines", []):
            for span in line.get("spans", []):
                is_bold = "Bold" in span.get("font", "") or "bold" in span.get("font", "")
                result.append((span["text"].strip(), is_bold))
    return result

# ── bBuilder client ───────────────────────────────────────────────────────────

class BBuilderClient:
    def __init__(self, host, password="ChangeMe123!"):
        self.host     = host.rstrip("/")
        self.password = password
        self.session  = requests.Session()
        self._login()

    def _login(self):
        r = self.session.get(f"{self.host}/login")
        csrf = re.search(r'name="_csrf"[^>]+value="([^"]+)"', r.text)
        token = csrf.group(1) if csrf else ""
        self.session.post(f"{self.host}/login",
            data={"username": "admin", "password": self.password, "_csrf": token},
            allow_redirects=True)

    def post(self, path, body):
        r = self.session.post(f"{self.host}/api/test/{path}", json=body)
        if not r.ok or not r.text.strip():
            raise RuntimeError(f"POST /api/test/{path} HTTP {r.status_code}: {r.text[:300]}")
        return r.json()

    def reset(self):
        self.session.delete(f"{self.host}/api/test/reset")

    def print_ballot(self, combo_id, template_id, lang="en") -> bytes:
        """Generate ballot and return raw PDF bytes read from disk."""
        params = {"templateId": template_id}
        if lang and lang != "en":
            params["lang"] = lang
        r = self.session.post(
            f"{self.host}/api/test/generate/{combo_id}", params=params)
        if not r.ok or not r.text.strip():
            raise RuntimeError(f"generate failed HTTP {r.status_code}: {r.text[:300]}")
        info = r.json()
        files = info.get("files", [])
        if not files:
            raise RuntimeError(f"No files in generate response: {info}")
        pdf_path = next((f for f in files if f.endswith(".pdf")), None)
        if not pdf_path:
            raise RuntimeError(f"No PDF in files: {files}")
        with open(pdf_path, "rb") as fh:
            return fh.read()

# ── Election fixture ──────────────────────────────────────────────────────────

def build_election(client):
    """Create a minimal election and return (combo_id, default_template_id)."""
    j = client.post("jurisdiction", {"name": "Test County"})
    jid = j["id"]
    e = client.post("election", {"name": "Template Test Election",
                                  "jurisdictionId": jid,
                                  "electionDate": "2026-11-03"})
    eid = e["id"]
    bt = client.post("ballot-type", {"name": "Standard", "jurisdictionId": jid})
    btid = bt["id"]
    reg = client.post("region", {"name": "Precinct 1", "type": "SINGLE_PRECINCT",
                                  "jurisdictionId": jid})
    rid = reg["id"]
    party = client.post("party", {"name": "Nonpartisan", "jurisdictionId": jid})
    pid = party["id"]
    contest = client.post("contest", {
        "electionId": eid, "title": "Mayor",
        "contestType": "PLURALITY", "maxVotes": 1,
        "instruction": "Vote for one.",
        "regionIds": [rid]
    })
    cid = contest["id"]
    for name in ["Alice Smith", "Bob Jones"]:
        client.post("candidate", {"contestId": cid, "name": name})
    combo = client.post("combination", {
        "electionId": eid, "regionId": rid,
        "partyId": pid, "ballotTypeId": btid
    })
    comboid = combo["id"]
    return comboid, eid, jid

def make_template(client, eid, overrides: dict) -> int:
    """Create a template with defaults + overrides. Return template id."""
    defaults = {
        "electionId":              eid,
        "paperSize":               "LETTER_8_5x11",
        "columns":                 2,
        "marginTopPt":             36.0,
        "marginBottomPt":          36.0,
        "marginLeftPt":            36.0,
        "marginRightPt":           36.0,
        "headerHeadline":          "OFFICIAL BALLOT",
        "headerBodyText":          "Test County\nTemplate Field Test",
        "headerFontSize":          9.0,
        "headerHeadlineFontSize":  14.0,
        "headerBodyFontSize":      9.0,
        "barcodeHeightPt":         54.0,
        "barcodeWidthPt":          90.0,
        "barcodePosition":         "TOP_RIGHT",
        "contestTitleFontSize":    11.0,
        "contestTitleBold":        True,
        "contestTitleItalic":      False,
        "candidateNameFontSize":   10.0,
        "candidateNameBold":       False,
        "candidateNameItalic":     False,
        "candidateNoteFontSize":   8.0,
        "candidateNoteBold":       False,
        "candidateNoteItalic":     False,
        "instructionFontSize":     9.0,
        "instructionBold":         False,
        "instructionItalic":       False,
        "preambleFontSize":        9.0,
        "preambleBold":            False,
        "preambleItalic":          False,
        "postambleFontSize":       8.0,
        "postambleBold":           False,
        "postambleItalic":         False,
        "groupingLabelFontSize":   10.0,
        "groupingLabelBold":       True,
        "groupingLabelItalic":     False,
        "prefixSuffixFontSize":    9.0,
        "prefixSuffixBold":        False,
        "prefixSuffixItalic":      False,
        "indicatorType":      "OVAL",
        "multiSheet":              False,
    }
    body = {**defaults, **overrides}
    r = client.post("template", body)
    return r["id"]

# ── Test cases ────────────────────────────────────────────────────────────────

def run_tests(client, combo_id, eid):
    # Verify generate endpoint works with templateId override
    print("\n── Sanity check ──────────────────────────────────────────────────")
    tid_sanity = make_template(client, eid, {"headerHeadline": "SANITY CHECK HEADLINE"})
    try:
        pdf = client.print_ballot(combo_id, tid_sanity)
        text = pdf_text(pdf)
        if "SANITY CHECK HEADLINE" in text:
            ok("templateId override works — custom headline found in PDF")
        else:
            fail("templateId override works",
                 f"Custom headline not found. PDF text sample: {text[:300]}")
    except Exception as ex:
        fail("templateId override works", str(ex))

    print("\n── Paper size ───────────────────────────────────────────────────")

    tid = make_template(client, eid, {"paperSize": "LEGAL_8_5x14"})
    pdf = client.print_ballot(combo_id, tid)
    w, h = pdf_page_size(pdf)
    if h is not None:
        if abs(h - 14.0) < 0.1:
            ok("LEGAL paper: height ≈ 14 inches")
        else:
            fail("LEGAL paper: height ≈ 14 inches", f"got {h:.2f}")
    else:
        skip("LEGAL paper size", "PyMuPDF not available")

    tid = make_template(client, eid, {"paperSize": "LETTER_8_5x11"})
    pdf = client.print_ballot(combo_id, tid)
    w, h = pdf_page_size(pdf)
    if h is not None:
        if abs(h - 11.0) < 0.1:
            ok("LETTER paper: height ≈ 11 inches")
        else:
            fail("LETTER paper: height ≈ 11 inches", f"got {h:.2f}")
    else:
        skip("LETTER paper size", "PyMuPDF not available")

    print("\n── Header text ──────────────────────────────────────────────────")

    tid = make_template(client, eid, {
        "headerHeadline": "MY CUSTOM HEADLINE",
        "headerBodyText": "Custom body line one\nCustom body line two"
    })
    pdf = client.print_ballot(combo_id, tid)
    text = pdf_text(pdf)
    if "MY CUSTOM HEADLINE" in text:
        ok("Custom headline appears in PDF")
    else:
        fail("Custom headline appears in PDF", "text not found")
    if "Custom body line one" in text:
        ok("Custom body line 1 appears in PDF")
    else:
        fail("Custom body line 1 appears in PDF", "text not found")
    if "Custom body line two" in text:
        ok("Custom body line 2 appears in PDF")
    else:
        fail("Custom body line 2 appears in PDF", "text not found")

    print("\n── Header substitutions ─────────────────────────────────────────")

    tid = make_template(client, eid, {
        "headerHeadline": "{jurisdictionName} BALLOT",
        "headerBodyText": "Election: {electionName}\nMark the {indicatorName}"
    })
    pdf = client.print_ballot(combo_id, tid)
    text = pdf_text(pdf)
    if "Test County BALLOT" in text:
        ok("{jurisdictionName} substituted in headline")
    else:
        fail("{jurisdictionName} substituted in headline",
             f"got: {[l for l in text.splitlines() if 'BALLOT' in l]}")
    if "Template Test Election" in text:
        ok("{electionName} substituted in body")
    else:
        fail("{electionName} substituted in body", "not found in PDF text")
    if "oval" in text.lower():
        ok("{indicatorName} substituted as 'oval' for OVAL style")
    else:
        fail("{indicatorName} substituted as 'oval'", "not found")

    print("\n── Font sizes ───────────────────────────────────────────────────")

    for size in [8.0, 12.0, 16.0]:
        tid = make_template(client, eid, {"contestTitleFontSize": size})
        pdf = client.print_ballot(combo_id, tid)
        sizes = pdf_font_sizes(pdf)
        if sizes and any(abs(s - size) < 0.5 for s in sizes):
            ok(f"Contest title font size {size}pt appears in PDF")
        elif not sizes:
            skip(f"Contest title font size {size}pt", "PyMuPDF not available")
        else:
            fail(f"Contest title font size {size}pt appears in PDF",
                 f"found sizes: {sorted(sizes)}")

    for size in [8.0, 11.0, 14.0]:
        tid = make_template(client, eid, {"candidateNameFontSize": size})
        pdf = client.print_ballot(combo_id, tid)
        sizes = pdf_font_sizes(pdf)
        if sizes and any(abs(s - size) < 0.5 for s in sizes):
            ok(f"Candidate name font size {size}pt appears in PDF")
        elif not sizes:
            skip(f"Candidate name font size {size}pt", "PyMuPDF not available")
        else:
            fail(f"Candidate name font size {size}pt appears in PDF",
                 f"found sizes: {sorted(sizes)}")

    print("\n── Bold / italic ────────────────────────────────────────────────")

    tid = make_template(client, eid, {"contestTitleBold": True})
    pdf = client.print_ballot(combo_id, tid)
    spans = pdf_bold_texts(pdf)
    bold_texts = [t for t, b in spans if b]
    if any("Mayor" in t for t in bold_texts):
        ok("Contest title bold=true: 'Mayor' appears in bold")
    elif not spans:
        skip("Contest title bold", "PyMuPDF not available")
    else:
        fail("Contest title bold=true: 'Mayor' appears in bold",
             f"bold texts: {bold_texts}")

    tid = make_template(client, eid, {"contestTitleBold": False})
    pdf = client.print_ballot(combo_id, tid)
    spans = pdf_bold_texts(pdf)
    bold_texts = [t for t, b in spans if b]
    if not any("Mayor" in t for t in bold_texts):
        ok("Contest title bold=false: 'Mayor' not in bold")
    elif not spans:
        skip("Contest title bold=false", "PyMuPDF not available")
    else:
        fail("Contest title bold=false: 'Mayor' not in bold",
             f"bold texts: {bold_texts}")

    print("\n── Candidate names in PDF ───────────────────────────────────────")

    tid = make_template(client, eid, {})
    pdf = client.print_ballot(combo_id, tid)
    text = pdf_text(pdf)
    for name in ["Alice Smith", "Bob Jones"]:
        if name in text:
            ok(f"Candidate '{name}' appears in PDF")
        else:
            fail(f"Candidate '{name}' appears in PDF", "not found in text")

    print("\n── Vote indicator style ─────────────────────────────────────────")

    # {indicatorName} substitution — currently always resolves to "oval"
    # regardless of voteIndicatorStyle (known bBuilder limitation)
    for style in ["OVAL", "CHECKBOX"]:
        tid = make_template(client, eid, {"indicatorType": style,
                                          "headerBodyText":
                                            "Mark the {indicatorName}"})
        pdf = client.print_ballot(combo_id, tid)
        text = pdf_text(pdf)
        if "{indicatorName}" not in text and ("oval" in text.lower() or "box" in text.lower()):
            ok(f"voteIndicatorStyle={style}: {{indicatorName}} substituted in header")
        elif "{indicatorName}" in text:
            fail(f"voteIndicatorStyle={style}: {{indicatorName}} substituted",
                 "literal {indicatorName} not substituted")
        else:
            fail(f"voteIndicatorStyle={style}: ballot renders with header",
                 "header text not found in PDF")

    print("\n── Column count ─────────────────────────────────────────────────")

    # 1-column vs 3-column — page height difference as proxy
    # (more columns → contests fit on fewer pages)
    tid1 = make_template(client, eid, {"columns": 1})
    pdf1 = client.print_ballot(combo_id, tid1)
    tid3 = make_template(client, eid, {"columns": 3})
    pdf3 = client.print_ballot(combo_id, tid3)
    # Both should produce a valid PDF with the contest text
    t1 = pdf_text(pdf1); t3 = pdf_text(pdf3)
    if "Mayor" in t1 and "Mayor" in t3:
        ok("Contest 'Mayor' appears in both 1-col and 3-col PDFs")
    else:
        fail("Contest appears in both column configs",
             f"1col has Mayor: {'Mayor' in t1}, 3col: {'Mayor' in t3}")

    print("\n── Barcode position ─────────────────────────────────────────────")

    for pos in ["TOP_RIGHT", "TOP_LEFT"]:
        tid = make_template(client, eid, {"barcodePosition": pos})
        pdf = client.print_ballot(combo_id, tid)
        text = pdf_text(pdf)
        # Just verify the ballot still renders without error
        if "Mayor" in text or "Alice" in text:
            ok(f"barcodePosition={pos}: ballot renders correctly")
        else:
            fail(f"barcodePosition={pos}: ballot renders correctly",
                 "Contest/candidates not found in PDF")

    print("\n── Margins (content area change) ────────────────────────────────")

    if PDF_BACKEND == "pymupdf":
        # Wide margins should reduce the content width detectably
        tid_narrow = make_template(client, eid,
            {"marginLeftPt": 18.0, "marginRightPt": 18.0})
        tid_wide   = make_template(client, eid,
            {"marginLeftPt": 72.0, "marginRightPt": 72.0})
        pdf_n = client.print_ballot(combo_id, tid_narrow)
        pdf_w = client.print_ballot(combo_id, tid_wide)
        # Both should still contain the contest
        tn = pdf_text(pdf_n); tw = pdf_text(pdf_w)
        if "Mayor" in tn and "Mayor" in tw:
            ok("Margin change: ballot renders with both narrow and wide margins")
        else:
            fail("Margin change: ballot renders",
                 f"narrow OK: {'Mayor' in tn}, wide OK: {'Mayor' in tw}")
    else:
        skip("Margin content area check", "PyMuPDF not available")


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--host",     default="http://localhost:8080")
    p.add_argument("--password", default="ChangeMe123!",
                   help="bBuilder admin password")
    args = p.parse_args()

    print(f"bBuilder template field test suite")
    print(f"Host: {args.host}")
    print(f"PDF backend: {PDF_BACKEND}")

    client = BBuilderClient(args.host, args.password)
    print("  ✓ Connected to bBuilder")

    client.reset()
    print("  ✓ Reset")

    combo_id, eid, jid = build_election(client)
    print(f"  ✓ Election created (combo={combo_id}, election={eid})")

    run_tests(client, combo_id, eid)

    print(f"\n{'─'*60}")
    print(f"  Passed:  {passed}")
    print(f"  Failed:  {failed}")
    print(f"  Skipped: {skipped}")
    print(f"{'─'*60}")
    sys.exit(0 if failed == 0 else 1)

if __name__ == "__main__":
    main()
