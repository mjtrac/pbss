/* Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 * candidates.js — external script to satisfy CSP script-src self
 */
document.addEventListener('DOMContentLoaded', function () {
  var writeIn = document.getElementById('writeIn');
  var printableName = document.getElementById('printableName');
  var recordName = document.getElementById('recordName');
  var clearBtn = document.querySelector('button[data-action="clear"]');

  // Sync record name from printable name if record name is empty
  if (printableName && recordName) {
    if (recordName.value.trim() === '' && printableName.value.trim() !== '') {
      recordName.value = printableName.value;
    }
    printableName.addEventListener('input', function () {
      if (recordName.value.trim() === '') recordName.value = printableName.value;
    });
  }

  // Write-in lock
  if (writeIn && printableName) {
    if (writeIn.checked) printableName.readOnly = true;
    writeIn.addEventListener('change', function () {
      if (this.checked) {
        printableName.value = 'Write-In';
        printableName.readOnly = true;
      } else {
        if (printableName.value === 'Write-In') printableName.value = '';
        printableName.readOnly = false;
      }
    });
  }

  // Clear button
  if (clearBtn) {
    clearBtn.addEventListener('click', function () {
      var form = document.getElementById('candidateForm');
      if (form) form.reset();
    });
  }
});
