# blCounter

JavaFX desktop conversion of bCounter — headless Spring Boot (DI/JPA/security only) driving a native JavaFX UI. See `~/pbss2/bCounter` for the original web app.

The counting engine itself (entities, repositories, model, and the 18
heavy image-processing/tallying services) lives in a shared module,
[`counter-core`](../counter-core/README.md), also used by bCounter,
viewer, and counter. **Run `mvn install` in `~/pbss2/counter-core`
before building this module for the first time.** Only `CounterUserService`
(diverges per-app — this one implements FX-login-appropriate credential
checking, not `UserDetailsService`), the `fx/` package, and
`viewer/controller`/`viewer/config` stay local to blCounter.

---

## Known Issues

### Viewer screen: image doesn't reliably render on navigation (unresolved)

**Symptom**: navigating to a ballot image in the Ballot Viewer (via "View" from the listing, or Next/Prev) alternates between showing the page text (filename/path bar) without the image, and showing a fully-rendered image with overlays — consistently one navigation behind. A stale-but-fully-rendered frame from the *previous* page has been observed lingering briefly before the new page's content takes over.

**Cause**: this is a JavaFX `WebView` frame-compositing lag, not a bug in the ballot-viewing logic itself (`viewer-view.js` and `view.html` are unmodified from the original web app — see the blCounter plan's "Viewer approach" decision for why the Viewer is embedded via `WebView` rather than reimplemented natively; a real browser doesn't have this problem, since it has no separate WebKit-to-JavaFX compositing step). Confirmed it's not a data/loading failure: the image, overlay boxes, and YAML-derived layout data all load and render correctly server-side and client-side (per request-timing logs and an observed correct render) — the rendered frame just doesn't reliably reach the screen at the right time.

**Tried and did not fix it**:
- Repaint nudges (`WebView` resize, visibility toggling) after the load-worker reaches `SUCCEEDED`.
- Forcing a second real page load (`WebEngine.reload()`) after every navigation.
- Forcing JavaFX's software rendering pipeline (`-Dprism.order=sw`) instead of GPU compositing.

**Current state**: no fix applied — `ViewerScreenController` just logs load-state transitions (`SUCCEEDED`/`FAILED`) to `bCounter.log` for future diagnosis. This remains a real, open limitation of the WebView-embedding approach on this platform/JavaFX version. Worth retrying the above workarounds (especially software rendering) on a future JavaFX/JDK upgrade, in case the underlying WebKit/Prism bug gets fixed upstream. If it needs to be fully solved rather than upgraded away, the likely next step is a native reimplementation of just this screen (dropping `WebView` for the Viewer specifically), not another compositing workaround.
