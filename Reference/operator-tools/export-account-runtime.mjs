// Local fixed-project handoff only. No network, runtime assembly, key generation,
// review renewal, operator credential, environment import or deployment.
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {decodeDatabasePassword, validateSupabaseCertificate} from './supabase-migrate.mjs';
import {validateCursorKeyrings} from './prepare-account-runtime.mjs';

const repository=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const javaHome='/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home';
const project='bljskfhazmnzmhkbkcev';
const issuer=`https://${project}.supabase.co/auth/v1`;
const configPath='.local/account-runtime/reviewed-config.json';
const exportPath='.local/account-runtime/render.env';
const aiNames=['FEEDME_ACCOUNT_AI_CONFIG','FEEDME_ACCOUNT_AI_ACCOUNT_ID','FEEDME_ACCOUNT_AI_API_TOKEN'];
const mediaRequired=['FEEDME_ACCOUNT_MEDIA_CONFIG','FEEDME_ACCOUNT_MEDIA_STORAGE_API_KEY'];
const mediaAllowed=[...mediaRequired,'FEEDME_ACCOUNT_MEDIA_STORAGE_BEARER'];
const accountExportRequired=['FEEDME_ACCOUNT_EXPORT_CONFIG','FEEDME_ACCOUNT_EXPORT_STORAGE_API_KEY','FEEDME_ACCOUNT_EXPORT_WRAPPING_KEY'];
const accountExportAllowed=[...accountExportRequired,'FEEDME_ACCOUNT_EXPORT_STORAGE_BEARER'];
const failure='Account runtime export refused. Check explicit reviewed inputs, current server package and owner-only files. No deployment was attempted; existing files were not overwritten. Partial output, if any, must be inspected locally.';
const need=value=>{if(!value)throw Error(failure);};
const same=(a,b)=>['dev','ino','uid','mode',...(a.isDirectory()?[]:['nlink'])].every(k=>a[k]===b[k]);
const decode=bytes=>new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(bytes);

export function accountExportOperation(args) {
  need(Array.isArray(args)&&args.length===1&&['--check','--export'].includes(args[0]));
  return args[0];
}

// Keep numeric spellings and string bytes; do not JSON.parse/stringify-launder
// configuration before the Kotlin parser has seen the original document.
function compactJson(raw) {
  let result='',quoted=false,escaped=false;
  for(const ch of raw) {
    if(quoted||!/[\x20\t\r\n]/.test(ch))result+=ch;
    if(escaped)escaped=false;else if(quoted&&ch==='\\')escaped=true;else if(ch==='"')quoted=!quoted;
  }
  return result;
}
function exactJsonMap(raw,names) {
  const parsed=JSON.parse(raw);
  need(parsed&&typeof parsed==='object'&&!Array.isArray(parsed)&&
    JSON.stringify(Object.keys(parsed).sort())===JSON.stringify([...names].sort())&&
    Object.values(parsed).every(v=>typeof v==='string')&&compactJson(raw)===JSON.stringify(parsed));
  return parsed;
}
function exactJsonEnvelope(raw,required,allowed) {
  const parsed=JSON.parse(raw),names=Object.keys(parsed).sort(),accepted=[...allowed].sort();
  need(parsed&&typeof parsed==='object'&&!Array.isArray(parsed)&&required.every(name=>Object.hasOwn(parsed,name))&&
    names.every(name=>accepted.includes(name))&&Object.values(parsed).every(value=>typeof value==='string')&&
    compactJson(raw)===JSON.stringify(parsed));
  return parsed;
}

