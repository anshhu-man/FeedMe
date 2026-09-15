import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {FIXTURE_DIRECTORY,FILES,ORIGINAL_RECEIPT,verifyHistoricalParserFixture,replayHistoricalParserFixture} from './verify-historical-parser-fixture.mjs';
import {exportHistoricalParserFixture,requireFixtureRootSeparation} from './export-historical-parser-fixture.mjs';

const source=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
function temporary(t){const root=fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()),'feedme-portable-fixture-test-'));t.after(()=>fs.rmSync(root,{recursive:true}));return root;}
function clone(t){const root=temporary(t);fs.mkdirSync(path.join(root,'Reference/Fixtures'),{recursive:true});fs.cpSync(path.join(source,FIXTURE_DIRECTORY),path.join(root,FIXTURE_DIRECTORY),{recursive:true});return root;}
const file=root=>path.join(root,FIXTURE_DIRECTORY,'workspace',FILES[0].sourcePath);
test('fresh portable copy replays exact ten parser tests without original reports',t=>{
 const root=clone(t);assert.equal(fs.existsSync(path.join(root,ORIGINAL_RECEIPT)),false);
 assert.equal(fs.existsSync(path.join(root,FIXTURE_DIRECTORY,'workspace/docs')),false);
 const result=replayHistoricalParserFixture(root);assert.equal(result.testsExecuted,10);assert.equal(result.files,9);
 assert.equal(result.originalReceiptBundled,false);assert.equal(result.testIdentities.length,10);
 fs.mkdirSync(path.join(root,'tools'));
 const standalone=path.join(root,'tools/verify-historical-parser-fixture.mjs');
 fs.copyFileSync(path.join(source,'tools/verify-historical-parser-fixture.mjs'),standalone);
 const run=spawnSync(process.execPath,[standalone,root,'--run-tests'],{cwd:root,encoding:'utf8',timeout:30_000});
 assert.equal(run.status,0,run.stderr);assert.equal(run.error,undefined);assert.equal(run.signal,null);
 const standaloneResult=JSON.parse(run.stdout);assert.deepEqual(standaloneResult.testIdentities,result.testIdentities);
 assert.equal(standaloneResult.testsExecuted,10);assert.equal(standaloneResult.originalReceiptBundled,false);
});
test('changed historical source bytes are rejected',t=>{const root=clone(t);fs.appendFileSync(file(root),'\n');assert.throws(()=>verifyHistoricalParserFixture(root));});
test('missing historical source is rejected',t=>{const root=clone(t);fs.unlinkSync(file(root));assert.throws(()=>verifyHistoricalParserFixture(root));});
test('unlisted files and even empty directories are rejected',t=>{
 const root=clone(t),extra=path.join(root,FIXTURE_DIRECTORY,'workspace/extra');fs.mkdirSync(extra);
 assert.throws(()=>verifyHistoricalParserFixture(root));fs.rmdirSync(extra);fs.writeFileSync(extra,'extra');assert.throws(()=>verifyHistoricalParserFixture(root));
});
test('manifest path escape is rejected rather than read',t=>{
 const root=clone(t),p=path.join(root,FIXTURE_DIRECTORY,'manifest.json'),manifest=JSON.parse(fs.readFileSync(p));
 manifest.files[0].path='../../outside';fs.writeFileSync(p,JSON.stringify(manifest,null,2)+'\n');assert.throws(()=>verifyHistoricalParserFixture(root));
});
test('symlinked historical file is rejected even with identical bytes',t=>{
 const root=clone(t),p=file(root),outside=path.join(root,'same-bytes.kt');fs.copyFileSync(p,outside);fs.unlinkSync(p);fs.symlinkSync(outside,p);
 assert.throws(()=>verifyHistoricalParserFixture(root));
});
test('invalid original receipt refuses export before destination writes',t=>{
 const workspace=temporary(t),destination=temporary(t),p=path.join(workspace,ORIGINAL_RECEIPT);
 fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,'{"passed":true}\n');
 assert.throws(()=>exportHistoricalParserFixture(workspace,destination),/receipt changed/);
 assert.deepEqual(fs.readdirSync(destination),[]);
});
test('publication sibling roots are allowed without permitting source overlap',()=>{
 const workspace='/workspace/Career';
 for(const destination of ['/workspace/Career/feedme-github','/workspace/Career/exports/feedme-github','/publication/feedme-github']){
  assert.doesNotThrow(()=>requireFixtureRootSeparation(workspace,destination));
 }
 for(const destination of [workspace,'/workspace','/','/workspace/Career/feedme','/workspace/Career/feedme/docs']){
  assert.throws(()=>requireFixtureRootSeparation(workspace,destination),/overlaps/);
 }
 assert.throws(()=>requireFixtureRootSeparation(workspace,'/workspace/Career/feedme/../feedme-github'),/Canonical/);
 assert.throws(()=>requireFixtureRootSeparation(workspace,'feedme-github'),/Canonical/);
});
