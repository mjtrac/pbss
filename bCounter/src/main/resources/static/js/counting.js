/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 * scanning.js — bCounter scanning progress page.
 * CSRF token read from meta tags to avoid th:inline="javascript".
 */
(function () {
  'use strict';

  // CSRF: read from <meta> tags injected by Thymeleaf
  function getCsrfHeader() {
    var m = document.querySelector('meta[name="_csrf_header"]');
    return m ? m.getAttribute('content') : 'X-CSRF-TOKEN';
  }
  function getCsrfToken() {
    var m = document.querySelector('meta[name="_csrf"]');
    return m ? m.getAttribute('content') : '';
  }

  let pollTimer;
  const shownDups    = new Set();
  const shownReview  = new Set();

  function poll() {
    fetch('/progress', { credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        document.getElementById('scanned').textContent   = data.processed ?? 0;
        document.getElementById('total').textContent     = data.total ?? '…';
        document.getElementById('remaining').textContent =
          data.total != null ? (data.total - data.processed) : '…';
        const pct = data.total > 0
          ? Math.round(100 * data.processed / data.total) : 0;
        document.getElementById('pbar').style.width = pct + '%';
        const path = data.current || '';
        document.getElementById('currentPath').textContent =
          path ? path : 'Starting…';
        const yamlEl = document.getElementById('yamlPath');
        const yaml = data.yamlSource || '';
        if (yamlEl) yamlEl.textContent = yaml ? 'YAML: ' + yaml : '';

        if (data.error) {
          clearInterval(pollTimer);
          document.getElementById('errorMsg').style.display  = 'block';
          document.getElementById('errorText').textContent   = data.error;
          return;
        }
        if (data.duplicates && data.duplicates.length > 0)
          showDuplicates(data.duplicates);
        if (data.reviewRequired && data.reviewRequired.length > 0)
          showReviewRequired(data.reviewRequired);
        if (data.stopped) { onStopped(); return; }
        if (data.complete || data.pauseForResults) {
          if (data.complete)
            document.getElementById('doneMsg').style.display = 'block';
          clearInterval(pollTimer);
          fetch('/resume', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { [getCsrfHeader()]: getCsrfToken() }
          }).finally(function () { window.location.href = '/results'; });
        }
      })
      .catch(function () {});
  }

  function showDuplicates(paths) {
    const warn = document.getElementById('dupWarning');
    const list = document.getElementById('dupList');
    let added = false;
    paths.forEach(function (p) {
      if (!shownDups.has(p)) {
        shownDups.add(p);
        const li = document.createElement('li');
        li.textContent = p;
        list.appendChild(li);
        added = true;
      }
    });
    if (added) warn.style.display = 'block';
  }

  function showReviewRequired(paths) {
    const warn = document.getElementById('reviewWarning');
    const list = document.getElementById('reviewList');
    let added = false;
    paths.forEach(function (p) {
      if (!shownReview.has(p)) {
        shownReview.add(p);
        const li = document.createElement('li');
        li.textContent = p;
        list.appendChild(li);
        added = true;
      }
    });
    if (added) warn.style.display = 'block';
  }

  function onStopped() {
    clearInterval(pollTimer);
    document.getElementById('stopBtn').style.display      = 'none';
    var resumeBtn = document.getElementById('resumeBtn');
    if (resumeBtn) resumeBtn.style.display = '';
    document.getElementById('restartBtn').style.display   = '';
    document.getElementById('resultsBtn').style.display   = '';
    document.getElementById('viewerBtn').style.display    = '';
    var stoppedMsg = document.getElementById('stoppedMsg');
    if (stoppedMsg) stoppedMsg.style.display = 'block';
    document.getElementById('currentPath').textContent    = 'Scanning stopped by user.';
    // Poll slowly so state stays fresh if user navigates away and back
    pollTimer = setInterval(poll, 5000);
  }

  // Expose functions needed by buttons in the template
  window.resumeScan = function () {
    // Clear stopped UI state
    var stoppedMsg = document.getElementById('stoppedMsg');
    if (stoppedMsg) stoppedMsg.style.display = 'none';
    var resumeBtn = document.getElementById('resumeBtn');
    if (resumeBtn) resumeBtn.style.display = 'none';
    document.getElementById('restartBtn').style.display = 'none';
    document.getElementById('resultsBtn').style.display = 'none';
    document.getElementById('viewerBtn').style.display  = 'none';
    var stopBtn = document.getElementById('stopBtn');
    stopBtn.style.display = '';
    stopBtn.disabled = false;
    stopBtn.textContent = '\u25fc Stop Scanning';
    document.getElementById('currentPath').textContent = 'Resuming…';
    fetch('/resume-scan', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { [getCsrfHeader()]: getCsrfToken() }
    }).catch(function () {});
    // Restart polling
    clearInterval(pollTimer);
    pollTimer = setInterval(poll, 1500);
  };

  window.stopScan = function () {
    const btn = document.getElementById('stopBtn');
    btn.disabled    = true;
    btn.textContent = 'Stopping…';
    fetch('/stop', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { [getCsrfHeader()]: getCsrfToken() }
    }).catch(function () {});
  };

  window.clearReviewWarning = function () {
    document.getElementById('reviewWarning').style.display = 'none';
    document.getElementById('reviewList').innerHTML = '';
    shownReview.clear();
  };

  window.clearDupWarning = function () {
    document.getElementById('dupWarning').style.display = 'none';
    document.getElementById('dupList').innerHTML = '';
    shownDups.clear();
  };

  document.addEventListener('DOMContentLoaded', function () {
    poll();
    pollTimer = setInterval(poll, 1500);
    // Wire data-action buttons
    document.querySelectorAll('[data-action]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var fn = window[btn.getAttribute('data-action')];
        if (typeof fn === 'function') fn();
      });
    });
  });
}());
