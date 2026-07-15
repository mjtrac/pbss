/* Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 * region-form.js — extracted from template to satisfy CSP script-src self
 */
function onRegionTypeChange(radio) {
  var isGroup = (radio.value === 'PRECINCT_GROUP');
  document.getElementById('groupTypeField').style.display = isGroup ? 'block' : 'none';
}
window.addEventListener('DOMContentLoaded', function () {
  var checked = document.querySelector('input[name="regionType"]:checked');
  if (checked) onRegionTypeChange(checked);
});
