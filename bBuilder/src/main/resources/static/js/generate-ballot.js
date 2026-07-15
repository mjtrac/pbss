
function loadTemplatesForCombination(combinationId) {
  var tmplSel = document.getElementById('templateId');
  if (!tmplSel || !combinationId) return;
  fetch('/print/templates-for-combination?combinationId=' + combinationId,
        { credentials: 'include' })
    .then(r => r.json())
    .then(templates => {
      tmplSel.innerHTML = '';
      if (!templates.length) {
        tmplSel.innerHTML = '<option value="">— no templates found —</option>';
        return;
      }
      templates.forEach(t => {
        var opt = document.createElement('option');
        opt.value = t.id;
        opt.textContent = t.label;
        tmplSel.appendChild(opt);
      });
    })
    .catch(() => {
      tmplSel.innerHTML = '<option value="">— could not load templates —</option>';
    });
}

// Track the last successfully generated combination ID
var lastGeneratedComboId = null;

// ── Overlay open/close ────────────────────────────────────────────────────────
// Uses a plain <div> overlay instead of <dialog showModal()> to avoid the
// Chrome/Safari bug where the native focus-trap is deactivated when a new
// PDF tab opens, making the OK button unclickable.

function closeFilesDialog() {
  var dlg = document.getElementById('filesDialog');
  if (dlg) {
    dlg.style.display = 'none';
    var gen = document.getElementById('generateBtn');
    if (gen) gen.focus();
  }
}

function openFilesDialog(sentToPrinter) {
  var dlg  = document.getElementById('filesDialog');
  var note = document.getElementById('printerNote');
  if (!dlg) return;
  if (note) note.style.display = sentToPrinter ? 'block' : 'none';
  dlg.style.display = 'flex';
  setTimeout(function () {
    var ok = document.getElementById('filesDialogOk');
    if (ok) ok.focus();
  }, 50);
}

// ── Printer section ───────────────────────────────────────────────────────────

function togglePrinter(checkbox) {
  var section = document.getElementById('printerSection');
  if (!section) { console.error('[bBuilder] printerSection not found!'); return; }
  section.style.display = checkbox.checked ? 'block' : 'none';
  console.log('[bBuilder] togglePrinter checked=', checkbox.checked,
              'section display=', section.style.display);
  if (checkbox.checked) loadPrinters();
}

function loadPrinters() {
  var sel = document.getElementById('printerSelect');
  var btn = document.getElementById('generateBtn');
  if (!sel) return;
  sel.innerHTML = '<option value="">— loading printers… —</option>';
  sel.style.display = 'block';
  if (btn) btn.disabled = true;
  console.log('[bBuilder] Fetching /print/printers...');
  fetch('/print/printers', { credentials: 'include' })
    .then(function (r) {
      console.log('[bBuilder] /print/printers status:', r.status, r.url);
      if (!r.ok) throw new Error('HTTP ' + r.status + ' from ' + r.url);
      return r.json();
    })
    .then(function (data) {
      console.log('[bBuilder] Printers:', data);
      sel.innerHTML = '';
      var printers = data.printers || [];
      if (printers.length === 0) {
        var opt = document.createElement('option');
        opt.value = ''; opt.textContent = '— no printers found —';
        sel.appendChild(opt);
      } else {
        printers.forEach(function (p) {
          var opt = document.createElement('option');
          opt.value = p; opt.textContent = p;
          if (p === data.defaultPrinter) opt.selected = true;
          sel.appendChild(opt);
        });
      }
    })
    .catch(function (err) {
      console.error('[bBuilder] Printer load error:', err);
      sel.innerHTML = '<option value="">— error: ' + err.message + ' —</option>';
    })
    .finally(function () {
      if (btn) { btn.disabled = false; btn.textContent = 'Generate PDF'; }
    });
}

// ── Ballot generation ─────────────────────────────────────────────────────────

function generateBallot() {
  var combinationId = document.getElementById('combinationId').value;
  if (!combinationId) { alert('Please select a ballot combination.'); return; }

  var copies  = document.getElementById('copies').value || '1';
  var csrf    = document.getElementById('csrfToken').value;
  var csrfH   = document.getElementById('csrfHeader').value;
  var btn     = document.getElementById('generateBtn');
  btn.disabled    = true;
  btn.textContent = 'Generating\u2026';

  var langSel    = document.getElementById('langSelect');
  var langCode   = langSel ? langSel.value : 'en';
  var tmplSel    = document.getElementById('templateId');
  var templateId = tmplSel ? tmplSel.value : '';

  var enablePrinter = document.getElementById('enablePrinter');
  var printerSel    = document.getElementById('printerSelect');
  var printerName   = (enablePrinter && enablePrinter.checked && printerSel)
                      ? printerSel.value : '';

  var formData = new FormData();
  formData.append('combinationId', combinationId);
  formData.append('copies', copies);
  formData.append('lang', langCode);
  if (templateId) formData.append('templateId', templateId);
  formData.append('_csrf', csrf);
  if (printerName) formData.append('printerName', printerName);

  var headers = {};
  headers[csrfH] = csrf;

  fetch('/print/generate', {
    method: 'POST',
    headers: headers,
    body: formData
  })
  .then(function (response) {
    var filesHeader = response.headers.get('X-Ballot-Files') || '';
    var files = filesHeader ? filesHeader.split('|') : [];
    return response.blob().then(function (blob) {
      return { blob: blob, files: files };
    });
  })
  .then(function (result) {
    var url = URL.createObjectURL(result.blob);
    window.open(url, '_blank');
    if (result.files.length > 0) {
      var list = document.getElementById('filesDialogList');
      list.innerHTML = '';
      result.files.forEach(function (f) {
        var li = document.createElement('li');
        li.textContent = f;
        list.appendChild(li);
      });
      lastGeneratedComboId = document.getElementById('combinationId').value;
      var exportNote = document.getElementById('exportNote');
      if (exportNote) exportNote.style.display = 'none';
      openFilesDialog(!!printerName);
    }
  })
  .catch(function (err) {
    alert('Error generating ballot: ' + err.message);
  })
  .finally(function () {
    btn.disabled    = false;
    btn.textContent = 'Generate PDF';
  });
}

// ── Wire up on DOM ready ──────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', function () {
  var btn = document.getElementById('generateBtn');
  if (btn) btn.addEventListener('click', generateBallot);
  var combSel = document.getElementById('combinationId');
  if (combSel) {
    combSel.addEventListener('change', function() {
      loadTemplatesForCombination(this.value);
    });
    if (combSel.value) loadTemplatesForCombination(combSel.value);
  }

  var enablePrinter = document.getElementById('enablePrinter');
  if (enablePrinter) {
    enablePrinter.addEventListener('change', function () {
      togglePrinter(this);
    });
  }

  var ok = document.getElementById('filesDialogOk');
  if (ok) ok.addEventListener('click', closeFilesDialog);

  // Click backdrop to close
  var dlg = document.getElementById('filesDialog');
  if (dlg) {
    dlg.addEventListener('click', function (e) {
      if (e.target === dlg) closeFilesDialog();
    });
  }

  // Esc key closes overlay
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') {
      var d = document.getElementById('filesDialog');
      if (d && d.style.display !== 'none') closeFilesDialog();
    }
  });
});
