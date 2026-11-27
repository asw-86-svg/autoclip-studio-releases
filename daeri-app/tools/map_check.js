const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const A = path.join(__dirname, '..', 'app/src/main/assets');
const KEY = process.argv[2] || '';
const OUT = process.argv[3] || 'map.png';
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: +(process.env.MW || 400), height: +(process.env.MH || 330) }, deviceScaleFactor: +(process.env.DSF || 2) });
  const blocked = new Set();
  p.on('requestfailed', r => blocked.add(new URL(r.url()).host + ' ' + (r.failure()||{}).errorText));
  p.on('pageerror', e => console.log('ERR', e.message));
  await p.route('https://appassets.androidplatform.net/**', r => {
    const f = path.join(A, new URL(r.request().url()).pathname.replace('/assets/', ''));
    r.fulfill({ body: fs.readFileSync(f), contentType: f.endsWith('.js') ? 'application/javascript' : f.endsWith('.css') ? 'text/css' : 'text/html' });
  });
  await p.goto('https://appassets.androidplatform.net/assets/map.html' + (KEY ? '?kakao=' + KEY : ''));
  await p.evaluate(() => setPoints(37.25071, 127.07859, 37.20566, 127.11239, '', '수원시 영통구 영통동 1012-4', '화성시 동탄구 영천동 701-12'));
  await p.waitForTimeout(8000);
  const r = await p.evaluate(() => ({ kakao: !!(window.kakao && kakao.maps && kakao.maps.Map), leaflet: !!document.querySelector('.leaflet-container'), pins: document.querySelectorAll('.drop').length, attr: (document.querySelector('.leaflet-control-attribution')||{}).textContent }));
  console.log(JSON.stringify(r)); console.log('blocked:', [...blocked].join(' | '));
  await p.screenshot({ path: OUT });
  await p.evaluate(() => focusPoint('d'));
  await p.waitForTimeout(2500);
  const f = await p.evaluate(() => document.querySelectorAll('.callout').length);
  console.log('focus-callout:', f);
  await p.screenshot({ path: OUT.replace('.png', '_focus.png') });
  await b.close();
})();
