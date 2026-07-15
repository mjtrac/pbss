const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const BASE = 'http://localhost:8082';
// Try a dedicated VIEWER-role test user first, then fall back to the
// seeded admin account (ADMIN role satisfies the VIEWER access gate too).
const CREDS = [
  { user: 'viewer1', pass: 'NewViewerPass456' },
  { user: 'admin', pass: 'ChangeMe123!' },
];

const SCREENSHOT_DIR = path.join(__dirname, 'screenshots');
fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
const shot = (page, name) => page.screenshot({ path: path.join(SCREENSHOT_DIR, `${name}.png`), fullPage: true });

(async () => {
  const browser = await chromium.launch({ args: ['--no-sandbox'] });
  const page = await browser.newPage();
  const consoleErrors = [];
  page.on('console', (msg) => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });
  page.on('pageerror', (err) => consoleErrors.push('pageerror: ' + err.message));
  const log = (...a) => console.log(...a);

  let loggedInAs = null;
  for (const c of CREDS) {
    await page.goto(`${BASE}/viewer/login`);
    await page.fill('input[name="username"]', c.user);
    await page.fill('input[name="password"]', c.pass);
    await Promise.all([page.waitForNavigation(), page.click('button[type="submit"]')]);
    if (!page.url().includes('/viewer/login')) { loggedInAs = c.user; log(`LOGIN OK as ${c.user} -> ${page.url()}`); break; }
    log(`LOGIN FAILED as ${c.user}, still at ${page.url()}`);
  }
  if (!loggedInAs) { log('FATAL: no working credentials'); await browser.close(); process.exit(1); }

  // ── Index / ballot list ──────────────────────────────────────────
  await page.goto(`${BASE}/viewer/`);
  await shot(page, '01_index');
  const bodyText1 = await page.locator('body').innerText();
  const rowCount = await page.locator('#imageTable tr').count();
  log(`Index loaded. #imageTable rows: ${rowCount}. Contains "image(s)": ${bodyText1.includes('image(s)')}`);

  // ── Name/glob filter ──────────────────────────────────────────────
  await page.click('#tabGlob').catch(() => {});
  await page.fill('#searchInput', 'ballot');
  const applyBtn = page.locator('[data-action="applyGlob"]');
  let nameFilterResult = 'not attempted';
  if (await applyBtn.count() > 0) {
    await Promise.all([page.waitForNavigation().catch(() => {}), applyBtn.click()]);
    nameFilterResult = page.url();
  }
  await shot(page, '02_name_filter');
  const bodyText2 = await page.locator('body').innerText();
  log('Name/glob filter submitted. URL after:', nameFilterResult);
  log('Page mentions match count after glob filter:', /match/i.test(bodyText2), '| filterActive banner text sample:', bodyText2.slice(0, 200).replace(/\s+/g, ' '));

  // clear filter
  const clearBtn = page.locator('form[action*="clear-filter"] button');
  if (await clearBtn.count() > 0) {
    await Promise.all([page.waitForNavigation().catch(() => {}), clearBtn.click()]);
  }

  // ── SQL filter ─────────────────────────────────────────────────────
  await page.goto(`${BASE}/viewer/`);
  await page.click('#tabSql').catch(() => {});
  await page.fill('#sqlInput', "bi.id > 0");
  let sqlFilterResult = 'not attempted';
  const sqlSubmit = page.locator('#sqlForm button[type="submit"], #sqlForm input[type="submit"]');
  if (await sqlSubmit.count() > 0) {
    await Promise.all([page.waitForNavigation().catch(() => {}), sqlSubmit.first().click()]);
    sqlFilterResult = page.url();
  } else {
    // form has no visible submit button in current markup — submit directly
    await Promise.all([page.waitForNavigation().catch(() => {}), page.locator('#sqlForm').evaluate(f => f.submit())]);
    sqlFilterResult = page.url();
  }
  await shot(page, '03_sql_filter');
  const bodyText3 = await page.locator('body').innerText();
  log('SQL filter submitted. URL after:', sqlFilterResult);
  log('Page text sample after SQL filter:', bodyText3.slice(0, 300).replace(/\s+/g, ' '));

  const clearBtn2 = page.locator('form[action*="clear-filter"] button');
  if (await clearBtn2.count() > 0) {
    await Promise.all([page.waitForNavigation().catch(() => {}), clearBtn2.click()]);
  }

  // ── Open first ballot's view page ───────────────────────────────────
  await page.goto(`${BASE}/viewer/`);
  const firstLink = page.locator('#imageTable a[href*="/viewer/view"]').first();
  let viewOpened = false;
  if (await firstLink.count() > 0) {
    const href = await firstLink.getAttribute('href');
    log('First ballot view link:', href);
    await Promise.all([page.waitForNavigation().catch(() => {}), firstLink.click()]);
    viewOpened = page.url().includes('/viewer/view');
  } else {
    log('NO BALLOT ROWS FOUND — cannot test view/overlay/toggle/nav below.');
  }
  log('View page opened:', viewOpened, 'url=', page.url());
  await page.waitForTimeout(600);
  await shot(page, '04_view');

  if (viewOpened) {
    const img = page.locator('img').first();
    log('Ballot <img> src:', await img.getAttribute('src').catch(() => null));
    log('Ballot <img> natural size check (0x0 means failed to load):');
    const dims = await img.evaluate(el => ({ w: el.naturalWidth, h: el.naturalHeight })).catch(() => null);
    log(dims);

    const showBoxes = page.locator('#showBoxes');
    const showNames = page.locator('#showNames');
    log('showBoxes present:', await showBoxes.count() > 0, '| showNames present:', await showNames.count() > 0);

    if (await showBoxes.count() > 0) {
      const before = await showBoxes.isChecked();
      await showBoxes.click();
      await page.waitForTimeout(300);
      log(`showBoxes toggled ${before} -> ${await showBoxes.isChecked()}`);
      await shot(page, '05_boxes_toggled');
      await showBoxes.click();
    }
    if (await showNames.count() > 0) {
      const before = await showNames.isChecked();
      await showNames.click();
      await page.waitForTimeout(300);
      log(`showNames toggled ${before} -> ${await showNames.isChecked()}`);
      await shot(page, '06_names_toggled');
      await showNames.click();
    }

    // view -> report
    const resultsLink = page.locator('a[href*="/viewer/report"]').first();
    let toReport = false;
    if (await resultsLink.count() > 0) {
      await Promise.all([page.waitForNavigation().catch(() => {}), resultsLink.click()]);
      toReport = page.url().includes('/viewer/report');
    }
    log('Navigated view -> report:', toReport, 'url=', page.url());
    await shot(page, '07_report');

    // report -> back to index
    const backLink = page.locator('a[href$="/viewer/"], a[href="/viewer/"]').first();
    let backToIndex = false;
    if (await backLink.count() > 0) {
      await Promise.all([page.waitForNavigation().catch(() => {}), backLink.click()]);
      backToIndex = page.url().endsWith('/viewer/');
    }
    log('Navigated report -> index:', backToIndex, 'url=', page.url());
    await shot(page, '08_back_to_index');
  }

  log('CONSOLE ERRORS:', JSON.stringify(consoleErrors, null, 2));
  await browser.close();
})();
