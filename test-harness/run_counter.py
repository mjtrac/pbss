#!/usr/bin/env python3
"""
run_counter.py — Drive bCounter via HTTP to scan the test image tree.

Logs in, sets the image folder and YAML folder, starts scanning,
polls progress, and waits for completion.

Usage: python run_counter.py [--host http://localhost:8081]
                              [--images ./images]
                              [--yaml-dir <path to bBuilder export dir>]
"""
import argparse, json, time, sys, re
from pathlib import Path
import requests

DEFAULT_HOST   = "http://localhost:8081"
CREDENTIALS    = ("admin", "ChangeMe123!")
POLL_INTERVAL  = 2   # seconds between progress polls

def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--host",     default=DEFAULT_HOST)
    p.add_argument("--images",   default=str(Path.home() / "bSuite_data/cast_ballot_scans"),
                   help="Path to test image folder tree")
    p.add_argument("--yaml-dir", default=None,
                   help="Path to YAML layout files (defaults to bBuilder export dir "
                        "from election_data.json)")
    p.add_argument("--election-data", default="election_data.json")
    p.add_argument("--threshold",     default="128")
    return p.parse_args()

class BCounterClient:
    def __init__(self, host):
        self.host    = host.rstrip("/")
        self.session = requests.Session()
        self._login()

    def _login(self):
        r = self.session.get(f"{self.host}/login")
        r.raise_for_status()
        m = re.search(r'name="_csrf"[^>]+value="([^"]+)"', r.text)
        csrf = m.group(1) if m else ""
        r2 = self.session.post(f"{self.host}/login", data={
            "username": CREDENTIALS[0],
            "password": CREDENTIALS[1],
            "_csrf":    csrf,
        }, allow_redirects=True)
        # Verify login succeeded by checking for logout link or dashboard content
        if "logout" not in r2.text.lower() and "scan" not in r2.text.lower():
            raise RuntimeError(
                f"Login may have failed — response URL: {r2.url}\n"
                f"Body snippet: {r2.text[:200]!r}"
            )
        print("  ✓ Logged in to bCounter")

    def _csrf(self):
        # Fetch token from authenticated pages first, fall back to /login
        for path in ["/", "/scanning", "/login"]:
            try:
                r = self.session.get(f"{self.host}{path}",
                                     allow_redirects=True)
                # If we ended up at /login, the session is not authenticated
                if "/login" in r.url and path != "/login":
                    continue
                m = re.search(r'name="_csrf"[^>]+value="([^"]+)"', r.text)
                if not m:
                    m = re.search(r'"_csrf"\s*:\s*"([^"]+)"', r.text)
                if m:
                    return m.group(1)
            except Exception:
                pass
        return ""

    def _extract_flash_error(self, html: str) -> str:
        """Extract Spring flash error message from the redirected page HTML."""
        m = re.search(r'class="[^"]*alert[^"]*error[^"]*"[^>]*>(.*?)</[^>]+>',
                      html, re.DOTALL | re.IGNORECASE)
        if m:
            return re.sub(r'<[^>]+>', '', m.group(1)).strip()
        # Also look for any visible error text
        m2 = re.search(r'(Image folder|Report folder|Failed to load|No image files)[^<"]{0,200}',
                       html)
        if m2:
            return m2.group(0).strip()
        return ""

    def start(self, image_folder: str, yaml_folder: str, threshold: str):
        # Verify session is still authenticated; re-login if needed
        probe = self.session.get(f"{self.host}/", allow_redirects=True)
        if "/login" in probe.url:
            print("  ⚠  Session expired — re-logging in")
            self._login()

        csrf = self._csrf()
        if not csrf:
            raise RuntimeError("Could not obtain CSRF token from bCounter")

        data = {
            "_csrf":        csrf,
            "imageFolder":  image_folder,
            "reportFolder": yaml_folder,
            "threshold":    str(int(float(threshold))),
            "darkPct":      "7.0",  # coverage threshold: >7% of indicator box must be dark
            "dpi":          "300",
        }
        print(f"  Sending POST /start")
        print(f"    imageFolder:  {image_folder}")
        print(f"    reportFolder: {yaml_folder}")

        # POST without auto-redirect so we can inspect the redirect destination
        r = self.session.post(f"{self.host}/start", data=data,
                              allow_redirects=False)

        print(f"  /start response: HTTP {r.status_code}  Location: {r.headers.get('Location', '(none)')}")

        if r.status_code in (301, 302, 303):
            location = r.headers.get("Location", "")
            # Follow the redirect manually so we can inspect the landing page
            r2 = self.session.get(f"{self.host}{location}" if location.startswith("/")
                                  else location, allow_redirects=True)

            if "/scanning" in r2.url or "/scanning" in location:
                print("  ✓ Scan started — redirected to scanning page")
                return r2

            # Redirected back to index or error page — extract error
            error = self._extract_flash_error(r2.text)
            if error:
                raise RuntimeError(f"/start failed: {error}")
            else:
                raise RuntimeError(
                    f"/start redirected to {r2.url} (not /scanning) — "
                    f"check folders exist and YAML files are present.\n"
                    f"Page snippet: {r2.text[:400]!r}"
                )

        if r.status_code == 400:
            raise RuntimeError(f"/start returned 400: {r.text[:300]!r}")

        # Unexpected status
        raise RuntimeError(f"/start returned unexpected status {r.status_code}")

    def progress(self) -> dict:
        r = self.session.get(f"{self.host}/progress")
        if not r.text.strip():
            # Empty response — session was reset; return safe default
            return {"started": False, "scanning": False, "processed": 0,
                    "total": 0, "complete": False, "error": ""}
        try:
            return r.json()
        except Exception:
            # Non-JSON (e.g. login redirect HTML) — treat as no active session
            return {"started": False, "scanning": False, "processed": 0,
                    "total": 0, "complete": False, "error": ""}

    def reset(self):
        csrf = self._csrf()
        self.session.post(f"{self.host}/reset", data={"_csrf": csrf},
                          allow_redirects=True)

