const { chromium } = require('playwright');
const path = require('path');
const OUT_DIR = '/Users/mjtrac/bSuite/docs/screenshots';

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  await page.goto('http://localhost:8081/login', { waitUntil: 'networkidle' });
  await page.fill('input[name="username"]', 'admin');
  await page.fill('input[name="password"]', 'ChangeMe123!');
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle' }),
    page.click('button[type="submit"]'),
  ]);
  await page.goto('http://localhost:8081/results', { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(OUT_DIR, 'bcounter_results_initial.png'), fullPage: false });
  console.log('captured viewport-only results screenshot');
  await browser.close();
})().catch((err) => { console.error(err); process.exit(1); });