function privateInputs(base) {
  need(typeof process.getuid==='function'&&path.isAbsolute(base)&&path.normalize(base)===base&&fs.realpathSync(base)===base);
  const directories=[],inputs=[],absent=[];
  for(const relative of ['', '.local', '.local/account-runtime', '.local/supabase', 'deploy', 'deploy/trust']) {
    const file=path.join(base,relative),stat=fs.lstatSync(file);
    need(stat.isDirectory()&&!stat.isSymbolicLink()&&fs.realpathSync(file)===file&&stat.uid===process.getuid()&&
      (stat.mode&0o022)===0&&(!relative.startsWith('.local/')||(stat.mode&0o777)===0o700));
    directories.push([file,stat]);
  }
  const recheck=()=>{
    for(const [file,before] of [...directories,...inputs]) {
      const after=fs.lstatSync(file);
      need(same(before,after)&&!after.isSymbolicLink()&&fs.realpathSync(file)===file);
      if(before.isFile())need(after.size===before.size&&after.mtimeMs===before.mtimeMs&&after.ctimeMs===before.ctimeMs);
    }
    for(const file of absent) {
      try{fs.lstatSync(file);throw Error(failure);}catch(e){if(e.code!=='ENOENT')throw e;}
    }
  };
  function read(relative,limit,{privateFile=true,optional=false,retain=true}={}) {
    recheck();const file=path.join(base,relative);let before;
    try{before=fs.lstatSync(file);}catch(e){if(optional&&e.code==='ENOENT'){if(retain)absent.push(file);return null;}throw e;}
    const valid=s=>s.isFile()&&!s.isSymbolicLink()&&s.nlink===1&&s.uid===process.getuid()&&s.size>0&&s.size<=limit&&
      (privateFile?(s.mode&0o777)===0o600:(s.mode&0o022)===0);
    need(valid(before));const fd=fs.openSync(file,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW|fs.constants.O_NONBLOCK);let bytes;
    try {
      const opened=fs.fstatSync(fd);need(valid(opened)&&same(before,opened));bytes=Buffer.alloc(opened.size+1);let n=0;
      while(n<bytes.length){const count=fs.readSync(fd,bytes,n,bytes.length-n,null);if(!count)break;n+=count;}
      const after=fs.fstatSync(fd),linked=fs.lstatSync(file);
      need(n===opened.size&&valid(after)&&valid(linked)&&same(opened,after)&&same(after,linked)&&
        after.size===opened.size&&after.mtimeMs===opened.mtimeMs&&after.ctimeMs===opened.ctimeMs);
      recheck();if(retain)inputs.push([file,after]);return Buffer.from(bytes.subarray(0,n));
    }finally{bytes?.fill(0);fs.closeSync(fd);}
  }
  function write(bytes) {
    recheck();const file=path.join(base,exportPath);let fd;
    try{fd=fs.openSync(file,fs.constants.O_WRONLY|fs.constants.O_CREAT|fs.constants.O_EXCL|fs.constants.O_NOFOLLOW,0o600);}
    catch(e){if(e.code!=='EEXIST')throw e;const old=read(exportPath,196608,{retain:false});try{need(old?.equals(bytes));return false;}finally{old?.fill(0);}}
    try {
      fs.fchmodSync(fd,0o600);fs.writeFileSync(fd,bytes);fs.fsyncSync(fd);
      const opened=fs.fstatSync(fd),linked=fs.lstatSync(file);
      need(same(opened,linked)&&opened.nlink===1&&opened.size===bytes.length&&(opened.mode&0o777)===0o600);
      recheck();const directory=path.dirname(file),dir=fs.openSync(directory,fs.constants.O_RDONLY|fs.constants.O_DIRECTORY|fs.constants.O_NOFOLLOW);
      try{need(same(fs.lstatSync(directory),fs.fstatSync(dir)));fs.fsyncSync(dir);}finally{fs.closeSync(dir);}
    }finally{fs.closeSync(fd);}
    return true;
  }
  return {read,write,recheck};
}

