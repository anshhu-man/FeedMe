// Local preparation only. No network, subprocess, environment-secret reads or deployment.
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomBytes} from 'node:crypto';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const purposes = ['ingredient','kitchen','planning','saved'];
const failure = 'Account runtime preparation refused or incomplete. Existing files were not overwritten. Inspect local ownership/permissions and the setup guide; no deployment was attempted.';
const need = condition => { if (!condition) throw Error(failure); };
const exact = (value,names) => value && typeof value === 'object' && !Array.isArray(value) &&
  JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...names].sort());

export function preparationOperation(args) {
  need(Array.isArray(args) && args.length === 1 && ['--prepare','--init-cursor-keys'].includes(args[0]));
  return args[0];
}

/** This envelope is intentionally NOT the exact AccountCoreRuntimeConfig root.
 * Missing facts remain null, not fabricated policy IDs or self-renewing review dates.
 * Engineering values are proposals, not provider attestation or launch approval. */
export function accountRuntimePreparation() {
  const issuer='https://bljskfhazmnzmhkbkcev.supabase.co/auth/v1';
  return {
    kind:'feedme-account-runtime-preparation', formatVersion:1, runnable:false,
    status:'DRAFT_NOT_RUNTIME_CONFIGURATION', project:'bljskfhazmnzmhkbkcev',
    basis:{
      evidencePaths:['docs/SUPABASE_PROJECT.md','docs/SUPABASE_LOW_AAL.md',
        'docs/release/low-aal-production-2026-09-18/checkpoint.json',
        'server/src/main/kotlin/com/feedme/server/config/AccountCoreRuntimeConfig.kt',
        'apps/androidApp/build.gradle.kts','docs/GOOGLE_OAUTH_SETUP.md','deploy/Dockerfile'],
      observationOnly:true, providerReviewApproved:false, policyApproved:false, contentApproved:false,
      operator:{legalName:'Anshuman Acharya',brand:'FeedMe',supportEmail:'hazloteams3@gmail.com',
        minimumAgePreference:18,countriesPreference:'worldwide'},
      caDerSha256:'807025ad50d4ed219d2c9c7d299c004f824eb00cf7f65afef607d07b72e6cafa',
      proposedContainerCaPath:'/opt/feedme/trust/prod-ca-2021.crt',
      containerCaMountAndUid10001ReadabilityVerified:false,
    },
    proposedAccountConfig:{
      version:1,environment:'production',
      listener:{mode:'container',host:'0.0.0.0',port:10000,minimumAppVersion:'0.1.0',maximumInFlightRequests:16},
      database:{host:'aws-0-ap-southeast-2.pooler.supabase.com',port:5432,name:'postgres',
        user:'feedme_api.bljskfhazmnzmhkbkcev',sslMode:'verify-full',sslRootCert:null,
        connectTimeoutSeconds:8,loginTimeoutSeconds:10,socketTimeoutSeconds:15},
      deployment:{databaseName:'postgres',authSourceRevision:'4eee58f296d9698a1c2c0ae14d7a0b379c7622d3',
        migrationVersions:null,reviewedAt:null,validUntil:null,
        timeboxSeconds:null,inactivitySeconds:null,singleSessionPerUser:false,lowAssuranceTimeoutSeconds:900,
        verification:{issuer,jwksEndpoint:issuer+'/.well-known/jwks.json',audience:'authenticated',
          algorithms:['ES256'],maximumTokenLifetimeSeconds:3600,allowedFutureClockSkewSeconds:0,maximumJwksAgeSeconds:60}},
      accountRules:{eligibilityPolicyVersion:null,requiredTermsVersion:null,acceptExactSubmittedTerms:false,
        adultSelfAttestationEnabled:false},
      reconnectionRules:null,termsNotice:null,
      keyPolicy:{connectTimeoutMillis:2000,socketTimeoutMillis:3000,totalTimeoutMillis:5000,
        cacheSeconds:30,minimumFetchIntervalMillis:1000,maximumAdmittedCalls:2},
      ingredientLimits:{maxReleaseBytes:262144,maxIngredients:256,cursorLifetimeSeconds:300},
      searchMode:'literal-prefix-v1',
      preferencePolicy:{revision:null,dietaryPatterns:null,equipmentIds:null,
        preferredTasteTags:['crunch','fresh','creamy','heat'],defaultEnergies:['assemble','little','happy'],
        minimumDefaultServings:1,maximumDefaultServings:4,consent:{currentVersion:null,acceptNewConsent:false}},
      kitchenPolicy:{maxResponseBytes:262144,cursorLifetimeSeconds:300},
      planningOperational:{revision:null,newPlanningEnabled:false,maxPlansPerUtcDay:20},
      planningPolicy:{rankingVersion:null,heatEnabled:false,improveEnabled:false,planRetentionSeconds:86400,cursorLifetimeSeconds:300},
      cookingPolicy:{maxResponseBytes:262144,sessionRetentionSeconds:86400},newCookingEnabled:false,
      savedPolicy:{maxResponseBytes:262144,cursorLifetimeSeconds:300,defaultCollectionName:'Saved'},
      newCopiesEnabled:false,databaseParallelism:2,
    },
    unresolved:[
      {paths:['database.sslRootCert'],requires:'Verify the exact reviewed certificate and UID 10001 readability in the actual target image/deployment. /opt/feedme/trust/prod-ca-2021.crt matches the packaged Dockerfile path; this draft is not evidence that a deployed image contains or can read it.'},
      {paths:['deployment.migrationVersions','deployment.reviewedAt','deployment.validUntil'],requires:'Obtain the exact current Auth migration vector and genuine operator provider-policy review, at most 24 hours. Application schema migrations are not the Auth vector; never auto-renew review dates.'},
      {paths:['accountRules.eligibilityPolicyVersion','accountRules.requiredTermsVersion','accountRules.acceptExactSubmittedTerms','accountRules.adultSelfAttestationEnabled','termsNotice'],requires:'Publish the connected 18+ Terms/privacy notice and explicitly configure the versioned adult self-attestation policy. A real termsNotice requires the exact current termsVersion, termsUrl and privacyUrl. New-account admission must bind the affirmative declaration to current Terms; old accounts/old consent are not promoted by this draft.'},
      {paths:['preferencePolicy.revision','preferencePolicy.dietaryPatterns','preferencePolicy.equipmentIds','preferencePolicy.consent'],requires:'Actual governed choices aligned with the client and reviewed content, plus exact approved consent text/version. No dietary safety assertion follows from an empty choice list.'},
      {paths:['planningOperational.revision','planningPolicy.rankingVersion'],requires:'Review and version the concrete quota/ranking/expiry choices. Proposed retention seconds are operational expiry, not a data-deletion guarantee.'},
      {paths:['planningOperational.newPlanningEnabled','newCookingEnabled','newCopiesEnabled'],requires:'Actual reviewed ingredient/recipe publications and current independent Saved-copy grants, with real first-account eligibility and essential connected acceptance. A license string or configuration flag is not approval.'},
      {paths:['listener'],requires:'Confirm the actual hosting service, HTTPS termination and exact PORT=10000 agreement; match minimum app version to the shipped client.'},
      {paths:[],requires:'The selected first-sign-in path is Supabase Google browser OAuth with PKCE: review the Google Web client and Supabase provider, exact Supabase callback and installed-app return allowlist, plus matching public Android provider/API/legal configuration. Signup-code email delivery is a separate requirement only if email signup is offered. No provider settings, callback allowlist or public API origin are created by this draft.'},
    ],
    protectedInputs:{
      apiPassword:{environmentName:'FEEDME_ACCOUNT_DB_PASSWORD',existingLocalPath:'.local/supabase/api-database-password',readByThisTool:false},
      cursorKeys:{environmentName:'FEEDME_ACCOUNT_CURSOR_KEYS',localPath:'.local/account-runtime/cursor-keys.json',
        explicitInitialization:'--init-cursor-keys',readByPrepare:false,
        parserShape:{purposes,keyringFields:['currentKeyId','keys'],keysPerPurpose:'1..8',
          keyIdPattern:'[a-z0-9_-]{1,32}',keyEncoding:'32 bytes, canonical unpadded base64url, exactly 43 characters'}},
    },
    limitations:[
      'This envelope and its null-filled candidate are rejected by the actual runtime parser. Do not set FEEDME_ACCOUNT_RUNTIME_CONFIG from this file.',
      'This preparation helper does not export runtime variables, copy passwords, make network calls, start a service, publish content or update eligibility. The separate protected exporter requires independently reviewed inputs.',
      'A configured server with rollout flags false can still accept account/profile/setup-preference writes; it is not this non-runnable draft.',
      'The separate legacy unconfigured preview uses no FEEDME_ACCOUNT_* variables and is not an account-core deployment.',
      'Source/configuration bytes and provider observations are not perpetual approval or live readiness.',
    ],
  };
}

