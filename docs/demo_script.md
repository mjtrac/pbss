# pbss Demonstration Script

**Audience:** Election officials, administrators, and technical reviewers  
**Duration:** Approximately 20–25 minutes  
**Prerequisites:** pbss unzipped, Java 21+ installed, Maven wrapper included  
**Setup:** Sample ballot images in `~/tmp/images/`, YAML layout files in `~/tmp/yaml/`

---

## Part 1 — Launch the Suite (0:00)

> *Show the pbss folder in Finder. It contains pbss.app, bBuilder, bCounter, bViewer, and the docs folder.*

Double-click **pbss.app**. The pbss menu appears — a simple list of actions for all three programs. No installation, no configuration files to edit.

> *Show the menu: Start bBuilder, Start bCounter, Start bViewer, Open in Browser options, Stop All, Quit.*

Select **Start bBuilder**. A dialog confirms it is starting, and the browser will open automatically when it is ready. The first run takes one to three minutes while Maven downloads dependencies — subsequent starts take about three seconds.

> *Browser opens to localhost:8080. Show the bBuilder dashboard.*

---

## Part 2 — bBuilder: Creating an Election from Scratch (1:30)

> *Show the bBuilder dashboard. The left sidebar shows Jurisdictions, Elections, Contests, Candidates, Templates, Regions, Parties, Ballot Types, Combinations.*

bBuilder organizes a ballot around a **jurisdiction** — a county or district — containing one or more **elections**. Each election has **contests**, and each contest has **candidates**. A **combination** ties together a region, party, ballot type, and election to define exactly which contests appear on a particular ballot style.

### 2a. Create a Jurisdiction and Election

Click **Jurisdictions → New**. Enter *Sample County* and save.

Click **Elections → New**. Enter *Sample General Election*, select *Sample County*, set the election date, and save.

> *Show the saved election appearing in the list.*

### 2b. Create a Contest and Candidates

Click **Contests → New**. Fill in:
- Title: *Mayor*
- Type: *Plurality*
- Max votes: *1*
- Election: *Sample General Election*

Save. Now add candidates — click **Add Candidate** and enter *Alice Smith*, then repeat for *Bill Jones* and a **Write-In** slot.

> *Show the three candidates listed under the Mayor contest.*

### 2c. Define a Region and Combination

Click **Regions → New**. Enter *Precinct 1* as a Single Precinct region.

Click **Combinations → New**. Select *Precinct 1*, the *Everyone* party, the *Precinct* ballot type, and *Sample General Election*. Save.

> *Show the combination saved. Explain: this combination defines one ballot style — the exact set of contests a voter in Precinct 1 receives.*

### 2d. Generate the Ballot

Click **Print / Generate Ballots** in the top navigation. Select the combination and click **Generate**. The system produces a PDF, an XML layout file, and a YAML layout file — all three in the bBuilder working folder.

> *Show the generated PDF opening. Point out the corner marks — the large rectangle in the top-left for orientation detection, and the square marks in the other three corners — and the QR barcode encoding jurisdiction, region, party, ballot type, election, and page number.*

### 2e. Delete and Use the Seed Button

> *Return to the bBuilder dashboard.*

We created that election manually to show the structure. Now watch what the **seed button** does. Click **Dashboard → Add Sample Contests & Generate Ballot**.

> *Pause while it runs.*

This creates a complete multi-contest election in one click — including a ranked-choice presidential race with four ranks per candidate, a plurality congressional race, an approval-voting mayoral race, and a measure — all assigned to two precinct groups. The ballot PDF is generated immediately and opens in the browser.

> *Scroll through the generated ballot PDF showing all contest types.*

Note the different indicator styles: ovals for plurality, stacked rank boxes for ranked choice, and checkboxes for approval. The layout files — XML and YAML — encode the precise position of every indicator box in page-absolute inches from the top-left corner. The ballot viewer and counter both use these coordinates.

---

## Part 3 — The Ballot YAML Layout File (6:00)

