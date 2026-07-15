const { chromium } = require('playwright');
const path = require('path');

const OUT_DIR = path.join(__dirname, 'bsuite_screenshots');
const USERNAME = 'admin';
const PASSWORD = 'ChangeMe123!';

async function login(page, baseUrl) {
  await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' });
  await page.fill('input[name="username"]', USERNAME);
  await page.fill('input[name="password"]', PASSWORD);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle' }),
    page.click('button[type="submit"]'),
  ]);
}

async function shoot(page, url, outfile) {
  await page.goto(url, { waitUntil: 'networkidle' });
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(OUT_DIR, outfile), fullPage: true });
  console.log('captured', outfile, 'from', url);
}

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();

  // ---- bBuilder (8080) ----
  const bbuilder = 'http://localhost:8080';
  await login(page, bbuilder);
  const bbuilderPages = [
    ['admin/jurisdictions', 'bbuilder_01_jurisdiction.png'],
    ['data/elections', 'bbuilder_02_election.png'],
    ['data/regions', 'bbuilder_03_region.png'],
    ['data/parties', 'bbuilder_04_party.png'],
    ['data/ballot-types', 'bbuilder_05_ballot_type.png'],
    ['data/contests', 'bbuilder_06_contest.png'],
    ['data/ballot-templates', 'bbuilder_07_design_template.png'],
    ['data/ballot-combinations', 'bbuilder_08_combination.png'],
  ];
  for (const [p, out] of bbuilderPages) {
    await shoot(page, `${bbuilder}/${p}`, out);
  }
  await context.close();

  // ---- bCounter (8081) ----
  const counterContext = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const counterPage = await counterContext.newPage();
  const bcounter = 'http://localhost:8081';
  await login(counterPage, bcounter);
  await shoot(counterPage, `${bcounter}/`, 'bcounter_initial.png');
  await counterContext.close();

  // ---- bScanner (8083) ----
  const scannerContext = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const scannerPage = await scannerContext.newPage();
  const bscanner = 'http://localhost:8083';
  await login(scannerPage, bscanner);
  await shoot(scannerPage, `${bscanner}/`, 'bscanner_initial.png');
  await scannerContext.close();

  await browser.close();
  console.log('done');
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