export function createCursorKeyrings(entropy=randomBytes) {
  const result={};
  for (const purpose of purposes) {
    const bytes=entropy(32);
    try { need(Buffer.isBuffer(bytes) && bytes.length===32); result[purpose]={currentKeyId:'k1',keys:{k1:bytes.toString('base64url')}}; }
    finally { if (Buffer.isBuffer(bytes)) bytes.fill(0); }
  }
  validateCursorKeyrings(JSON.stringify(result));
  return result;
}

/** Validate without returning any key values. Whitespace is allowed; duplicate keys,
 * escaped aliases, unexpected fields and noncanonical key encoding are refused. */
export function validateCursorKeyrings(raw) {
  try {
    need(typeof raw==='string' && Buffer.byteLength(raw,'utf8')>0 && Buffer.byteLength(raw,'utf8')<=32768);
    const rings=JSON.parse(raw); need(exact(rings,purposes));
    let compact='', quoted=false, escaped=false;
    for (const character of raw) {
      if (quoted || !/\s/.test(character)) compact+=character;
      if (escaped) escaped=false;
      else if (quoted && character==='\\') escaped=true;
      else if (character==='"') quoted=!quoted;
    }
    need(compact===JSON.stringify(rings));
    const values=new Set(); let keyCount=0;
    for (const purpose of purposes) {
      const ring=rings[purpose]; need(exact(ring,['currentKeyId','keys']));
      need(typeof ring.currentKeyId==='string' && /^[a-z0-9_-]{1,32}$/.test(ring.currentKeyId));
      need(ring.keys && typeof ring.keys==='object' && !Array.isArray(ring.keys));
      const entries=Object.entries(ring.keys); need(entries.length>=1 && entries.length<=8 && Object.hasOwn(ring.keys,ring.currentKeyId));
      for (const [id,key] of entries) {
        need(/^[a-z0-9_-]{1,32}$/.test(id) && typeof key==='string' && /^[A-Za-z0-9_-]{43}$/.test(key) && !values.has(key));
        const bytes=Buffer.from(key,'base64url');
        try { need(bytes.length===32 && bytes.toString('base64url')===key); }
        finally { bytes.fill(0); }
        values.add(key); keyCount++;
      }
    }
    return {purposes:4,keys:keyCount};
  } catch { throw Error(failure); }
}

