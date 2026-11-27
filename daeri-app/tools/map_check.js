const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const A = path.join(__dirname, '..', 'app/src/main/assets');
const KEY = process.argv[2] || '';
const OUT = process.argv[3] || 'map.png';
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 400, height: 330 }, deviceScaleFactor: 1 });
  const blocked = new Set();
  p.on('requestfailed', r => blocked.add(new URL(r.url()).host + ' ' + (r.failure()||{}).errorText));
  p.on('pageerror', e => console.log('ERR', e.message));
  await p.route('https://appassets.androidplatform.net/**', r => {
    const f = path.join(A, new URL(r.request().url()).pathname.replace('/assets/', ''));
    r.fulfill({ body: fs.readFileSync(f), contentType: f.endsWith('.js') ? 'application/javascript' : f.endsWith('.css') ? 'text/css' : 'text/html' });
  });
  await p.goto('https://appassets.androidplatform.net/assets/map.html' + (KEY ? '?kakao=' + KEY : ''));
  await p.evaluate(() => setPoints(37.2580, 127.0560, 37.2050, 127.0980, ''));
  await p.waitForTimeout(8000);
  const r = await p.evaluate(() => ({ kakao: !!(window.kakao && kakao.maps && kakao.maps.Map), leaflet: !!document.querySelector('.leaflet-container'), pins: document.querySelectorAll('.kpin,.pin').length, attr: (document.querySelector('.leaflet-control-attribution')||{}).textContent }));
  console.log(JSON.stringify(r)); console.log('blocked:', [...blocked].join(' | '));
  await p.screenshot({ path: OUT });
  await b.close();
})();
