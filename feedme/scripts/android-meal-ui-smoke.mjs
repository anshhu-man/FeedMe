import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),serial=process.env.FEEDME_TEST_DEVICE;
if(!/^emulator-\d+$/.test(serial||'')||!process.env.ANDROID_HOME)throw new Error('Explicit local emulator and Android SDK required');
const adb=path.join(process.env.ANDROID_HOME,'platform-tools/adb'),pkg='com.feedme.app.test';
const runner=`${pkg}/androidx.test.runner.AndroidJUnitRunner`,className='com.feedme.app.mealflow.AndroidMealFlowPresentationTest';
const apkDir=path.join(root,'shared/app/build/outputs/apk/androidTest/debug');
const metadataBytes=fs.readFileSync(path.join(apkDir,'output-metadata.json')),metadata=JSON.parse(metadataBytes);
if(metadata.applicationId!==pkg||metadata.elements?.length!==1||metadata.elements[0].outputFile!=='app-debug-androidTest.apk')throw new Error('Unexpected isolated UI test artifact');
const apk=path.join(apkDir,metadata.elements[0].outputFile),apkBytes=fs.readFileSync(apk);
const startedAt=new Date().toISOString(),base=path.join(root,'docs/verification/meal-ui');
const attempt=path.join(base,'attempts',startedAt.replaceAll(':','-'));fs.mkdirSync(attempt,{recursive:true});
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const descriptor=file=>{const b=fs.readFileSync(file);return {path:path.relative(root,file),bytes:b.length,sha256:hash(b)}};
fs.writeFileSync(path.join(attempt,'output-metadata.json'),metadataBytes);
const source=path.join(root,'shared/app/src/androidInstrumentedTest/kotlin/com/feedme/app/mealflow/AndroidMealFlowPresentationTest.kt');
const methods=[...fs.readFileSync(source,'utf8').matchAll(/@Test\s+fun\s+(\w+)\s*\(/g)].map(x=>x[1]).sort();
if(methods.length!==6||new Set(methods).size!==6)throw new Error('UI test inventory changed');
const report={startedAt,passed:false,serial,scope:'Six actual Android Compose layout/accessibility/event checks, using synthetic test-only view-state fixtures. Verifies explicit event wiring, exact recipe quantities/mandatory safety steps, retained-retry copy and unavailable-state redaction. Does not authenticate a user, connect providers/HTTP/catalog, test retained-experience end-to-end composition or prove cooking/save/share authorization. Screenshots are native renders, not generated images. API35 ARM64 only; iOS and full release acceptance remain open.',
  apk:descriptor(apk),apkMetadata:descriptor(path.join(attempt,'output-metadata.json')),attemptDirectory:path.relative(root,attempt),screenshots:[]};
function run(name,args,timeout=10000){const result=spawnSync(adb,['-s',serial,...args],{encoding:'utf8',timeout,maxBuffer:8*1024*1024});
  const output=(result.stdout||'')+(result.stderr||'');fs.writeFileSync(path.join(attempt,`${name}.log`),output);
  if(result.error||result.status!==0)throw new Error(`${name} failed; inspect retained log`);return output;}
try {
  if(run('boot',['shell','getprop','sys.boot_completed']).trim()!=='1')throw new Error('Emulator not booted');
  report.androidApi=run('api',['shell','getprop','ro.build.version.sdk']).trim();report.androidAbi=run('abi',['shell','getprop','ro.product.cpu.abi']).trim();
  run('install',['install','-r','-t',apk],60000);
  if(!run('runner',['shell','pm','list','instrumentation']).includes(runner))throw new Error('UI test runner unavailable');
  const output=run('instrumentation',['shell','am','instrument','-w','-r','-e','class',className,runner],90000);
  const events=[];let fields={};
  for(const line of output.split(/\r?\n/)){
    const f=line.match(/^INSTRUMENTATION_STATUS: (class|test|id|current|numtests)=(.*)$/);
    if(f){if(Object.hasOwn(fields,f[1]))throw new Error('Duplicate UI runner field');fields[f[1]]=f[2];}
    const status=line.match(/^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$/);if(status){events.push({...fields,code:Number(status[1])});fields={};}
  }
  if(Object.keys(fields).length||events.length!==12)throw new Error('Incomplete UI runner events');
  const identities=[];
  for(let i=0;i<6;i++){
    const start=events[i*2],end=events[i*2+1];
    for(const e of [start,end])if(e.class!==className||e.id!=='AndroidJUnitRunner'||e.current!==String(i+1)||e.numtests!=='6'||!methods.includes(e.test))throw new Error('Wrong UI runner identity');
    if(start.code!==1||end.code!==0||start.test!==end.test)throw new Error('UI test failed/skipped/mismatched');identities.push(`${className}#${end.test}`);
  }
  const endings=[...output.matchAll(/^INSTRUMENTATION_CODE: (-?\d+)\s*$/gm)];
  if(JSON.stringify(identities.map(x=>x.split('#')[1]).sort())!==JSON.stringify(methods)||
    [...output.matchAll(/^OK \(6 tests\)\s*$/gm)].length!==1||endings.length!==1||endings[0][1]!=='-1'||
    /FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(output))throw new Error('UI tests did not pass exact inventory');
  report.tests={tests:6,failures:0,errors:0,skipped:0};report.identities=identities;
  for(const name of ['request','recommendations','recipe-ingredients','recipe-safety-step','unavailable']){
    const result=spawnSync(adb,['-s',serial,'exec-out','run-as',pkg,'cat',`no_backup/meal-ui-evidence/${name}.png`],{timeout:10000,maxBuffer:16*1024*1024});
    const bytes=result.stdout;if(result.error||result.status!==0||!bytes||!bytes.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])))throw new Error('Native screenshot missing/invalid');
    const file=path.join(attempt,`${name}.png`);fs.writeFileSync(file,bytes);report.screenshots.push(descriptor(file));
  }
  if(!fs.readFileSync(apk).equals(apkBytes)||!fs.readFileSync(path.join(apkDir,'output-metadata.json')).equals(metadataBytes))throw new Error('UI APK changed during run');
  report.passed=true;
}catch(error){report.failure=error.message;process.exitCode=1;}
finally{report.finishedAt=new Date().toISOString();report.evidence=fs.readdirSync(attempt).filter(n=>n!=='report.json').map(n=>descriptor(path.join(attempt,n)));
  const json=JSON.stringify(report,null,2)+'\n';fs.writeFileSync(path.join(attempt,'report.json'),json);fs.writeFileSync(path.join(base,'last-attempt.json'),json);
  if(report.passed)fs.writeFileSync(path.join(base,'verification.json'),json);console.log(JSON.stringify({passed:report.passed,tests:report.tests,failure:report.failure,attemptDirectory:report.attemptDirectory}));}
