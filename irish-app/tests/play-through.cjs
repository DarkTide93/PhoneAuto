// Plays every lesson in the built index.html with headless Chromium and fails
// on wrong grading, a lesson that never ends, or any console/page error.
// Run from the repo root:  ./irish-app/build.sh && node irish-app/tests/play-through.cjs
// (needs the `playwright` package; set NODE_PATH if it is installed globally)
const { chromium } = require('playwright');
const path = require('path');

const URL = 'file://' + path.resolve(__dirname, '..', 'index.html') + '?test';
const errors = [];
const isFontNoise = s => /fonts\.(googleapis|gstatic)\.com|ERR_CERT|Failed to load resource/.test(s);

const cur = page => page.evaluate(() => {
  const r = window.__cf.run;
  if (!r || !r.cur) return null;
  const c = r.cur;
  return { type: c.type, id: c.id, ids: c.ids, bank: c.bank, ga: c.id ? window.__cf.gaOf(c.id) : null };
});

async function solve(page) {
  const c = await cur(page);
  if (c.type === 'intro') return page.click('#check');
  if (c.type === 'pickEn' || c.type === 'pickGa') {
    await page.click(`.opt[data-id="${c.id}"]`);
  } else if (c.type === 'tiles') {
    const used = new Set();
    for (const w of c.ga.replace(/[?!.,]/g, '').split(/\s+/).filter(Boolean)) {
      const t = c.bank.find(b => b.w === w && !used.has(b.i));
      if (!t) throw new Error(`no tile for "${w}" in ${c.id}`);
      used.add(t.i);
      await page.click(`#bank .tile[data-i="${t.i}"]`);
    }
  } else if (c.type === 'type') {
    await page.fill('#typed', c.ga);
  } else if (c.type === 'match') {
    for (const id of c.ids) {
      await page.click(`.mbtn[data-side="l"][data-id="${id}"]`);
      await page.click(`.mbtn[data-side="r"][data-id="${id}"]`);
    }
    await page.waitForSelector('#fb:not([hidden])');
    return page.click('#check');
  }
  if (c.type !== 'match') await page.click('#check');
  if (await page.isVisible('.lfoot.wrong')) throw new Error(`correct answer marked wrong: ${c.type} ${c.id}`);
  await page.click('#check');
}

(async () => {
  const proxy = process.env.HTTPS_PROXY ? { server: process.env.HTTPS_PROXY } : undefined;
  const browser = await chromium.launch({ proxy });
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, hasTouch: true });
  page.on('console', m => { if (m.type() === 'error' && !isFontNoise(m.text())) errors.push(m.text()); });
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(URL);

  const cases = [
    ['imWell', 'Táim go maith', true], ['imWell', 'tá mé go maith', true], ['imWell', 'taim go maith', true],
    ['howAreYou', 'conas taoi', true], ['howAreYou', 'Cén chaoi a bhfuil tú', true],
    ['thanks', 'go raibh maith agat!', true], ['thanks', 'go raibh maith', false],
  ];
  for (const [id, input, want] of cases) {
    const got = await page.evaluate(([i, s]) => window.__cf.grade(i, s).ok, [id, input]);
    if (got !== want) throw new Error(`grade(${id}, "${input}") = ${got}, expected ${want}`);
  }

  const lessons = await page.$$eval('.node', ns => ns.length);
  for (let i = 0; i < lessons; i++) {
    await page.click(`.node[data-i="${i}"]`);
    await page.click(`[data-act="start"][data-i="${i}"]`);
    for (let n = 0; await page.isVisible('#lesson'); n++) {
      if (n > 60) throw new Error(`lesson ${i + 1} never finished`);
      await solve(page);
    }
    await page.waitForSelector('#complete:not([hidden])');
    await page.click('[data-act="home"]');
  }
  const S = await page.evaluate(() => window.__cf.S);
  if (Object.keys(S.done).length !== lessons) throw new Error('not every lesson was marked done');
  if (errors.length) throw new Error('console errors:\n' + errors.join('\n'));
  console.log(`ok: ${lessons} lessons played, ${S.xp} XP, ${cases.length} grading cases`);
  await browser.close();
})().catch(e => { console.error('FAIL:', e.message); process.exit(1); });
