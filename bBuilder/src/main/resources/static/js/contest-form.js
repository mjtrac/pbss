/* Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 * contest-form.js — external script to satisfy CSP script-src self
 */
document.addEventListener('DOMContentLoaded', function () {

  // Show/hide ranked-choice fields based on voting method
  function applyVotingMethod(value) {
    var isRanked = (value === 'RANKED_CHOICE');
    var maxRank    = document.getElementById('maxRankField');
    var maxChoices = document.getElementById('maxChoicesField');
    if (maxRank)    maxRank.style.display    = isRanked ? 'block' : 'none';
    if (maxChoices) maxChoices.style.display = isRanked ? 'none'  : 'block';
  }

  document.querySelectorAll('input[name="votingMethod"]').forEach(function (r) {
    r.addEventListener('change', function () { applyVotingMethod(this.value); });
  });

  var checked = document.querySelector('input[name="votingMethod"]:checked');
  if (checked) applyVotingMethod(checked.value);

  // Sync record title from printable title when record title is empty
  var pt = document.getElementById('printableTitle');
  var rt = document.getElementById('recordTitle');
  if (pt && rt) {
    if (rt.value.trim() === '' && pt.value.trim() !== '') rt.value = pt.value;
    pt.addEventListener('input', function () {
      if (rt.value.trim() === '') rt.value = pt.value;
    });
  }
});
