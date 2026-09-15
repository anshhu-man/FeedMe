// Pure exact evidence parsing. Deliberately killed tests are not ordinary JUnit successes.
export const startupClass = 'com.feedme.session.AndroidSessionSetupProcessInterruptionTest';
export const startupScenarios = Object.freeze([
  {name:'opening', interrupt:'interruptDuringExistingOpen', recover:'recoverAfterInterruptedExistingOpen', control:'unconfirmed', closed:0},
  {name:'ready', interrupt:'interruptReadyWithoutConfirmation', recover:'recoverUnconfirmedReadyWithoutInferringConsent', control:'unconfirmed', closed:0},
  {name:'work-aborted', interrupt:'interruptAfterWorkAbort', recover:'recoverAfterAcknowledgedWorkAbort', control:'requested', closed:0},
  {name:'data-aborted', interrupt:'interruptAfterDataAbort', recover:'recoverAfterAcknowledgedDataAbort', control:'requested', closed:0},
  {name:'work-closed', interrupt:'interruptAfterWorkClose', recover:'recoverAfterAcknowledgedWorkClose', control:'requested', closed:1},
  {name:'data-closed', interrupt:'interruptAfterDataClose', recover:'recoverAfterAcknowledgedDataClose', control:'requested', closed:2},
  {name:'complete', interrupt:'interruptAfterCompleteBeforeControlClose', recover:'rejectCompleteAsFreshAbortAuthority', control:'complete', closed:3},
].map(Object.freeze));
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function need(value, message) { if (!value) throw new Error(message); }
function knownScenario(scenario) {
  const expected=startupScenarios.find(x=>x.name===scenario?.name);
  need(expected && Object.keys(scenario).length===5 && Object.keys(expected).every(key=>scenario[key]===expected[key]),'Invalid scenario definition');
}
export function startupCheckpoint(raw, ownershipRaw, runId, scenario, livePid) {
  knownScenario(scenario);
  need(uuid.test(runId) && startupScenarios.some(x=>x.name===scenario.name), 'Invalid scenario/run binding');
  need(typeof raw === 'string' && Buffer.byteLength(raw)>0 && Buffer.byteLength(raw)<=1024, 'Invalid checkpoint bound');
  need(typeof ownershipRaw === 'string' && Buffer.byteLength(ownershipRaw)>0 && Buffer.byteLength(ownershipRaw)<=1024, 'Invalid ownership bound');
  need(Number.isSafeInteger(livePid) && livePid>0, 'Invalid live PID');
  const expected = {version:1,runId,scenario:scenario.name,pid:livePid,control:scenario.control,closed:scenario.closed};
  need(raw===JSON.stringify(expected), 'Checkpoint differs from exact witnessed stage');
  need(ownershipRaw===JSON.stringify({version:1,runId,scenario:scenario.name,creatorPid:livePid}), 'Wrong fixture ownership witness');
  return expected;
}
export function startupEvents(output) {
  const events=[]; let fields={};
  for (const line of output.split(/\r?\n/)) {
    const field=line.match(/^INSTRUMENTATION_STATUS: (class|test|current|numtests|id)=(.*)$/);
    if(field) { need(!Object.hasOwn(fields,field[1]), 'Duplicate runner field'); fields[field[1]]=field[2]; }
    const status=line.match(/^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$/);
    if(status) { events.push({...fields,code:Number(status[1])}); fields={}; }
  }
  need(Object.keys(fields).length===0, 'Incomplete runner event');
  return events;
}
function isEvent(e, method, code) { return e?.class===startupClass && e?.test===method && e?.id==='AndroidJUnitRunner' &&
  e?.current==='1' && e?.numtests==='1' && e?.code===code; }
export function startupInterrupted(output, scenario) {
  knownScenario(scenario);
  const events=startupEvents(output);
  need(events.length===1 && isEvent(events[0],scenario.interrupt,1), 'Interruption requires exactly one matching start and no finish');
  need(!/^OK \(/m.test(output) && !/FAILURES!!!|Host did not interrupt|AssertionError/.test(output), 'Interrupted stage cannot count as passing or timed-out test');
  return {identity:`${startupClass}#${scenario.interrupt}`, witnessedInterruption:true, passedTest:false};
}
export function startupRecovered(output, scenario, previousPid) {
  knownScenario(scenario);
  const events=startupEvents(output);
  need(events.length===2 && isEvent(events[0],scenario.recover,1) && isEvent(events[1],scenario.recover,0), 'Recovery requires an exact start/success pair');
  need([...output.matchAll(/^OK \(1 test\)\s*$/gm)].length===1, 'Recovery must report exactly one passing test');
  const endings=[...output.matchAll(/^INSTRUMENTATION_CODE: (-?\d+)\s*$/gm)];
  need(endings.length===1 && endings[0][1]==='-1' && !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(output), 'Recovery instrumentation failed');
  function result(name) { const found=[...output.matchAll(new RegExp(`^INSTRUMENTATION_RESULT: ${name}=(.*)$`,'gm'))];
    need(found.length===1,'Missing/duplicate recovery result'); return found[0][1].trim(); }
  need(result('startup_recovery_scenario')===scenario.name, 'Wrong recovery scenario');
  need(result('startup_recovery_previous_pid')===String(previousPid), 'Wrong interrupted PID');
  const rawPid=result('startup_recovery_pid'); need(/^[1-9]\d*$/.test(rawPid),'Invalid recovery PID');
  const pid=Number(rawPid); need(Number.isSafeInteger(pid) && pid!==previousPid,'Recovery must use a distinct fresh process');
  need(result('startup_recovery_control')==='complete' && result('startup_recovery_closed')==='3' &&
    result('startup_recovery_cleanup')==='exact-owned-fixture-removed','Incomplete recovery/cleanup evidence');
  return {identity:`${startupClass}#${scenario.recover}`,passed:true,tests:1,previousPid,pid};
}
