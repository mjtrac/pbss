# Election Ballot Management System

A Spring Boot application for government jurisdictions to create, manage, and print election ballots as PDFs.

---

## Quick Start

### Requirements
- Java 21+
- Maven 3.9+ (or use the included `mvnw` wrapper)

### Run with SQLite (default — no setup needed)
```bash
./mvnw spring-boot:run
# Open browser: http://localhost:8080
```

### Run with PostgreSQL
```bash
export DB_USER=your_db_user
export DB_PASS=your_db_password
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

---

## Building a Single Distributable JAR

```bash
./mvnw clean package -DskipTests
java -jar target/election-ballot-system-1.0.0.jar
```

---

## Packaging with Bundled Java Runtime (no JDK required on target machine)

### Step 1 — Create a minimal JRE
```bash
jlink \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.base,java.desktop,java.sql,java.net.http,java.naming,java.security.jgss,jdk.crypto.ec \
  --output ./election-jre \
  --strip-debug \
  --compress=2 \
  --no-header-files \
  --no-man-pages
```

### Step 2 — Create platform installer

**macOS (.dmg):**
```bash
jpackage \
  --input target \
  --name "ElectionBallot" \
  --main-jar election-ballot-system-1.0.0.jar \
  --runtime-image ./election-jre \
  --type dmg \
  --app-version 1.0.0
```

**Windows (.exe installer):**
```bash
jpackage \
  --input target \
  --name "ElectionBallot" \
  --main-jar election-ballot-system-1.0.0.jar \
  --runtime-image ./election-jre \
  --type exe \
  --win-menu --win-shortcut
```

**Linux (.deb or .rpm):**
```bash
jpackage \
  --input target \
  --name "election-ballot" \
  --main-jar election-ballot-system-1.0.0.jar \
  --runtime-image ./election-jre \
  --type deb
```

---

## Running Tests

```bash
./mvnw test
```

---

## Architecture Overview

```
src/main/java/gov/election/ballot/
├── ElectionBallotApplication.java   # Spring Boot entry point
├── config/
│   ├── SecurityConfig.java          # BCrypt, form login, CSRF, CSP headers
│   └── DatabaseConfig.java          # JPA / transaction setup
├── model/                           # JPA entities (one per DB table)
│   ├── Jurisdiction.java
│   ├── SubJurisdiction.java
│   ├── Party.java
│   ├── Election.java
│   ├── BallotType.java
│   ├── Contest.java
│   ├── Candidate.java
│   ├── BallotCombination.java       # links sub-jurisdiction+party+type+election to contests
│   ├── BallotDesignTemplate.java    # paper size, font sizes, barcode position, etc.
│   ├── PrintLog.java                # audit trail of all print events
│   └── User.java                    # ADMIN / DATA_ENTRY / PRINTER roles
├── repository/                      # Spring Data JPA interfaces
├── service/
│   ├── BallotGenerationService.java # core PDF generation (iText 8)
│   ├── BarcodeService.java          # QR + Code128 barcode rendering (ZXing)
│   ├── BallotLayoutService.java     # stores position offsets for export
│   ├── ExportService.java           # XML / YAML offset reports; OCR name list
│   ├── PrintLogService.java         # records print events
│   └── UserService.java             # Spring Security UserDetailsService + user mgmt
├── controller/
│   ├── AuthController.java          # login / dashboard pages
│   ├── BallotController.java        # PDF generation, export, OCR list endpoints
│   ├── ContestController.java       # contest & candidate data entry
│   └── AdminController.java         # user management, print audit log
└── util/
    ├── BallotDimensions.java        # position data structures for offset report
    └── MeasurementUtil.java         # pt ↔ inches ↔ mm conversions
```

---

## User Roles

| Role | Can Do |
|---|---|
| `ADMIN` | Everything: manage users, enter data, print ballots, view audit log |
| `DATA_ENTRY` | Enter jurisdictions, contests, candidates, combinations, templates |
| `PRINTER` | Print ballots (read-only data access); tracked in audit log |

---

## Ballot PDF Design

### Machine-countability features
- **Vote target**: Filled oval (ellipse) recommended — standard for optical scan.
- **Orientation mark**: Solid black triangle at top-left corner tells the scanner which corner is up.
- **Barcodes**: QR code + Code128 linear barcode both encode `JurId|SubJurId|PartyId|BallotTypeId|ElectionId|Sheet/Total`.
- **Ranked-choice**: One small numbered box per allowable rank per candidate.
- **Offset report**: After generating a ballot, download XML or YAML giving the exact offset (inches or mm) of every vote indicator from the bounding box upper-left corner.

### Paper sizes supported
- 8.5 × 11 (US Letter)
- 8.5 × 14 (US Legal)
- 8.5 × 7
- 8.5 × 5.5 (Half letter)
- A3, A4, A5

---

## Security Notes

| Library | License | Notes |
|---|---|---|
| Spring Boot 3.3.x | Apache 2.0 | Monitor https://spring.io/security for CVEs |
| iText 8 | **AGPL 3.0** | AGPL requires your app to be open source, or purchase commercial license. Government internal use generally satisfies AGPL. Confirm with legal. |
| ZXing 3.5.x | Apache 2.0 | Stable; no significant recent CVEs |
| SnakeYAML 2.2 | Apache 2.0 | **Use 2.x only** — 1.x had critical RCE CVEs (CVE-2022-1471). Never call `new Yaml().load()` on untrusted input. |
| SQLite JDBC 3.45.x | Apache 2.0 | Restrict OS file permissions on .db file to app user |
| PostgreSQL JDBC 42.x | BSD 2-Clause | Keep updated; older versions had CVE-2022-21724 |
| BCrypt (Spring Security) | Apache 2.0 | Cost factor 12; increase over time as hardware improves |

Additional protections implemented:
- CSRF tokens on all forms
- Content-Security-Policy header
- X-Frame-Options: DENY (clickjacking)
- Session fixation protection
- One session per user
- Passwords BCrypt-hashed, never logged
- All DB queries via JPA (parameterized — no SQL injection)
- Thymeleaf auto-escapes HTML output (XSS protection)

---

## Extending the System

- **New paper size**: Add entry to `BallotDesignTemplate.PaperSize` enum with width/height in points.
- **New voting method**: Add entry to `Contest.VotingMethod`, handle in `BallotGenerationService.drawVoteTarget()` and `buildInstruction()`.
- **Multi-sheet ballots**: `BallotDesignTemplate.multiSheet = true`; extend `BallotGenerationService.generateBallot()` to add pages and call `drawBallotHeader()` with correct sheet numbers.
- **PostgreSQL production**: Enable Flyway, set `spring.jpa.hibernate.ddl-auto=validate`, use environment variables for credentials.

