import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';

// Drives only the development package on an explicitly selected local emulator.
// Reads fresh accessibility bounds before every tap; never clears another app's data.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sdk = process.env.ANDROID_HOME;
if (!sdk) throw new Error('Set ANDROID_HOME to the installed Android SDK.');
const serial = process.env.FEEDME_TEST_DEVICE || 'emulator-5554';
if (!serial.startsWith('emulator-')) throw new Error('This smoke runner is restricted to an emulator.');
const adb = path.join(sdk, 'platform-tools/adb');
const pkg = 'com.feedme.development';
const out = path.join(root, 'docs/verification/android-smoke');
const apk = path.join(root, 'apps/android/build/outputs/apk/debug/android-debug.apk');
fs.mkdirSync(out, {recursive: true});
const events = [];
const report = {startedAt: new Date().toISOString(), device: serial, package: pkg,
  apkSha256: createHash('sha256').update(fs.readFileSync(apk)).digest('hex'), events};
const call = (...args) => execFileSync(adb, ['-s', serial, ...args], {encoding: 'utf8', timeout: 30000, maxBuffer: 4 * 1024 * 1024});
function decode(value) { return value.replaceAll('&#10;', '\n').replaceAll('&amp;', '&').replaceAll('&quot;', '"').replaceAll('&apos;', "'").replaceAll('&lt;', '<').replaceAll('&gt;', '>'); }
function dump() {
  call('shell', 'uiautomator', 'dump', '/sdcard/feedme-smoke.xml');
  const xml = call('exec-out', 'cat', '/sdcard/feedme-smoke.xml');
  if (!xml.includes(`package="${pkg}"`)) throw new Error('FeedMe is not the foreground app.');
  return {xml, nodes: [...xml.matchAll(/<node\b[^>]*>/g)].map(m => {
    const attrs = Object.fromEntries([...m[0].matchAll(/([\w-]+)="([^"]*)"/g)].map(a => [a[1], decode(a[2])]));
    const b = attrs.bounds?.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/)?.slice(1).map(Number);
    return {...attrs, box: b};
  })};
}
function find(nodes, text) { return nodes.find(n => (n.text === text || n['content-desc'] === text) && n.box && n.box[3] > n.box[1]); }
function tap(text) {
  // Search only the current screen, first down and then back up; every tap is grounded in its fresh tree.
  for (const direction of [0, 1, -1]) {
    for (let i = 0; i < (direction ? 5 : 1); i++) {
      const {nodes} = dump();
      const n = find(nodes, text);
      if (n && n.box[3] - n.box[1] >= 15) {
        const [x1,y1,x2,y2] = n.box;
        call('shell', 'input', 'tap', String(Math.round((x1+x2)/2)), String(Math.round((y1+y2)/2)));
        return;
      }
      if (direction) call('shell', 'input', 'swipe', '540', direction > 0 ? '1440' : '650', '540', direction > 0 ? '650' : '1440', '350');
    }
  }
  throw new Error(`No accessible control found: ${text}`);
}
function expect(text) {
  const {xml} = dump();
  if (!decode(xml).includes(text)) throw new Error(`Missing screen text: ${text}`);
}
function record(name, expected, status = 'passed') {
  const {xml} = dump();
  if (!decode(xml).includes(expected)) throw new Error(`${name}: missing ${expected}`);
  fs.writeFileSync(path.join(out, `${name}.xml`), xml);
  const png = execFileSync(adb, ['-s', serial, 'exec-out', 'screencap', '-p'], {timeout: 15000, maxBuffer: 8 * 1024 * 1024});
  fs.writeFileSync(path.join(out, `${name}.png`), png);
  events.push({name, status, observedAt: new Date().toISOString()});
  console.log(`${status === 'passed' ? 'PASS' : 'CAPTURE'} ${name}`);
}
try {
  call('install', '-r', apk);
  call('shell', 'am', 'force-stop', pkg);
  call('shell', 'am', 'start', '-n', `${pkg}/.MainActivity`);
  record('01-welcome', 'feedme.');
  tap('Join the club / Log in');
  expect('Sign-up and login are not connected');
  tap('Got it');
  events.push({name: 'auth-truthful-unavailable', status: 'passed'});
  tap('Explore the demo kitchen');
  record('02-kitchen', 'Good food.');
  tap('Today');
  record('03-today', 'What’s cooking?');
  tap('Make Mine  ↗');
  record('04-make-mine', 'THE SAME IDEA. MORE YOU.');
  tap('30 min');
  tap('Up for cooking');
  tap('Find my version  ↗');
  expect('The crunch club bowl');
  call('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  // The form is deliberately retained; the following default-change also tests its controlled inputs.
  tap('15 min');
  tap('Light prep');
  tap('Find my version  ↗');
  record('05-your-version', 'The quick crunch bowl');
  tap('Save to my cookbook');
  expect('Saved in this local demo session');
  tap('Got it');
  tap('Let’s make it  ↗');
  record('06-cooking', 'STEP 1 OF 3');
  tap('Next step  →');
  expect('STEP 2 OF 3');
  tap('Next step  →');
  expect('STEP 3 OF 3');
  tap('Done. That’s my dinner.  ✓');
  record('07-complete', 'SESSION COMPLETE');
  tap('Create a local demo plate');
  record('08-share-preview', 'Made it.');
  tap('Keep on My Plate');
  tap('Publish to local demo only');
  record('09-my-plate', 'My Plate.');
  tap('Illustrative The quick crunch bowl');
  call('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  expect('My Plate.');
  events.push({name: 'my-plate-back-origin', status: 'passed'});
  tap('Saves');
  record('10-cookbook', '1 SAVED MEAL');
  tap('The quick crunch bowl');
  expect('The quick crunch bowl');
  call('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  expect('The keepers.');
  events.push({name: 'cookbook-back-origin', status: 'passed'});
  tap('Demo settings');
  tap('Reset local demo');
  expect('Reset this local demo?');
  tap('Reset demo');
  expect('feedme.');
  tap('Explore the demo kitchen');
  tap('Saves');
  expect('0 SAVED MEALS');
  events.push({name: 'reset-clears-demo-state', status: 'passed'});
  tap('Kitchen');
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = String(error);
  try { record('failure-state', '', 'diagnostic'); } catch {}
  console.error(error);
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  fs.writeFileSync(path.join(out, 'report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({status: report.status, checks: events.filter(e => e.status === 'passed').length, report: path.join(out, 'report.json')}));
}
