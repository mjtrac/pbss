/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 * counter-index.js — bCounter main index page.
 */
document.addEventListener('DOMContentLoaded', function () {
  var cb = document.getElementById('debugCoordinates');
  if (cb) {
    var row = document.getElementById('debugFolderRow');
    if (row) {
      row.style.display = cb.checked ? '' : 'none';
      cb.addEventListener('change', function () {
        row.style.display = this.checked ? '' : 'none';
      });
    }
  }
});
