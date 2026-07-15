/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 * viewer-index.js — Ballot list page: name filter, SQL filter tabs, scan banner.
 * External script to satisfy Content-Security-Policy script-src 'self'.
 */
(function () {
  'use strict';

  // ── Filter history (persisted in localStorage) ──────────────────────────────
  const HISTORY_KEY = 'bviewer_filter_history';
  const FILTER_KEY  = 'bviewer_filter_last';
  const MAX_HISTORY = 10;

  function loadHistory() {
    try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]'); }
    catch { return []; }
  }
  function saveHistory(hist) {
    try { localStorage.setItem(HISTORY_KEY, JSON.stringify(hist)); } catch {}
  }
  function pushHistory(value) {
    if (!value) return;
    let hist = loadHistory().filter(h => h !== value);
    hist.unshift(value);
    if (hist.length > MAX_HISTORY) hist = hist.slice(0, MAX_HISTORY);
    saveHistory(hist);
  }
  function saveLastFilter(value) {
    try { localStorage.setItem(FILTER_KEY, value); } catch {}
  }
  function loadLastFilter() {
    try { return localStorage.getItem(FILTER_KEY) || ''; } catch { return ''; }
  }

  function escHtml(s) {
    return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  function hideDropdown() {
    const dd = document.getElementById('historyDropdown');
    if (dd) dd.style.display = 'none';
  }

  function renderDropdown() {
    const hist = loadHistory();
    const dd   = document.getElementById('historyDropdown');
    if (!dd) return;
    if (!hist.length) { dd.style.display = 'none'; return; }
    dd.innerHTML = hist.map((h, i) =>
      `<li data-idx="${i}"
           style="padding:.4rem .75rem;cursor:pointer;font-size:.85rem;
                  border-bottom:1px solid #334155;color:#cbd5e1">${escHtml(h)}</li>`
    ).join('');
    dd.querySelectorAll('li').forEach(li => {
      li.addEventListener('mousedown', function () {
        const val = loadHistory()[+this.dataset.idx] || '';
        const si  = document.getElementById('searchInput');
        if (si) { si.value = val; saveLastFilter(val); hideDropdown(); filterTable(); }
      });
      li.addEventListener('mouseover', function () { this.style.background = '#334155'; });
      li.addEventListener('mouseout',  function () { this.style.background = ''; });
    });
    dd.style.display = 'block';
  }

  function globToRegex(pattern) {
    const re = pattern
      .replace(/\.(?![*?])/g, '\\.')
      .replace(/[?]/g, '.')
      .replace(/[*]/g, '.*');
    const suffix = (pattern.includes('/') && !pattern.endsWith('*')) ? '.*' : '';
    return pattern.includes('/')
      ? new RegExp(re + suffix + '$', 'i')
      : new RegExp(re, 'i');
  }

  function filterTable() {
    const si  = document.getElementById('searchInput');
    const raw = si ? si.value.trim() : '';
    const q   = raw.toLowerCase();
    let testFn;
    if (q === '') {
      testFn = () => true;
    } else if (q.includes('*') || q.includes('?')) {
      const re = globToRegex(q);
      testFn = (name, path) => re.test(path) || re.test(name);
    } else {
      testFn = (name, path) => path.includes(q) || name.includes(q);
    }
    document.querySelectorAll('#imageTable tr').forEach(row => {
      const name = (row.cells[1]?.textContent ?? '').toLowerCase();
      const path = (row.dataset.path ?? name).toLowerCase();
      row.style.display = testFn(name, path) ? '' : 'none';
    });
  }

  // ── Tab switching ────────────────────────────────────────────────────────────
  function switchTab(which) {
    const panelGlob = document.getElementById('panelGlob');
    const panelSql  = document.getElementById('panelSql');
    const tabGlob   = document.getElementById('tabGlob');
    const tabSql    = document.getElementById('tabSql');
    if (!panelGlob || !panelSql) return;

    const showGlob = (which === 'glob');
    panelGlob.style.display = showGlob ? '' : 'none';
    panelSql.style.display  = showGlob ? 'none' : '';

    if (tabGlob) {
      tabGlob.style.background  = showGlob ? '#334155' : '#0a0f1a';
      tabGlob.style.color       = showGlob ? '#e2e8f0' : '#475569';
      tabGlob.style.fontWeight  = showGlob ? '600' : 'normal';
      tabGlob.style.borderColor = showGlob ? '#475569' : '#334155';
    }
    if (tabSql) {
      tabSql.style.background  = showGlob ? '#0a0f1a' : '#334155';
      tabSql.style.color       = showGlob ? '#475569' : '#e2e8f0';
      tabSql.style.fontWeight  = showGlob ? 'normal' : '600';
      tabSql.style.borderColor = showGlob ? '#334155' : '#475569';
    }
  }

  // ── Scan-in-progress banner ──────────────────────────────────────────────────
  function checkScanStatus() {
    fetch('/progress', { credentials: 'include' })
      .then(r => r.json())
      .then(d => {
        const b = document.getElementById('scanBanner');
        if (b) b.style.display = d.scanning ? 'block' : 'none';
      })
      .catch(() => {});
  }

  // ── DOM ready ────────────────────────────────────────────────────────────────
  document.addEventListener('DOMContentLoaded', function () {

    // Restore last glob filter
    const si = document.getElementById('searchInput');
    if (si) {
      const last = loadLastFilter();
      if (last) { si.value = last; filterTable(); }

      si.addEventListener('input', function () {
        saveLastFilter(this.value);
        filterTable();
      });
      si.addEventListener('click', function () {
        if (loadHistory().length) renderDropdown();
      });
      si.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
          const val = this.value.trim();
          if (val) pushHistory(val);
          hideDropdown();
        } else if (e.key === 'Escape') {
          hideDropdown();
        } else if (e.key === 'ArrowDown') {
          renderDropdown();
        }
      });
    }

    // History toggle button
    const ht = document.getElementById('historyToggle');
    if (ht) {
      ht.style.display = loadHistory().length ? 'block' : 'none';
      ht.addEventListener('click', function () {
        const dd = document.getElementById('historyDropdown');
        if (dd && (dd.style.display === 'none' || !dd.style.display)) {
          renderDropdown();
        } else {
          hideDropdown();
        }
      });
    }

    // Close dropdown on outside click
    document.addEventListener('click', function (e) {
      if (!e.target.closest('#panelGlob')) hideDropdown();
    });

    // Tab buttons
    document.querySelectorAll('.filter-tab').forEach(btn => {
      btn.addEventListener('click', function () {
        switchTab(this.dataset.tab);
      });
    });

    // Restore active tab if SQL filter was active
    const activeFilterType = document.querySelector('input[name="filterType"]');
    if (activeFilterType && activeFilterType.value === 'sql') {
      switchTab('sql');
    } else {
      switchTab('glob');
    }

    // Apply glob button
    const applyBtn = document.querySelector('[data-action="applyGlob"]');
    if (applyBtn) {
      applyBtn.addEventListener('click', function () {
        const val = si ? si.value.trim() : '';
        if (val) pushHistory(val);
        document.getElementById('globFormValue').value = val;
        document.getElementById('globForm').submit();
      });
    }

    // Clear filter button
    const clearBtn = document.querySelector('[data-action="clearFilter"]');
    if (clearBtn) {
      clearBtn.addEventListener('click', function () {
        if (si) { si.value = ''; saveLastFilter(''); }
        hideDropdown();
        filterTable();
      });
    }

    // Scan banner
    checkScanStatus();
    setInterval(checkScanStatus, 5000);
  });

}());
