import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {product,features,screens,modules} from '../spec/model.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const read=p=>fs.readFileSync(path.join(root,p),'utf8');
const api=JSON.parse(read('architecture/04_API_Contract.json'));
const checks=[];
const check=(name,pass,detail='')=>checks.push({name,passed:!!pass,...(detail?{detail}: {})});
const sids=new Set(screens.map(s=>s.id)),fids=new Set(features.map(f=>f.id));
const actions=screens.flatMap(s=>s.actions.map(a=>({...a,screen:s.id})));
const operations=Object.entries(api.paths).flatMap(([url,item])=>Object.entries(item).filter(([m])=>['get','post','put','patch','delete'].includes(m)).map(([method,o])=>({request:method.toUpperCase()+' '+url,url,method,...o})));
const byRequest=new Map(operations.map(o=>[o.request,o]));
const resolve=ref=>ref.slice(2).split('/').map(x=>x.replaceAll('~1','/').replaceAll('~0','~')).reduce((o,k)=>o?.[k],api);
check('54 unique feature specifications',features.length===54&&fids.size===54);
check('Unique screen and action IDs',sids.size===screens.length&&new Set(actions.map(a=>a.id)).size===actions.length);
check('Welcome is the app entry',product.start==='AUTH_WELCOME');
check('Confirmed shared Kotlin platform',product.platform.includes('Android + iOS')&&product.platform.includes('Kotlin'));
const headings=['Strategy','Experience and screen design','Production sequence','Data and API contracts','Permissions and lifecycle','Failure and offline behavior','Integration and dependencies','Execution plan','Acceptance and release gates'];
for(const f of features){const text=read(f.file);check(f.id+' has all nine feature design sections',headings.every(h=>text.toLowerCase().includes('## '+h.toLowerCase())));check(f.id+' maps to screens',screens.some(s=>s.features.includes(f.id)));}
const badTargets=[];for(const s of screens)for(const a of s.actions){for(const id of [a.target,...(a.branches||[]).map(b=>b[0]),a.picker?.returnTo,a.picker?.cancelTo].filter(Boolean))if(!sids.has(id))badTargets.push(a.id+' → '+id);}
check('Every destination, branch and picker return resolves',!badTargets.length,badTargets.join('; '));
check('Every screen has feature/module/entry state contracts',screens.every(s=>s.features.every(f=>fids.has(f))&&modules[s.module]&&s.actions.length&&s.loads.length&&s.contextRules&&Object.keys(s.states).length===4));
check('Every action declares effects, auth, failure, offline and retry',actions.every(a=>a.effect&&a.auth&&a.guard&&a.errors&&a.offline&&a.idempotency));
check('Destructive commands have an explicit confirmation',actions.filter(a=>a.destructive).every(a=>a.confirm));
const http=[...actions.map(a=>({source:a.id,request:a.request})),...screens.flatMap(s=>s.loads.map(request=>({source:s.id+' entry',request})))].filter(a=>/^(GET|POST|PUT|PATCH|DELETE) /.test(a.request));
const missing=http.filter(a=>!byRequest.has(a.request));check('All screen HTTP actions and hydration reads resolve to OpenAPI',!missing.length,missing.map(x=>x.source+': '+x.request).join('; '));
check('Unique OpenAPI operation IDs',new Set(operations.map(o=>o.operationId)).size===operations.length);
const unresolved=[];let refs=0;function walk(v){if(!v||typeof v!=='object')return;for(const [k,x]of Object.entries(v)){if(k==='$ref'){refs++;if(!x.startsWith('#/')||resolve(x)===undefined)unresolved.push(x);}else walk(x);}}walk(api);
check('All OpenAPI local references resolve',!unresolved.length,refs+' refs inspected'+(unresolved.length?'; '+unresolved.join(', '):''));
const badPath=[];for(const o of operations)for(const name of o.url.matchAll(/\{([^}]+)\}/g)){const params=[...(api.paths[o.url].parameters||[]),...(o.parameters||[])].map(p=>p.$ref?resolve(p.$ref):p);if(!params.some(p=>p.in==='path'&&p.name===name[1]&&p.required))badPath.push(o.request+': '+name[1]);}
check('Every API path parameter is declared and required',!badPath.length,badPath.join('; '));
check('Every operation has a success response',operations.every(o=>Object.keys(o.responses||{}).some(k=>/^2\d\d$/.test(k))));
check('Every operation declares security or intentional public access',operations.every(o=>Array.isArray(o.security)));
const durable=operations.filter(o=>['post','put','patch','delete'].includes(o.method)&&!['POST /v1/media/{mediaId}/access','POST /v1/webhooks/revenuecat'].includes(o.request));
check('Durable API commands declare idempotency',durable.every(o=>o['x-idempotency-required']&&o.parameters?.some(p=>(p.$ref?resolve(p.$ref):p).name==='Idempotency-Key')));
const seen=new Set(['AUTH_WELCOME','ADMIN_LOGIN']);for(let n=0;n<screens.length;n++)for(const s of screens.filter(s=>seen.has(s.id)))for(const a of s.actions)for(const id of [a.target,...(a.branches||[]).map(b=>b[0]),a.picker?.returnTo,a.confirm?'CONFIRM_ACTION':null].filter(Boolean))seen.add(id);
const orphan=screens.filter(s=>!seen.has(s.id));check('All screens reachable from app or separate staff entry',!orphan.length,orphan.map(s=>s.id).join(', '));
for(const map of ['maps/all_screens_mindmap.svg','maps/all_screens_architecture.svg','maps/all_screens_mindmap.mmd','maps/all_screens_architecture.mmd','maps/all_button_navigation.mmd']){const text=read(map);check(map+' includes every screen',screens.every(s=>text.includes(s.id)));}
check('Every screen has a generated specification',screens.every(s=>fs.existsSync(path.join(root,'screens',s.id+'.md'))));
const registry=JSON.parse(read('registry/screen_registry.json'));
check('Generated registry matches current source',registry.screens.length===screens.length&&JSON.stringify(registry.screens.map(s=>[s.id,s.actions.map(a=>a.id)]))===JSON.stringify(screens.map(s=>[s.id,s.actions.map(a=>a.id)])));
check('Complete action ledger row count',read('registry/button_actions.csv').split('\n').length===actions.length+1);
check('Complete feature traceability row count',read('registry/feature_traceability.csv').split('\n').length===features.length+1);
check('Self-contained portal has embedded data',read('index.html').includes('id="blueprint-data"')&&!read('index.html').includes('__FEEDME_DATA__'));
const explicitMissing=[];for(const f of features){const text=read(f.file);for(const m of text.matchAll(/\b(GET|POST|PUT|PATCH|DELETE)\s+(\/v1\/[A-Za-z0-9_{}./-]+)/g)){const r=m[1]+' '+m[2].replace(/[.,]+$/,'');if(!byRequest.has(r))explicitMissing.push(f.id+': '+r);}}
check('Explicit feature HTTP route mentions resolve',!explicitMissing.length,[...new Set(explicitMissing)].join('; '));
const browserPath=path.join(root,'verification/browser-report.json');const browser=fs.existsSync(browserPath)?JSON.parse(read('verification/browser-report.json')):null;
const inputFiles=['spec/model.mjs','spec/refinements.mjs','scripts/explorer.template.html','scripts/build.mjs','architecture/04_API_Contract.json'];
const sourceDigest=crypto.createHash('sha256').update(inputFiles.map(p=>p+'\n'+read(p)).join('\n')).digest('hex');
if(browser){check('Browser smoke scenarios pass',browser.passed);check('Browser test receipt matches current portal sources',browser.sourceDigest===sourceDigest);}
const report={passed:checks.every(c=>c.passed),timestamp:new Date().toISOString(),scope:'Specification, contract and synthetic navigation QA only; not a deployed backend, full OpenAPI semantic certification, or native-device/provider test.',counts:{features:features.length,screens:screens.length,buttonBindings:actions.length,apiOperations:operations.length,schemas:Object.keys(api.components.schemas).length,checks:checks.length},sourceDigest,checks,browser:browser?{passed:browser.passed,scenarios:browser.scenarios?.length||0,screensRendered:browser.screensRendered,actionsExercised:browser.actionsExercised}:null,unexecuted:['Android/iOS device and accessibility QA','Real backend/provider authentication and object authorization tests','Store sandbox billing and reconciliation','Review by qualified food-content professionals and launch privacy/legal approval','Load, failover, restore, recall propagation and penetration tests']};
fs.mkdirSync(path.join(root,'verification'),{recursive:true});fs.writeFileSync(path.join(root,'verification/report.json'),JSON.stringify(report,null,2));
fs.writeFileSync(path.join(root,'verification/report.md'),'# FeedMe verification receipt\n\n'+(report.passed?'Specification checks passed.':'Outstanding specification checks remain.')+' '+report.scope+'\n\nGenerated '+report.timestamp+'.\n\n'+Object.entries(report.counts).map(([k,v])=>'- '+k+': '+v).join('\n')+'\n\n## Checks\n\n'+checks.map(c=>'- '+(c.passed?'PASS':'FAIL')+' — '+c.name+(c.detail?': '+c.detail:'')).join('\n')+'\n\n## Not executed\n\n'+report.unexecuted.map(x=>'- '+x).join('\n')+'\n');
console.log(JSON.stringify({passed:report.passed,counts:report.counts,failures:checks.filter(c=>!c.passed)},null,2));if(!report.passed)process.exitCode=1;
