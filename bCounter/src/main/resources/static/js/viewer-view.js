/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 * viewer-view.js — ballot view overlay rendering + scan banner.
 * Server data read from #ballotData data-* attributes.
 */
(function () {
  'use strict';

  // ── Read server data from data-* attributes ───────────────────────────
  var bd            = document.getElementById('ballotData');
  var BOXES         = bd ? JSON.parse(bd.getAttribute("data-boxes") || "[]") : [];
  var IMAGE_DPI     = bd ? parseFloat(bd.getAttribute("data-image-dpi") || "300") : 300;
  var WARP_DPI      = bd ? parseFloat(bd.getAttribute("data-warp-dpi") || "300") : 300;
  var CANONICAL_W   = bd ? parseFloat(bd.getAttribute("data-canonical-w") || "0") : 0;
  var CANONICAL_H   = bd ? parseFloat(bd.getAttribute("data-canonical-h") || "0") : 0;
  var CORNER_MARKS  = bd ? bd.getAttribute("data-corner-marks") : null;
  var PREV_ID       = bd ? bd.getAttribute("data-prev-id") : null;
  var NEXT_ID       = bd ? bd.getAttribute("data-next-id") : null;
  var WAS_ROTATED   = bd ? bd.getAttribute("data-was-rotated") === "true" : false;

  // ── Homography: canonical → image ────────────────────────────────────────
  // Boxes are stored as (absLeft, absTop) in canonical (warped) pixel coords.
  // We need to map them back to original image pixel coords using H⁻¹.
  // H maps canonical (0..W, 0..H) to the detected corner quadrilateral.
  // We compute H from the stored corner marks and use it to transform each box corner.

  function parseCorners() {
    if (!CORNER_MARKS) return null;
    const parts = CORNER_MARKS.split(',').map(Number);
    if (parts.length < 8) return null;
    return [
      {x: parts[0], y: parts[1]},   // TL
      {x: parts[2], y: parts[3]},   // TR
      {x: parts[4], y: parts[5]},   // BR
      {x: parts[6], y: parts[7]},   // BL
    ];
  }

  function computeHomography(srcPts, dstPts) {
    // DLT algorithm: solve A·h = 0 via SVD approximation using Gaussian elimination.
    // Maps src → dst.  Here: canonical corners → image corners.
    const N = 4;
    const A = [];
    for (let i = 0; i < N; i++) {
      const {x: sx, y: sy} = srcPts[i];
      const {x: dx, y: dy} = dstPts[i];
      A.push([-sx, -sy, -1,  0,   0,  0, dx*sx, dx*sy, dx]);
      A.push([  0,   0,  0, -sx, -sy, -1, dy*sx, dy*sy, dy]);
    }
    // Use pseudo-SVD via least squares — JS doesn't have built-in SVD,
    // so use the numeric approach: H from 4-point correspondence directly.
    return solveHomography4pt(srcPts, dstPts);
  }

  function solveHomography4pt(src, dst) {
    // Build 8×8 system and solve via Gaussian elimination for the 8 unknowns of H
    // (H is a 3×3 matrix with h[2][2]=1, so 8 free variables)
    const A = [], b = [];
    for (let i = 0; i < 4; i++) {
      const {x: sx, y: sy} = src[i];
      const {x: dx, y: dy} = dst[i];
      A.push([ sx, sy, 1,  0,  0, 0, -dx*sx, -dx*sy]);  b.push(dx);
      A.push([  0,  0, 0, sx, sy, 1, -dy*sx, -dy*sy]);  b.push(dy);
    }
    const h = gaussElim(A, b);
    if (!h) return null;
    return [
      [h[0], h[1], h[2]],
      [h[3], h[4], h[5]],
      [h[6], h[7], 1.0 ],
    ];
  }

  function gaussElim(A, b) {
    const n = A.length;
    const M = A.map((row, i) => [...row, b[i]]);
    for (let col = 0; col < n; col++) {
      let maxRow = col;
      for (let row = col + 1; row < n; row++)
        if (Math.abs(M[row][col]) > Math.abs(M[maxRow][col])) maxRow = row;
      [M[col], M[maxRow]] = [M[maxRow], M[col]];
      if (Math.abs(M[col][col]) < 1e-12) return null;
      for (let row = col + 1; row < n; row++) {
        const f = M[row][col] / M[col][col];
        for (let k = col; k <= n; k++) M[row][k] -= f * M[col][k];
      }
    }
    const x = new Array(n).fill(0);
    for (let i = n - 1; i >= 0; i--) {
      x[i] = M[i][n];
      for (let j = i + 1; j < n; j++) x[i] -= M[i][j] * x[j];
      x[i] /= M[i][i];
    }
    return x;
  }

  function applyH(H, cx, cy) {
    const w = H[2][0]*cx + H[2][1]*cy + H[2][2];
    return {
      x: (H[0][0]*cx + H[0][1]*cy + H[0][2]) / w,
      y: (H[1][0]*cx + H[1][1]*cy + H[1][2]) / w,
    };
  }

  // Pre-compute homography once after page load
  let H_can2img = null;
  function buildHomography() {
    let corners = parseCorners();
    if (!corners || CANONICAL_W <= 0 || CANONICAL_H <= 0) {
      H_can2img = null;
      return;
    }

    // If the ballot was rotated 180° by the counter before processing,
    // the corner marks are stored in the rotated (upright) image pixel space.
    // But the canvas sits over the original (unrotated) image, so we must
    // map the corners back to the original image coordinate space by
    // reflecting through the image centre: (x,y) → (W-x, H-y).
    if (WAS_ROTATED) {
      corners = corners.map(c => ({
        x: natW - c.x,
        y: natH - c.y
      }));
    }

    const srcPts = [
      {x: 0,           y: 0},
      {x: CANONICAL_W, y: 0},
      {x: CANONICAL_W, y: CANONICAL_H},
      {x: 0,           y: CANONICAL_H},
    ];
    H_can2img = computeHomography(srcPts, corners);
  }

  // Transform a canonical bounding box to an image-space axis-aligned bounding rect
  function canonicalBoxToImageRect(absLeft, absTop, w, h) {
    if (!H_can2img) {
      // Fallback: scale by dpi ratio (approximate)
      const ratio = IMAGE_DPI / (WARP_DPI || IMAGE_DPI);
      return {x: absLeft * ratio, y: absTop * ratio,
              w: w * ratio,       h: h * ratio};
    }
    const corners = [
      applyH(H_can2img, absLeft,     absTop),
      applyH(H_can2img, absLeft + w, absTop),
      applyH(H_can2img, absLeft + w, absTop + h),
      applyH(H_can2img, absLeft,     absTop + h),
    ];
    const xs = corners.map(c => c.x), ys = corners.map(c => c.y);
    const minX = Math.min(...xs), maxX = Math.max(...xs);
    const minY = Math.min(...ys), maxY = Math.max(...ys);
    return {x: minX, y: minY, w: maxX - minX, h: maxY - minY,
            corners};  // also expose corners for precise quad drawing
  }

  const img      = document.getElementById('ballotImg');
  const canvas   = document.getElementById('overlayCanvas');
  const wrapper  = document.getElementById('ballotWrapper');
  const pane     = document.getElementById('canvasPane');
  const ctx      = canvas.getContext('2d');

  // Persist viewer state across page navigations using sessionStorage
  const ZOOM_KEY   = 'viewer_zoom';
  const AUTO_KEY   = 'viewer_auto';    // '1' if auto-advance is on
  const SECS_KEY   = 'viewer_secs';    // seconds value
  const SCROLL_KEY = 'viewer_scroll';  // 'scrollLeft,scrollTop'
  let scale    = parseFloat(sessionStorage.getItem(ZOOM_KEY) || '0') || 0;
  let natW     = 0, natH = 0;
  let activeId = null;
  let autoTimer = null;
  let autoElapsed = 0;

  // ── Colors ────────────────────────────────────────────────────────────────
  const STATUS_COLOR = {
    'VOTED':     '#22c55e',
    'OVERVOTED': '#eab308',
    'UNMARKED':  '#3b82f6',
  };
  function colorFor(status) {
    return STATUS_COLOR[status] || '#3b82f6';
  }

  // ── Image load ────────────────────────────────────────────────────────────
  function onImageLoad() {
    natW = img.naturalWidth;
    natH = img.naturalHeight;
    // Note: WAS_ROTATED ballots are served as-is (upside-down pixel data).
    // The corner reflection in buildHomography() maps canonical coords to
    // the original unrotated pixel space, so overlays are correct when the
    // image is displayed in its natural orientation. We do NOT CSS-rotate
    // the image because the canvas coordinate space would not match.
    buildHomography();
    const saved = parseFloat(sessionStorage.getItem(ZOOM_KEY) || '0');
    if (saved > 0) {
      requestAnimationFrame(() => {
        setZoom(saved, false);
        restoreScroll();
        restoreAutoTimer();
      });
    } else {
      fitToPane();   // deferred internally via rAF; scroll restored inside fitToPane
    }
  }

  // Measure actual header + nav heights and update the CSS variable so
  // the pane fills exactly the right amount of vertical space.
  function updatePaneHeight() {
    const header = document.querySelector('.app-header');
    const nav    = document.getElementById('navBar');
    const hh = header ? header.offsetHeight : 52;
    const nh = nav    ? nav.offsetHeight    : 38;
    document.documentElement.style.setProperty('--header-h', hh + 'px');
    document.documentElement.style.setProperty('--nav-h',    nh + 'px');
  }

  window.addEventListener('DOMContentLoaded', () => {
    updatePaneHeight();

    // Restore overlay state from URL params (survive page reload on navigation)
    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.get('boxes') === '0') {
      boxesVisible = false;
      document.getElementById('showBoxes').checked = false;
    }
    if (urlParams.get('names') === '0') {
      namesVisible = false;
      document.getElementById('showNames').checked = false;
    }

    // Restore auto-advance and seconds
    const savedAuto = sessionStorage.getItem(AUTO_KEY);
    const savedSecs = sessionStorage.getItem(SECS_KEY);
    const cbElem    = document.getElementById('autoAdvance');
    const secsElem  = document.getElementById('autoSecs');
    if (savedSecs && secsElem) secsElem.value = savedSecs;
    if (savedAuto === '1' && cbElem) {
      cbElem.checked = true;
      // Timer starts after image loads (in onImageLoad)
    }

    if (img.complete && img.naturalWidth > 0) onImageLoad();
  });
  window.addEventListener('resize', () => {
    updatePaneHeight();
    // Re-fit if no saved zoom
    if (!sessionStorage.getItem(ZOOM_KEY)) fitToPane();
  });

  // ── Sizing ────────────────────────────────────────────────────────────────
  function applyScale() {
    const w = Math.round(natW * scale);
    const h = Math.round(natH * scale);
    img.style.width       = w + 'px';
    img.style.height      = h + 'px';
    canvas.width          = w;
    canvas.height         = h;
    canvas.style.width    = w + 'px';
    canvas.style.height   = h + 'px';
    wrapper.style.width   = w + 'px';
    wrapper.style.height  = h + 'px';
  }

  function fitToPane() {
    if (!natW || !natH) return;
    // Defer one frame so the browser has finished layout before we measure.
    requestAnimationFrame(() => {
      const availW = pane.offsetWidth  - 24;
      const availH = pane.offsetHeight - 24;
      if (availW <= 0 || availH <= 0) return;
      scale = Math.min(availW / natW, availH / natH);
      sessionStorage.removeItem(ZOOM_KEY);
      sessionStorage.removeItem(SCROLL_KEY);  // fit resets scroll too
      document.getElementById('zoomLabel').value = Math.round(scale * 100) + '';
      applyScale();
      render();
      restoreScroll();
      restoreAutoTimer();
    });
  }

  function setZoom(z, save = true) {
    scale = Math.max(0.05, Math.min(8, z));
    if (save) sessionStorage.setItem(ZOOM_KEY, scale.toString());
    document.getElementById('zoomLabel').value = Math.round(scale * 100) + '';
    applyScale();
    render();
  }

  function zoom(delta) { setZoom(scale + delta); }

  function onZoomInput() {
    const v = parseInt(document.getElementById('zoomLabel').value, 10);
    if (!isNaN(v) && v >= 1 && v <= 800) setZoom(v / 100);
  }

  // ── Canvas drawing — always clear, then draw each visible layer ──────────
  function render() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    wrapper.querySelectorAll('.hit-box').forEach(el => el.remove());
    if (boxesVisible) renderBoxLayer();
    if (namesVisible) renderNameLayer();
    renderHitBoxes();
  }

  function toggleBoxes() {
    boxesVisible = document.getElementById('showBoxes').checked;
    render();
  }

  function toggleNames() {
    namesVisible = document.getElementById('showNames').checked;
    render();
  }

  // Each call to render() always starts with a full clear.
  // Boxes pass draws fills+borders. Names pass draws labels.
  // Calling toggleBoxes or toggleNames just calls render() — no partial updates.

  function renderBoxLayer() {
    BOXES.forEach(box => {
      const imgRect = canonicalBoxToImageRect(box.x, box.y, box.w, box.h);
      const x = imgRect.x * scale, y = imgRect.y * scale;
      const w = imgRect.w * scale, h = imgRect.h * scale;
      const color    = colorFor(box.status);
      const isActive = box.id === activeId;
      ctx.globalAlpha = isActive ? 0.35 : 0.18;
      ctx.fillStyle   = color;
      ctx.fillRect(x, y, w, h);
      ctx.globalAlpha = isActive ? 1.0 : 0.75;
      ctx.strokeStyle = color;
      ctx.lineWidth   = isActive ? 3 : 2;
      ctx.strokeRect(x, y, w, h);
      ctx.globalAlpha = 1.0;
    });
  }

  function renderNameLayer() {
    BOXES.forEach(box => {
      const imgRect = canonicalBoxToImageRect(box.x, box.y, box.w, box.h);
      const x = imgRect.x * scale, y = imgRect.y * scale;
      const w = imgRect.w * scale, h = imgRect.h * scale;
      const color    = colorFor(box.status);
      const isActive = box.id === activeId;
      const fontSize = Math.max(8, Math.min(13, h * 0.7));
      ctx.font        = `bold ${fontSize}px system-ui, sans-serif`;
      ctx.textBaseline = 'bottom';
      // For ranked-choice boxes (candidate name ends with "(Rank N)"),
      // show an abbreviated label so the narrow boxes don't overflow.
      const rankMatch = box.candidate.match(/\(Rank (\d+)\)$/);
      const label = rankMatch
          ? (rankMatch[1] === '1'
              ? box.candidate.replace(/\s*\(Rank \d+\)$/, '') + ' ▶R1'
              : 'R' + rankMatch[1])
          : box.candidate;
      const textW = ctx.measureText(label).width;
      const padX = 3, padY = 2;
      ctx.fillStyle   = color;
      ctx.globalAlpha = isActive ? 0.95 : 0.85;
      ctx.fillRect(x, y - fontSize - padY * 2, textW + padX * 2, fontSize + padY * 2);
      ctx.fillStyle   = '#000';
      ctx.globalAlpha = 1.0;
      ctx.fillText(label, x + padX, y - padY);
      ctx.globalAlpha = 1.0;
    });
  }

  function renderHitBoxes() {
    wrapper.querySelectorAll('.hit-box').forEach(el => el.remove());
    BOXES.forEach(box => {
      const imgRect = canonicalBoxToImageRect(box.x, box.y, box.w, box.h);
      const x = imgRect.x * scale, y = imgRect.y * scale;
      const w = imgRect.w * scale, h = imgRect.h * scale;
      const hit = document.createElement('div');
      hit.className   = 'hit-box';
      hit.dataset.id  = box.id;
      const rankM = box.candidate.match(/\(Rank (\d+)\)$/);
      const statusLabel = rankM
          ? (box.status === 'VOTED' ? 'MARKED at Rank ' + rankM[1] : box.status)
          : box.status;
      hit.title       = box.contest + '\n' + box.candidate + '\n' + statusLabel;
      hit.style.left   = Math.round(x) + 'px';
      hit.style.top    = Math.round(y) + 'px';
      hit.style.width  = Math.round(w) + 'px';
      hit.style.height = Math.round(h) + 'px';
      hit.addEventListener('click', () => activate(box.id));
      hit.addEventListener('mouseenter', () => {
        if (box.id !== activeId) { hoverHighlight(box.id, true); }
      });
      hit.addEventListener('mouseleave', () => {
        if (box.id !== activeId) { hoverHighlight(box.id, false); }
      });
      wrapper.appendChild(hit);
    });
  }

  // ── Hover: do a full re-render (simpler and correct with two-pass system) ──
  function hoverHighlight(id, on) {
    render();
  }

  // ── Activate a box (click on canvas hit or sidebar row) ───────────────────
  function activate(id) {
    const prev = activeId;
    activeId   = id === activeId ? null : id;   // toggle

    // Update sidebar
    if (prev !== null) {
      document.getElementById('row-' + prev)?.classList.remove('active');
    }
    if (activeId !== null) {
      const row = document.getElementById('row-' + activeId);
      if (row) {
        row.classList.add('active');
        row.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      }
      // Scroll canvas so the active box is centred
      const box = BOXES.find(b => b.id === activeId);
      if (box) {
        const cx = box.x * scale + (box.w * scale) / 2;
        const cy = box.y * scale + (box.h * scale) / 2;
        pane.scrollTo({
          left: cx - pane.clientWidth  / 2,
          top:  cy - pane.clientHeight / 2,
          behavior: 'smooth'
        });
      }
    }
    render();
  }

  // Called from sidebar
  function sidebarClick(id) { activate(id); }

  // ── Navigate to prev / next ──────────────────────────────────────────────
  // ── Jump to image by name or id ──────────────────────────────────────────
  function jumpTo() {
    const val = document.getElementById('jumpInput').value.trim();
    if (!val) return;
    if (/^\d+$/.test(val)) {
      saveNavState();
      window.location.href = '/viewer/view?id=' + val + overlayParams();
    } else {
      saveNavState();
      window.location.href = '/viewer/view?path=' + encodeURIComponent(val) + overlayParams();
    }
  }

  // ── Overlay visibility — two independent passes ──────────────────────────
  let boxesVisible = true;
  let namesVisible = true;

  // Keep old overlay toggle for keyboard shortcut (o) — toggles both
  function toggleOverlay() {
    const both = boxesVisible || namesVisible;
    boxesVisible = !both;
    namesVisible = !both;
    document.getElementById('showBoxes').checked = boxesVisible;
    document.getElementById('showNames').checked = namesVisible;
    sessionStorage.setItem('viewer_boxes', boxesVisible ? '1' : '0');
    sessionStorage.setItem('viewer_names', namesVisible ? '1' : '0');
    render();
  }

  // ── Hold button ───────────────────────────────────────────────────────────
  let held = false;

  function toggleHold() {
    held = !held;
    const btn = document.getElementById('holdBtn');
    if (held) {
      btn.textContent = '▶ Resume';
      btn.style.background = '#b45309';
      // Pause the auto-advance timer
      if (autoTimer) { clearInterval(autoTimer); autoTimer = null; }
    } else {
      btn.textContent = '⏸ Hold';
      btn.style.background = '';
      // Restart auto-advance if checkbox still checked
      if (document.getElementById('autoAdvance').checked) startAutoTimer();
    }
  }

  function saveNavState() {
    const cb   = document.getElementById('autoAdvance');
    const secs = document.getElementById('autoSecs');
    if (cb)   sessionStorage.setItem(AUTO_KEY,   cb.checked ? '1' : '0');
    if (secs) sessionStorage.setItem(SECS_KEY,   secs.value);
    sessionStorage.setItem(SCROLL_KEY, pane.scrollLeft + ',' + pane.scrollTop);
  }

  function overlayParams() {
    return '&boxes=' + (boxesVisible ? '1' : '0') + '&names=' + (namesVisible ? '1' : '0');
  }

  function goNext() {
    if (NEXT_ID !== null) { saveNavState(); window.location.href = '/viewer/view?id=' + NEXT_ID + overlayParams(); }
  }
  function goPrev() {
    if (PREV_ID !== null) { saveNavState(); window.location.href = '/viewer/view?id=' + PREV_ID + overlayParams(); }
  }

  // ── Auto-advance ──────────────────────────────────────────────────────────
  function onAutoChange() {
    const cb = document.getElementById('autoAdvance');
    sessionStorage.setItem(AUTO_KEY, cb.checked ? '1' : '0');
    held = false;  // reset hold when toggling auto
    document.getElementById('holdBtn').style.display = cb.checked ? '' : 'none';
    document.getElementById('holdBtn').textContent = '⏸ Hold';
    if (cb.checked) { startAutoTimer(); } else { stopAutoTimer(); }
  }

  function resetAutoTimer() {
    const cb = document.getElementById('autoAdvance');
    if (cb && cb.checked) { stopAutoTimer(); startAutoTimer(); }
    // Save updated seconds value
    const secs = document.getElementById('autoSecs');
    if (secs) sessionStorage.setItem(SECS_KEY, secs.value);
  }

  function startAutoTimer() {
    stopAutoTimer();
    if (NEXT_ID === null) return;   // nothing to advance to
    autoElapsed = 0;
    const secs = Math.max(1, parseInt(document.getElementById('autoSecs').value, 10) || 5);
    document.getElementById('autoProgress').style.display = 'flex';

    autoTimer = setInterval(() => {
      autoElapsed += 100;
      const pct = Math.min(100, (autoElapsed / (secs * 1000)) * 100);
      document.getElementById('autoBar').style.width = pct + '%';
      const remaining = Math.max(0, Math.ceil((secs * 1000 - autoElapsed) / 1000));
      document.getElementById('autoCountdown').textContent = remaining + 's';
      if (autoElapsed >= secs * 1000) {
        if (!held) {
          stopAutoTimer();
          goNext();
        } else {
          // Reset countdown but stay on this image
          autoElapsed = 0;
          document.getElementById('autoBar').style.width = '0%';
        }
      }
    }, 100);
  }

  function stopAutoTimer() {
    if (autoTimer) { clearInterval(autoTimer); autoTimer = null; }
    document.getElementById('autoProgress').style.display = 'none';
    document.getElementById('autoBar').style.width = '0%';
  }

  function restoreScroll() {
    const saved = sessionStorage.getItem(SCROLL_KEY);
    if (!saved) return;
    const [sl, st] = saved.split(',').map(Number);
    pane.scrollLeft = sl || 0;
    pane.scrollTop  = st || 0;
  }

  function restoreAutoTimer() {
    const cb = document.getElementById('autoAdvance');
    if (cb && cb.checked) startAutoTimer();
  }

  // ── Keyboard ──────────────────────────────────────────────────────────────
  document.addEventListener('keydown', e => {
    if (e.target.tagName === 'INPUT') return;

    // Zoom
    if (e.key === '+' || e.key === '=') { e.preventDefault(); zoom(+0.05); }
    if (e.key === '-')                  { e.preventDefault(); zoom(-0.05); }
    if (e.key === '0') { e.preventDefault(); sessionStorage.removeItem(ZOOM_KEY); fitToPane(); }
    if (e.key === '1') { e.preventDefault(); setZoom(1); }

    // Arrow keys — scroll the image pane
    const SCROLL_PX = 120;
    if (e.key === 'ArrowDown')  { e.preventDefault(); pane.scrollBy(0,  SCROLL_PX); }
    if (e.key === 'ArrowUp')    { e.preventDefault(); pane.scrollBy(0, -SCROLL_PX); }
    if (e.key === 'ArrowRight') { e.preventDefault(); pane.scrollBy( SCROLL_PX, 0); }
    if (e.key === 'ArrowLeft')  { e.preventDefault(); pane.scrollBy(-SCROLL_PX, 0); }

    // n / p — navigate to next / previous ballot
    if (e.key === 'n' || e.key === 'N') goNext();
    if (e.key === 'p' || e.key === 'P') goPrev();

    // h — hold auto-advance on current image
    if (e.key === 'h' || e.key === 'H') {
      if (document.getElementById('autoAdvance').checked) toggleHold();
    }

    // o — toggle all overlays (boxes + names together)
    if (e.key === 'o' || e.key === 'O') {
      toggleOverlay();
    }

    // ? — show help; Escape — close help
    if (e.key === '?') showHelp();
    if (e.key === 'Escape') hideHelp();
  });

  // ── Help modal ────────────────────────────────────────────────────────────
  function showHelp() {
    const m = document.getElementById('helpModal');
    m.style.display = 'flex';
    // Close on backdrop click
    m.onclick = e => { if (e.target === m) hideHelp(); };
  }

  function hideHelp() {
    document.getElementById('helpModal').style.display = 'none';
  }

  // ── Scan banner ───────────────────────────────────────────────────────────
  function checkScanStatus() {
    fetch('/progress', { credentials: 'include' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        var b = document.getElementById('scanBanner');
        if (b) b.style.display = d.scanning ? 'block' : 'none';
      })
      .catch(function () {});
  }


  // ── Wire data-action buttons and data-onchange/onload elements ───────────
  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-action]').forEach(function (el) {
      el.addEventListener('click', function (e) {
        e.preventDefault();
        var fnName = el.getAttribute('data-action');
        var fn = window[fnName];
        if (typeof fn === 'function') {
          // Pass box id if present (for sidebarClick)
          var boxId = el.getAttribute('data-box-id');
          if (boxId !== null) fn(parseInt(boxId, 10));
          else fn();
        }
      });
    });
    document.querySelectorAll('[data-onchange]').forEach(function (el) {
      el.addEventListener('change', function () {
        var fn = window[el.getAttribute('data-onchange')];
        if (typeof fn === 'function') fn();
      });
    });
    document.querySelectorAll('[data-onload]').forEach(function (el) {
      el.addEventListener('load', function () {
        var fn = window[el.getAttribute('data-onload')];
        if (typeof fn === 'function') fn();
      });
    });
    checkScanStatus();
    setInterval(checkScanStatus, 5000);
  });


  window.zoomOut       = function () { if (typeof zoom === 'function') zoom(-0.05); };
  window.zoomIn        = function () { if (typeof zoom === 'function') zoom(+0.05); };
  window.resetZoom     = function () { if (typeof setZoom === 'function') setZoom(1); };
  window.goPrev        = goPrev;
  window.goNext        = goNext;
  window.jumpTo        = jumpTo;
  window.toggleBoxes   = toggleBoxes;
  window.toggleNames   = toggleNames;
  window.onAutoChange  = onAutoChange;
  window.resetAutoTimer = resetAutoTimer;
  window.onImageLoad   = onImageLoad;
  window.onZoomInput   = onZoomInput;
  window.sidebarClick  = sidebarClick;

}());
