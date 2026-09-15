import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash,randomUUID} from 'node:crypto';
import {spawn,spawnSync} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
import {startupClass,startupScenarios,startupCheckpoint,startupInterrupted,startupRecovered} from './startup-process-evidence.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const serial=process.env.FEEDME_TEST_DEVICE;
if(!/^emulator-\d+$/.test(serial||'') || !process.env.ANDROID_HOME) throw new Error('Explicit local emulator and ANDROID_HOME required');
const adb=path.join(process.env.ANDROID_HOME,'platform-tools/adb'), pkg='com.feedme.session.test';
const runner=`${pkg}/androidx.test.runner.AndroidJUnitRunner`;
const apkDir=path.join(root,'shared/session/build/outputs/apk/androidTest/debug');
const metadataBytes=fs.readFileSync(path.join(apkDir,'output-metadata.json')),metadata=JSON.parse(metadataBytes);
if(metadata.applicationId!==pkg || metadata.elements?.length!==1 || metadata.elements[0].outputFile!=='session-debug-androidTest.apk') throw new Error('Unexpected isolated test APK identity');
const apk=path.join(apkDir,metadata.elements[0].outputFile),apkBytes=fs.readFileSync(apk);
const startedAt=new Date().toISOString(),base=path.join(root,'docs/verification/startup-process');
const attempt=path.join(base,'attempts',startedAt.replaceAll(':','-'));fs.mkdirSync(attempt,{recursive:true});
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const descriptor=file=>{const b=fs.readFileSync(file);return {path:path.relative(root,file),bytes:b.length,sha256:hash(b)}};
fs.writeFileSync(path.join(attempt,'output-metadata.json'),metadataBytes);
const sourcePath=path.join(root,'shared/session/src/androidInstrumentedTest/kotlin/com/feedme/session/AndroidSessionSetupProcessInterruptionTest.kt');
const declared=[...fs.readFileSync(sourcePath,'utf8').matchAll(/@Test\s+fun\s+(\w+)\s*\(/g)].map(x=>x[1]).sort();
if(JSON.stringify(declared)!==JSON.stringify(startupScenarios.flatMap(x=>[x.interrupt,x.recover]).sort())) throw new Error('Exact source selectors differ');
const report={startedAt,passed:false,serial,scope:'Seven host-witnessed forced terminations of the isolated session-test process at acknowledged startup boundaries, each followed by a distinct-process public-owner recovery. Interrupted starts are NOT passed tests. Real native stores/plans with synthetic test identity and programmatic consent; not physical power loss, in-transaction crash, general journal/orphan recovery, real provider/UI/iOS or release acceptance.',
  apk:descriptor(apk),apkMetadata:descriptor(path.join(attempt,'output-metadata.json')),attemptDirectory:path.relative(root,attempt),stages:[]};
function raw(args,timeout=10000){return spawnSync(adb,['-s',serial,...args],{encoding:'utf8',timeout,maxBuffer:4*1024*1024});}
function run(name,args,timeout=10000,allowFailure=false){const r=raw(args,timeout),out=(r.stdout||'')+(r.stderr||'');
  fs.writeFileSync(path.join(attempt,`${name}.log`),out);if(!allowFailure&&(r.error||r.status!==0))throw new Error(`${name} failed; inspect retained log`);return {out,status:r.status,error:r.error?.code};}
function pids(name){const r=run(name,['shell','pidof',pkg],10000,true);if(r.error||![0,1].includes(r.status))throw new Error('PID observation failed');
  const value=r.out.trim();if(!value)return [];if(!/^[1-9]\d*( [1-9]\d*)*$/.test(value))throw new Error('Unexpected PID observation');return value.split(' ').map(Number);}
let active=null;
try {
  if(run('boot',['shell','getprop','sys.boot_completed']).out.trim()!=='1')throw new Error('Emulator not booted');
  report.androidApi=run('api',['shell','getprop','ro.build.version.sdk']).out.trim();
  report.androidAbi=run('abi',['shell','getprop','ro.product.cpu.abi']).out.trim();
  run('install',['install','-r','-t',apk],60000);
  if(!run('runners',['shell','pm','list','instrumentation']).out.includes(runner))throw new Error('Isolated runner unavailable');
  // These tests are purpose-selected, never invoked as a regular all-tests suite.
  for(const [index,scenario] of startupScenarios.entries()) {
    const name=`${index+1}-${scenario.name}`,runId=randomUUID();
    // A previously successful instrumentation process may linger; terminate only this test package.
    run(`${name}-preflight-stop`,['shell','am','force-stop',pkg]);
    if(pids(`${name}-preflight-pid`).length)throw new Error('Test process did not stop before new stage');
    const args=['-s',serial,'shell','am','instrument','-w','-r','-e','class',`${startupClass}#${scenario.interrupt}`,
      '-e','startupRunId',runId,'-e','startupScenario',scenario.name,'-e','startupAction','interrupt',runner];
    const child=spawn(adb,args,{stdio:['ignore','pipe','pipe']});
    active={child,scenario,name,runId,output:'',terminal:false};
    const running=active;
    child.stdout.on('data',b=>{running.output+=b.toString('utf8');if(running.output.length>4*1024*1024)child.kill('SIGTERM');});
    child.stderr.on('data',b=>{running.output+=b.toString('utf8');});
    const completed=new Promise(resolve=>{child.once('error',e=>{running.error=e.code;running.terminal=true;resolve();});child.once('close',(code,signal)=>{running.code=code;running.signal=signal;running.terminal=true;resolve();});});
    const stage={name:scenario.name,runId,interruptSelector:`${startupClass}#${scenario.interrupt}`,recoverSelector:`${startupClass}#${scenario.recover}`,passed:false};report.stages.push(stage);
    const marker=`no_backup/retirement-integration-process-${runId}`;
    const deadline=performance.now()+45000;let witness=null;
    while(performance.now()<deadline&&!running.terminal){
      const observed=raw(['shell','run-as',pkg,'cat',`${marker}/checkpoint.json`],5000);
      if(observed.status===0&&!observed.error){
        const live=pids(`${name}-live-pid`);if(live.length!==1)throw new Error('Witness needs one exact live test PID');
        const owned=run(`${name}-ownership`,['shell','run-as',pkg,'cat',`${marker}/ownership.json`]);
        witness=startupCheckpoint(observed.stdout,owned.out,runId,scenario,live[0]);
        fs.writeFileSync(path.join(attempt,`${name}-checkpoint.json`),observed.stdout);
        fs.writeFileSync(path.join(attempt,`${name}-before-stop.log`),running.output);
        startupInterrupted(running.output,scenario);if(running.terminal)throw new Error('Stage terminated before host interruption');
        break;
      }
      if(observed.error)throw new Error('Witness observation failed');
      await delay(150);
    }
    if(!witness)throw new Error('No live exact-stage checkpoint before deadline; fixture preserved');
    stage.checkpoint=witness;stage.forceStop=run(`${name}-force-stop`,['shell','am','force-stop',pkg]);
    for(let i=0;i<20&&!running.terminal;i++)await Promise.race([completed,delay(100)]);
    if(!running.terminal)throw new Error('Interrupted instrumentation handle still live');
    fs.writeFileSync(path.join(attempt,`${name}-interrupted.log`),running.output);
    stage.interruption=startupInterrupted(running.output,scenario);stage.interruptedExit={code:running.code,signal:running.signal};
    const after=pids(`${name}-after-stop-pid`);if(after.length)throw new Error('Killed process still present');
    stage.processAbsentAfterStop=true;active=null;
    const recovered=run(`${name}-recovered`,['shell','am','instrument','-w','-r','-e','class',`${startupClass}#${scenario.recover}`,
      '-e','startupRunId',runId,'-e','startupScenario',scenario.name,'-e','startupAction','recover',runner],60000);
    stage.recovery=startupRecovered(recovered.out,scenario,witness.pid);
    const remaining=run(`${name}-fixture-cleanup`,['shell','run-as',pkg,'ls','no_backup']).out;
    if(remaining.includes(`retirement-integration-process-${runId}`))throw new Error('Successful recovery left owned fixture');
    stage.passed=true;console.log(`${scenario.name}: witnessed interruption + fresh-process recovery passed`);
  }
  if(!fs.readFileSync(apk).equals(apkBytes)||!fs.readFileSync(path.join(apkDir,'output-metadata.json')).equals(metadataBytes))throw new Error('APK changed during process test');
  report.tests={tests:7,failures:0,errors:0,skipped:0};report.witnessedInterruptions=7;report.passed=true;
} catch(error){report.failure=error.message;process.exitCode=1;}
finally {
  if(active){fs.writeFileSync(path.join(attempt,`${active.name}-interrupted.log`),active.output);
    // Stops only the explicitly installed instrumentation package; no fixture data is cleared.
    run(`${active.name}-failure-stop`,['shell','am','force-stop',pkg],10000,true);
    if(!active.terminal)active.child.kill('SIGTERM');
  }
  report.finishedAt=new Date().toISOString();
  report.evidence=fs.readdirSync(attempt).filter(n=>n!=='report.json').map(n=>descriptor(path.join(attempt,n)));
  const output=JSON.stringify(report,null,2)+'\n';fs.writeFileSync(path.join(attempt,'report.json'),output);
  fs.writeFileSync(path.join(base,'last-attempt.json'),output);if(report.passed)fs.writeFileSync(path.join(base,'verification.json'),output);
  console.log(JSON.stringify({passed:report.passed,tests:report.tests,witnessedInterruptions:report.witnessedInterruptions,failure:report.failure,attemptDirectory:report.attemptDirectory}));
}
