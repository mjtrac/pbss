# bSuite Configuration Guide

Each application reads its settings from `src/main/resources/application.properties`
at build time. You can override any property at runtime without rebuilding using
Java system properties or environment variables.

---

## Changing the port

**In `application.properties`:**
```properties
server.port=8080   # bBuilder default
server.port=8081   # bCounter default
server.port=8082   # bViewer default
```

**At runtime (overrides the file):**
```bash
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=9090"
```

---

## Common properties (all three apps)

| Property | Default | Description |
|---|---|---|
| `server.port` | 8080/8081/8082 | HTTP listen port |
| `app.background-color` | *(none)* | CSS color for page background, e.g. `#f0f4f8` or `lightblue` |
| `logging.file.name` | *(stdout only)* | Path to log file; set to enable file logging |
| `spring.thymeleaf.cache` | `false` | Set `true` in production for faster template rendering |

---

## bBuilder (`election-ballot-system`)

| Property | Default | Description |
|---|---|---|
| `ballot.export.dir` | `.` (working directory) | Where generated PDFs and YAML/XML files are written |
| `ballot.export.format` | `yaml` | Export format: `yaml`, `xml`, or `both` |
| `spring.datasource.url` | `jdbc:sqlite:${user.dir}/election_ballot.db` | Builder database location |

---

## bCounter (`election-counter`)

| Property | Default | Description |
|---|---|---|
| `ballot.layout.format` | `yaml` | Preferred layout file format; falls back to the other if absent |
| `scanner.max-review-before-stop` | `10` | Stop scanning after this many review-required ballots (0 = unlimited) |
| `corner.detection.force-bottom-edge-fallback` | `false` | Debug: skip primary corner detection |
| `spring.datasource.url` | `jdbc:sqlite:${user.dir}/counter_results.db` | Counter database location |
| `logging.file.name` | `${user.dir}/bCounter.log` | Log file path |

---

## bViewer (`ballot-viewer`)

| Property | Default | Description |
|---|---|---|
| `spring.datasource.url` | `jdbc:sqlite:${COUNTER_DB:${user.dir}/../bCounter/counter_results.db}` | Path to the bCounter database (read-only) |
| `logging.file.name` | `${user.dir}/bViewer.log` | Log file path |

**Changing the database path at runtime:**
```bash
# Via environment variable
export COUNTER_DB=/path/to/counter_results.db
./mvnw spring-boot:run

# Via JVM argument
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.datasource.url=jdbc:sqlite:/path/to/counter_results.db"
```

---

## Runtime override examples

```bash
# Run bBuilder on port 9080 with a custom export directory
cd bBuilder
./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dserver.port=9080 -Dballot.export.dir=/data/exports"

# Run bCounter with no scan stop limit and logging to a custom path
cd bCounter
./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dscanner.max-review-before-stop=0 -Dlogging.file.name=/var/log/bcounter.log"

# Run bViewer with a green background
cd bViewer
./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dapp.background-color=#e8f5e9"
```

---

## Session and security

| Property | Description |
|---|---|
| `server.servlet.session.timeout` | Session inactivity timeout (e.g. `30m`, `1h`) |
| `server.servlet.session.cookie.name` | Cookie name (unique per app to allow all three in one browser) |
| `server.servlet.session.cookie.secure` | Set `true` when running behind HTTPS |
| `server.error.include-stacktrace` | Set `always` for debugging; `never` for production |