> *Open the generated `_adjusted.yaml` file in a text editor, or show it in the browser.*

The YAML that the counter uses looks like this:

```yaml
contests:
  - id: 1
    title: President of the United States
    contestType: RANKED_CHOICE
    maxVotes: 4
    offsetLeft: 0.75
    offsetTop: 1.20
    indicators:
      - candidateId: 1
        candidateName: George Washington (Rank 1)
        offsetLeft: 1.45
        offsetTop: 1.55
        width: 0.18
        height: 0.14
```

Every indicator box has an `offsetLeft` and `offsetTop` in inches from the page content area, plus a width and height. The counter multiplies these by the image DPI to get pixel coordinates, then applies a perspective homography to correct for any tilt or skew in the scan. The **adjusted YAML** — written alongside each scanned image — shows those same coordinates transformed into the actual pixel space of that specific scan, making it easy to verify the counter placed its boxes correctly.

---

## Part 4 — bCounter: Scanning Ballots (8:00)

> *Return to the Election Suite menu. Select Start bCounter. Browser opens to localhost:8081.*

Log in with username `admin` and password `ChangeMe123!`.

> *Show the bCounter start screen.*

The counter needs two things: the folder containing scanned ballot images, and the folder containing the YAML or XML layout files produced by bBuilder.

Enter:
- **Image folder:** `~/tmp/images`
- **YAML/XML folder:** `~/tmp/yaml`

Leave the other settings at their defaults — threshold intensity at 8% and dark pixel minimum at 8%.

> *Click Start Scanning.*

### 4a. The Scanning Progress Page

> *Show the scanning progress page with the current image path, scanned count, remaining count, and progress bar.*

The counter processes images one at a time. For each image it:

1. Decodes the QR barcode to identify the precinct, party, ballot type, and page
2. Detects the four corner marks to compute a perspective homography
3. Warps the image to a canonical upright rectangle
4. Samples each indicator box and measures the percentage of dark pixels
5. Applies the threshold to determine VOTED, UNMARKED, or OVERVOTED
6. Writes the result immediately to the SQLite database
7. Renames the image file, appending `.counted`

### 4b. Tilt Correction

> *Show an example of a tilted ballot image alongside its adjusted YAML overlay.*

Notice that even when a ballot is fed into the scanner at an angle, the corner marks allow the counter to compute the exact homography and sample the correct pixels. The adjusted YAML written alongside each image shows the transformed indicator positions — you can load these coordinates in any image viewer to verify the counter placed its boxes on the right spots.

### 4c. Upside-Down Ballots

> *Show the upside-down ballot being processed.*

The counter detects orientation automatically. The top-left corner mark is a wide rectangle rather than a square — when the counter finds the rectangle in the bottom half of the image, it knows the ballot is upside down, rotates it 180 degrees in memory, and proceeds normally. The rotation is recorded in the database. The ballot viewer will display such images right-side up with overlays correctly aligned.

### 4d. Double-Count Protection

The counter provides two layers of protection against counting the same ballot twice.

**First layer — file renaming:** After each image is successfully counted and written to the database, it is renamed by appending `.counted` to the filename. The scanner skips any file with a `.counted` extension when building its image queue. If you re-scan the same folder, already-counted images are simply not in the queue.

**Second layer — database uniqueness:** The `ballot_image` table enforces a unique constraint on the full absolute file path. If an image with the same path somehow reaches the persist step — for example after manually renaming a `.counted` file back — the counter detects the duplicate, skips it, and displays a warning banner that must be explicitly cleared by the user. The warning lists the full path of every skipped image.

> *Show the amber warning banner appearing if a duplicate is detected.*

### 4e. Results Page

> *Show the results page after scanning completes.*

The results page queries the database directly and shows vote totals by contest, broken down by precinct and by party. These numbers reflect everything in the database — not just the current session — so results from multiple scanning sessions accumulate correctly.

---

