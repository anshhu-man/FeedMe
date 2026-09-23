#!/usr/bin/env node
// Fixed-project, insert-only owner authority installer. Default planning reads no
// decisions or credentials. This creates no identity or policy value, performs
// no migration/grant/configuration/deployment, and never updates/deletes existing
// authority. A conflict is terminal and there is no automatic retry.
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {validateStaffAuthorityDecisions} from './prepare-staff-authority.mjs';
import {withSupabaseOperatorEnvironment} from './supabase-migrate.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const project = 'bljskfhazmnzmhkbkcev';
const decisionPath = path.join(root,'.local/staff-authority/decisions.json');
const psqlPath = '/opt/homebrew/opt/postgresql@15/bin/psql';
const applyArgument = '--apply-owner-approved-staff-authority';
const usage = `Use node deploy/feedme-staff-authority.mjs [--plan|--preflight|${applyArgument}].`;
const generic = 'Staff authority operation refused or outcome unverified. Private identity and diagnostics were not printed. No automatic retry.';
const need = value => { if (!value) throw Error(generic); };
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const pins = Object.freeze({
  'server/src/main/resources/db/migration/V040__staff_publication_approvals.sql':'bda1e378cf5b444845e1fa9de10df1b14c129f84771859197119e9c03e813f7a',
  'server/src/main/resources/db/migration/V077__staff_moderator_enrollments.sql':'7370ae2a9ad96dc639512d5c858a880661689ac11ac2f21fdb795b83f6121329',
  'server/src/main/resources/db/migration/V090__staff_operational_health_reader.sql':'13b093289a20928b91bbf439714a788525ea179bbe3ec52a87e5ce47e8d20b0d',
  'deploy/prepare-staff-authority.mjs':'46ea725694b0a634f58cbfcafb293019b6d09f7c06c0cf23d9f81d9403d472fb',
  'deploy/supabase-migrate.mjs':'d4c64225226372450a6832158d413dd280333ba0a7a62ec350cb834b0fe3f836',
});

export function staffAuthorityOperation(args) {
  if (args.length === 0 || args.length === 1 && args[0] === '--plan') return '--plan';
  if (args.length === 1 && args[0] === '--preflight') return '--preflight';
  if (args.length === 1 && args[0] === applyArgument) return '--apply';
  throw Error(usage);
}

export function verifyStaffAuthorityArtifacts(entries) {
  need(entries && typeof entries === 'object' && !Array.isArray(entries));
  need(Object.keys(entries).sort().join('\n') === Object.keys(pins).sort().join('\n'));
  for (const [name,expected] of Object.entries(pins)) {
    const bytes=entries[name]; need(Buffer.isBuffer(bytes) && bytes.length > 0 && bytes.length <= 2*1024*1024);
    need(digest(bytes) === expected);
  }
  return {...pins};
}

function localArtifacts() {
  const entries={};
  for (const name of Object.keys(pins)) {
    const file=path.join(root,name),before=fs.lstatSync(file);
    need(before.isFile()&&!before.isSymbolicLink()&&before.nlink===1&&before.size>0&&before.size<=2*1024*1024&&fs.realpathSync(file)===file);
    const bytes=fs.readFileSync(file),after=fs.lstatSync(file);
    need(after.ino===before.ino&&after.dev===before.dev&&after.size===before.size&&after.mtimeMs===before.mtimeMs&&after.ctimeMs===before.ctimeMs);
    entries[name]=bytes;
  }
  return verifyStaffAuthorityArtifacts(entries);
}

function readDecision() {
  let fd,bytes;
  try {
    const before=fs.lstatSync(decisionPath);
    const valid=stat=>stat.isFile()&&!stat.isSymbolicLink()&&stat.nlink===1&&stat.uid===process.getuid()&&
      (stat.mode&0o777)===0o600&&stat.size>0&&stat.size<=32768;
    need(valid(before)&&fs.realpathSync(decisionPath)===decisionPath);
    fd=fs.openSync(decisionPath,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW|fs.constants.O_NONBLOCK);
    const opened=fs.fstatSync(fd);need(valid(opened)&&opened.ino===before.ino&&opened.dev===before.dev);
    bytes=Buffer.alloc(opened.size+1);let count=0;
    while(count<bytes.length){const read=fs.readSync(fd,bytes,count,bytes.length-count,null);if(!read)break;count+=read;}
    const after=fs.fstatSync(fd);need(count===opened.size&&after.size===opened.size&&after.mtimeMs===opened.mtimeMs&&after.ctimeMs===opened.ctimeMs);
    const raw=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(bytes.subarray(0,count));
    validateStaffAuthorityDecisions(raw);
    const encoded=Buffer.from(raw).toString('base64');
    need(encoded.length>0&&encoded.length<=65536&&/^[A-Za-z0-9+/]+={0,2}$/.test(encoded));
    return encoded;
  } catch { throw Error(generic); }
  finally { bytes?.fill(0); if(fd!==undefined)fs.closeSync(fd); }
}

