const { chromium } = require('playwright');
const path = require('path');

const OUT_DIR = '/Users/mjtrac/bSuite/docs/screenshots';
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

async function shootCurrent(page, outfile) {
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(OUT_DIR, outfile), fullPage: true });
  console.log('captured', outfile, 'from', page.url());
}

async function shoot(page, url, outfile) {
  await page.goto(url, { waitUntil: 'networkidle' });
  await shootCurrent(page, outfile);
}

(async () => {
  const browser = await chromium.launch();

  // ---- bBuilder (8080) ----
  const bbuilder = 'http://localhost:8080';
  const bbCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const bb = await bbCtx.newPage();
  await login(bb, bbuilder);

  const bbuilderEntities = [
    ['admin/jurisdictions', 'bbuilder_01_jurisdiction.png', '+ New Jurisdiction', 'bbuilder_01_jurisdiction_new.png'],
    ['data/elections', 'bbuilder_02_election.png', '+ New Election', 'bbuilder_02_election_new.png'],
    ['data/regions', 'bbuilder_03_region.png', '+ New Region', 'bbuilder_03_region_new.png'],
    ['data/parties', 'bbuilder_04_party.png', '+ New Party', 'bbuilder_04_party_new.png'],
    ['data/ballot-types', 'bbuilder_05_ballot_type.png', '+ New Ballot Type', 'bbuilder_05_ballot_type_new.png'],
    ['data/contests', 'bbuilder_06_contest.png', '+ New Contest', 'bbuilder_06_contest_new.png'],
    ['data/ballot-templates', 'bbuilder_07_design_template.png', '+ New Template', 'bbuilder_07_design_template_new.png'],
    ['data/ballot-combinations', 'bbuilder_08_combination.png', '+ New Combination', 'bbuilder_08_combination_new.png'],
  ];

  for (const [listPath, listOut, btnText, newOut] of bbuilderEntities) {
    await shoot(bb, `${bbuilder}/${listPath}`, listOut);
    await Promise.all([
      bb.waitForNavigation({ waitUntil: 'networkidle' }),
      bb.click(`a:has-text("${btnText}")`),
    ]);
    await shootCurrent(bb, newOut);
  }
  await bbCtx.close();

  // ---- bCounter (8081) ----
  const bcounter = 'http://localhost:8081';
  const bcCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const bc = await bcCtx.newPage();
  await login(bc, bcounter);
  await shoot(bc, `${bcounter}/`, 'bcounter_initial.png');
  await shoot(bc, `${bcounter}/viewer`, 'bcounter_viewer_initial.png');
  await shoot(bc, `${bcounter}/results`, 'bcounter_results_initial.png');
  await bcCtx.close();

  // ---- bScanner (8083) ----
  const bscanner = 'http://localhost:8083';
  const bsCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const bs = await bsCtx.newPage();
  await login(bs, bscanner);
  await shoot(bs, `${bscanner}/`, 'bscanner_initial.png');
  await bsCtx.close();

  await browser.close();
  console.log('done');
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
