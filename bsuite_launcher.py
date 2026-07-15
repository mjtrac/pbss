#!/usr/bin/env python3
"""
bsuite_launcher.py — Cross-platform GUI launcher for pbss.

Starts/stops bBuilder (port 8080) and bCounter/bViewer (ports 8081/8082),
opens browser windows, and runs the test harness.

Requirements: Python 3.9+, tkinter (included with most Python installations).
Usage:        python3 bsuite_launcher.py
              Place this script in the pbss/ root folder, next to bBuilder/
              and bCounter/.
"""

import os
import sys
import signal
import subprocess
import threading
import time
import webbrowser
import tkinter as tk
from tkinter import ttk, messagebox, scrolledtext
from pathlib import Path

# ── Locate pbss root ────────────────────────────────────────────────────────

def find_suite_dir() -> Path | None:
    """Find the pbss root — the folder containing bBuilder/ and bCounter/."""
    script_dir = Path(__file__).resolve().parent
    candidates = [
        script_dir,
        script_dir.parent,
        Path.home() / "pbss",
    ]
    for d in candidates:
        if (d / "bBuilder" / mvnw()).exists() and \
           (d / "bCounter" / mvnw()).exists():
            return d
    return None

def mvnw() -> str:
    return "mvnw.cmd" if sys.platform == "win32" else "mvnw"

def python_exe() -> str:
    return sys.executable  # same Python that's running this script

SUITE_DIR = find_suite_dir()

# ── Process management ────────────────────────────────────────────────────────

class Service:
    """Manages one Spring Boot service (start/stop/status)."""

    def __init__(self, name: str, directory: Path, port: int, log_file: Path):
        self.name     = name
        self.directory= directory
        self.port     = port
        self.log_file = log_file
        self._process: subprocess.Popen | None = None
        self._lock    = threading.Lock()

    def is_running(self) -> bool:
        with self._lock:
            if self._process and self._process.poll() is None:
                return True
            # Also check if the port is occupied (started outside the launcher)
            return self._port_open()

    def _port_open(self) -> bool:
        import socket
        try:
            with socket.create_connection(("localhost", self.port), timeout=0.3):
                return True
        except OSError:
            return False

    def start(self, log_callback=None) -> bool:
        """Start the service. Returns True if started, False if already running."""
        if self.is_running():
            return False
        cmd = [str(self.directory / mvnw()), "spring-boot:run"]
        try:
            with open(self.log_file, "a") as lf:
                proc = subprocess.Popen(
                    cmd,
                    cwd=str(self.directory),
                    stdout=lf,
                    stderr=lf,
                    # On Windows, prevent a console window from flashing
                    creationflags=subprocess.CREATE_NO_WINDOW
                        if sys.platform == "win32" else 0,
                )
            with self._lock:
                self._process = proc
            if log_callback:
                log_callback(f"[{self.name}] Starting (PID {proc.pid})…")
            return True
        except Exception as e:
            if log_callback:
                log_callback(f"[{self.name}] Failed to start: {e}")
            return False

    def stop(self, log_callback=None) -> None:
        """Stop the service."""
        with self._lock:
            proc = self._process
            self._process = None

        if proc and proc.poll() is None:
            try:
                if sys.platform == "win32":
                    proc.terminate()
                else:
                    os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
            except Exception:
                proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()

        # Also kill anything holding the port
        self._kill_port()
        if log_callback:
            log_callback(f"[{self.name}] Stopped.")

    def _kill_port(self):
        try:
            if sys.platform == "win32":
                result = subprocess.check_output(
                    ["netstat", "-ano", "-p", "TCP"],
                    text=True, stderr=subprocess.DEVNULL)
                for line in result.splitlines():
                    if f":{self.port} " in line and "LISTENING" in line:
                        pid = line.strip().split()[-1]
                        subprocess.call(["taskkill", "/F", "/PID", pid],
                                        stderr=subprocess.DEVNULL)
            else:
                result = subprocess.check_output(
                    ["lsof", "-ti", f"tcp:{self.port}"],
                    text=True, stderr=subprocess.DEVNULL).strip()
                if result:
                    for pid in result.splitlines():
                        try:
                            os.kill(int(pid), signal.SIGTERM)
                        except Exception:
                            pass
        except Exception:
            pass

    def wait_until_ready(self, timeout=120, callback=None) -> bool:
        """Poll until port responds or timeout."""
        start = time.time()
        while time.time() - start < timeout:
            if self._port_open():
                return True
            if callback:
                elapsed = int(time.time() - start)
                callback(f"[{self.name}] Waiting… ({elapsed}s)")
            time.sleep(2)
        return False


