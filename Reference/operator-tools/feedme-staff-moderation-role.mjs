#!/usr/bin/env node
// Fixed-project optional staff serving grants. No role creation, migration,
// credential arguments, enrollment, policy mutation, product activation or retry.
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {withSupabaseOperatorEnvironment} from './supabase-migrate.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const project = 'bljskfhazmnzmhkbkcev';
const psqlPath = '/opt/homebrew/opt/postgresql@15/bin/psql';
const resourcePath = path.join(root,
  'server/src/main/resources/db/provider/feedme-staff-moderation-serving-grants.sql');
const resourceSha256 = '06d7452ef2d3e08a680726c857b52a13eb68fccdd4ee95c99c7a4b6e3c629984';
const marker = '-- FEEDME_STAFF_MODERATION_SERVING_EXPLICIT_GRANTS_BEGIN';
const verifierMarker = 'DO $feedme_staff_serving_verify$';
const generic = 'Staff moderation grant operation refused or outcome unverified. Private diagnostics were not printed. No automatic retry.';
const need = value => { if (!value) throw Error(generic); };
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

export function staffModerationGrantOperation(args) {
  if (args.length === 0 || args.length === 1 && args[0] === '--plan') return '--plan';
  if (args.length === 1 && args[0] === '--preflight') return '--preflight';
  if (args.length === 1 && args[0] === '--apply') return '--apply';
  throw Error('Use node deploy/feedme-staff-moderation-role.mjs [--plan|--preflight|--apply].');
}

export function splitStaffModerationGrantResource(text) {
  need(typeof text === 'string' && digest(Buffer.from(text)) === resourceSha256);
  need(text.split(marker).length === 2 && text.split(verifierMarker).length === 2);
  const prefix = text.slice(0, text.indexOf(marker)).trim();
  const explicit = text.slice(text.indexOf(marker) + marker.length, text.indexOf(verifierMarker)).trim();
  const verifier = text.slice(text.indexOf(verifierMarker)).trim();
  const grants = [...explicit.matchAll(/^GRANT\s[\s\S]*?;/gm)].map(value => value[0]);
  need(grants.length === 15 && grants.join('\n').replace(/\s+/g, ' ') === explicit
    .replace(/--[^\n]*/g, '').trim().replace(/\s+/g, ' '));
  need(!/^\s*(?:ALTER|CREATE|DROP|DELETE|INSERT|UPDATE|TRUNCATE)\b/im.test(explicit));
  need(prefix.includes("current_user='feedme_api'") && prefix.includes("(90,'staff_operational_health_reader'") &&
    prefix.includes('Unsafe staff-moderation serving role'));
  need(verifier.endsWith('$feedme_staff_serving_verify$;') &&
    verifier.includes('Staff-moderation serving role has prohibited authority'));
  return {prefix, explicit, verifier, grants};
}

function regular(file, maximum = 1024 * 1024) {
  let fd;
  try {
    const before = fs.lstatSync(file);
    need(before.isFile() && !before.isSymbolicLink() && before.nlink === 1 &&
      before.size > 0 && before.size <= maximum && fs.realpathSync(file) === file);
    fd = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
    const opened = fs.fstatSync(fd);
    need(opened.ino === before.ino && opened.dev === before.dev && opened.size === before.size);
    const bytes = fs.readFileSync(fd);
    const after = fs.fstatSync(fd);
    need(bytes.length === opened.size && after.size === opened.size &&
      after.mtimeMs === opened.mtimeMs && after.ctimeMs === opened.ctimeMs);
    return bytes;
  } finally { if (fd !== undefined) fs.closeSync(fd); }
}

