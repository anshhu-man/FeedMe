import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {spawn} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
import {cookingPngInfo} from './verify-native-cooking-host.mjs';
import {verifyReleaseScope} from './verify-release-scope.mjs';

// Focused eighth-batch checkpoint, not a rerun of the historical 354 native tests.
// Requires an explicitly owned emulator with these four packages ABSENT. Never clear or
// uninstall an existing UID. All external execution is serialized by the coordinating task.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const shared = ['core','contracts','transport','storage','sync','kitchen','session','planning','mealflow','app'];
const libraries = shared.filter(name => !['core','contracts'].includes(name));
// Reviewed source inventory, checked against exact declarations and actual fresh results.
const fixed = {shared:2089, progress:32, server:211, postgresql:361, timers:25, timerOnly:6, host:6, timerHost:4, processDeclarations:2, node:192};
const progressPackage = 'com.feedme.development.progress', timerPackage = 'com.feedme.mealflow.test', sessionPackage = 'com.feedme.session.test';
const hostClass = `${progressPackage}.AndroidProgressHostTest`, processClass = `${progressPackage}.AndroidProgressProcessTest`;
const timerHostClass = `${progressPackage}.AndroidProgressTimerHostTest`;
const timerOnlyClass = 'com.feedme.session.AndroidTimerOnlyCancellationTest';
const stageA = `${processClass}#stageARetainAcknowledgedCookingThenAwaitExternalProcessStop`;
const stageB = `${processClass}#stageBRestoreExactCookingAndExistingServiceThenExplicitlyReset`;
const pngNames = ['start','request','confirmation','cooking'].map(name => `progress-${name}`);
const pngMethod = `${hostClass}#bActualLauncherRequiresConsentAndRetainsCookingAcrossActivityRecreation`;
const timerPngMethods = [
  {name:'progress-timer-running',identity:`${timerHostClass}#aVisibleControlsKeepTwoExactTimersAndRequireExplicitRemovalConsent`},
  {name:'progress-timer-paused',identity:`${timerHostClass}#aVisibleControlsKeepTwoExactTimersAndRequireExplicitRemovalConsent`},
  {name:'progress-timer-retained',identity:`${timerHostClass}#bBackAndActivityRecreationRetainExactTimerOwnerWithoutPauseOrNewCommand`},
  {name:'progress-timer-due',identity:`${timerHostClass}#cForegroundDueBannerIsNativeGatedAndDoesNotAdvanceOrCompleteCooking`},
];
const installedPackages = [sessionPackage,timerPackage,progressPackage,progressPackage+'.test'];
const need = (value, message) => { if (!value) throw new Error(message); };
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const read = file => fs.readFileSync(path.resolve(root, file));
function descriptor(file) {
  const absolute = path.resolve(root, file); need(fs.lstatSync(absolute).isFile(), `Nonregular input: ${file}`);
  const bytes = read(file); return {path:file, bytes:bytes.length, sha256:hash(bytes)};
}
function walk(directory) {
  need(fs.lstatSync(directory).isDirectory(), `Nonregular directory: ${directory}`);
  return fs.readdirSync(directory, {withFileTypes:true}).flatMap(entry => {
    need(!entry.isSymbolicLink() && (entry.isFile() || entry.isDirectory()), 'Symlink/special input forbidden');
    const file = path.join(directory, entry.name); return entry.isDirectory() ? walk(file) : [path.relative(root,file)];
  }).sort();
}
function sourceFiles() {
  const files = ['build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat',
    ...walk(path.join(root,'gradle')), ...walk(path.join(root,'scripts')),
    ...shared.flatMap(name => [`shared/${name}/build.gradle.kts`,...walk(path.join(root,`shared/${name}/src`))]),
    'server/build.gradle.kts',...walk(path.join(root,'server/src')),
    'apps/android/build.gradle.kts',...walk(path.join(root,'apps/android/src')),
    'docs/V1_RELEASE_SCOPE.json','docs/verification/canonical-validation/format-corpus.json',
    'docs/verification/schema-validator-spike/cases.json','docs/verification/contract-artifacts/generated-receipt.json',
    ...['architecture','registry','features'].flatMap(dir => walk(path.resolve(root,`../outputs/biteclub_blueprint/${dir}`)))];
  need(new Set(files).size === files.length, 'Duplicate source path'); return files.sort().map(descriptor);
}
function declarations(roots) {
  const ids = [];
  for (const dir of roots) for (const file of walk(path.resolve(root,dir)).filter(f => f.endsWith('.kt'))) {
    const source = read(file).toString('utf8'), count = [...source.matchAll(/^\s*@Test\b/gm)].length;
    if (!count) continue;
    const methods = [...source.matchAll(/@Test(?:\([^)]*\))?\s+fun\s+(\x60[^\x60]+\x60|[A-Za-z_][A-Za-z0-9_]*)\s*\(/g)].map(m => m[1].replace(/^\x60|\x60$/g,''));
    const pkg = source.match(/^package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$/m)?.[1], name = path.basename(file,'.kt');
    need(pkg && methods.length === count && new RegExp(`^class\\s+${name}\\b`,'m').test(source), `Review test declaration shape: ${file}`);
    ids.push(...methods.map(method => `${pkg}.${name}#${method}`));
  }
  need(ids.length && new Set(ids).size === ids.length, 'Missing/duplicate source test identities'); return ids.sort();
}
function nodeDeclarations(files) {
  const ids = [];
  for (const file of files) {
    const names = file.endsWith('/generate-contract-artifacts.test.mjs') ? '(?:test|rejectsDocument|rejectsRegistry)' : 'test';
    const pattern = new RegExp('^\\s*'+names+'\\(\\s*(\'(?:\\\\.|[^\'\\\\])*\'|"(?:\\\\.|[^"\\\\])*")','gm');
    const found = [...read(file).toString('utf8').matchAll(pattern)].map(m => {
      if (m[1][0] === '"') return JSON.parse(m[1]);
      const literal = m[1].slice(1,-1); need(!/\\[^\\']/.test(literal), 'Review escaped Node test identity'); return literal.replace(/\\(['\\])/g,'$1');
    });
    need(found.length, `No Node declarations: ${file}`); ids.push(...found);
  }
  need(ids.length === fixed.node && new Set(ids).size === ids.length, 'Node inventory changed'); return ids.sort();
}
const xmlText = s => s.replace(/&#(x[0-9a-f]+|\d+);|&(quot|apos|lt|gt|amp);/gi, (_,n,k) => n ?
  String.fromCodePoint(n[0].toLowerCase()==='x'?parseInt(n.slice(1),16):Number(n)) : ({quot:'"',apos:"'",lt:'<',gt:'>',amp:'&'})[k.toLowerCase()]);