# ── Main GUI ──────────────────────────────────────────────────────────────────

DARK   = "#1e293b"
DARKER = "#0f172a"
ACCENT = "#38bdf8"
GREEN  = "#22c55e"
RED    = "#ef4444"
AMBER  = "#f59e0b"
TEXT   = "#e2e8f0"
MUTED  = "#64748b"
FONT   = ("Segoe UI" if sys.platform == "win32"
          else "SF Pro Display" if sys.platform == "darwin"
          else "DejaVu Sans", 11)
FONT_B = (FONT[0], FONT[1], "bold")
FONT_SM= (FONT[0], 9)


class App(tk.Tk):

    def __init__(self):
        super().__init__()
        self.title("pbss Launcher")
        self.configure(bg=DARKER)
        self.resizable(True, True)
        self.geometry("620x580")
        self.minsize(520, 480)

        if SUITE_DIR is None:
            self._no_suite_found()
            return

        # Services
        self.builder = Service(
            "bBuilder", SUITE_DIR / "bBuilder", 8080,
            SUITE_DIR / "bBuilder.log")
        self.counter = Service(
            "bCounter", SUITE_DIR / "bCounter", 8081,
            SUITE_DIR / "bCounter.log")

        self._harness_proc: subprocess.Popen | None = None
        self._build_ui()
        self._poll()

    def _no_suite_found(self):
        tk.Label(self, text="pbss not found",
                 bg=DARKER, fg=RED, font=(FONT[0], 14, "bold")).pack(pady=20)
        tk.Label(self,
            text="Could not find bBuilder/ and bCounter/ folders.\n\n"
                 "Place bsuite_launcher.py inside the pbss/ root folder,\n"
                 "next to bBuilder/ and bCounter/.",
            bg=DARKER, fg=TEXT, font=FONT, justify="center").pack(pady=10)
        tk.Button(self, text="Quit", command=self.destroy,
                  bg=RED, fg="white", font=FONT_B,
                  relief="flat", padx=20, pady=8).pack(pady=20)

    def _build_ui(self):
        # ── Header ────────────────────────────────────────────────────────────
        hdr = tk.Frame(self, bg=DARK, pady=10)
        hdr.pack(fill="x")
        tk.Label(hdr, text="pbss Launcher",
                 bg=DARK, fg=TEXT, font=(FONT[0], 16, "bold")).pack(side="left", padx=16)
        tk.Label(hdr, text=str(SUITE_DIR),
                 bg=DARK, fg=MUTED, font=FONT_SM).pack(side="left", padx=4)

        # ── Status panel ──────────────────────────────────────────────────────
        status_frame = tk.Frame(self, bg=DARK, padx=16, pady=10)
        status_frame.pack(fill="x", padx=0, pady=(2, 0))

        self._builder_dot = tk.Label(status_frame, text="●", bg=DARK, fg=MUTED,
                                      font=(FONT[0], 14))
        self._builder_dot.grid(row=0, column=0, padx=(0, 6))
        tk.Label(status_frame, text="bBuilder", bg=DARK, fg=TEXT, font=FONT_B,
                 width=12, anchor="w").grid(row=0, column=1, sticky="w")
        tk.Label(status_frame, text="Ballot design & PDF generation",
                 bg=DARK, fg=MUTED, font=FONT_SM).grid(row=0, column=2, sticky="w")
        tk.Label(status_frame, text="port 8080",
                 bg=DARK, fg=MUTED, font=FONT_SM).grid(row=0, column=3, padx=12)

        self._counter_dot = tk.Label(status_frame, text="●", bg=DARK, fg=MUTED,
                                      font=(FONT[0], 14))
        self._counter_dot.grid(row=1, column=0, padx=(0, 6), pady=(4, 0))
        tk.Label(status_frame, text="bCounter", bg=DARK, fg=TEXT, font=FONT_B,
                 width=12, anchor="w").grid(row=1, column=1, sticky="w", pady=(4, 0))
        tk.Label(status_frame, text="Scanning, counting & ballot viewer",
                 bg=DARK, fg=MUTED, font=FONT_SM).grid(row=1, column=2, sticky="w",
                                                         pady=(4, 0))
        tk.Label(status_frame, text="ports 8081/8082",
                 bg=DARK, fg=MUTED, font=FONT_SM).grid(row=1, column=3, padx=12,
                                                         pady=(4, 0))

        # ── Main buttons ──────────────────────────────────────────────────────
        btn_frame = tk.Frame(self, bg=DARKER, pady=10, padx=14)
        btn_frame.pack(fill="x")

        def btn(parent, text, cmd, color=ACCENT, row=0, col=0, span=1):
            b = tk.Button(parent, text=text, command=cmd,
                          bg=color, fg=DARKER if color != MUTED else TEXT,
                          activebackground=color,
                          font=FONT_B, relief="flat", cursor="hand2",
                          padx=12, pady=7, borderwidth=0)
            b.grid(row=row, column=col, columnspan=span,
                   sticky="ew", padx=4, pady=3)
            return b

        btn_frame.columnconfigure((0,1,2), weight=1)

        btn(btn_frame, "▶  Start All",          self._start_all,
            color="#0ea5e9", row=0, col=0, span=3)

        btn(btn_frame, "Start bBuilder",         self._start_builder,
            color=ACCENT,  row=1, col=0)
        btn(btn_frame, "Start bCounter",         self._start_counter,
            color=ACCENT,  row=1, col=1)
        btn(btn_frame, "Stop All",               self._stop_all,
            color=RED,     row=1, col=2)

        tk.Frame(btn_frame, bg=DARKER, height=4).grid(
            row=2, column=0, columnspan=3, sticky="ew")

        btn(btn_frame, "🌐  Open bBuilder",      lambda: webbrowser.open("http://localhost:8080"),
            color=DARK,    row=3, col=0)
        btn(btn_frame, "🌐  Open bCounter",      lambda: webbrowser.open("http://localhost:8081"),
            color=DARK,    row=3, col=1)
        btn(btn_frame, "🔍  Open bViewer",
            lambda: webbrowser.open("http://localhost:8082/viewer/"),
            color=DARK,    row=3, col=2)

        # ── Test harness ──────────────────────────────────────────────────────
        harness_frame = tk.Frame(self, bg=DARK, padx=14, pady=8)
        harness_frame.pack(fill="x", pady=(4, 0))
        tk.Label(harness_frame, text="Test Harness",
                 bg=DARK, fg=MUTED, font=FONT_SM).pack(anchor="w")

        hbtn = tk.Frame(harness_frame, bg=DARK)
        hbtn.pack(fill="x", pady=(4, 0))
        hbtn.columnconfigure((0, 1, 2), weight=1)

        tk.Button(hbtn, text="▶  Full Run",
                  command=self._harness_full,
                  bg=AMBER, fg=DARKER, activebackground=AMBER,
                  font=FONT_B, relief="flat", cursor="hand2",
                  padx=12, pady=7, borderwidth=0).grid(
                      row=0, column=0, sticky="ew", padx=4)
        tk.Button(hbtn, text="↺  Rescan Only",
                  command=self._harness_rescan,
                  bg=AMBER, fg=DARKER, activebackground=AMBER,
                  font=FONT_B, relief="flat", cursor="hand2",
                  padx=12, pady=7, borderwidth=0).grid(
                      row=0, column=1, sticky="ew", padx=4)
        tk.Button(hbtn, text="✕  Stop Harness",
                  command=self._harness_stop,
                  bg=MUTED, fg=TEXT, activebackground=MUTED,
                  font=FONT_B, relief="flat", cursor="hand2",
                  padx=12, pady=7, borderwidth=0).grid(
                      row=0, column=2, sticky="ew", padx=4)

        # ── Log panel ─────────────────────────────────────────────────────────
        log_frame = tk.Frame(self, bg=DARKER, padx=14, pady=6)
        log_frame.pack(fill="both", expand=True)
        tk.Label(log_frame, text="Activity Log",
                 bg=DARKER, fg=MUTED, font=FONT_SM).pack(anchor="w")
        self._log = scrolledtext.ScrolledText(
            log_frame, bg="#0a1628", fg="#94a3b8",
            font=(("Consolas" if sys.platform == "win32" else "Menlo"), 9),
            relief="flat", height=8, state="disabled",
            insertbackground=TEXT, wrap="word")
        self._log.pack(fill="both", expand=True, pady=(4, 0))

        # ── Footer ────────────────────────────────────────────────────────────
        foot = tk.Frame(self, bg=DARK, pady=6)
        foot.pack(fill="x", side="bottom")
        tk.Button(foot, text="Quit", command=self._quit,
                  bg=DARKER, fg=MUTED, activebackground=DARKER,
                  font=FONT_SM, relief="flat", cursor="hand2",
                  padx=10, pady=4, borderwidth=0).pack(side="right", padx=12)

        self._log_msg(f"pbss root: {SUITE_DIR}")

    # ── Logging ───────────────────────────────────────────────────────────────

    def _log_msg(self, text: str):
        def _do():
            self._log.configure(state="normal")
            ts = time.strftime("%H:%M:%S")
            self._log.insert("end", f"{ts}  {text}\n")
            self._log.see("end")
            self._log.configure(state="disabled")
        self.after(0, _do)

    # ── Status polling ────────────────────────────────────────────────────────

    def _poll(self):
        b = self.builder.is_running()
        c = self.counter.is_running()
        self._builder_dot.configure(fg=GREEN if b else MUTED)
        self._counter_dot.configure(fg=GREEN if c else MUTED)
        self.after(2000, self._poll)

    # ── Service actions ───────────────────────────────────────────────────────

    def _start_all(self):
        threading.Thread(target=self._start_all_thread, daemon=True).start()

    def _start_all_thread(self):
        started = []
        if not self.builder.is_running():
            self.builder.start(self._log_msg)
            started.append(("bBuilder", 8080))
        if not self.counter.is_running():
            self.counter.start(self._log_msg)
            started.append(("bCounter", 8081))

        if not started:
            self._log_msg("Both services already running.")
            return

        for name, port in started:
            svc = self.builder if port == 8080 else self.counter
            self._log_msg(f"[{name}] Waiting for port {port}…")
            if svc.wait_until_ready(callback=self._log_msg):
                self._log_msg(f"[{name}] Ready — opening browser.")
                webbrowser.open(f"http://localhost:{port}")
            else:
                self._log_msg(f"[{name}] Timed out waiting for port {port}.")

    def _start_builder(self):
        if self.builder.is_running():
            self._log_msg("[bBuilder] Already running.")
            return
        threading.Thread(target=self._start_one,
                         args=(self.builder, 8080), daemon=True).start()

    def _start_counter(self):
        if self.counter.is_running():
            self._log_msg("[bCounter] Already running.")
            return
        threading.Thread(target=self._start_one,
                         args=(self.counter, 8081), daemon=True).start()

    def _start_one(self, svc: Service, port: int):
        svc.start(self._log_msg)
        self._log_msg(f"[{svc.name}] Waiting for port {port}…")
        if svc.wait_until_ready(callback=self._log_msg):
            self._log_msg(f"[{svc.name}] Ready — opening browser.")
            webbrowser.open(f"http://localhost:{port}")
        else:
            self._log_msg(f"[{svc.name}] Timed out.")

    def _stop_all(self):
        threading.Thread(target=self._stop_all_thread, daemon=True).start()

    def _stop_all_thread(self):
        self.builder.stop(self._log_msg)
        self.counter.stop(self._log_msg)

    # ── Test harness ──────────────────────────────────────────────────────────

    def _harness_check(self) -> bool:
        harness = SUITE_DIR / "test-harness"
        if not harness.exists():
            messagebox.showerror("Test Harness",
                f"test-harness/ not found in:\n{SUITE_DIR}")
            return False
        if not self.builder.is_running() or not self.counter.is_running():
            messagebox.showwarning("Test Harness",
                "Both bBuilder and bCounter must be running before "
                "starting the test harness.\n\nClick 'Start All' first.")
            return False
        return True

    def _harness_full(self):
        if not self._harness_check():
            return
        if not messagebox.askyesno("Full Test Run",
            "This will:\n"
            "1. Stop bCounter\n"
            "2. Reset the scan database and image files\n"
            "3. Restart bCounter\n"
            "4. Run run_all.sh (generate, mark, distort, scan, verify)\n\n"
            "This takes several minutes. Continue?"):
            return
        threading.Thread(target=self._harness_full_thread, daemon=True).start()

    def _harness_full_thread(self):
        harness = SUITE_DIR / "test-harness"
        self._log_msg("[Harness] Stopping bCounter for reset…")
        self.counter.stop(self._log_msg)
        time.sleep(2)

        self._log_msg("[Harness] Running reset_scan.sh…")
        reset = harness / "reset_scan.sh"
        subprocess.run(["bash", str(reset)], cwd=str(harness))

        self._log_msg("[Harness] Restarting bCounter…")
        self.counter.start(self._log_msg)
        if not self.counter.wait_until_ready(callback=self._log_msg):
            self._log_msg("[Harness] bCounter failed to start — aborting.")
            return

        self._log_msg("[Harness] Starting full run_all.sh…")
        run_all = harness / "run_all.sh"
        try:
            self._harness_proc = subprocess.Popen(
                ["bash", str(run_all),
                 "--counter-dir", str(SUITE_DIR / "bCounter")],
                cwd=str(harness),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True
            )
            for line in self._harness_proc.stdout:
                self._log_msg(line.rstrip())
            self._harness_proc.wait()
            self._log_msg("[Harness] Full run complete.")
        except Exception as e:
            self._log_msg(f"[Harness] Error: {e}")
        finally:
            self._harness_proc = None

    def _harness_rescan(self):
        if not self._harness_check():
            return
        threading.Thread(target=self._harness_rescan_thread, daemon=True).start()

    def _harness_rescan_thread(self):
        harness = SUITE_DIR / "test-harness"
        yaml_dir = Path.home() / "bBuilder_ballots"
        if not yaml_dir.exists():
            yaml_dir = SUITE_DIR / "bBuilder"

        self._log_msg(f"[Harness] Rescanning images with YAML from {yaml_dir}…")
        images = harness / "images"
        try:
            self._harness_proc = subprocess.Popen(
                [python_exe(), "run_counter.py",
                 "--images", str(images),
                 "--yaml-dir", str(yaml_dir)],
                cwd=str(harness),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True
            )
            for line in self._harness_proc.stdout:
                self._log_msg(line.rstrip())
            self._harness_proc.wait()
            self._log_msg("[Harness] Rescan complete.")
        except Exception as e:
            self._log_msg(f"[Harness] Error: {e}")
        finally:
            self._harness_proc = None

    def _harness_stop(self):
        if self._harness_proc and self._harness_proc.poll() is None:
            self._harness_proc.terminate()
            self._log_msg("[Harness] Stopped.")
        else:
            self._log_msg("[Harness] No harness running.")

    # ── Quit ──────────────────────────────────────────────────────────────────

    def _quit(self):
        if self.builder.is_running() or self.counter.is_running():
            ans = messagebox.askyesnocancel(
                "Quit", "Stop running services before quitting?",
                default=messagebox.YES)
            if ans is None:      # Cancel
                return
            if ans:              # Yes — stop first
                self.builder.stop()
                self.counter.stop()
        self.destroy()


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    app = App()
    app.mainloop()
