/*
 * unsaved-changes.js
 * Marks any <form class="data-form"> as dirty when a field changes.
 * Shows a browser confirmation if the user tries to navigate away with unsaved changes.
 * Cleared when the form is submitted.
 *
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
(function () {
  var dirty = false;

  function markDirty() {
    dirty = true;
    document.querySelectorAll('button[type="submit"]').forEach(function (btn) {
      if (!btn.dataset.originalText) btn.dataset.originalText = btn.textContent;
      if (!btn.textContent.includes('*'))
        btn.textContent = '* ' + btn.dataset.originalText + ' (unsaved)';
    });
  }

  function clearDirty() {
    dirty = false;
    document.querySelectorAll('button[type="submit"]').forEach(function (btn) {
      if (btn.dataset.originalText) btn.textContent = btn.dataset.originalText;
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('form.data-form').forEach(function (form) {
      form.addEventListener('input',  markDirty);
      form.addEventListener('change', markDirty);
      form.addEventListener('submit', clearDirty);
    });
    // Wire data-confirm buttons — replacement for onclick="return confirm(...)"
    document.querySelectorAll('[data-confirm]').forEach(function (btn) {
      btn.addEventListener('click', function (e) {
        if (!window.confirm(btn.getAttribute('data-confirm'))) e.preventDefault();
      });
    });
  });

  window.addEventListener('beforeunload', function (e) {
    if (dirty) {
      e.preventDefault();
      e.returnValue = 'You have unsaved changes. Leave anyway?';
      return e.returnValue;
    }
  });
})();
