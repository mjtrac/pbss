/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 * results.js — bCounter results page: scan banner + embedded report sections.
 */
(function () {
  'use strict';

  function checkScanStatus() {
    fetch('/progress', { credentials: 'include' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        var banner = document.getElementById('scanBanner');
        if (banner) banner.style.display = d.scanning ? 'block' : 'none';
      })
      .catch(function () {});
  }

  function fetchEmbed(url, targetId, errorColor) {
    var el = document.getElementById(targetId);
    if (!el) return;
    fetch(url, { credentials: 'include' })
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.text();
      })
      .then(function (html) {
        html = html
          .replace(/<html[^>]*>/gi, '')
          .replace(/<\/html>/gi, '')
          .replace(/<head[\s\S]*?<\/head>/gi, '')
          .replace(/<body[^>]*>/gi, '')
          .replace(/<\/body>/gi, '');
        el.innerHTML = html;
      })
      .catch(function (err) {
        el.innerHTML = '<em style="color:' + errorColor + '">Could not load report: '
          + err.message + '</em>';
      });
  }

  document.addEventListener('DOMContentLoaded', function () {
    checkScanStatus();
    setInterval(checkScanStatus, 5000);
    fetchEmbed('/scribble-report', 'scribble-embed', '#92400e');
    fetchEmbed('/rcv-report',      'rcv-embed',      '#555');

    var printBtn = document.getElementById('printResultsBtn');
    if (printBtn) printBtn.addEventListener('click', function () { window.print(); });
  });
}());
