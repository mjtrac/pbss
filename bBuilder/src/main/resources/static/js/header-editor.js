/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 * header-editor.js — Quill WYSIWYG / HTML code dual-pane editor for ballot header.
 * Loaded as an external script to satisfy Content-Security-Policy 'script-src self'.
 */
(function () {
  'use strict';

  // ── Quill attribute registration ────────────────────────────────────────────
  // Wrap in try/catch — a registration failure must not prevent the editor loading.
  try {
    var Parchment = Quill.import('parchment');
    var LineHeightStyle = new Parchment.StyleAttributor('lineheight', 'line-height', {
      scope: Parchment.Scope.BLOCK,
      whitelist: ['1', '1.2', '1.4', '1.5', '1.6', '1.8', '2']
    });
    Quill.register(LineHeightStyle, true);
  } catch (e) {
    console.warn('header-editor: could not register line-height attributor:', e);
  }

  try {
    var SizeStyle = Quill.import('attributors/style/size');
    SizeStyle.whitelist = ['7pt','8pt','9pt','10pt','11pt','12pt',
                           '13pt','14pt','16pt','18pt'];
    Quill.register(SizeStyle, true);
  } catch (e) {
    console.warn('header-editor: could not register size attributor:', e);
  }

  // ── Quill initialisation ────────────────────────────────────────────────────
  var quill = new Quill('#quillEditor', {
    theme:   'snow',
    modules: { toolbar: '#quillToolbar' },
    formats: ['bold','italic','underline','align','size','font','lineheight'],
  });

  // Ensure the editor surface is editable (safety net for CSP/init edge cases)
  if (quill.root) quill.root.setAttribute('contenteditable', 'true');

  var ta = document.getElementById('headerHtmlArea');

  // ── Sync flags ──────────────────────────────────────────────────────────────
  var updatingFromQuill = false;
  var updatingFromCode  = false;

  // ── HTML → Quill ─────────────────────────────────────────────────────────────
  // Quill 1.x: dangerouslyPasteHTML is required — setting innerHTML directly
  // is overwritten when Quill syncs its Delta model to the DOM.
  // Quill 2.x: innerHTML works; dangerouslyPasteHTML corrupts inline styles.
  var isQuill2 = typeof Quill.version === 'string' && Quill.version.startsWith('2');

  function htmlToQuill(html) {
    if (!html || !html.trim()) {
      quill.setContents([], 'silent');
      return;
    }
    updatingFromCode = true;
    try {
      if (isQuill2 && quill.clipboard.convert) {
        var delta = quill.clipboard.convert({ html: html });
        quill.setContents(delta, 'silent');
      } else {
        quill.clipboard.dangerouslyPasteHTML(html);
      }
    } catch (e) {
      console.warn('header-editor htmlToQuill:', e);
    }
    updatingFromCode = false;
  }

  // Initial load — use Quill 2.x API: clipboard.convert() + setContents()
  // innerHTML direct assignment gets overwritten by Quill after init.
  // Quill.find(container) retrieves the instance Quill stored on the DOM node.
  setTimeout(function () {
    var html = ta.value || ta.textContent || '';
    if (!html.trim()) return;
    try {
      var q = Quill.find(document.getElementById('quillEditor'));
      if (q && q.clipboard) {
        var delta = q.clipboard.convert({ html: html.trim() });
        q.setContents(delta, 'silent');
      }
    } catch (e) {
      console.warn('header-editor: initial load failed:', e);
    }
  }, 200);

  // ── Quill → textarea ─────────────────────────────────────────────────────────
  quill.on('text-change', function (delta, old, source) {
    if (source !== 'user') return;
    if (updatingFromCode) return;
    updatingFromQuill = true;
    var html = (typeof quill.getSemanticHTML === 'function')
               ? quill.getSemanticHTML()
               : quill.root.innerHTML;
    // Convert Quill CSS classes to inline styles for iText html2pdf compatibility
    html = html
      .replace(/class="ql-align-center"/g, 'style="text-align:center"')
      .replace(/class="ql-align-right"/g,  'style="text-align:right"')
      .replace(/class="ql-align-justify"/g, 'style="text-align:justify"')
      .replace(/<p>\s*<\/p>/g, '')
      .replace(/<p>\s*&nbsp;\s*<\/p>/g, '');
    ta.value = html;
    updatingFromQuill = false;
  });

  // ── Textarea → Quill ─────────────────────────────────────────────────────────
  ta.addEventListener('input', function () {
    if (updatingFromQuill) return;
    if (ta.value.trim()) htmlToQuill(ta.value);
  });

  // ── Snippets ──────────────────────────────────────────────────────────────────
  var HEADER_SNIPPETS = {
    'default':
      '<div style="font-family:Helvetica,Arial,sans-serif;padding:4px 0">\n' +
      '  <p style="font-size:13pt;font-weight:bold;line-height:1.6">OFFICIAL BALLOT</p>\n' +
      '  <p style="font-size:9pt;line-height:1.4">{jurisdictionName}</p>\n' +
      '  <p style="font-size:9pt;line-height:1.8">{electionName}</p>\n' +
      '  <p style="font-size:9pt;font-weight:bold;line-height:1.4">HOW TO VOTE:</p>\n' +
      '  <p style="font-size:9pt;line-height:1.4">To vote, completely fill in the {indicatorName} next to your choice.</p>\n' +
      '</div>',
    'twocol':
      '<table style="width:100%;border-collapse:collapse;font-family:Helvetica,Arial,sans-serif">\n' +
      '  <tr>\n' +
      '    <td style="width:60pt;vertical-align:middle;padding-right:6pt">\n' +
      '      <img src="data:image/png;base64,REPLACE_WITH_BASE64" style="max-width:54pt;max-height:54pt"/>\n' +
      '    </td>\n' +
      '    <td style="vertical-align:top">\n' +
      '      <p style="font-size:13pt;font-weight:bold;line-height:1.6">OFFICIAL BALLOT</p>\n' +
      '      <p style="font-size:9pt;line-height:1.4">{jurisdictionName} | {electionName}</p>\n' +
      '      <p style="font-size:9pt;line-height:1.4">Fill the {indicatorName} completely next to your choice.</p>\n' +
      '    </td>\n' +
      '  </tr>\n' +
      '</table>',
    'minimal':
      '<div style="font-family:Helvetica,Arial,sans-serif;padding:4px 0">\n' +
      '  <p style="font-size:11pt;font-weight:bold;line-height:1.6">OFFICIAL BALLOT</p>\n' +
      '  <p style="font-size:9pt;line-height:1.4">{electionName}</p>\n' +
      '</div>',
    'table':
      '<table style="width:100%;border-collapse:collapse;font-family:Helvetica,Arial,sans-serif;font-size:9pt">\n' +
      '  <tr><td colspan="2" style="font-size:13pt;font-weight:bold;line-height:1.6">OFFICIAL BALLOT</td></tr>\n' +
      '  <tr>\n' +
      '    <td style="width:50%;vertical-align:top;padding-right:6pt">\n' +
      '      <strong>{jurisdictionName}</strong><br/>{electionName}\n' +
      '    </td>\n' +
      '    <td style="vertical-align:top">\n' +
      '      <strong>HOW TO VOTE:</strong><br/>\n' +
      '      Fill the {indicatorName} next to your choice.\n' +
      '    </td>\n' +
      '  </tr>\n' +
      '</table>'
  };

  // Attach snippet buttons via event delegation — no inline onclick needed
  document.querySelectorAll('[data-snippet]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var key  = btn.getAttribute('data-snippet');
      var html = HEADER_SNIPPETS[key] || '';
      ta.value = html;
      htmlToQuill(html);
    });
  });

  // ── Header preview ────────────────────────────────────────────────────────────
  // Shows the exact string that will be submitted to the server so the user
  // can verify token placeholders are intact and alignment is inline-styled.
  var previewBtn = document.getElementById('headerPreviewBtn');
  var previewBox = document.getElementById('headerPreviewBox');
  if (previewBtn && previewBox) {
    previewBtn.addEventListener('click', function () {
      if (previewBox.style.display === 'none') {
        previewBox.textContent = ta.value;
        previewBox.style.display = 'block';
        previewBtn.textContent = 'Hide submitted value';
      } else {
        previewBox.style.display = 'none';
        previewBtn.textContent = 'Show submitted value';
      }
    });
  }

}());