function psql(sql, timeout = 60_000) {
  need(Number.isSafeInteger(timeout) && timeout >= 1_000 && timeout <= 120_000);
  return withSupabaseOperatorEnvironment(operator => {
    const env = {PATH: operator.PATH, PGHOST: operator.FEEDME_MIGRATION_DB_HOST,
      PGPORT: operator.FEEDME_MIGRATION_DB_PORT, PGDATABASE: operator.FEEDME_MIGRATION_DB_NAME,
      PGUSER: operator.FEEDME_MIGRATION_DB_USER, PGPASSWORD: operator.FEEDME_MIGRATION_DB_PASSWORD,
      PGSSLMODE: 'verify-full', PGSSLROOTCERT: operator.FEEDME_MIGRATION_DB_SSL_ROOT_CERT,
      PGCONNECT_TIMEOUT: '8'};
    try {
      return spawnSync(psqlPath,
        ['-X', '-qAt', '--no-password', '--set=ON_ERROR_STOP=1', '--set=VERBOSITY=verbose'],
        {cwd: root, env, input: sql, encoding: 'utf8', timeout, maxBuffer: 1024 * 1024});
    } finally { delete env.PGPASSWORD; }
  });
}

function operatorGuard(label) {
  return `DO $feedme_operator$ BEGIN IF current_user<>'postgres' OR current_database()<>'postgres'
    OR current_setting('server_version_num')::integer NOT BETWEEN 170000 AND 179999 THEN
    RAISE EXCEPTION 'Unexpected ${label} operator'; END IF; END; $feedme_operator$;`;
}

const installedQuery = `SELECT (
  has_schema_privilege('feedme_api','auth','USAGE')
  AND has_schema_privilege('feedme_api','staff','USAGE')
  AND has_schema_privilege('feedme_api','safety','USAGE')
  AND has_schema_privilege('feedme_api','social','USAGE')
  AND has_table_privilege('feedme_api','staff.publication_policies','SELECT')
  AND has_table_privilege('feedme_api','staff.actors','SELECT')
  AND has_table_privilege('feedme_api','staff.moderator_enrollments','SELECT')
  AND has_function_privilege('feedme_api','staff.lock_moderation_actor(text,text,text)','EXECUTE')
  AND has_table_privilege('feedme_api','safety.reports','SELECT')
  AND has_table_privilege('feedme_api','safety.report_evidence','SELECT')
  AND has_table_privilege('feedme_api','safety.moderation_cases','SELECT')
  AND has_table_privilege('feedme_api','safety.moderation_actions','SELECT')
  AND has_table_privilege('feedme_api','safety.moderation_access_audit','SELECT')
  AND has_table_privilege('feedme_api','safety.moderation_removals','SELECT')
  AND has_table_privilege('feedme_api','social.posts','SELECT')
  AND has_table_privilege('feedme_api','social.thread_messages','SELECT')
  AND has_column_privilege('feedme_api','platform.media_processing_jobs','environment','SELECT')
  AND has_column_privilege('feedme_api','platform.media_processing_jobs','state','SELECT')
  AND has_column_privilege('feedme_api','platform.media_processing_jobs','created_at','SELECT')
  AND NOT EXISTS (
    SELECT 1 FROM (VALUES
      ('platform.schema_migrations','SELECT',ARRAY['description']::text[]),
      ('auth.sessions','SELECT',ARRAY['id','user_id','factor_id']::text[]),
      ('auth.mfa_factors','SELECT',ARRAY['id','user_id','status','factor_type']::text[]),
      ('platform.media_processing_jobs','SELECT',ARRAY['environment','state','created_at']::text[]),
      ('safety.reports','UPDATE',ARRAY['version','status','updated_at']::text[]),
      ('safety.moderation_cases','UPDATE',ARRAY['version','status','assignee_staff_id','reason_code','updated_at','action']::text[]),
      ('safety.moderation_actions','INSERT',ARRAY['environment','id','case_id','report_id','case_version','report_version','actor_id',
        'provider_session_id','authority_revision','action','reason_code','notes','operation_id','command_key','request_sha256',
        'request_text','if_match','response_text','response_sha256','event_id','trace_id','created_at','target_version']::text[]),
      ('safety.moderation_access_audit','INSERT',ARRAY['environment','id','actor_id','provider_session_id','authority_revision',
        'purpose','observed_cases','trace_id','created_at']::text[]),
      ('safety.moderation_removals','INSERT',ARRAY['environment','id','case_id','report_id','action_id','target_type','target_id',
        'target_owner_id','target_version','target_sha256','state','created_at']::text[]),
      ('safety.report_evidence','UPDATE',ARRAY['report_id']::text[]),
      ('safety.moderation_actions','UPDATE',ARRAY['id']::text[]),
      ('safety.moderation_removals','UPDATE',ARRAY['id']::text[]),
      ('social.posts','UPDATE',ARRAY['id']::text[]),
      ('social.thread_messages','UPDATE',ARRAY['id']::text[])
    ) required(relation_name,privilege_name,columns)
    CROSS JOIN LATERAL unnest(required.columns) selected(column_name)
    WHERE NOT has_column_privilege('feedme_api',required.relation_name,selected.column_name,required.privilege_name)
  )
)::text;`;