function identity(left,right) {
  return ['dev','ino','uid','mode'].every(key=>left[key]===right[key]);
}
function privateDirectory(base) {
  need(typeof process.getuid==='function' && path.isAbsolute(base) && path.normalize(base)===base && fs.realpathSync(base)===base);
  const snapshots=[]; let current=base;
  const check=(target,ownerOnly) => {
    const stat=fs.lstatSync(target);
    need(stat.isDirectory() && !stat.isSymbolicLink() && fs.realpathSync(target)===target && stat.uid===process.getuid() &&
      (stat.mode & 0o022)===0 && (!ownerOnly || (stat.mode & 0o777)===0o700));
    snapshots.push([target,stat,ownerOnly]);
  };
  const recheck=()=>{
    for (const [target,before,ownerOnly] of snapshots) {
      const stat=fs.lstatSync(target);
      need(stat.isDirectory() && !stat.isSymbolicLink() && fs.realpathSync(target)===target && identity(before,stat) &&
        stat.uid===process.getuid() && (stat.mode & 0o022)===0 && (!ownerOnly || (stat.mode & 0o777)===0o700));
    }
  };
  check(current,false);
  for (const name of ['.local','account-runtime']) {
    const parent=current;
    current=path.join(current,name);
    try { fs.mkdirSync(current,{mode:0o700}); } catch (error) { if (error.code!=='EEXIST') throw error; }
    check(current,name==='account-runtime'); recheck();
    // Persist each containing directory entry before acknowledging the child.
    // Also sync existing entries: they may remain from an earlier interrupted
    // attempt whose parent fsync did not complete. Never replace keys on retry.
    const fd=fs.openSync(parent,fs.constants.O_RDONLY|fs.constants.O_DIRECTORY|fs.constants.O_NOFOLLOW);
    try {
      need(identity(fs.lstatSync(parent),fs.fstatSync(fd))); recheck();
      fs.fsyncSync(fd); recheck();
    } finally { fs.closeSync(fd); }
  }
  return {directory:current,recheck};
}
const validFile=stat=>stat.isFile() && !stat.isSymbolicLink() && stat.nlink===1 && stat.uid===process.getuid() &&
  (stat.mode & 0o777)===0o600 && stat.size>0 && stat.size<=65536;
