#!/usr/bin/env python3
"""
check_setup.py — Verify all dependencies are installed before running the harness.
"""
import sys, shutil

errors = []

# Python version
if sys.version_info < (3, 9):
    errors.append(f"Python 3.9+ required (found {sys.version})")

# Python packages
packages = {
    "PIL":       "Pillow",
    "yaml":      "PyYAML",
    "cv2":       "opencv-python-headless",
    "numpy":     "numpy",
    "requests":  "requests",
    "pdf2image": "pdf2image",
}
print(f"  Python: {sys.executable}")
for module, pkg in packages.items():
    try:
        __import__(module)
        print(f"  ✓ {pkg}")
    except ImportError:
        errors.append(
            f"Missing Python package: {pkg}\n"
            f"    Run:  {sys.executable} -m pip install {pkg}"
        )

# System tools
tools = {
    "pdftoppm": "poppler  (brew install poppler  /  apt install poppler-utils)",
    "sqlite3":  "sqlite3  (usually built-in)",
}
for tool, install_hint in tools.items():
    if shutil.which(tool):
        print(f"  ✓ {tool}")
    else:
        # pdf2image can use its own poppler — only warn
        if tool == "pdftoppm":
            print(f"  ⚠  {tool} not found on PATH — pdf2image will try to use its bundled poppler")
        else:
            errors.append(f"Missing system tool: {tool}  →  install {install_hint}")

print()
if errors:
    print("SETUP ERRORS — fix these before running the harness:")
    for e in errors:
        print(f"  ✗ {e}")
    sys.exit(1)
else:
    print("All dependencies satisfied. Ready to run.")
