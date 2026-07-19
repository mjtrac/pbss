<!--
  Copyright (C) 2026 Mitch Trachtenberg
  Election Ballot System — licensed under the GNU General Public License v3.
  See <https://www.gnu.org/licenses/> for the full license text.
-->

# Running the Web Apps in Docker

This covers containerizing the three **web** apps — **bBuilder**,
**bCounter** (which embeds bViewer), and **bScanner**. It's a separate
guide from the main [README.md](../README.md) because packaging a
browser-UI app for a container is a different concern from jlink/jpackage-ing
a desktop one — see the README for the recommended Swing apps
(`builder`/`counter`/`scanner`/`viewer`), which are not covered here and
aren't good Docker candidates (a desktop GUI has no meaning inside a
headless container).

**Reminder:** [the README's air-gap guidance](../README.md#running-offline--air-gapped)
still applies. None of these apps need internet access to run — only the
initial `docker build` needs it, to download Maven/Java base images and
dependencies. Once an image is built, it runs (and should run, for any real
election) with no network access beyond what's needed to reach the
attached scanner/printer and whatever port you expose the UI on locally.

## Why the build context is the repo root, not the app subdirectory

`bBuilder`/`bCounter`/`bScanner` each depend on a shared `-core` library
(`builder-core`/`counter-core`/`scanner-core` respectively) that isn't
published anywhere — it has to be `mvn install`ed into the local Maven
repository before the app builds, exactly as when building from source
(see the main README). So the Docker build stage needs both the `-core`
module and the app's own directory available, which means running `docker
build` **from the repo root**, not from inside `bBuilder/` etc.

## Dockerfile

This same multi-stage pattern works for all three apps — swap the `ARG`
values per the table below. Save as `bBuilder/Dockerfile` (or
`bCounter/Dockerfile`, `bScanner/Dockerfile`):

```dockerfile
# syntax=docker/dockerfile:1
ARG APP_DIR=bBuilder
ARG CORE_DIR=builder-core
ARG JAR_NAME=bBuilder

# ── Build stage ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
ARG APP_DIR
ARG CORE_DIR
WORKDIR /src

# Install the shared -core module first (not a multi-module reactor —
# each consumer declares it as a normal Maven dependency, so it must
# already be in the local repo before the app builds).
COPY ${CORE_DIR} ${CORE_DIR}
RUN mvn -f ${CORE_DIR}/pom.xml install -DskipTests

COPY ${APP_DIR} ${APP_DIR}
RUN mvn -f ${APP_DIR}/pom.xml clean package -DskipTests

# ── Runtime stage ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
ARG APP_DIR
ARG JAR_NAME
RUN useradd --create-home --home-dir /home/pbss pbss
USER pbss
WORKDIR /home/pbss
COPY --from=build /src/${APP_DIR}/target/${JAR_NAME}-*.jar app.jar

# Data (SQLite db, ballot templates, scans, reports, logs) lives under
# ${user.home}/pbss_data by default — that's /home/pbss/pbss_data here.
VOLUME /home/pbss/pbss_data

ENTRYPOINT ["java", "-jar", "app.jar"]
```

Build each image from the **repo root**:

```bash
# bBuilder
docker build -f bBuilder/Dockerfile \
  --build-arg APP_DIR=bBuilder --build-arg CORE_DIR=builder-core --build-arg JAR_NAME=bBuilder \
  -t pbss/bbuilder:0.9.14 .

# bCounter
docker build -f bCounter/Dockerfile \
  --build-arg APP_DIR=bCounter --build-arg CORE_DIR=counter-core --build-arg JAR_NAME=bCounter \
  -t pbss/bcounter:0.9.14 .

# bScanner
docker build -f bScanner/Dockerfile \
  --build-arg APP_DIR=bScanner --build-arg CORE_DIR=scanner-core --build-arg JAR_NAME=bScanner \
  -t pbss/bscanner:0.9.14 .
```

| App | `APP_DIR` | `CORE_DIR` | `JAR_NAME` | Port(s) |
|---|---|---|---|---|
| bBuilder | `bBuilder` | `builder-core` | `bBuilder` | 8080 |
| bCounter | `bCounter` | `counter-core` | `bCounter` | 8081 (bCounter), 8082 (embedded bViewer) |
| bScanner | `bScanner` | `scanner-core` | `bScanner` | 8083 |

## Running a container

Persist data with a named volume (or a bind mount to a real host
directory) so `~/pbss_data`'s contents — the SQLite database, ballot
templates, scans, reports — survive container restarts:

```bash
docker volume create pbss_builder_data

docker run -d \
  --name pbss-builder \
  -p 8080:8080 \
  -v pbss_builder_data:/home/pbss/pbss_data \
  pbss/bbuilder:0.9.14
```

```bash
docker volume create pbss_counter_data

docker run -d \
  --name pbss-counter \
  -p 8081:8081 -p 8082:8082 \
  -v pbss_counter_data:/home/pbss/pbss_data \
  pbss/bcounter:0.9.14
```

Both bBuilder and bCounter read from/write to `ballot_templates/`, so if
you're running both, mount the **same** volume for both containers rather
than two separate ones — otherwise bCounter won't see the ballots bBuilder
generated:

```bash
docker run -d --name pbss-builder -p 8080:8080 -v pbss_data:/home/pbss/pbss_data pbss/bbuilder:0.9.14
docker run -d --name pbss-counter -p 8081:8081 -p 8082:8082 -v pbss_data:/home/pbss/pbss_data pbss/bcounter:0.9.14
```

Override any property the normal Spring Boot ways (see
[the README's property-override section](../README.md#configuring-these-apps-property-overrides))
via environment variables or `docker run`'s trailing command-line args:

```bash
docker run -d --name pbss-builder -p 8080:8080 \
  -v pbss_data:/home/pbss/pbss_data \
  -e APP_LOGIN_TITLE="My County Election System" \
  pbss/bbuilder:0.9.14
```

## docker-compose.yml

A `docker-compose.yml` at the repo root running bBuilder + bCounter
together on a shared data volume:

```yaml
services:
  bbuilder:
    build:
      context: .
      dockerfile: bBuilder/Dockerfile
      args:
        APP_DIR: bBuilder
        CORE_DIR: builder-core
        JAR_NAME: bBuilder
    ports:
      - "8080:8080"
    volumes:
      - pbss_data:/home/pbss/pbss_data

  bcounter:
    build:
      context: .
      dockerfile: bCounter/Dockerfile
      args:
        APP_DIR: bCounter
        CORE_DIR: counter-core
        JAR_NAME: bCounter
    ports:
      - "8081:8081"
      - "8082:8082"
    volumes:
      - pbss_data:/home/pbss/pbss_data

volumes:
  pbss_data:
```

```bash
docker compose up -d --build
# bBuilder: http://localhost:8080
# bCounter: http://localhost:8081
# bViewer:  http://localhost:8082
```

## bBuilder with PostgreSQL instead of SQLite

bBuilder ships an `application-postgres.properties` profile as an
alternative to the SQLite default (see `bBuilder/src/main/resources/`).
Add a `postgres` service and point bBuilder at it:

```yaml
services:
  db:
    image: postgres:16
    environment:
      POSTGRES_DB: election_ballot
      POSTGRES_USER: pbss
      POSTGRES_PASSWORD: change-me
    volumes:
      - pbss_pg_data:/var/lib/postgresql/data

  bbuilder:
    build:
      context: .
      dockerfile: bBuilder/Dockerfile
      args:
        APP_DIR: bBuilder
        CORE_DIR: builder-core
        JAR_NAME: bBuilder
    depends_on:
      - db
    environment:
      SPRING_PROFILES_ACTIVE: postgres
      DB_USER: pbss
      DB_PASS: change-me
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/election_ballot
    ports:
      - "8080:8080"

volumes:
  pbss_pg_data:
```

SQLite (the default) needs no extra service and is sufficient for most
elections — only reach for Postgres if you specifically need concurrent
write access from multiple bBuilder processes against one database, which
a single-container deployment doesn't need.

## bScanner in a container: a real hardware caveat

bScanner drives a **physically attached** scanner, which containers don't
have direct access to by default:

- **NAPS2** (the macOS/Windows backend) doesn't run on Linux at all, so it
  cannot be used from inside a Linux container regardless of device
  access — if you're on macOS/Windows and want to drive a scanner, run
  `bScanner` (or `blScanner`/`scanner`) directly on the host instead of in
  Docker.
- **`scanimage`** (the Linux/SANE backend) *can* work in a container, but
  the container needs the scanner's USB device passed through explicitly,
  e.g. `docker run --device=/dev/bus/usb ...` (exact device path depends
  on your host and scanner), plus SANE installed in the image and
  configured for that device. This is host- and hardware-specific enough
  that it's not scripted here — treat containerizing bScanner as
  optional, and prefer running it directly on the counting-station host
  if it turns out to be more trouble than it's worth.
- **Custom command backend** — if your scanning setup already deposits
  images into a shared directory some other way (a network-free process
  external to pbss), you likely don't need bScanner running in a
  container at all: any tool that writes into the `cast_ballot_scans/`
  volume bCounter reads from works, containerized or not.