function packageFiles(base) {
  need(path.isAbsolute(base)&&fs.realpathSync(base)===base);
  const snapshots=new Map(),entries=new Map();
  function directory(relative) {
    let file=base;
    for(const part of ['',...relative.split('/').filter(Boolean)]) {
      if(part)file=path.join(file,part);
      if(snapshots.has(file))continue;
      const s=fs.lstatSync(file);need(s.isDirectory()&&!s.isSymbolicLink()&&s.uid===process.getuid()&&
        (s.mode&0o022)===0&&fs.realpathSync(file)===file);snapshots.set(file,s);
    }
    return file;
  }
  const recheck=()=>{for(const [file,before] of snapshots){const after=fs.lstatSync(file);
    need(same(before,after)&&!after.isSymbolicLink()&&fs.realpathSync(file)===file);
    if(before.isFile())need(after.size===before.size&&after.mtimeMs===before.mtimeMs&&after.ctimeMs===before.ctimeMs);
  }for(const [dir,names] of entries)need(JSON.stringify(fs.readdirSync(dir).sort())===JSON.stringify(names));};
  function read(relative,withBytes=false) {
    directory(path.dirname(relative));recheck();const file=path.join(base,relative),before=fs.lstatSync(file);
    const valid=s=>s.isFile()&&!s.isSymbolicLink()&&s.nlink===1&&s.uid===process.getuid()&&
      (s.mode&0o022)===0&&s.size>0&&s.size<=134217728;
    need(valid(before));const fd=fs.openSync(file,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW|fs.constants.O_NONBLOCK);let bytes;
    try {
      const opened=fs.fstatSync(fd);need(valid(opened)&&same(before,opened));
      if(withBytes){bytes=Buffer.alloc(opened.size+1);let n=0;while(n<bytes.length){const count=fs.readSync(fd,bytes,n,bytes.length-n,null);if(!count)break;n+=count;}need(n===opened.size);}
      const after=fs.fstatSync(fd),linked=fs.lstatSync(file);
      need(valid(after)&&valid(linked)&&same(opened,after)&&same(after,linked)&&after.size===opened.size&&
        after.mtimeMs===opened.mtimeMs&&after.ctimeMs===opened.ctimeMs);
      recheck();snapshots.set(file,after);return {stat:after,bytes:bytes?Buffer.from(bytes.subarray(0,opened.size)):null};
    }finally{bytes?.fill(0);fs.closeSync(fd);}
  }
  const library=directory('server/build/install/server/lib');
  const names=fs.readdirSync(library).sort();
  entries.set(library,names);
  need(names.length>0&&names.length<=256&&names.includes('server.jar')&&names.every(n=>/^[A-Za-z0-9_.+-]+\.jar$/.test(n)));
  const files=names.map(n=>path.join(library,n));
  for(const name of names)read(`server/build/install/server/lib/${name}`);
  function sourceTree(relative,builtAt) {
    const dir=directory(relative),names=fs.readdirSync(dir).sort();entries.set(dir,names);
    for(const name of names) {
      const child=`${relative}/${name}`,s=fs.lstatSync(path.join(base,child));need(!s.isSymbolicLink());
      if(s.isDirectory())sourceTree(child,builtAt);else need(read(child).stat.mtimeMs<=builtAt);
    }
  }
  // Ordinary current-build guard across the actual parser's project dependencies,
  // including policy constructors; not a cryptographic source/build attestation.
  for(const [module,name] of [['server','server.jar'],['shared/core','core-jvm.jar'],
    ['shared/contracts','contracts-jvm.jar'],['shared/planning','planning-jvm.jar']]) {
    const installed=read(`server/build/install/server/lib/${name}`,true),built=read(`${module}/build/libs/${name}`,true);
    try{need(installed.bytes.equals(built.bytes));}finally{installed.bytes.fill(0);built.bytes.fill(0);}
    for(const source of module==='server'?['main']:['commonMain','jvmMain']) {
      const relative=`${module}/src/${source}`;
      if(fs.existsSync(path.join(base,relative)))sourceTree(relative,built.stat.mtimeMs);
    }
  }
  recheck();return {files,recheck};
}

/** Calls ONLY the packaged parser entrypoint. Alternate package base is for tests;
 * CLI supplies no override. No caller/process environment or secret arguments. */
