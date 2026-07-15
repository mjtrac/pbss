/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 * generate-all.js — bulk ballot generation page JS.
 * External to satisfy Content-Security-Policy script-src 'self'.
 */
(function () {
  'use strict';

  let printersLoaded = false;

  function togglePrinter(checked) {
    const sel = document.getElementById('printerSelector');
    sel.style.display = checked ? 'block' : 'none';
    if (checked && !printersLoaded) loadPrinters();
  }

  function loadPrinters() {
    fetch('/print/printers', { credentials: 'include' })
      .then(r => r.json())
      .then(data => {
        const select = document.getElementById('printerName');
        select.innerHTML = '';
        if (!data.printers || data.printers.length === 0) {
          select.innerHTML =
            '<option value="">— no printers found on server —</option>';
          return;
        }
        data.printers.forEach(p => {
          const opt = document.createElement('option');
          opt.value = p;
          opt.textContent = p;
          if (p === data.defaultPrinter) opt.selected = true;
          select.appendChild(opt);
        });
        printersLoaded = true;
      })
      .catch(() => {
        document.getElementById('printerName').innerHTML =
          '<option value="">— could not load printers —</option>';
      });
  }

  function showSpinner() {
    document.getElementById('spinner').style.display = 'inline-block';
    document.getElementById('spinnerLabel').style.display = 'inline';
  }

  document.addEventListener('DOMContentLoaded', function () {
    // Wire printer checkbox
    const printNow = document.getElementById('printNow');
    if (printNow) {
      printNow.addEventListener('change', function () {
        togglePrinter(this.checked);
      });
    }

    // Wire spinner on submit button
    const submitBtn = document.querySelector('#genForm button[type="submit"]');
    if (submitBtn) {
      submitBtn.addEventListener('click', showSpinner);
    }

    // Clear printer selection when printing unchecked
    const genForm = document.getElementById('genForm');
    if (genForm) {
      genForm.addEventListener('submit', function () {
        const cb = document.getElementById('printNow');
        if (!cb.checked) {
          document.getElementById('printerName').disabled = true;
        }
      });
    }
  });
}());
