/* Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 * ballot-combination-form.js — external script to satisfy CSP script-src self
 */
document.addEventListener('DOMContentLoaded', function () {
  var electionSelect = document.querySelector('select[name="electionId"]');
  if (electionSelect) {
    electionSelect.addEventListener('change', function () {
      var electionId = this.value;
      if (!electionId) return;
      var idField = document.querySelector('input[name="id"]');
      var idParam = idField && idField.value ? '&id=' + idField.value : '';
      window.location.href = '/data/ballot-combinations/for-election?electionId='
                             + electionId + idParam;
    });
  }
});
