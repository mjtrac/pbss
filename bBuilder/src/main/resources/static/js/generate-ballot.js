// Track the last successfully generated combination ID
var lastGeneratedComboId = null;

function closeFilesDialog() {
  const dlg = document.getElementById('filesDialog');
  if (dlg) {
    dlg.close();
    // Return focus to the generate button after dismissal
    const gen = document.getElementById('generateBtn');
    if (gen) gen.focus();
  }
}

// Chrome: when the new PDF tab steals focus, the modal loses
// pointer interactivity. Re-focus the OK button whenever the
// page becomes visible again while the dialog is open.
document.addEventListener('visibilitychange', function () {
  if (document.visibilityState === 'visible') {
    const dlg = document.getElementById('filesDialog');
    const ok  = document.getElementById('filesDialogOk');
    if (dlg && dlg.open && ok) {
      // Force Chrome to re-activate the modal by briefly moving
      // focus to the document body then to the button.
      document.body.focus();
      setTimeout(() => { ok.focus(); }, 50);
    }
  }
});

// Also re-focus when the window regains focus (covers tab-switch back)
window.addEventListener('focus', function () {
  const dlg = document.getElementById('filesDialog');
  const ok  = document.getElementById('filesDialogOk');
  if (dlg && dlg.open && ok) {
    setTimeout(() => { ok.focus(); }, 50);
  }
});

function generateBallot() {
  const combinationId = document.getElementById('combinationId').value;
  if (!combinationId) { alert('Please select a ballot combination.'); return; }
  const copies = document.getElementById('copies').value || '1';
  const csrf   = document.getElementById('csrfToken').value;
  const csrfH  = document.getElementById('csrfHeader').value;
  const btn = document.getElementById('generateBtn');
  btn.disabled = true;
  btn.textContent = 'Generating…';
  const langSel  = document.getElementById('langSelect');
  const langCode = langSel ? langSel.value : 'en';
  const formData = new FormData();
  formData.append('combinationId', combinationId);
  formData.append('copies', copies);
  formData.append('lang', langCode);
  formData.append('_csrf', csrf);
  fetch('/print/generate', {
    method: 'POST',
    headers: { [csrfH]: csrf },
    body: formData
  })
  .then(response => {
    const filesHeader = response.headers.get('X-Ballot-Files') || '';
    const files = filesHeader ? filesHeader.split('|') : [];
    return response.blob().then(blob => ({ blob, files }));
  })
  .then(({ blob, files }) => {
    const url = URL.createObjectURL(blob);
    window.open(url, '_blank');
    if (files.length > 0) {
      const list = document.getElementById('filesDialogList');
      list.innerHTML = '';
      files.forEach(f => {
        const li = document.createElement('li');
        li.textContent = f;
        list.appendChild(li);
      });
      // Record which combination was just generated
      lastGeneratedComboId = document.getElementById('combinationId').value;
      const note = document.getElementById('exportNote');
      if (note) note.style.display = 'none';
      const dlg = document.getElementById('filesDialog');
      dlg.showModal();
      // Focus the OK button immediately; visibilitychange will re-focus
      // it when the user switches back from the PDF tab.
      setTimeout(() => {
        const ok = document.getElementById('filesDialogOk');
        if (ok) ok.focus();
      }, 100);
    }
  })
  .catch(err => {
    alert('Error generating ballot: ' + err.message);
  })
  .finally(() => {
    btn.disabled = false;
    btn.textContent = 'Generate PDF';
  });
}

document.addEventListener('DOMContentLoaded', function () {
  var btn = document.getElementById('generateBtn');
  if (btn) btn.addEventListener('click', generateBallot);

  // Wire OK button via addEventListener (more reliable than onclick in Chrome
  // when the dialog is open and the document has lost focus to another tab).
  var ok = document.getElementById('filesDialogOk');
  if (ok) ok.addEventListener('click', closeFilesDialog);

  // Also allow clicking the dialog backdrop to close it
  var dlg = document.getElementById('filesDialog');
  if (dlg) {
    dlg.addEventListener('click', function (e) {
      if (e.target === dlg) closeFilesDialog();
    });
  }
});
