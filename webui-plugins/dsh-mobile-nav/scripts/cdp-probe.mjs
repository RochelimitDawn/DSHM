import { spawn } from 'node:child_process';
import net from 'node:net';
const CHROME = '/data/data/com.termux/files/usr/lib/chromium/chrome';
const SESSION = 'session-cd1f3fcc-ec1a-4b51-b7c1-840d9359bc18';
const PROFILE = '/data/data/com.termux/files/home/.cache/cdp-probe-' + Date.now();
const port = await new Promise((resolve, reject) => {
  const srv = net.createServer();
  srv.listen(0, '127.0.0.1', () => { const p = srv.address().port; srv.close(() => resolve(p)); });
  srv.on('error', reject);
});
const chrome = spawn(CHROME, ['--headless=new', '--no-sandbox', '--disable-dev-shm-usage', '--remote-debugging-port=' + port, '--user-data-dir=' + PROFILE, '--window-size=390,844', 'about:blank'], { stdio: 'ignore' });
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
async function getJson(path) { const res = await fetch('http://127.0.0.1:' + port + path); return res.json(); }
async function waitForTarget() { for (let i = 0; i < 60; i++) { try { const l = await getJson('/json'); if (l.length) return l[0]; } catch {} await sleep(300); } throw new Error('no target'); }
let msgId = 0; const pending = new Map(); let ws;
function send(method, params = {}) { return new Promise((resolve, reject) => { const id = ++msgId; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })); }); }
async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) return { __evalError: String(r.exceptionDetails.exception?.description || r.exceptionDetails.text).slice(0, 200) };
  return r.result.value;
}
async function main() {
  const target = await waitForTarget();
  ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  ws.onmessage = (ev) => { const msg = JSON.parse(ev.data); if (msg.id && pending.has(msg.id)) { const { resolve, reject } = pending.get(msg.id); pending.delete(msg.id); msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result); } };
  await send('Page.enable'); await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
  await send('Page.navigate', { url: 'http://127.0.0.1:3080/' });
  await sleep(8000);
  await evaluate(`localStorage.setItem('dsh.sessions.current', JSON.stringify({ sessionId: '${SESSION}' }));`);
  await send('Page.reload');
  let ready = false;
  for (let i = 0; i < 25; i++) {
    await sleep(2000);
    ready = await evaluate(`!!document.querySelector('[data-gitgraph-chip-anchor] [data-gitgraph-chip]')`);
    if (ready) break;
  }
  if (!ready) { console.log('chip not found'); process.exit(0); }
  // 1. computed styles of the enlarged chip
  const styles = await evaluate(`(() => {
    const btn = document.querySelector('[data-gitgraph-chip-anchor] [data-gitgraph-chip]');
    const cs = getComputedStyle(btn);
    const r = btn.getBoundingClientRect();
    return { minHeight: cs.minHeight, touchAction: cs.touchAction, fontSize: cs.fontSize, rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) } };
  })()`);
  console.log('STYLES ' + JSON.stringify(styles));
  // 2. pressed feedback via real mouse events
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: 60, y: 657, button: 'left', clickCount: 1 });
  await sleep(120);
  const pressed = await evaluate(`(() => { const btn = document.querySelector('[data-gitgraph-chip-anchor] [data-gitgraph-chip]'); const cs = getComputedStyle(btn); return { transform: cs.transform, active: btn.matches(':active') }; })()`);
  console.log('PRESSED ' + JSON.stringify(pressed));
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 60, y: 657, button: 'left', clickCount: 1 });
  await sleep(2500);
  const after = await evaluate(`(() => {
    const p = document.querySelector('[data-gitgraph-popover]');
    if (!p) return { popover: null };
    const r = p.getBoundingClientRect();
    return { popover: { y: Math.round(r.y), h: Math.round(r.height), inViewport: r.y >= 0 && r.bottom <= innerHeight }, options: document.querySelectorAll('[data-gitgraph-popover] [role="option"]').length };
  })()`);
  console.log('AFTER ' + JSON.stringify(after));
  process.exit(0);
}
main().catch(e => { console.error('FATAL', e); process.exit(1); });
