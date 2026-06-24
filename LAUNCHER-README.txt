Election Suite Launcher
=======================

SETUP
-----
Keep ElectionSuite.app in the SAME FOLDER as:
  election-ballot-system/
  election-counter/

Example layout:
  MyElectionFolder/
    ElectionSuite.app          ← double-click this
    election-ballot-system/
    election-counter/

FIRST RUN
---------
macOS may warn "app cannot be opened because the developer cannot be verified."
To allow it:
  System Settings → Privacy & Security → scroll down → click "Open Anyway"

Or right-click ElectionSuite.app → Open → Open (bypasses Gatekeeper once).

WHAT IT DOES
------------
Double-clicking the app shows a dialog with buttons to:
  Start Ballot System   → starts on http://localhost:8080
  Start Counter         → starts on http://localhost:8081
  Open Ballot System    → opens browser to port 8080
  Open Counter          → opens browser to port 8081
  Stop All              → stops both programs
  Quit                  → optionally stops programs and exits the launcher

The browser opens automatically once each program is ready.

LOG FILES
---------
  ballot-system.log   — Spring Boot output for the ballot system
  counter.log         — Spring Boot output for the counter

Both are written to the same folder as ElectionSuite.app.

FIRST-RUN TIME
--------------
On first launch, Maven downloads dependencies (~100 MB).
This takes 1–3 minutes depending on your connection.
Subsequent starts take about 10–15 seconds.

REQUIREMENTS
------------
  Java 21 or later  (java -version to check)
  Internet access on first build only