const migrationGuard = `DO $feedme_staff_authority_guard$ BEGIN
 IF current_user<>'postgres' OR current_database()<>'postgres'
   OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999
   OR current_setting('session_replication_role')<>'origin' THEN
  RAISE EXCEPTION 'Unexpected staff authority operator';
 END IF;
 IF (SELECT count(*) FROM platform.schema_migrations WHERE
   (version=40 AND description='staff_publication_approvals' AND checksum='bda1e378cf5b444845e1fa9de10df1b14c129f84771859197119e9c03e813f7a') OR
   (version=77 AND description='staff_moderator_enrollments' AND checksum='7370ae2a9ad96dc639512d5c858a880661689ac11ac2f21fdb795b83f6121329') OR
   (version=90 AND description='staff_operational_health_reader' AND checksum='13b093289a20928b91bbf439714a788525ea179bbe3ec52a87e5ce47e8d20b0d'))<>3 THEN
  RAISE EXCEPTION 'Staff authority migrations unavailable';
 END IF;
END; $feedme_staff_authority_guard$;`;

function inputCte(encoded) {
  need(typeof encoded==='string'&&encoded.length>0&&encoded.length<=65536&&/^[A-Za-z0-9+/]+={0,2}$/.test(encoded));
  return `WITH raw AS (SELECT convert_from(decode('${encoded}','base64'),'utf8')::jsonb AS j),
i AS (SELECT j->>'environment' environment,j->>'policyVersion' policy_version,j->>'issuer' issuer,
 j->>'policyClientId' client_id,j->>'audience' audience,(j->>'notBefore')::timestamptz not_before,
 (j->>'validUntil')::timestamptz valid_until,(j->>'actorId')::uuid actor_id,j->>'subject' subject,
 (j->>'canPublish')::boolean can_publish,(j->>'canReview')::boolean can_review,
 (j->>'tokenValidAfter')::timestamptz token_valid_after,(j->>'moderatorEnabled')::boolean moderator_enabled
 FROM raw)`;
}

export function staffAuthorityStateQuery(encoded) {
  return `${inputCte(encoded)}
SELECT CASE WHEN
 NOT EXISTS(SELECT 1 FROM staff.publication_policies p,i WHERE p.environment=i.environment)
 AND NOT EXISTS(SELECT 1 FROM staff.actors a,i WHERE a.environment=i.environment AND (a.actor_id=i.actor_id OR (a.issuer=i.issuer AND a.subject=i.subject)))
 AND NOT EXISTS(SELECT 1 FROM staff.moderator_enrollments m,i WHERE m.environment=i.environment AND m.actor_id=i.actor_id)
 THEN 'absent'
 WHEN EXISTS(SELECT 1 FROM staff.publication_policies p,i WHERE p.environment=i.environment AND p.version=i.policy_version
   AND p.issuer=i.issuer AND p.client_id=i.client_id AND p.audience=i.audience AND p.enabled
   AND p.not_before=i.not_before AND p.valid_until=i.valid_until)
 AND EXISTS(SELECT 1 FROM staff.actors a,i WHERE a.environment=i.environment AND a.actor_id=i.actor_id
   AND a.issuer=i.issuer AND a.subject=i.subject AND a.can_publish=i.can_publish AND a.can_review=i.can_review AND a.enabled
   AND a.token_valid_after=i.token_valid_after AND a.not_before=i.not_before AND a.valid_until=i.valid_until)
 AND EXISTS(SELECT 1 FROM staff.moderator_enrollments m,i WHERE m.environment=i.environment AND m.actor_id=i.actor_id
   AND m.version=1 AND m.policy_version=i.policy_version AND m.enabled=i.moderator_enabled
   AND m.not_before=i.not_before AND m.valid_until=i.valid_until)
 THEN 'current' ELSE 'conflict' END FROM i`;
}

export function staffAuthorityPreflightSql(encoded) {
  return `BEGIN READ ONLY;
SET LOCAL lock_timeout='10s'; SET LOCAL statement_timeout='60s';
SET LOCAL search_path=pg_catalog,pg_temp; SET LOCAL standard_conforming_strings=on;
${migrationGuard}
${staffAuthorityStateQuery(encoded)};
ROLLBACK;`;
}