def main():
    args = parse_args()

    # Determine YAML folder
    yaml_dir = args.yaml_dir
    if not yaml_dir:
        try:
            with open(args.election_data) as f:
                data = json.load(f)
            all_yamls = []
            for combo in data.get("combinations", []):
                all_yamls.extend(combo.get("yamlFiles", []))
            if all_yamls:
                yaml_dir = str(Path(all_yamls[0]).parent)
                print(f"  YAML folder from election_data.json: {yaml_dir}")
                # Verify it actually exists and has YAML files
                yaml_path = Path(yaml_dir)
                if not yaml_path.is_dir():
                    print(f"  ⚠  YAML folder does not exist: {yaml_dir}")
                    print(f"     Falling back to parent of election_data.json")
                    yaml_dir = str(Path(args.election_data).resolve().parent)
                else:
                    found = list(yaml_path.glob("*.yaml")) + list(yaml_path.glob("*.yml"))
                    print(f"  ✓ YAML folder exists, contains {len(found)} YAML file(s)")
            else:
                # No yaml_files in election_data.json.
                # Check candidate locations in priority order:
                #   1. ~/bBuilder_ballots (default bBuilder output dir)
                #   2. sibling bBuilder source directory (dev layout)
                import os as _os
                _candidates = [
                    _Path(_os.path.expanduser("~/bBuilder_ballots")),
                    _Path(args.election_data).resolve().parent.parent / "bBuilder",
                ]
                _found = next(
                    (c for c in _candidates
                     if c.is_dir() and (list(c.glob("*.yaml")) or list(c.glob("*.yml")))),
                    None
                )
                if _found:
                    yaml_dir = str(_found)
                    print(f"  YAML folder: {yaml_dir}")
                else:
                    yaml_dir = "."
                    print(f"  No YAML files found -- using current dir")
        except Exception as e:
            yaml_dir = "."
            print(f"  Could not read election_data.json: {e}")

    images_abs = str(Path(args.images).resolve())
    yaml_abs   = str(Path(yaml_dir).resolve())

    # Pre-flight checks
    print(f"\nbCounter at {args.host}")
    print(f"  Image folder: {images_abs}")
    print(f"  YAML folder:  {yaml_abs}")

    img_path = Path(images_abs)
    if not img_path.is_dir():
        print(f"  ✗ Image folder does not exist: {images_abs}")
        sys.exit(1)
    pngs = list(img_path.rglob("*.png")) + list(img_path.rglob("*.jpg"))
    print(f"  ✓ Image folder has {len(pngs)} image(s) (recursive)")

    yaml_path = Path(yaml_abs)
    if not yaml_path.is_dir():
        print(f"  ✗ YAML folder does not exist: {yaml_abs}")
        sys.exit(1)
    yamls = list(yaml_path.glob("*.yaml")) + list(yaml_path.glob("*.yml"))
    print(f"  ✓ YAML folder has {len(yamls)} YAML file(s)")
    if not yamls:
        print(f"  ✗ No YAML files found in {yaml_abs} — bCounter will fail to load layouts")
        sys.exit(1)

    client = BCounterClient(args.host)

    # Reset any previous session
    print("\n── Resetting counter session ─────────────────────────────────")
    # Note: reset_scan.sh deletes counter_results.db while bCounter may still
    # have it open. If so, bCounter must be restarted before scanning.
    # We detect this by attempting a lightweight DB operation via /progress.
    client.reset()
    time.sleep(1)

    # Verify reset worked
    prog = client.progress()
    if prog.get("scanning"):
        print("  ⚠  Still scanning after reset — waiting 3s")
        time.sleep(3)

    # Start scanning
    print("\n── Starting scan ─────────────────────────────────────────────")
    try:
        client.start(images_abs, yaml_abs, args.threshold)
    except RuntimeError as e:
        print(f"\n  ✗ FAILED TO START: {e}")
        sys.exit(1)

    # Brief pause then confirm scan actually started
    time.sleep(2)
    prog = client.progress()
    if not prog.get("started") and not prog.get("scanning"):
        print(f"  ✗ Scan does not appear to have started. Progress: {prog}")
        sys.exit(1)
    print(f"  ✓ Scan confirmed started: {prog.get('total', '?')} images queued")

    # Poll progress
    print("\n── Scanning progress ─────────────────────────────────────────")
    last_processed = -1
    while True:
        try:
            prog = client.progress()
        except Exception as e:
            print(f"  Progress poll failed: {e}")
            time.sleep(POLL_INTERVAL)
            continue

        processed = prog.get("processed", 0)
        total     = prog.get("total", "?")
        pass_num  = prog.get("passNumber", 1)
        current   = prog.get("current", "")
        complete  = prog.get("complete", False)
        error     = prog.get("error", "")
        dups      = prog.get("duplicates", [])
        stopped   = prog.get("stopped", False)
        yaml_src  = prog.get("yamlSource", "")

        if processed != last_processed:
            yaml_note = f"  [YAML: {yaml_src}]" if yaml_src else ""
            pass_label = f" pass {pass_num}" if pass_num and pass_num > 1 else ""
            print(f"  [{processed}/{total}{pass_label}] {current[:80] if current else '…'}{yaml_note}")
            last_processed = processed

        if error:
            print(f"\n  ✗ SCAN ERROR: {error}")
            sys.exit(1)

        if dups:
            print(f"\n  ⚠  DUPLICATES SKIPPED:")
            for d in dups:
                print(f"      {d}")

        if stopped and not complete:
            review = prog.get("reviewRequired", [])
            print(f"\n  ⚠  Scan stopped early after {processed}/{total} images")
            print(f"     Reason: {len(review)} ballot(s) required manual review")
            print(f"     (scanner.max-review-before-stop limit reached)")
            print(f"     Proceeding to verify results with partial data.")
            break

        if complete:
            passes_label = f" in {pass_num} pass(es)" if pass_num and pass_num > 1 else ""
            print(f"\n  ✓ Scanning complete: {processed}/{total} images{passes_label}")
            # Print report file locations
            report_dir = prog.get("reportOutputDir", "")
            if report_dir:
                print(f"\n  Reports written to: {report_dir}")
                import os
                for fname, label in [
                    ("results_report.html", "Results report"),
                    ("rcv_report.html",     "RCV tabulation"),
                    ("ballot_manifest.csv", "Arlo manifest"),
                    ("cvr_export.csv",      "Arlo CVR"),
                    ("overvote_report.txt", "Overvote report"),
                ]:
                    fpath2 = os.path.join(report_dir, fname)
                    if os.path.exists(fpath2):
                        print(f"    {label + ':':<22} {fpath2}")
            break

        if prog.get("pauseForResults"):
            print(f"\n  ── 1000-image milestone: results available ──────────")
            csrf = client._csrf()
            client.session.post(f"{client.host}/resume", data={"_csrf": csrf})
            print(f"  ✓ Resumed scanning")

        time.sleep(POLL_INTERVAL)

    print("\n── Done ──────────────────────────────────────────────────────")
    print("Results are available at http://localhost:8081/results")

if __name__ == "__main__":
    main()
