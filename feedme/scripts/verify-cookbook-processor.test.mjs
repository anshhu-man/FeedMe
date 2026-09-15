import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {cookbookMarker,verifyCookbookNotice,verifyCookbookLineage,interrupted,verifyNative,
  instrumentEvents,marker,verifyScreenshotSources,cookbookHostClass,cookbookStageA,cookbookStageB,cookbookPngMethods,declarations} from './verify-cookbook-processor.mjs';

const id='00000000-0000-4000-8000-000000000001',saved='00000000-0000-4000-8000-000000000002';
function raw({stage='retained-unclosed',revision='9007199254740993',uid=10001,pid=301,elapsed='1000'}={}) {
  return Buffer.from(`{"version":1,"stage":"${stage}","planId":"${id}","savedRecipeId":"${saved}","savedContentSha256":"${'a'.repeat(64)}","savedLocalRevision":${revision},"uid":${uid},"pid":${pid},"startElapsedRealtime":${elapsed}}`);
}
function notice(stage='RETAINED_UNCLOSED',pid='301',uid='10001',elapsed='1000') {
  return {code:2,feedme_cookbook_stage:stage,feedme_cookbook_pid:pid,feedme_cookbook_uid:uid,feedme_cookbook_start_elapsed:elapsed};
}
function status(fields,code) { return Object.entries(fields).map(([k,v])=>`INSTRUMENTATION_STATUS: ${k}=${v}\n`).join('')+`INSTRUMENTATION_STATUS_CODE: ${code}\n`; }
function noticeText(value=notice()) {const {code,...fields}=value;return status(fields,code);}
function event(identity,code) {const [klass,method]=identity.split('#');return status({class:klass,test:method,id:'AndroidJUnitRunner',current:'1',numtests:'1'},code);}
const start=()=>event(cookbookStageA,1)+noticeText();
const crash='INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n';
const success=()=>event(cookbookStageB,1)+noticeText(notice('RESTORED_AND_RESET','302','10001','1001'))+event(cookbookStageB,0)+'OK (1 test)\nINSTRUMENTATION_CODE: -1\n';