## Part 5 — bViewer: Reviewing Ballot Images (15:00)

> *Return to the Election Suite menu. Select Start bViewer. Browser opens to localhost:8082.*

Log in with the same credentials. The index page lists every ballot image in the database.

> *Click the first ballot to open it.*

### 5a. The Overlay

> *Show the ballot image with colored rectangles overlaid on every indicator box.*

Each rectangle corresponds to one vote opportunity sampled by the counter.

- **Green** — the counter recorded this as VOTED
- **Yellow** — OVERVOTED (more marks than the contest allows)
- **Blue** — UNMARKED

The rectangles are drawn at the exact pixel coordinates the counter used — derived from the stored corner marks and the same homography the counter computed. What you see here is precisely what the counter saw and measured.

Click any rectangle to highlight it and scroll the sidebar to the matching candidate. Click any sidebar entry to scroll the image to that indicator.

### 5b. Upside-Down Display

> *Navigate to a ballot that was scanned upside-down.*

Ballots that were detected upside-down are displayed right-side up here, with overlays aligned correctly. The original file on disk is unchanged — only the display is rotated.

### 5c. Navigation and Auto-Advance

> *Show the navigation bar at the top.*

Use **Prev** and **Next** to move through ballots, or press `p` and `n` on the keyboard. The arrow keys scroll the image. Zoom in with `+` and out with `-`, type any percentage directly into the zoom field, or press `0` to fit the ballot to the window.

Check **Auto-advance** and enter a number of seconds to cycle through ballots automatically — useful for a spot-check review of a large batch. Zoom level and scroll position carry over from ballot to ballot.

Press `?` to see all keyboard shortcuts.

---

## Part 6 — Inspecting the Database (20:00)

> *Open DB Browser for SQLite. File → Open → navigate to pbss/bCounter/counter_results.db.*

The database has five tables. Click **Browse Data** and select each one in turn.

**barcode** — one row per unique barcode scanned, with the jurisdiction, region, party, ballot type, election, and page fields split out individually.

**ballot_image** — one row per image file. Note the `was_rotated` column recording which ballots were flipped, the `corner_marks` column storing the four detected corner positions, and `warp_dpi` recording the resolution used for the perspective correction.

**contest** and **candidate** — the contest and candidate names as decoded from the YAML, normalized into their own tables so vote queries can join on integer keys rather than string matching.

**vote_opportunity** — the core fact table. Every row is one indicator box from one ballot image, with `abs_left` and `abs_top` in canonical pixel coordinates, the sampling statistics (dark pixel count, dark percentage, mean intensity), the threshold used, and the `vote_status`.

> *Switch to the Execute SQL tab. Paste the first query from docs/query_examples.md.*

```sql
SELECT
    k.contest_title,
    c.candidate_name,
    COUNT(*) FILTER (WHERE v.vote_status = 'VOTED')     AS votes,
    COUNT(*) FILTER (WHERE v.vote_status = 'OVERVOTED') AS overvotes
FROM vote_opportunity  v
JOIN candidate         c ON v.candidate_id_fk = c.id
JOIN contest           k ON v.contest_id      = k.id
GROUP BY k.contest_title, c.candidate_name
ORDER BY k.contest_title, votes DESC;
```

> *Run it. Show the results grid.*

Every vote in the election is in this database, queryable with standard SQL. The `docs/query_examples.md` file in the pbss folder contains additional queries breaking results down by precinct, party, and ballot type, plus notes on running the same queries against PostgreSQL for larger deployments.

---

## Closing (23:00)

pbss is three Spring Boot applications that work together:

- **bBuilder** designs ballots and generates the PDF and layout files
- **bCounter** scans ballot images, detects marks, and writes results to a standard SQL database
- **bViewer** lets you review every ballot image with its sampled indicator boxes overlaid, so any counting question can be answered by looking at exactly what the counter saw

The database is portable SQLite by default and can be configured for PostgreSQL. All source code is available under the GPL v3 license.

---

*End of script*
