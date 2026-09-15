import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';

export const FIXTURE_DIRECTORY='Reference/Fixtures/ninth-parser';
export const ORIGINAL_RECEIPT='feedme/docs/verification/cookbook-processor/attempts/2026-09-14T08-50-11.189Z/report.json';
export const ORIGINAL_RECEIPT_SHA='840990ff363394aa3ae152616ba92e84659f9ab293452ee4c31e2c996df504ab';
export const ORIGINAL_SOURCE_MANIFEST_SHA='6173cde4f7baaf2dcae6af2067c93fecebca719880851ba38c2ed2197ea793a7';
export const FILES=Object.freeze([
 ['apps/android/src/androidTestProgress/kotlin/com/feedme/development/progress/AndroidProgressCookbookHostTest.kt',20628,'c448a0371fe78b7da44e85c79d997481655a0ceca87301e663c0dddffd4b0309'],
 ['apps/android/src/androidTestProgress/kotlin/com/feedme/development/progress/AndroidProgressCookbookProcessTest.kt',15570,'d513652825935b36c9b090490034cfe8569ac4731c7c40d9e8766e70b91b4842'],
 ['apps/android/src/androidTestProgress/kotlin/com/feedme/development/progress/AndroidProgressHostTest.kt',29850,'d25f0a8daa760db7ac7c62c12051a6547e84e8845f67cf32470fdd277f67b1b0'],
 ['apps/android/src/androidTestProgress/kotlin/com/feedme/development/progress/AndroidProgressProcessTest.kt',15811,'2ba8d5b66daacf1b898f5640789fb29158cc3a579ead1d784afc9b8d54c7b11b'],
 ['apps/android/src/androidTestProgress/kotlin/com/feedme/development/progress/AndroidProgressTimerHostTest.kt',28356,'3810629b47c461420eb77b68be3bb8af713de8715ef9e0b81b25492640c2f7ca'],
 ['scripts/verify-cookbook-processor.mjs',55082,'6659895bbe6aa3996d44eefb8b917d5e2df92c82b366bc78a7f09ad5d9d7c9e4'],
 ['scripts/verify-cookbook-processor.test.mjs',8520,'efd35842080e1d2259115baf189427ab326c497575db76a5d296ac4fa8b7366a'],
 ['scripts/verify-native-cooking-host.mjs',16771,'4abe629382b6d5097df348040699a65c5ba691a92ac5fb1fb40a99374acc85ba'],
 ['scripts/verify-release-scope.mjs',7505,'8501dc81a0bf0e6d49e4a1212fd014416c3986c3836dba14ae516e906308d578'],
].map(([sourcePath,bytes,sha256])=>Object.freeze({sourcePath,bytes,sha256})));
export const FIXTURE_SCOPE='Portable frozen parser regression fixture only; not current-source, original receipt, native or release acceptance.';
export const README_TEXT=`# Portable frozen ninth parser fixture

These nine unchanged source files support ten frozen Node parser regressions. The original local acceptance receipt is NOT bundled. Its SHA-256 is recorded only as provenance; this portable manifest is a new extraction record, not that receipt or its acceptance authority.

Run the standalone verifier from the publication root with \`node tools/verify-historical-parser-fixture.mjs . --run-tests\`. Without \`--run-tests\`, it checks fixture integrity only. Replaying these ten tests does not rerun historical native tests or verify the expanded current app.

The source workspace's historicalNinthClosure and fixed acceptance hashes remain unchanged and still require the original private receipt. Do not substitute this manifest, rewrite those hashes, or advertise all historical checkpoint scripts or a wildcard Node command as portable current-source acceptance.

The workspace subdirectory preserves original relative paths solely for immutable test imports and source reads. No Gradle, Android device, network, provider or original report is required by the ten parser tests.
`;
export const need=(value,message)=>{if(!value)throw Error(message);};
export const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
export function canonicalDirectory(directory){
 const absolute=path.resolve(directory),stat=fs.lstatSync(absolute);
 need(stat.isDirectory()&&!stat.isSymbolicLink()&&fs.realpathSync(absolute)===absolute,'Canonical regular directory required');
 return absolute;
}
export function relativePath(value){
 need(typeof value==='string'&&value.length>0&&!value.includes('\\')&&!path.isAbsolute(value)&&
  value.split('/').every(part=>part&&part!=='.'&&part!=='..'),'Unsafe relative path');return value;
}
export function readRegularWithin(root,relative){
 const parts=relativePath(relative).split('/');let current=root;
 for(const [index,part]of parts.entries()){
  current=path.join(current,part);const stat=fs.lstatSync(current);
  need(!stat.isSymbolicLink()&&(index===parts.length-1?stat.isFile():stat.isDirectory()),'Symlink or nonregular fixture input');
 }
 return fs.readFileSync(current);
}
export function requirePrivateDataAbsent(bytes){
 need(!bytes.includes(0)&&Buffer.from(bytes.toString('utf8')).equals(bytes),'Fixture must be exact UTF-8 text');
 const text=bytes.toString('utf8');
 need(!/\/Users\/(?!LOCAL_USER(?:\/|\b))[^/\s"'<>]+|%2fUsers%2f(?!LOCAL_USER)[A-Za-z0-9._-]+/i.test(text),'Fixture needs home-path redaction; refuse changed historical bytes');
 need(!/-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----|\bgh[pousr]_[A-Za-z0-9]{30,}\b|\bgithub_pat_[A-Za-z0-9_]{40,}\b|\bAKIA[A-Z0-9]{16}\b|\bAIza[0-9A-Za-z_-]{30,}\b/.test(text),'Fixture contains a credential pattern');
}
export function manifestText(){return JSON.stringify({version:1,scope:FIXTURE_SCOPE,
 originalLocalProvenance:{receiptSha256:ORIGINAL_RECEIPT_SHA,sourceManifestSha256:ORIGINAL_SOURCE_MANIFEST_SHA,
  receiptBundled:false,originalAcceptanceVerifiedByPortableTool:false},
 files:FILES.map(file=>({path:'workspace/'+file.sourcePath,originalSourcePath:file.sourcePath,bytes:file.bytes,sha256:file.sha256}))},null,2)+'\n';}
function entries(root,relative=''){
 return fs.readdirSync(path.join(root,relative),{withFileTypes:true}).flatMap(entry=>{
  const next=relative?relative+'/'+entry.name:entry.name;
  need(!entry.isSymbolicLink()&&(entry.isFile()||entry.isDirectory()),'Symlink or special fixture entry');
  return entry.isDirectory()?[next+'/',...entries(root,next)]:[next];
 }).sort();
}
export function verifyHistoricalParserFixture(publicationRoot){
 const root=canonicalDirectory(publicationRoot),fixture=path.join(root,FIXTURE_DIRECTORY);
 const manifest=readRegularWithin(root,FIXTURE_DIRECTORY+'/manifest.json');
 need(manifest.equals(Buffer.from(manifestText())),'Portable fixture manifest differs from exact extraction contract');
 need(readRegularWithin(root,FIXTURE_DIRECTORY+'/README.md').equals(Buffer.from(README_TEXT)),'Fixture scope notice changed');
 const expected=new Set(['manifest.json','README.md']);
 for(const file of FILES){
  const relative='workspace/'+file.sourcePath;expected.add(relative);
  for(let parent=path.posix.dirname(relative);parent!=='.';parent=path.posix.dirname(parent))expected.add(parent+'/');
  const bytes=readRegularWithin(root,FIXTURE_DIRECTORY+'/'+relative);requirePrivateDataAbsent(bytes);
  need(bytes.length===file.bytes&&sha(bytes)===file.sha256,'Historical fixture source changed: '+file.sourcePath);
 }
 need(JSON.stringify(entries(fixture))===JSON.stringify([...expected].sort()),'Missing or extra fixture entry');
 return {passed:true,scope:FIXTURE_SCOPE,files:FILES.length,bytes:FILES.reduce((n,file)=>n+file.bytes,0),manifestSha256:sha(manifest),originalReceiptBundled:false,testsExecuted:0};
}
const TEST_NAMES=Object.freeze([
 'cookbook marker retains exact positive Long revision and elapsed precision',
 'cookbook marker rejects oversized extra duplicate reordered and noncanonical fields',
 'cookbook marker rejects invalid identity hash numeric types and overflow',
 'cookbook notice requires all four exact bounded status fields including UID',
 'cookbook interrupted stage is live start only then exactly one non-success crash terminal',
 'cookbook recovery requires exact start success notice and unique normal terminal',
 'cookbook lineage rejects any changed content revision owner reused PID or stale elapsed time',
 'cookbook PNG inventory is tied to four literal calls in their actual source methods',
 'cookbook process source declares only the exact interrupted and recovery identities',
 'existing timer witness retains exact Long precision and cannot be counted as a passing test',
]);
export function replayHistoricalParserFixture(publicationRoot){
 const result=verifyHistoricalParserFixture(publicationRoot),root=canonicalDirectory(publicationRoot);
 const workspace=path.join(root,FIXTURE_DIRECTORY,'workspace');
 // A caller may itself be a Node test; this is a separate explicit test-runner process.
 const environment={...process.env};delete environment.NODE_TEST_CONTEXT;
 const run=spawnSync(process.execPath,['--test','--test-reporter=tap',path.join(workspace,'scripts/verify-cookbook-processor.test.mjs')],
  {cwd:workspace,env:environment,encoding:'utf8',timeout:30_000,maxBuffer:4*1024*1024});
 const tap=run.stdout??'';need(run.status===0&&!run.error&&!run.signal,'Frozen parser replay failed: '+(run.stderr??''));
 const started=[...tap.matchAll(/^# Subtest: (.*)$/gm)].map(m=>m[1]);
 const passed=[...tap.matchAll(/^ok (\d+) - (.*)$/gm)];
 need(JSON.stringify(started)===JSON.stringify(TEST_NAMES)&&JSON.stringify(passed.map(m=>m[2]))===JSON.stringify(TEST_NAMES)&&
  passed.every((m,i)=>Number(m[1])===i+1)&&!/^not ok\b|# (?:SKIP|TODO)\b/m.test(tap),'Frozen parser test identities changed');
 for(const [name,count]of Object.entries({tests:10,pass:10,fail:0,cancelled:0,skipped:0,todo:0})){
  const fields=[...tap.matchAll(new RegExp('^# '+name+' (\\d+)$','gm'))];need(fields.length===1&&Number(fields[0][1])===count,'Frozen parser result count changed');
 }
 verifyHistoricalParserFixture(root);
 return {...result,testsExecuted:10,testIdentities:[...TEST_NAMES],tap};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const args=process.argv.slice(2);need(args.length===1||(args.length===2&&args[1]==='--run-tests'),'Use PUBLICATION_ROOT [--run-tests]');
 const result=args.length===2?replayHistoricalParserFixture(args[0]):verifyHistoricalParserFixture(args[0]);
 console.log(JSON.stringify(result,null,2));
}