export function staffAuthorityApplySql(encoded) {
  const state=staffAuthorityStateQuery(encoded);
  const input=inputCte(encoded);
  return `BEGIN;
SET LOCAL lock_timeout='10s'; SET LOCAL statement_timeout='60s';
SET LOCAL search_path=pg_catalog,pg_temp; SET LOCAL standard_conforming_strings=on;
${migrationGuard}
LOCK TABLE ONLY staff.publication_policies,ONLY staff.actors,ONLY staff.moderator_enrollments IN SHARE ROW EXCLUSIVE MODE;
DO $feedme_staff_authority_apply$ DECLARE observed text; BEGIN
 SELECT (${state}) INTO observed;
 IF observed='conflict' THEN RAISE EXCEPTION 'Conflicting staff authority exists'; END IF;
 IF observed='absent' THEN
  ${input}
  INSERT INTO staff.publication_policies(environment,version,issuer,client_id,audience,enabled,not_before,valid_until)
   SELECT environment,policy_version,issuer,client_id,audience,true,not_before,valid_until FROM i;
  ${input}
  INSERT INTO staff.actors(environment,actor_id,issuer,subject,can_publish,can_review,enabled,token_valid_after,not_before,valid_until)
   SELECT environment,actor_id,issuer,subject,can_publish,can_review,true,token_valid_after,not_before,valid_until FROM i;
  ${input}
  INSERT INTO staff.moderator_enrollments(environment,actor_id,version,policy_version,enabled,not_before,valid_until)
   SELECT environment,actor_id,1,policy_version,moderator_enabled,not_before,valid_until FROM i;
 END IF;
 SELECT (${state}) INTO observed;
 IF observed<>'current' THEN RAISE EXCEPTION 'Staff authority postcondition failed'; END IF;
END; $feedme_staff_authority_apply$;
SELECT 'applied-or-current';
COMMIT;`;
}

function psql(sql,timeout=60_000) {
  return withSupabaseOperatorEnvironment(operator=>{
    const env={PATH:operator.PATH,PGHOST:operator.FEEDME_MIGRATION_DB_HOST,PGPORT:operator.FEEDME_MIGRATION_DB_PORT,
      PGDATABASE:operator.FEEDME_MIGRATION_DB_NAME,PGUSER:operator.FEEDME_MIGRATION_DB_USER,
      PGPASSWORD:operator.FEEDME_MIGRATION_DB_PASSWORD,PGSSLMODE:'verify-full',
      PGSSLROOTCERT:operator.FEEDME_MIGRATION_DB_SSL_ROOT_CERT,PGCONNECT_TIMEOUT:'8'};
    try{return spawnSync(psqlPath,['-X','-qAt','--no-password','--set=ON_ERROR_STOP=1','--set=VERBOSITY=verbose'],
      {cwd:root,env,input:sql,encoding:'utf8',timeout,maxBuffer:1024*1024});}
    finally{delete env.PGPASSWORD;}
  });
}

function lines(result) {
  need(!result.error&&!result.signal&&result.status===0);
  return result.stdout.trim().split(/\r?\n/).filter(Boolean);
}

function main(args) {
  const operation=staffAuthorityOperation(args),artifacts=localArtifacts();
  if(operation==='--plan') {
    console.log(JSON.stringify({project,status:'local-only-staff-authority-install-plan',artifactSha256:artifacts,
      decisionsRead:false,credentialRead:false,connected:false,changed:false,automaticRetry:false,applyArgument,
      limitation:'Insert-only policy, actor and moderator authority; no identity generation, migration, grant, runtime configuration, deployment or product activation'}));
    return;
  }
  const encoded=readDecision();
  const before=lines(psql(staffAuthorityPreflightSql(encoded))).at(-1);
  need(['absent','current'].includes(before));
  if(operation==='--preflight') {
    console.log(JSON.stringify({project,status:before==='current'?'staff-authority-current':'staff-authority-ready-to-install',
      changed:false,automaticRetry:false,identityRedacted:true}));return;
  }
  if(before==='absent') need(lines(psql(staffAuthorityApplySql(encoded),120_000)).at(-1)==='applied-or-current');
  const after=lines(psql(staffAuthorityPreflightSql(encoded))).at(-1);need(after==='current');
  console.log(JSON.stringify({project,status:'staff-authority-installed-and-verified',changed:before==='absent',
    automaticRetry:false,identityRedacted:true,
    limitation:'Owner-approved registry rows only; no migration, grant, runtime configuration, service deployment or product activation'}));
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  try{main(process.argv.slice(2));}catch(error){console.error(error.message===usage?usage:generic);process.exitCode=1;}
}