export function staffModerationPreflightSql(parts, includeInstalled = true) {
  return `BEGIN READ ONLY;
SET LOCAL lock_timeout='10s'; SET LOCAL statement_timeout='60s';
SET LOCAL search_path=pg_catalog,pg_temp; SET LOCAL standard_conforming_strings=on;
${operatorGuard('staff-moderation preflight')}
${parts.prefix}
${includeInstalled ? installedQuery : ''}
ROLLBACK;`;
}

export function staffModerationPostflightSql(parts) {
  return `BEGIN READ ONLY;
SET LOCAL lock_timeout='10s'; SET LOCAL statement_timeout='60s';
SET LOCAL search_path=pg_catalog,pg_temp; SET LOCAL standard_conforming_strings=on;
${operatorGuard('staff-moderation postflight')}
${parts.prefix}
${parts.verifier}
${installedQuery}
ROLLBACK;`;
}

export function staffModerationApplySql(text) {
  return `BEGIN;
SET LOCAL lock_timeout='10s'; SET LOCAL statement_timeout='60s';
SET LOCAL search_path=pg_catalog,pg_temp; SET LOCAL standard_conforming_strings=on;
${operatorGuard('staff-moderation grant')}
${text}
COMMIT;`;
}

function successful(result) {
  need(!result.error && !result.signal && result.status === 0);
  return result.stdout.trim().split(/\r?\n/).filter(Boolean);
}

function main(args) {
  const operation = staffModerationGrantOperation(args);
  const bytes = regular(resourcePath);
  const text = bytes.toString('utf8');
  const parts = splitStaffModerationGrantResource(text);
  if (operation === '--plan') {
    console.log(JSON.stringify({project,status:'local-only-staff-moderation-grant-plan',
      resourceSha256,grantStatements:parts.grants.length,
      requiredMigrations:[10,38,40,52,77,78,80,90],credentialRead:false,connected:false,
      changed:false,automaticRetry:false,
      limitation:'Optional serving grants only; no migration, role, credential, staff authority, product activation or deployment'}));
    return;
  }
  if (operation === '--preflight') {
    const lines = successful(psql(staffModerationPreflightSql(parts)));
    const installed = lines.at(-1) === 'true';
    if (installed) need(successful(psql(staffModerationPostflightSql(parts))).at(-1) === 'true');
    console.log(JSON.stringify({project,status:installed ? 'staff-moderation-grants-current' : 'staff-moderation-grants-pending',
      resourceSha256,changed:false,automaticRetry:false,
      limitation:'Locked read-only preflight only; no migration, grant, enrollment, product activation or deployment'}));
    return;
  }
  // A known COMMIT success is followed by a separate read-only postflight. Any
  // failure or interruption is outcome-unknown and is never retried automatically.
  successful(psql(staffModerationApplySql(text), 120_000));
  need(successful(psql(staffModerationPostflightSql(parts))).at(-1) === 'true');
  console.log(JSON.stringify({project,status:'staff-moderation-grants-installed-and-verified',
    resourceSha256,changed:true,automaticRetry:false,passwordChanged:false,
    limitation:'Optional database serving grants only; no migration, role, staff enrollment, configuration, service deployment or product activation'}));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(process.argv.slice(2)); }
  catch (error) {
    const usage = 'Use node deploy/feedme-staff-moderation-role.mjs [--plan|--preflight|--apply].';
    console.error(error.message === usage ? usage : generic);
    process.exitCode = 1;
  }
}