export function validatePackagedAccountRuntime(values,base=repository) {
  let input;
  try {
    const packaged=packageFiles(base),jars=packaged.files;
    input=Buffer.from(JSON.stringify(values));need(input.length<=131072);
    packaged.recheck();
    const result=spawnSync(path.join(javaHome,'bin/java'),['-XX:-HeapDumpOnOutOfMemoryError','-XX:ErrorFile=/dev/null',
      '-cp',jars.join(path.delimiter),'com.feedme.server.config.AccountRuntimeExportMainKt','--check-stdin'],{
      cwd:base,env:{JAVA_HOME:javaHome,PATH:`${javaHome}/bin:/usr/bin:/bin`},input,timeout:15000,maxBuffer:4096,
      stdio:['pipe','pipe','pipe']});
    packaged.recheck();
    need(!result.error&&!result.signal&&result.status===0&&result.stderr.length===0);
    const report=JSON.parse(decode(result.stdout));
    const fields=['status','adultAdmissionEnabled','planningEnabled','cookingEnabled','savedCopiesEnabled','aiConfigured',
      'mediaConfigured','accountExportConfigured','accountDeletionConfigured','providerReviewValidUntil'];
    fields.push('staffSessionConfigured','staffCatalogDraftsEnabled','staffCatalogReviewsEnabled',
      'staffCatalogPublicationEnabled','staffModerationEnabled');
    need(JSON.stringify(Object.keys(report).sort())===JSON.stringify(fields.sort())&&report.status==='config-valid-local'&&
      report.adultAdmissionEnabled===true&&['planningEnabled','cookingEnabled','savedCopiesEnabled','aiConfigured',
      'mediaConfigured','accountExportConfigured','accountDeletionConfigured'].every(k=>typeof report[k]==='boolean')&&
      ['staffSessionConfigured','staffCatalogDraftsEnabled','staffCatalogReviewsEnabled',
        'staffCatalogPublicationEnabled','staffModerationEnabled'].every(k=>typeof report[k]==='boolean')&&
      typeof report.providerReviewValidUntil==='string');
    return report;
  }catch{throw Error(failure);}finally{input?.fill(0);}
}

/** Explicit reviewed settings only. Alternate base/clock/validator are isolated test
 * seams, never CLI arguments. This is NOT evidence of consent or deployed health. */
