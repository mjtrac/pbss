# viewer

Standalone, native Swing ballot image viewer — a separate desktop program
from counter/blCounter, reading the same `~/pbss_data/db/counter_results.db`
that bCounter/blCounter/counter write to. viewer never writes to it (see
`application.properties`: `spring.jpa.hibernate.ddl-auto=none`), so it's
safe to run alongside bCounter/blCounter/counter, even mid-scan.

Entities, repositories, and `BallotViewService` come from the shared
[`counter-core`](../counter-core/README.md) module (also used by
bCounter/blCounter/counter). **Run `mvn install` in `~/pbss2/counter-core`
before building this module for the first time.**

## Why this exists

blCounter's own Ballot Viewer screen embeds the original web Viewer inside
a JavaFX `WebView` — see [`../blCounter/README.md`](../blCounter/README.md)
"Known Issues" for a documented, unresolved rendering bug in that approach
(a newly-loaded ballot image doesn't reliably paint on screen). viewer
sidesteps that whole class of bug by not using a browser engine at all: the
ballot image and its color-coded vote-indicator overlays are drawn directly
with Java2D (`Graphics2D`) in a plain `JPanel`, and the image itself is
loaded straight from disk via `ImageIO` — no HTTP layer, no WebKit, no
Prism-compositing hop.

## Scope

This is the "core viewer" — a smaller feature set than blCounter's embedded
Viewer, by design:

| Feature | viewer | blCounter's Viewer |
|---|---|---|
| Ballot list | ✅ | ✅ |
| Name/glob filter | ✅ | ✅ |
| SQL filter | ❌ | ✅ |
| Image + color-coded overlay boxes | ✅ | ✅ |
| Candidate name labels | ✅ | ✅ |
| Next/Prev navigation | ✅ | ✅ |
| Zoom / Fit | ✅ | ✅ |
| Hover/click box info | ✅ (status bar) | ✅ (tooltip) |
| RCV tabulation report | ❌ | (separate screen) |
| Scribble report | ❌ | (separate screen) |
| Auto-advance review mode | ❌ | ✅ |

## Login

Same shared `CounterUser` accounts and roles as bCounter/blCounter/counter —
a user needs the `VIEWER` or `ADMIN` role. There's no separate viewer-only
account store. Login authenticates directly against `CounterUserRepository`
+ `PasswordEncoder` in `LoginDialog` (no `UserDetailsService`), same pattern
as `counter`.

## Run from source (development)

```bash
cd viewer
./mvnw spring-boot:run
```

## Build a standalone desktop program

Same jlink + jpackage pattern as builder/counter/scanner — see the
top-level [README's Native Desktop Versions section](../README.md#native-desktop-versions)
for the general steps, or just run `../package_all_desktop.sh viewer` from
the repo root. viewer's module list drops `java.scripting` (needed
elsewhere only for JavaFX's `FXMLLoader`, which viewer doesn't use) but
otherwise matches:

```bash
jlink \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.base,java.desktop,java.sql,java.net.http,java.naming,java.security.jgss,jdk.crypto.ec,java.logging,java.management,jdk.unsupported,java.instrument \
  --output target/viewer-jre \
  --strip-debug --no-header-files --no-man-pages --compress=2

jpackage \
  --input target/lib \
  --name viewer \
  --main-jar viewer-0.9.13.jar \
  --main-class com.mjtrac.viewerui.Launcher \
  --runtime-image target/viewer-jre \
  --type app-image \
  --app-version 1.0.0 \
  --dest target/dist
```

## Implementation notes

- `com.mjtrac.counter.entity`/`repository`/`config.PasswordEncoderConfig`
  and `com.mjtrac.viewer.service.BallotViewService` come from the shared
  `counter-core` module (see above) rather than a local copy — a change to
  `BallotViewService` there is picked up here automatically after
  `mvn install`.
- `Homography.java` is a direct, tested port of `viewer-view.js`'s
  homography math (`computeHomography`/`solveHomography4pt`/`gaussElim`/
  `applyH`/`canonicalBoxToImageRect`) — see `HomographyTest.java`. Kept
  numerically equivalent so overlay boxes land in the same place here as in
  the web/WebView Viewer.
- `spring.main.headless` must be explicitly turned off
  (`SpringApplicationBuilder.headless(false)` in `ViewerApp`) — Spring
  Boot defaults `java.awt.headless=true`, which throws `HeadlessException`
  on the first `JFrame`/`JDialog` construction otherwise.
- The `spring-boot-maven-plugin`'s `<skip>true</skip>` (needed so
  `repackage` doesn't fight jpackage's flat-classpath layout) must be
  scoped to the `repackage` execution specifically, not the plugin's
  top-level `<configuration>` — the latter also silently no-ops
  `mvn spring-boot:run`, which is viewer's only dev-run path (unlike the
  JavaFX apps, there's no `javafx:run` alternative).