const attrs = text => Object.fromEntries([...text.matchAll(/([A-Za-z]+)="([^"]*)"/g)].map(m => [m[1],xmlText(m[2])]));
function instrumentEvents(output) {
  const events = []; let fields = {};
  for (const line of output.split(/\r?\n/)) {
    const f = line.match(/^INSTRUMENTATION_STATUS: (class|test|id|current|numtests|feedme_progress_stage|feedme_progress_pid|feedme_progress_start_elapsed)=(.*)$/);
    if (f) { need(!Object.hasOwn(fields,f[1]), 'Duplicate instrumentation field'); fields[f[1]]=f[2]; }
    const c = line.match(/^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$/);
    if (c) { events.push({...fields,code:Number(c[1])}); fields={}; }
  }
  need(!Object.keys(fields).length,'Incomplete instrumentation event'); return events;
}
function verifyNative(output, declared, notice = null, ordered = false) {
  need(!/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(output), 'Native failure despite shell status');
  const terminal = [...output.matchAll(/^INSTRUMENTATION_CODE: (-?\d+)\s*$/gm)], summary = [...output.matchAll(/^OK \((\d+) tests?\)\s*$/gm)];
  need(terminal.length===1 && terminal[0][1]==='-1' && summary.length===1 && Number(summary[0][1])===declared.length,'No unique native success');
  const all = instrumentEvents(output), notes = all.filter(e=>e.code===2), events = all.filter(e=>e.code!==2), ids=[];
  need(events.length===declared.length*2 && notes.length===(notice?1:0),'Unexpected native event inventory');
  for (let i=0;i<events.length;i+=2) {
    const a=events[i],b=events[i+1],id=`${a.class}#${a.test}`;
    need(a.code===1 && b.code===0 && b.class===a.class && b.test===a.test && declared.includes(id),'Native start/success mismatch');
    for (const e of [a,b]) need(e.id==='AndroidJUnitRunner' && e.current===String(i/2+1) && e.numtests===String(declared.length),'Native test ordinal/count mismatch'); ids.push(id);
  }
  need(JSON.stringify([...ids].sort())===JSON.stringify(declared),'Native source identity mismatch');
  if (ordered) need(JSON.stringify(ids)===JSON.stringify(declared),'Required host test ordering changed');
  if (notice) verifyNotice(notes[0],notice);
  return {tests:ids.length,failures:0,errors:0,skipped:0,identities:ids};
}
function verifyNotice(e, expected) {
  need(e?.code===2 && Object.keys(e).sort().join(',')==='code,feedme_progress_pid,feedme_progress_stage,feedme_progress_start_elapsed','Malformed stage notice');
  need(e.feedme_progress_stage===expected && /^[1-9]\d*$/.test(e.feedme_progress_pid) && /^(0|[1-9]\d*)$/.test(e.feedme_progress_start_elapsed),'Wrong process checkpoint');
  return {pid:Number(e.feedme_progress_pid),startElapsedRealtime:Number(e.feedme_progress_start_elapsed)};
}
function interrupted(output, beforeStop = false) {
  const events=instrumentEvents(output), starts=events.filter(e=>e.code!==2), notices=events.filter(e=>e.code===2), a=starts[0];
  need(starts.length===1 && a.code===1 && `${a.class}#${a.test}`===stageA && a.id==='AndroidJUnitRunner' && a.current==='1' && a.numtests==='1','Interrupted stage must be start-only');
  need(notices.length===1 && !/^OK \(|FAILURES!!!|AssertionError|TimeoutCancellationException/m.test(output),'Interrupted test must not pass or time out');
  if(beforeStop) need(!/^INSTRUMENTATION_CODE:|INSTRUMENTATION_FAILED|Process crashed/m.test(output),'Stage terminated before witnessed stop');
  return verifyNotice(notices[0],'RETAINED_UNCLOSED');
}
function marker(raw, stage) {
  need(raw.length>0 && raw.length<=512,'Process marker exceeds bound'); const m=JSON.parse(raw.toString('utf8'));
  const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  const generation=raw.toString('utf8').match(/,"timerGeneration":([1-9][0-9]*),/)?.[1];
  need(m.version===2 && m.stage===stage && uuid.test(m.planId) && uuid.test(m.sessionId) && uuid.test(m.timerId) &&
    generation && generation.length<=19 && BigInt(generation)<=9223372036854775807n &&
    Number.isSafeInteger(m.pid) && m.pid>0 && Number.isSafeInteger(m.startElapsedRealtime) && m.startElapsedRealtime>=0,'Invalid process marker');
  const canonical=`{"version":2,"stage":${JSON.stringify(stage)},"planId":${JSON.stringify(m.planId)},"sessionId":${JSON.stringify(m.sessionId)},"timerId":${JSON.stringify(m.timerId)},"timerGeneration":${generation},"pid":${m.pid},"startElapsedRealtime":${m.startElapsedRealtime}}`;
  need(raw.equals(Buffer.from(canonical)),'Noncanonical/extra process marker fields');
  // Exact canonical Long retained as decimal text rather than rounded JavaScript Number.
  return {...m,timerGeneration:generation};
}
function verifyProgressManifest(badging, tree) {
  const packages=[...badging.matchAll(/^package: name='([^']+)'/gm)], launchers=[...badging.matchAll(/^launchable-activity: name='([^']+)'/gm)];
  need(packages.length===1&&packages[0][1]===progressPackage&&launchers.length===1&&launchers[0][1]===`${progressPackage}.ProgressActivity`,'Wrong packaged progress package/launcher');
  need(!/^uses-permission(?:-sdk-\d+)?: name='android\.permission\.(?:INTERNET|ACCESS_NETWORK_STATE|WAKE_LOCK|RECEIVE_BOOT_COMPLETED|FOREGROUND_SERVICE(?:_[A-Z_]+)?)'/m.test(badging),'Progress APK acquired forbidden network/background permission');
  need(!/E: instrumentation\b|androidx\.test\.|WorkManagerInitializer/.test(tree),'Test runner/automatic WorkManager initialization in progress APK');
  const application=tree.match(/^([ ]*)E: application\b[^\n]*\n([\s\S]*?)(?=^\1E: |$(?![\s\S]))/m)?.[2];
  need(application&&/android:name\([^)]*\)="com\.feedme\.development\.progress\.ProgressApplication"/.test(application),'Wrong packaged application');
  need(/android:allowBackup\([^)]*\)=\(type 0x12\)0x0\b/.test(application)&&/android:usesCleartextTraffic\([^)]*\)=\(type 0x12\)0x0\b/.test(application),'Packaged backup/cleartext policy is not disabled');
  const receivers=manifestElements(tree,'receiver').filter(body=>manifestString(body,'name')===`${progressPackage}.ProgressTimerCancellationReceiver`);
  need(receivers.length===1&&manifestFalse(receivers[0],'enabled')&&manifestFalse(receivers[0],'exported'),'Timer cancellation receiver must be exactly disabled and non-exported');
  return {package:progressPackage,launcher:`${progressPackage}.ProgressActivity`,allowBackup:false,usesCleartextTraffic:false,automaticWorkManagerInitialization:false,forbiddenPermissions:[],instrumentation:false,
    timerCancellationReceiver:{name:`${progressPackage}.ProgressTimerCancellationReceiver`,enabled:false,exported:false,delivery:false}};
}
function manifestElements(tree, tag) {
  const lines=tree.split(/\r?\n/), result=[];
  for(let i=0;i<lines.length;i++) {
    const element=lines[i].match(new RegExp(`^( *)E: ${tag}\\b`)); if(!element)continue;
    const depth=element[1].length, own=[];
    for(let j=i+1;j<lines.length;j++) {
      const next=lines[j].match(/^( *)E: /); if(next&&next[1].length<=depth)break;
      if(next)break; // Only this element's own attributes, not its descendants' permissions.
      if(new RegExp(`^ {${depth+2}}A: `).test(lines[j]))own.push(lines[j]);
    }
    result.push(own.join('\n'));
  }
  return result;
}
function manifestString(body, name) { const values=[...body.matchAll(new RegExp(`android:${name}\\([^)]*\\)="([^"\\n]*)"`,'g'))]; return values.length===1?values[0][1]:null; }
function manifestFalse(body,name) { return new RegExp(`android:${name}\\([^)]*\\)=\\(type 0x12\\)0x0\\b`).test(body); }
function verifyInstalledManifest(badging,tree,pkg,launcher,targetPackage) {
  const packages=[...badging.matchAll(/^package: name='([^']+)'/gm)],launchers=[...badging.matchAll(/^launchable-activity: name='([^']+)'/gm)].map(m=>m[1]);
  need(packages.length===1&&packages[0][1]===pkg&&JSON.stringify(launchers)===JSON.stringify(launcher?[launcher]:[]),'Installed APK package/launcher mismatch');
  const instruments=manifestElements(tree,'instrumentation');
  if(targetPackage) need(instruments.length===1&&manifestString(instruments[0],'name')==='androidx.test.runner.AndroidJUnitRunner'&&
    manifestString(instruments[0],'targetPackage')===targetPackage,'Installed APK instrumentation/target mismatch');
  else need(instruments.length===0,'Application APK must not contain instrumentation');
  need(!/WorkManagerInitializer/.test(tree),'Installed APK acquired implicit WorkManager initialization');
  return {package:pkg,launchers,instrumentation:targetPackage?{name:'androidx.test.runner.AndroidJUnitRunner',targetPackage}:null,automaticWorkManagerInitialization:false};
}
function verifyScreenshotSources(source, klass, expected) {
  const methods=[...source.matchAll(/@Test\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(/g)], found=[];
  for(let i=0;i<methods.length;i++){const body=source.slice(methods[i].index,methods[i+1]?.index??source.length);
    for(const call of body.matchAll(/\bscreenshot\("([^"]+)"\)/g))found.push({name:call[1],identity:`${klass}#${methods[i][1]}`});}
  need(expected.length&&new Set(expected.map(e=>e.name)).size===expected.length&&JSON.stringify(found)===JSON.stringify(expected),'Progress PNG/source method binding changed');return found;
}
function verifyProgressScreenshots(source) { return verifyScreenshotSources(source,hostClass,pngNames.map(name=>({name,identity:pngMethod}))); }

async function main() {
  const serial=process.env.FEEDME_TEST_DEVICE;
  need(/^emulator-\d+$/.test(serial||'') && serial!=='emulator-5554' && process.env.ANDROID_HOME,'Explicit owned emulator other than user preview5554 and ANDROID_HOME required');
  const adb=path.join(process.env.ANDROID_HOME,'platform-tools/adb'), began=new Date().toISOString();
  const base=path.join(root,'docs/verification/cookbook-timer-media'), attempt=path.join(base,'attempts',began.replaceAll(':','-'));
  fs.mkdirSync(path.dirname(attempt),{recursive:true}); fs.mkdirSync(attempt);
  const report={startedAt:began,passed:false,serial,attemptDirectory:path.relative(root,attempt),commands:[],tests:[],native:[],artifacts:[],androidLint:[],screenshots:[],
    scope:'Focused eighth-batch source-bound checkpoint: complete current shared/progress/server JVM and PostgreSQL/Node regressions; eight libraries and isolated progress app; actual foreground timer controls, exact cancellation and explicit synthetic progress identity/catalog/service; media reservation/completion/deletion HTTP and PostgreSQL contracts use explicit test authority/storage composition, not a deployed media processor. Native scope is only25 timer +6 timer-only cancellation +6 progress host +4 actual timer host +1 fresh-process recovery passed tests and1 separately witnessed interrupted start, with8 source-bound PNGs. Historical354 native tests and legacy VFS/process matrices are NOT rerun. Timer-only cancellation executes in a separate fresh session-test invocation before other instrumentation, with WorkManager initialization explicitly refused. No real login/provider, deployment, complete F19/Make Again/social copy, signed offline manifest, physical power-loss, background timer/notification delivery, iOS or store-release acceptance. Only the explicitly supplied owned emulator is addressed; user preview5554 is rejected.'};
  let active=null, installedProgress=false;
  function write(name, bytes) { const file=path.join(attempt,name); fs.mkdirSync(path.dirname(file),{recursive:true}); fs.writeFileSync(file,bytes); return descriptor(path.relative(root,file)); }
  function retain(file, name) { const source=descriptor(file), bytes=read(file), copy=write(name,bytes); need(source.sha256===copy.sha256,'Retention race'); return {source,retained:copy}; }
  async function run(name, command, args, timeout=600000, allowed=[0], binary=false) {
    const startedAt=new Date().toISOString(), chunks=[]; let size=0;
    const outcome=await new Promise(resolve=>{
      const child=spawn(command,args,{cwd:root,env:process.env,stdio:['ignore','pipe','pipe']}); let error=null;
      const timer=setTimeout(()=>{error='timeout';child.kill('SIGTERM');},timeout);
      for(const stream of [child.stdout,child.stderr]) stream.on('data',data=>{size+=data.length;chunks.push(data);if(size>64*1024*1024){error='output bound';child.kill('SIGTERM');}});
      child.on('error',e=>{error=e.code;});child.on('close',(exitCode,signal)=>{clearTimeout(timer);resolve({exitCode,signal,error});});
    });
    const bytes=Buffer.concat(chunks),log=write(`${name}.${binary?'bin':'log'}`,bytes);
    report.commands.push({name,startedAt,finishedAt:new Date().toISOString(),command,args,...outcome,log});
    need(!outcome.error && allowed.includes(outcome.exitCode),`${name} failed; inspect retained evidence`);
    return binary?bytes:bytes.toString('utf8');
  }
  const device=(name,args,timeout=10000,allowed=[0],binary=false)=>run(name,adb,['-s',serial,...args],timeout,allowed,binary);
  async function pids(name) { const text=(await device(name,['shell','pidof',progressPackage],10000,[0,1])).trim(); need(!text||/^[1-9]\d*( [1-9]\d*)*$/.test(text),'Invalid PID witness');return text?text.split(' ').map(Number):[]; }
  function fresh(file,since) { const m=fs.statSync(path.resolve(root,file)).mtimeMs;need(m>=since&&m<=Date.now(),`Stale artifact: ${file}`); }
  try {
    report.sourceFiles=sourceFiles();report.sourceManifestSha256=hash(JSON.stringify(report.sourceFiles));write('source-manifest.json',JSON.stringify(report.sourceFiles,null,2)+'\n');
    report.frozenSources=report.sourceFiles.map((d,i)=>{const pair=retain(d.path,`sources/${String(i).padStart(4,'0')}-${path.basename(d.path)}`);need(pair.source.sha256===d.sha256,'Source changed during freeze');return pair;});
    const suites=shared.map(name=>({name,roots:['commonTest','jvmTest'].map(kind=>`shared/${name}/src/${kind}`).filter(d=>fs.existsSync(path.join(root,d))),results:`shared/${name}/build/test-results/jvmTest`,suffix:'[jvm]'}));
    suites.push({name:'progress',roots:['apps/android/src/testProgress'],results:'apps/android/build/test-results/testProgressUnitTest',suffix:''},
      {name:'server',roots:['server/src/test'],results:'server/build/test-results/test',suffix:''},{name:'postgresql',roots:['server/src/integrationTest'],results:'server/build/test-results/integrationTest',suffix:''});
    suites.forEach(s=>{s.declared=declarations(s.roots);if(fixed[s.name])need(s.declared.length===fixed[s.name],`${s.name} inventory changed`);});
    const timerIds=declarations(['shared/mealflow/src/androidInstrumentedTest']),progressIds=declarations(['apps/android/src/androidTestProgress']);
    const timerOnlyIds=declarations(['shared/session/src/androidInstrumentedTest']).filter(id=>id.startsWith(timerOnlyClass+'#'));
    const hostIds=progressIds.filter(id=>id.startsWith(hostClass+'#')),timerHostIds=progressIds.filter(id=>id.startsWith(timerHostClass+'#'));
    const processIds=progressIds.filter(id=>!hostIds.includes(id)&&!timerHostIds.includes(id));
    need(timerIds.length===fixed.timers&&timerOnlyIds.length===fixed.timerOnly&&hostIds.length===fixed.host&&timerHostIds.length===fixed.timerHost&&
      processIds.length===fixed.processDeclarations&&JSON.stringify(processIds)===JSON.stringify([stageA,stageB]),'Focused native declarations changed');
    report.screenshotSources=verifyProgressScreenshots(read('apps/android/src/androidTestProgress/kotlin/com/feedme/development/progress/AndroidProgressHostTest.kt').toString('utf8'));
    report.timerScreenshotSources=verifyScreenshotSources(read('apps/android/src/androidTestProgress/kotlin/com/feedme/development/progress/AndroidProgressTimerHostTest.kt').toString('utf8'),timerHostClass,timerPngMethods);
    const nodeFiles=walk(path.join(root,'scripts')).filter(f=>f.endsWith('.test.mjs')), nodeIds=nodeDeclarations(nodeFiles);
    need((await device('boot',['shell','getprop','sys.boot_completed'])).trim()==='1','Owned emulator not booted');
    report.androidApi=(await device('api',['shell','getprop','ro.build.version.sdk'])).trim();report.androidAbi=(await device('abi',['shell','getprop','ro.product.cpu.abi'])).trim();
    for(const pkg of installedPackages) need(!(await device('fresh-'+pkg,['shell','pm','list','packages',pkg])).trim(),`Existing UID must be preserved: ${pkg}`);
    const tasks=[...shared.map(m=>`:shared:${m}:jvmTest`),...libraries.flatMap(m=>['jvmJar','assembleDebug','lintDebug'].map(t=>`:shared:${m}:${t}`)),
      ...['storage','session','app','mealflow'].map(m=>`:shared:${m}:assembleDebugAndroidTest`),':apps:android:testProgressUnitTest',':apps:android:assembleProgress',':apps:android:assembleProgressAndroidTest',':apps:android:lintProgress',':server:test',':server:integrationTest',':verifyReleaseScope'];
    const buildStarted=Date.now(), gradle=await run('gradle','./gradlew',[...tasks,'--rerun-tasks','--console=plain'],1800000);
    need(/^BUILD SUCCESSFUL\b/m.test(gradle),'No successful Gradle terminal');for(const task of tasks)need(gradle.split(/\r?\n/).filter(l=>l.trimEnd()===`> Task ${task}`).length===1,`Not freshly executed: ${task}`);report.gradleTasks=tasks;
    for(const suite of suites) {
      const actual=[],classes=[],files=walk(path.join(root,suite.results)).filter(f=>/\/TEST-[^/]+\.xml$/.test(f));let count=0;
      for(const file of files) {
        fresh(file,buildStarted);const xml=read(file).toString('utf8'),nodes=[...xml.matchAll(/<testsuite\b([^>]+)>/g)];
        need(nodes.length===1&&!/<(?:failure|error|skipped)\b/.test(xml),'Failed/unexpected JUnit XML');const a=attrs(nodes[0][1]);
        need(Date.parse(a.timestamp)>=buildStarted&&Date.parse(a.timestamp)<=Date.now(),'Stale JUnit timestamp');
        for(const key of ['tests','failures','errors','skipped'])need(/^\d+$/.test(a[key]),'Invalid JUnit counts');
        need(a.failures==='0'&&a.errors==='0'&&a.skipped==='0','Unsuccessful JUnit suite');
        const cases=[...xml.matchAll(/<testcase\b([^>]+)>/g)].map(m=>attrs(m[1]));need(cases.length===Number(a.tests),'JUnit count mismatch');
        for(const test of cases){need(test.classname===a.name&&(!suite.suffix||test.name.endsWith(suite.suffix)),'JUnit identity/target mismatch');actual.push(`${a.name}#${suite.suffix?test.name.slice(0,-suite.suffix.length):test.name}`);}
        count+=cases.length;classes.push(a.name);retain(file,`junit/${suite.name}/${path.basename(file)}`);
      }
      need(new Set(classes).size===classes.length&&JSON.stringify(actual.sort())===JSON.stringify(suite.declared),'Exact source/JUnit identity mismatch');
      report.tests.push({name:suite.name,tests:count,classes:classes.length,failures:0,errors:0,skipped:0,identities:actual});
    }
    report.kotlinTests={tests:report.tests.reduce((n,s)=>n+s.tests,0),shared:report.tests.filter(s=>shared.includes(s.name)).reduce((n,s)=>n+s.tests,0),progress:report.tests.find(s=>s.name==='progress').tests,server:fixed.server,postgresql:fixed.postgresql};
    need(report.kotlinTests.shared===fixed.shared&&report.kotlinTests.progress===fixed.progress&&report.kotlinTests.tests===fixed.shared+fixed.progress+fixed.server+fixed.postgresql,'Frozen complete Kotlin inventory changed');
    const artifactPaths=libraries.flatMap(m=>[`shared/${m}/build/libs/${m}-jvm.jar`,`shared/${m}/build/outputs/aar/${m}-debug.aar`]);
    const apks=[...['storage','session','app','mealflow'].map(m=>({path:`shared/${m}/build/outputs/apk/androidTest/debug/${m}-debug-androidTest.apk`,pkg:`com.feedme.${m}.test`,variant:'debugAndroidTest'})),
      {path:'apps/android/build/outputs/apk/progress/android-progress.apk',pkg:progressPackage,variant:'progress'},
      {path:'apps/android/build/outputs/apk/androidTest/progress/android-progress-androidTest.apk',pkg:progressPackage+'.test',variant:'progressAndroidTest'}];
    artifactPaths.push(...apks.map(a=>a.path));
    for(const [i,file]of artifactPaths.entries()){fresh(file,buildStarted);report.artifacts.push(retain(file,`artifacts/${i}-${path.basename(file)}`));}
    need(report.artifacts.length===22,'Expected22 fresh current artifacts');
    for(const apk of apks){const file=path.join(path.dirname(apk.path),'output-metadata.json');fresh(file,buildStarted);const metadata=JSON.parse(read(file));need(metadata.applicationId===apk.pkg&&metadata.variantName===apk.variant&&metadata.elements?.length===1&&metadata.elements[0].outputFile===path.basename(apk.path)&&metadata.elements[0].filters?.length===0,'APK metadata identity mismatch');apk.metadata=retain(file,`metadata/${apk.pkg}.json`);}
    report.apks=apks;
    const aaptVersions=fs.readdirSync(path.join(process.env.ANDROID_HOME,'build-tools')).filter(v=>/^\d+\.\d+\.\d+$/.test(v)).sort((a,b)=>a.localeCompare(b,undefined,{numeric:true}));
    need(aaptVersions.length,'No configured Android build-tools');const aapt=path.join(process.env.ANDROID_HOME,'build-tools',aaptVersions.at(-1),'aapt');
    report.installedManifests=[];
    for(const pkg of installedPackages){const apk=apks.find(a=>a.pkg===pkg);need(apk,'Missing install artifact');const absolute=path.resolve(root,apk.path);
      const badging=await run('apk-badging-'+pkg,aapt,['dump','badging',absolute]),tree=await run('apk-manifest-'+pkg,aapt,['dump','xmltree',absolute,'AndroidManifest.xml']);
      report.installedManifests.push({artifact:descriptor(apk.path),...verifyInstalledManifest(badging,tree,pkg,pkg===progressPackage?`${progressPackage}.ProgressActivity`:null,
        pkg===progressPackage?null:pkg===progressPackage+'.test'?progressPackage:pkg)});
      if(pkg===progressPackage)report.progressManifest=verifyProgressManifest(badging,tree);
    }
    need(report.installedManifests.length===4&&report.progressManifest,'All installed package manifests required');
    report.progressManifest.aapt=descriptor(aapt);
    for(const [name,file]of [...libraries.map(m=>[m,`shared/${m}/build/reports/lint-results-debug.xml`]),['progress','apps/android/build/reports/lint-results-progress.xml']]){fresh(file,buildStarted);need(!/<issue\b/.test(read(file).toString('utf8')),`Outstanding ${name} lint`);report.androidLint.push({name,issues:0,...retain(file,`lint/${name}.xml`)});}
    need(report.androidLint.length===9,'Nine clean lint reports required');
    const demo='apps/android/build/outputs/apk/debug/android-debug.apk';report.historicalDemo=retain(demo,'historical/android-debug.apk');
    need(report.historicalDemo.source.sha256==='bf6dd07e31a4fd49b798672ba82edcee7f958b9d5c12ae0d7a89491d20ff3805','Historical demo bytes changed');
    const helper='libfeedmeSqliteSyncFailure.so',nativeHelpers=[],elfAbis={'arm64-v8a':[2,183],'armeabi-v7a':[1,40],x86:[1,3],x86_64:[2,62]};
    for(const file of [...artifactPaths.filter(f=>/\.(apk|aar)$/.test(f)),demo]){const listing=await run('zip-'+path.basename(file),'unzip',['-Z','-1',path.resolve(root,file)]);const entries=listing.split(/\r?\n/).filter(Boolean),matches=entries.filter(s=>/feedmeSqliteSyncFailure|sqlite_sync_failure/i.test(s));need(new Set(entries).size===entries.length,'Duplicate packaging entry');
      if(file.includes('storage-debug-androidTest.apk')){const expected=['arm64-v8a','armeabi-v7a','x86','x86_64'].map(a=>`lib/${a}/${helper}`).sort();need(JSON.stringify(matches.sort())===JSON.stringify(expected),'Test helper ABI set changed');nativeHelpers.push(...matches);}
      else need(matches.length===0,'Test-only fault helper escaped storage test APK');}
    const elfLibraries=[];
    for(const [abi,[elfClass,machine]]of Object.entries(elfAbis)){const entry=`lib/${abi}/${helper}`,bytes=await run('elf-'+abi,'unzip',['-p',path.resolve(root,apks[0].path),entry],10000,[0],true);
      need(bytes.length>=20&&bytes.subarray(0,4).equals(Buffer.from([127,69,76,70]))&&bytes[4]===elfClass&&bytes[5]===1&&bytes.readUInt16LE(18)===machine,'Packaged helper ELF ABI mismatch');
      const current=`shared/storage/build/intermediates/stripped_native_libs/debugAndroidTest/stripDebugAndroidTestDebugSymbols/out/${entry}`;fresh(current,buildStarted);const source=descriptor(current);need(source.sha256===hash(bytes)&&source.bytes===bytes.length,'Packaged helper differs from current build');elfLibraries.push({abi,entry,source,retained:write(`native-packaging/${abi}-${helper}`,bytes)});}
    report.packaging={testHelperPaths:nativeHelpers,elfLibraries,scope:'Static four-ABI ELF confinement, excluded from eight AARs, all other test/progress APKs and the unchanged historical demo. Legacy VFS tests were not executed in this checkpoint.'};
    const scopeStart=Date.now();await run('release-scope',process.execPath,['scripts/verify-release-scope.mjs','--write-report']);
    const scopePath='docs/verification/release-scope-report.json', scope=JSON.parse(read(scopePath)),inputs={manifest:read('docs/V1_RELEASE_SCOPE.json').toString(),registry:read('../outputs/biteclub_blueprint/registry/screen_registry.json').toString()};
    const expectedScope=verifyReleaseScope({manifest:JSON.parse(inputs.manifest),registry:JSON.parse(inputs.registry)}),{verifiedAt,sourceSha256,...scopeResult}=scope;
    need(Date.parse(verifiedAt)>=scopeStart&&Date.parse(verifiedAt)<=Date.now()&&sourceSha256===hash(JSON.stringify(inputs))&&JSON.stringify(scopeResult)===JSON.stringify(expectedScope)&&expectedScope.passed,'Release-scope evidence mismatch');report.releaseScope=retain(scopePath,'release-scope-report.json');
    const tap=await run('node-tests',process.execPath,['--test','--test-reporter=tap',...nodeFiles]);
    const starts=[...tap.matchAll(/^# Subtest: (.*)$/gm)].map(m=>m[1]),passed=[...tap.matchAll(/^ok (\d+) - (.*)$/gm)];
    need(JSON.stringify(starts.sort())===JSON.stringify(nodeIds)&&JSON.stringify(passed.map(m=>m[2]).sort())===JSON.stringify(nodeIds)&&passed.every((m,i)=>Number(m[1])===i+1)&&!/^not ok\b|# (?:SKIP|TODO)\b/m.test(tap),'Exact Node TAP identity mismatch');
    for(const [key,count]of Object.entries({tests:fixed.node,pass:fixed.node,fail:0,cancelled:0,skipped:0,todo:0})){const found=[...tap.matchAll(new RegExp(`^# ${key} (\\d+)$`,'gm'))];need(found.length===1&&Number(found[0][1])===count,'Node TAP count mismatch');}report.node={tests:fixed.node,identities:nodeIds,files:nodeFiles};
    for(const pkg of installedPackages){const apk=apks.find(a=>a.pkg===pkg);need(!(await device('recheck-fresh-'+pkg,['shell','pm','list','packages',pkg])).trim(),'UID appeared before install');await device('install-'+pkg,['install','-t',path.resolve(root,apk.path)],60000);if(pkg===progressPackage)installedProgress=true;}
    const timerRunner=`${timerPackage}/androidx.test.runner.AndroidJUnitRunner`, sessionRunner=`${sessionPackage}/androidx.test.runner.AndroidJUnitRunner`,progressRunner=`${progressPackage}.test/androidx.test.runner.AndroidJUnitRunner`;
    for(const [name,runner,target]of [['session',sessionRunner,sessionPackage],['timer',timerRunner,timerPackage],['progress',progressRunner,progressPackage]])
      need((await device(name+'-runner',['shell','pm','list','instrumentation',target])).trim()===`instrumentation:${runner} (target=${target})`,'Expected unique isolated runner missing');
    const uid=(await device('progress-uid',['shell','run-as',progressPackage,'id','-u'])).trim();need(/^[1-9]\d*$/.test(uid),'Invalid owned progress UID');report.progressUid=Number(uid);
    // First and only instrumentation in this newly installed session-test UID: the suite checks
    // absent WorkManager configuration; no legacy native initializer test runs here.
    const timerOnlyLog=await device('timer-only-cancellation-tests',['shell','am','instrument','-w','-r','-e','class',timerOnlyClass,sessionRunner],180000);
    report.native.push({name:'timer-only-cancellation',...verifyNative(timerOnlyLog,timerOnlyIds)});
    const timerLog=await device('timer-tests',['shell','am','instrument','-w','-r','-e','class',[...new Set(timerIds.map(id=>id.split('#')[0]))].join(','),timerRunner],360000);
    report.native.push({name:'foreground-timers',...verifyNative(timerLog,timerIds)});
    need(!/foreground-timer-native-|Permission denied|run-as:|error:/i.test(await device('timer-cleanup',['shell','run-as',timerPackage,'ls','-1','no_backup'])),'Timer fixture cleanup failed');
    const hostLog=await device('progress-host-tests',['shell','am','instrument','-w','-r','-e','class',hostClass,progressRunner],420000);
    report.native.push({name:'progress-host',...verifyNative(hostLog,hostIds,null,true)});
    const captureList=(await device('progress-captures',['shell','run-as',progressPackage,'ls','-1','cache/progress-ui-evidence'])).trim().split(/\r?\n/).sort();need(JSON.stringify(captureList)===JSON.stringify(pngNames.map(n=>n+'.png').sort()),'Progress capture inventory mismatch');
    for(const name of pngNames){const raw=await device('capture-'+name,['exec-out','run-as',progressPackage,'cat',`cache/progress-ui-evidence/${name}.png`],10000,[0],true);const info=cookingPngInfo(raw);report.screenshots.push({name,identity:pngMethod,...info,image:write(`screenshots/${name}.png`,raw)});}
    const timerHostLog=await device('progress-timer-host-tests',['shell','am','instrument','-w','-r','-e','class',timerHostClass,progressRunner],420000);
    report.native.push({name:'progress-timer-host',...verifyNative(timerHostLog,timerHostIds,null,true)});
    const timerCaptures=(await device('progress-timer-captures',['shell','run-as',progressPackage,'ls','-1','cache/progress-timer-ui-evidence'])).trim().split(/\r?\n/).sort();
    need(JSON.stringify(timerCaptures)===JSON.stringify(timerPngMethods.map(p=>p.name+'.png').sort()),'Timer capture inventory mismatch');
    for(const {name,identity}of timerPngMethods){const raw=await device('capture-'+name,['exec-out','run-as',progressPackage,'cat',`cache/progress-timer-ui-evidence/${name}.png`],10000,[0],true);
      report.screenshots.push({name,identity,...cookingPngInfo(raw),image:write(`timer-screenshots/${name}.png`,raw)});}
    need(report.screenshots.length===8&&new Set(report.screenshots.map(p=>p.name)).size===8,'Eight exact source-bound screenshots required');
    // Ten successful host cases explicitly closed their known owners. Stop only this freshly
    // installed owned UID to establish a distinct initial process for the controlled witness.
    await device('before-stage-a-stop',['shell','am','force-stop',progressPackage]);need(!(await pids('before-stage-a-pids')).length,'Progress process did not stop');
    const child=spawn(adb,['-s',serial,'shell','am','instrument','-w','-r','-e','class',stageA,progressRunner],{stdio:['ignore','pipe','pipe']});
    active={child,output:'',terminal:false};const running=active;
    for(const stream of [child.stdout,child.stderr])stream.on('data',b=>{running.output+=b.toString('utf8');if(running.output.length>4*1024*1024)child.kill('SIGTERM');});
    child.on('error',e=>{running.error=e.code;running.terminal=true;});child.on('close',(code,signal)=>{running.exitCode=code;running.signal=signal;running.terminal=true;});
    const deadline=performance.now()+60000;let noticed=null;
    while(performance.now()<deadline&&!running.terminal){if(/INSTRUMENTATION_STATUS: feedme_progress_stage=RETAINED_UNCLOSED\r?\n/.test(running.output)&&/INSTRUMENTATION_STATUS_CODE: 2\r?\n/.test(running.output)){noticed=interrupted(running.output,true);break;}await delay(150);}
    need(noticed&&!running.terminal,'No live acknowledged checkpoint before deadline');
    const retainedMarker=await device('retained-marker',['exec-out','run-as',progressPackage,'cat','cache/progress-process-evidence/retained.json'],10000,[0],true),old=marker(retainedMarker,'retained-unclosed');
    need(old.pid===noticed.pid&&old.startElapsedRealtime===noticed.startElapsedRealtime&&JSON.stringify(await pids('live-stage-a-pids'))===JSON.stringify([old.pid]),'Checkpoint/live PID mismatch');
    const proc=await device('live-stage-a-status',['shell','run-as',progressPackage,'cat',`/proc/${old.pid}/status`]);need(new RegExp(`^Uid:\\s+${uid}\\s+${uid}\\s+${uid}\\s+${uid}\\s*$`,'m').test(proc),'Checkpoint process is not owned UID');
    write('stage-a-before-stop.log',running.output);interrupted(running.output,true);need(!running.terminal,'Stage ended before force-stop');
    await device('witnessed-stage-a-stop',['shell','am','force-stop',progressPackage]);for(let i=0;i<40&&!running.terminal;i++)await delay(100);
    need(running.terminal&&!running.error,'Interrupted instrumentation did not terminate');write('stage-a-interrupted.log',running.output);interrupted(running.output);
    need(!(await pids('after-stage-a-pids')).length,'Interrupted PID still live');active=null;
    report.witnessedInterruption={identity:stageA,passedTest:false,checkpoint:old,exitCode:running.exitCode,signal:running.signal,processAbsentAfterStop:true};
    const recovery=await device('progress-process-recovery',['shell','am','instrument','-w','-r','-e','class',stageB,progressRunner],180000);
    report.native.push({name:'progress-process-recovery',...verifyNative(recovery,[stageB],'RESTORED_AND_RESET')});
    const restored=marker(await device('restored-marker',['exec-out','run-as',progressPackage,'cat','cache/progress-process-evidence/restored-and-reset.json'],10000,[0],true),'restored-and-reset');
    const recoveryNotice=verifyNotice(instrumentEvents(recovery).find(e=>e.code===2),'RESTORED_AND_RESET');
    need(restored.planId===old.planId&&restored.sessionId===old.sessionId&&restored.timerId===old.timerId&&restored.timerGeneration===old.timerGeneration&&
      restored.pid!==old.pid&&restored.startElapsedRealtime>old.startElapsedRealtime&&restored.pid===recoveryNotice.pid&&restored.startElapsedRealtime===recoveryNotice.startElapsedRealtime,'Fresh process did not preserve exact acknowledged cooking/timer lineage');
    const markers=(await device('process-marker-inventory',['shell','run-as',progressPackage,'ls','-1','cache/progress-process-evidence'])).trim().split(/\r?\n/).sort();need(JSON.stringify(markers)===JSON.stringify(['restored-and-reset.json','retained.json']),'Process evidence changed');
    const retainedAgain=await device('retained-marker-after-recovery',['exec-out','run-as',progressPackage,'cat','cache/progress-process-evidence/retained.json'],10000,[0],true);need(retainedAgain.equals(retainedMarker),'Original interruption marker changed during recovery');
    report.processRecovery={restored,originalMarkerSha256:hash(retainedMarker),originalMarkerUnchanged:true,
      scope:'Source-bound stage B restores the exact canonical timer and generation, with no inherited native acknowledgement/due/auto-install and an uncertain clock; actual explicit reset and final CLOSED/SIGNED_OUT are asserted. Timer generation is retained as canonical decimal text to preserve Long precision. Persistent control/install files are not erased or mislabeled as absent. Witness is real emulated process interruption, not physical power loss or background delivery.'};
    report.nativeTests={tests:report.native.reduce((n,s)=>n+s.tests,0),failures:0,errors:0,skipped:0,witnessedInterruptions:1};
    need(report.native.length===5&&report.nativeTests.tests===42,'Only42 focused native passes in five successful invocations allowed');
    for(const pair of [...report.artifacts,...apks.map(a=>a.metadata),report.historicalDemo])need(descriptor(pair.source.path).sha256===pair.source.sha256,'Built artifact/metadata or historical demo changed during verification');
    report.passed=true;
  } catch(error) { report.failure=error.message;process.exitCode=1; }
  finally {
    if(active){write('stage-a-failed.log',active.output);if(installedProgress)try{await device('failed-owned-stage-stop',['shell','am','force-stop',progressPackage]);}catch{}if(!active.terminal)active.child.kill('SIGTERM');}
    try{const after=sourceFiles();write('source-manifest-final.json',JSON.stringify(after,null,2)+'\n');report.sourceUnchanged=JSON.stringify(after)===JSON.stringify(report.sourceFiles);need(report.sourceUnchanged,'Sources changed during verification');}
    catch(error){report.passed=false;report.sourceFailure=error.message;process.exitCode=1;}
    report.finishedAt=new Date().toISOString();report.receiptArtifacts=walk(attempt).map(descriptor);
    const json=JSON.stringify(report,null,2)+'\n';write('report.json',json);
    for(const name of ['last-attempt.json',...(report.passed?['verification.json']:[])]){fs.writeFileSync(path.join(base,name+'.tmp'),json);fs.renameSync(path.join(base,name+'.tmp'),path.join(base,name));}
    console.log(JSON.stringify({passed:report.passed,attemptDirectory:report.attemptDirectory,kotlin:report.kotlinTests,node:report.node?.tests,native:report.nativeTests,sourceCount:report.sourceFiles?.length,artifacts:report.artifacts.length,failure:report.failure,sourceFailure:report.sourceFailure}));
  }
}
export {sourceFiles,declarations,nodeDeclarations,instrumentEvents,verifyNative,verifyNotice,interrupted,marker,verifyProgressManifest,verifyProgressScreenshots,verifyScreenshotSources,verifyInstalledManifest};
if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) await main();