export function exportAccountRuntime(operation,base=repository,validate=validatePackagedAccountRuntime,now=Date.now) {
  const owned=[];
  try {
    accountExportOperation([operation]);const guard=privateInputs(base);
    const read=(file,max,options)=>{const bytes=guard.read(file,max,options);if(bytes)owned.push(bytes);return bytes;};
    const configText=decode(read(configPath,65536));
    const keyText=decode(read('.local/account-runtime/cursor-keys.json',32768));validateCursorKeyrings(keyText);
    validateSupabaseCertificate(read('deploy/trust/prod-ca-2021.crt',16384,{privateFile:false}));
    const password=decodeDatabasePassword(read('.local/supabase/api-database-password',16386));
    need(/^[A-Za-z0-9_-]{43}$/.test(password));
    const aiBytes=read('.local/account-runtime/ai.json',16384,{optional:true});
    const ai=aiBytes?exactJsonMap(decode(aiBytes),aiNames):{};
    if(aiBytes)need(ai.FEEDME_ACCOUNT_AI_ACCOUNT_ID==='60af02a9cae7b8408be51f6f35bc652f');
    const mediaBytes=read('.local/account-runtime/media.json',32768,{optional:true});
    const media=mediaBytes?exactJsonEnvelope(decode(mediaBytes),mediaRequired,mediaAllowed):{};
    const accountExportBytes=read('.local/account-runtime/account-export.json',32768,{optional:true});
    const accountExport=accountExportBytes?exactJsonEnvelope(decode(accountExportBytes),accountExportRequired,accountExportAllowed):{};
    // Original config and key JSON strings reach Kotlin before any whitespace compaction.
    const values={FEEDME_ACCOUNT_RUNTIME_CONFIG:configText,FEEDME_ACCOUNT_DB_PASSWORD:password,
      FEEDME_ACCOUNT_CURSOR_KEYS:keyText,...ai,...media,...accountExport,PORT:'10000'};
    const checked=validate(values);guard.recheck();
    need(checked.status==='config-valid-local'&&checked.adultAdmissionEnabled===true);
    const c=JSON.parse(configText),db=c.database,v=c.deployment.verification;
    need(c.environment==='production'&&c.listener.mode==='container'&&c.listener.host==='0.0.0.0'&&c.listener.port===10000&&
      db.host==='aws-0-ap-southeast-2.pooler.supabase.com'&&db.port===5432&&db.name==='postgres'&&
      db.user===`feedme_api.${project}`&&db.sslMode==='verify-full'&&db.sslRootCert==='/opt/feedme/trust/prod-ca-2021.crt'&&
      v.issuer===issuer&&v.jwksEndpoint===`${issuer}/.well-known/jwks.json`&&v.audience==='authenticated');
    const current=()=>{const stamp=now(),from=Date.parse(c.deployment.reviewedAt),until=Date.parse(c.deployment.validUntil);
      need(Number.isSafeInteger(stamp)&&Number.isFinite(from)&&Number.isFinite(until)&&from<=stamp&&stamp<until&&
        until>from&&until-from<=86400000&&Date.parse(checked.providerReviewValidUntil)===until);};
    current();
    const report={status:'account-runtime-inputs-verified',project,providerReviewValidUntil:checked.providerReviewValidUntil,
      adultAdmissionEnabled:true,planningEnabled:checked.planningEnabled,cookingEnabled:checked.cookingEnabled,
      savedCopiesEnabled:checked.savedCopiesEnabled,aiConfigured:checked.aiConfigured,
      mediaConfigured:checked.mediaConfigured,accountExportConfigured:checked.accountExportConfigured,
      accountDeletionConfigured:checked.accountDeletionConfigured,
      staffSessionConfigured:checked.staffSessionConfigured,
      staffCatalogDraftsEnabled:checked.staffCatalogDraftsEnabled,
      staffCatalogReviewsEnabled:checked.staffCatalogReviewsEnabled,
      staffCatalogPublicationEnabled:checked.staffCatalogPublicationEnabled,
      staffModerationEnabled:checked.staffModerationEnabled,
      networkAttempted:false,deployed:false,productReady:false};
    if(operation==='--check'){guard.recheck();current();return report;}
    const output={...values,FEEDME_ACCOUNT_RUNTIME_CONFIG:compactJson(configText),FEEDME_ACCOUNT_CURSOR_KEYS:compactJson(keyText)};
    if(aiBytes) {
      output.FEEDME_ACCOUNT_AI_CONFIG=compactJson(ai.FEEDME_ACCOUNT_AI_CONFIG);
      need(!/["\\]/.test(ai.FEEDME_ACCOUNT_AI_API_TOKEN));
    }
    if(mediaBytes) output.FEEDME_ACCOUNT_MEDIA_CONFIG=compactJson(media.FEEDME_ACCOUNT_MEDIA_CONFIG);
    if(accountExportBytes) output.FEEDME_ACCOUNT_EXPORT_CONFIG=compactJson(accountExport.FEEDME_ACCOUNT_EXPORT_CONFIG);
    // Render dotenv handoff: refuse interpolation/comment/quoting ambiguity, never source
    // this file as a shell script. No value is escaped into a different credential/config.
    for(const value of Object.values(output))need(typeof value==='string'&&value.length>0&&value.trim()===value&&!/[\r\n#'`$]/.test(value));
    const bytes=Buffer.from(Object.entries(output).map(([name,value])=>`${name}=${value}\n`).join(''));owned.push(bytes);need(bytes.length<=196608);
    const old=read(exportPath,196608,{optional:true,retain:false});need(!old||old.equals(bytes));
    guard.recheck();current();const created=old?false:guard.write(bytes);
    const final=read(exportPath,196608,{retain:false});need(final?.equals(bytes));guard.recheck();current();
    return {...report,status:created?'account-runtime-export-created':'identical-export-preserved',importPath:exportPath,
      removeConflictingEnvironment:['FEEDME_ACCOUNT_DEPENDENCY_HOLD_CONFIG','FEEDME_SERVER_*','FEEDME_PANTRY_*','FEEDME_MIGRATION_*',
        'legacy database overrides',...(!aiBytes?aiNames:[]),...(!mediaBytes?mediaAllowed:[]),
        ...(!accountExportBytes?accountExportAllowed:[])]};
  }catch{throw Error(failure);}finally{owned.forEach(bytes=>bytes.fill(0));}
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try{console.log(JSON.stringify(exportAccountRuntime(accountExportOperation(process.argv.slice(2)))));}
  catch{console.error(failure+' Usage: node deploy/export-account-runtime.mjs --check|--export');process.exitCode=1;}
}