function readExisting(file,guard) {
  guard.recheck();
  let before;
  try { before=fs.lstatSync(file); } catch (error) { if (error.code==='ENOENT') return null; throw error; }
  need(validFile(before));
  const fd=fs.openSync(file,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW|fs.constants.O_NONBLOCK);
  let bytes;
  try {
    const opened=fs.fstatSync(fd); need(validFile(opened) && identity(before,opened));
    bytes=Buffer.alloc(opened.size+1); let count=0;
    while (count<bytes.length) { const read=fs.readSync(fd,bytes,count,bytes.length-count,null); if (!read) break; count+=read; }
    const after=fs.fstatSync(fd), linked=fs.lstatSync(file);
    need(count===opened.size && validFile(after) && validFile(linked) && identity(opened,after) && identity(after,linked) &&
      after.size===opened.size && after.mtimeMs===opened.mtimeMs && after.ctimeMs===opened.ctimeMs);
    guard.recheck(); return Buffer.from(bytes.subarray(0,count));
  } finally { bytes?.fill(0); fs.closeSync(fd); }
}
function writeExclusive(file,bytes,guard) {
  guard.recheck();
  let fd;
  try { fd=fs.openSync(file,fs.constants.O_WRONLY|fs.constants.O_CREAT|fs.constants.O_EXCL|fs.constants.O_NOFOLLOW,0o600); }
  catch (error) { if (error.code==='EEXIST') return false; throw error; }
  try {
    fs.fchmodSync(fd,0o600); fs.writeFileSync(fd,bytes); fs.fsyncSync(fd);
    const written=fs.fstatSync(fd), linked=fs.lstatSync(file);
    need(validFile(written) && validFile(linked) && identity(written,linked) && written.size===bytes.length);
    guard.recheck();
    const directory=fs.openSync(guard.directory,fs.constants.O_RDONLY|fs.constants.O_DIRECTORY|fs.constants.O_NOFOLLOW);
    try { need(identity(fs.lstatSync(guard.directory),fs.fstatSync(directory))); fs.fsyncSync(directory); }
    finally { fs.closeSync(directory); }
  } finally { fs.closeSync(fd); }
  // An interrupted/failed new write may leave an owner-only partial file. It is
  // deliberately not deleted or overwritten; a later run must validate or refuse.
  return true;
}

/** Exported base path is for owned temporary-directory tests; CLI has no path override. */
export function prepareAccountRuntime(base=repository) {
  let bytes,existing;
  try {
    const guard=privateDirectory(base),file=path.join(guard.directory,'preparation.json');
    bytes=Buffer.from(JSON.stringify(accountRuntimePreparation(),null,2)+'\n');
    existing=readExisting(file,guard);
    if (existing) { need(existing.equals(bytes)); return {status:'existing-draft-preserved',path:'.local/account-runtime/preparation.json',runnable:false}; }
    const created=writeExclusive(file,bytes,guard);
    if (!created) { existing=readExisting(file,guard); need(existing?.equals(bytes)); }
    return {status:created?'non-runnable-draft-created':'existing-draft-preserved',path:'.local/account-runtime/preparation.json',runnable:false};
  } catch { throw Error(failure); }
  finally { bytes?.fill(0); existing?.fill(0); }
}

export function initializeCursorKeys(base=repository,entropy=randomBytes) {
  let bytes,existing;
  try {
    const guard=privateDirectory(base),file=path.join(guard.directory,'cursor-keys.json');
    existing=readExisting(file,guard);
    if (existing) { validateCursorKeyrings(new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(existing));
      return {status:'existing-cursor-keys-preserved',path:'.local/account-runtime/cursor-keys.json',runnable:false}; }
    bytes=Buffer.from(JSON.stringify(createCursorKeyrings(entropy),null,2)+'\n');
    const created=writeExclusive(file,bytes,guard);
    if (!created) { existing=readExisting(file,guard); need(existing); validateCursorKeyrings(new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(existing)); }
    return {status:created?'cursor-keys-created':'existing-cursor-keys-preserved',path:'.local/account-runtime/cursor-keys.json',runnable:false};
  } catch { throw Error(failure); }
  finally { bytes?.fill(0); existing?.fill(0); }
}

if (process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    const operation=preparationOperation(process.argv.slice(2));
    console.log(JSON.stringify(operation==='--prepare'?prepareAccountRuntime():initializeCursorKeys()));
  } catch { console.error(failure+' Use --prepare or --init-cursor-keys only.'); process.exitCode=1; }
}