test('cookbook marker retains exact positive Long revision and elapsed precision',()=>{
  const value=cookbookMarker(raw({revision:'9223372036854775807',elapsed:'9223372036854775806'}),'retained-unclosed');
  assert.equal(value.savedLocalRevision,'9223372036854775807');assert.equal(value.startElapsedRealtime,'9223372036854775806');
  assert.equal(value.uid,10001);assert.equal(value.savedContentSha256,'a'.repeat(64));
});
test('cookbook marker rejects oversized extra duplicate reordered and noncanonical fields',()=>{
  const base=raw().toString();
  for(const invalid of [Buffer.alloc(513),Buffer.from(base+'\n'),Buffer.from(base.replace('{','{"extra":1,')),
    Buffer.from(base.replace('"version":1','"version":1,"version":1')),Buffer.from(JSON.stringify(JSON.parse(base),null,2)),
    Buffer.from(base.replace('"version":1','"version":2'))])assert.throws(()=>cookbookMarker(invalid,'retained-unclosed'));
  assert.throws(()=>cookbookMarker(raw(),'unrecognized-stage'));
});
test('cookbook marker rejects invalid identity hash numeric types and overflow',()=>{
  for(const revision of ['0','-1','01','1.0','1e0','"1"','9223372036854775808'])assert.throws(()=>cookbookMarker(raw({revision}),'retained-unclosed'));
  for(const elapsed of ['-1','00','1.0','"1"','9223372036854775808'])assert.throws(()=>cookbookMarker(raw({elapsed}),'retained-unclosed'));
  for(const uid of [0,-1,2147483648,1.5])assert.throws(()=>cookbookMarker(raw({uid}),'retained-unclosed'));
  for(const invalid of [raw().toString().replace(saved,'foreign'),raw().toString().replace('a'.repeat(64),'A'.repeat(64)),
    raw().toString().replace('"pid":301','"pid":"301"')])assert.throws(()=>cookbookMarker(Buffer.from(invalid),'retained-unclosed'));
});
test('cookbook notice requires all four exact bounded status fields including UID',()=>{
  assert.deepEqual(verifyCookbookNotice(notice(),'RETAINED_UNCLOSED'),{pid:301,uid:10001,startElapsedRealtime:'1000'});
  const absent=notice();delete absent.feedme_cookbook_uid;
  for(const invalid of [absent,{...notice(),unexpected:'x'},notice('RESTORED_AND_RESET'),notice('RETAINED_UNCLOSED','0'),
    notice('RETAINED_UNCLOSED','301','2147483648'),notice('RETAINED_UNCLOSED','301','10001','9223372036854775808')])
    assert.throws(()=>verifyCookbookNotice(invalid,'RETAINED_UNCLOSED'));
});
test('cookbook interrupted stage is live start only then exactly one non-success crash terminal',()=>{
  assert.equal(interrupted(start(),true,cookbookStageA,verifyCookbookNotice).pid,301);
  assert.equal(interrupted(start()+crash,false,cookbookStageA,verifyCookbookNotice).uid,10001);
  for(const invalid of [start()+crash,start()+'OK (1 test)\n',start()+'TimeoutCancellationException\n',start()+event(cookbookStageA,0)])
    assert.throws(()=>interrupted(invalid,true,cookbookStageA,verifyCookbookNotice));
  for(const invalid of [start(),start()+crash+crash,start()+crash.replace('CODE: 0','CODE: -1'),start()+crash+'OK (1 test)\n'])
    assert.throws(()=>interrupted(invalid,false,cookbookStageA,verifyCookbookNotice));
});
test('cookbook recovery requires exact start success notice and unique normal terminal',()=>{
  const validate=e=>verifyCookbookNotice(e,'RESTORED_AND_RESET');
  assert.equal(verifyNative(success(),[cookbookStageB],validate).tests,1);
  for(const invalid of [success().replace('STATUS_CODE: 0','STATUS_CODE: -2'),success()+'INSTRUMENTATION_CODE: 0\n',
    success().replace('feedme_cookbook_uid=10001','feedme_cookbook_uid=0'),success().replace(noticeText(notice('RESTORED_AND_RESET','302','10001','1001')),''),
    success().replace('STATUS_CODE: 2','STATUS: feedme_cookbook_uid=10001\nINSTRUMENTATION_STATUS_CODE: 2')])
    assert.throws(()=>verifyNative(invalid,[cookbookStageB],validate));
  assert.throws(()=>instrumentEvents('INSTRUMENTATION_STATUS: feedme_cookbook_uid=10001\n'));
});
test('cookbook lineage rejects any changed content revision owner reused PID or stale elapsed time',()=>{
  const old=cookbookMarker(raw(),'retained-unclosed'),fresh=cookbookMarker(raw({stage:'restored-and-reset',pid:302,elapsed:'1001'}),'restored-and-reset');
  const n=verifyCookbookNotice(notice('RESTORED_AND_RESET','302','10001','1001'),'RESTORED_AND_RESET');
  verifyCookbookLineage(old,fresh,n,10001);
  for(const [key,value]of Object.entries({planId:saved,savedRecipeId:id,savedContentSha256:'b'.repeat(64),savedLocalRevision:'9007199254740992',uid:10002,pid:301,startElapsedRealtime:'1000'}))
    assert.throws(()=>verifyCookbookLineage(old,{...fresh,[key]:value},n,10001));
  assert.throws(()=>verifyCookbookLineage(old,fresh,{...n,uid:10002},10001));
  assert.throws(()=>verifyCookbookLineage(old,fresh,n,10002));
});
test('cookbook PNG inventory is tied to four literal calls in their actual source methods',()=>{
  const source=fs.readFileSync(new URL('../apps/android/src/androidTestProgress/kotlin/com/feedme/development/progress/AndroidProgressCookbookHostTest.kt',import.meta.url),'utf8');
  assert.deepEqual(verifyScreenshotSources(source,cookbookHostClass,cookbookPngMethods),cookbookPngMethods);
  for(const invalid of [source.replace('screenshot("progress-cookbook-saved")',''),source.replace('screenshot("progress-cookbook-page")','screenshot("unknown")'),
    source.replace('fun aExplicitSaveShowsExactMaterializedRecipeThenDistinctRemotePage','fun changedOwner')])
    assert.throws(()=>verifyScreenshotSources(invalid,cookbookHostClass,cookbookPngMethods));
});
test('cookbook process source declares only the exact interrupted and recovery identities',()=>{
  const ids=declarations(['apps/android/src/androidTestProgress']).filter(id=>id.startsWith('com.feedme.development.progress.AndroidProgressCookbookProcessTest#'));
  assert.deepEqual(ids,[cookbookStageA,cookbookStageB]);
  assert.throws(()=>verifyNative(success(),[cookbookStageA],e=>verifyCookbookNotice(e,'RESTORED_AND_RESET')));
});
test('existing timer witness retains exact Long precision and cannot be counted as a passing test',()=>{
  const klass='com.feedme.development.progress.AndroidProgressProcessTest',identity=klass+'#stageARetainAcknowledgedCookingThenAwaitExternalProcessStop';
  const timerNotice=status({feedme_progress_stage:'RETAINED_UNCLOSED',feedme_progress_pid:'201',feedme_progress_start_elapsed:'500'},2);
  const output=event(identity,1)+timerNotice;
  assert.equal(interrupted(output,true).pid,201);assert.equal(interrupted(output+crash).pid,201);
  assert.throws(()=>verifyNative(output+crash,[identity]));
  const bytes=Buffer.from(`{"version":2,"stage":"retained-unclosed","planId":"${id}","sessionId":"${saved}","timerId":"${id}","timerGeneration":9223372036854775807,"pid":201,"startElapsedRealtime":500}`);
  assert.equal(marker(bytes,'retained-unclosed').timerGeneration,'9223372036854775807');
});
