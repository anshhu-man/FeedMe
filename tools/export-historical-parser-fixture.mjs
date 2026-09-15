import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {FILES,FIXTURE_DIRECTORY,ORIGINAL_RECEIPT,ORIGINAL_RECEIPT_SHA,ORIGINAL_SOURCE_MANIFEST_SHA,
 README_TEXT,canonicalDirectory,relativePath,readRegularWithin,requirePrivateDataAbsent,manifestText,sha,need,
 verifyHistoricalParserFixture} from './verify-historical-parser-fixture.mjs';

// Pure policy for already-canonical roots; filesystem/symlink checks remain with the caller.
export function requireFixtureRootSeparation(workspace,destination){
 need([workspace,destination].every(root=>typeof root==='string'&&path.isAbsolute(root)&&path.resolve(root)===root),'Canonical absolute roots required');
 const contains=(parent,child)=>parent===child||child.startsWith(parent.endsWith(path.sep)?parent:parent+path.sep);
 const sourceTree=path.join(workspace,'feedme');
 need(!contains(destination,workspace)&&!contains(destination,sourceTree)&&!contains(sourceTree,destination),'Fixture destination overlaps the source workspace or feedme tree');
}

// Explicit publication-only extraction. No source writes, deletion, Git, network or native work.
export function exportHistoricalParserFixture(sourceWorkspace,publicationRoot){
 const workspace=canonicalDirectory(sourceWorkspace),destination=canonicalDirectory(publicationRoot);
 requireFixtureRootSeparation(workspace,destination);
 const raw=readRegularWithin(workspace,ORIGINAL_RECEIPT);need(sha(raw)===ORIGINAL_RECEIPT_SHA,'Original historical receipt changed');
 const receipt=JSON.parse(raw.toString('utf8'));
 need(receipt.passed===true&&receipt.startedAt==='2026-09-14T08:50:11.189Z'&&receipt.finishedAt==='2026-09-14T09:00:46.781Z'&&
  Array.isArray(receipt.sourceFiles)&&Array.isArray(receipt.frozenSources)&&
  receipt.sourceManifestSha256===ORIGINAL_SOURCE_MANIFEST_SHA&&sha(JSON.stringify(receipt.sourceFiles))===ORIGINAL_SOURCE_MANIFEST_SHA,'Original source authority changed');
 const names=FILES.map(file=>file.sourcePath),scripts=names.filter(name=>name.startsWith('scripts/'));
 const selected=receipt.frozenSources.filter(pair=>scripts.includes(pair.source?.path)||pair.source?.path?.startsWith('apps/android/src/androidTestProgress/'));
 need(JSON.stringify(selected.map(pair=>pair.source.path).sort())===JSON.stringify([...names].sort()),'Historical dependency closure changed');
 const prefix=path.posix.dirname(ORIGINAL_RECEIPT.slice('feedme/'.length))+'/sources/';
 const pending=FILES.map(file=>{
  const pairs=selected.filter(pair=>pair.source.path===file.sourcePath),sources=receipt.sourceFiles.filter(source=>source.path===file.sourcePath);
  need(pairs.length===1&&sources.length===1,'Duplicate historical source');const pair=pairs[0];
  need(sources[0].bytes===file.bytes&&sources[0].sha256===file.sha256&&pair.source.bytes===file.bytes&&pair.source.sha256===file.sha256&&
   pair.retained.bytes===file.bytes&&pair.retained.sha256===file.sha256&&pair.retained.path.startsWith(prefix)&&
   /^\d{4}-[^/]+$/.test(path.posix.basename(pair.retained.path))&&path.posix.basename(pair.retained.path).slice(5)===path.posix.basename(file.sourcePath),'Historical source/copy binding changed');
  relativePath(pair.retained.path);const bytes=readRegularWithin(workspace,'feedme/'+pair.retained.path);
  need(bytes.length===file.bytes&&sha(bytes)===file.sha256,'Historical retained source changed');requirePrivateDataAbsent(bytes);
  return {path:'workspace/'+file.sourcePath,bytes};
 });
 // Validate every existing destination component before the first write; refuse replacement.
 let current=destination;
 for(const part of FIXTURE_DIRECTORY.split('/')){
  current=path.join(current,part);
  if(fs.existsSync(current)||fs.lstatSync(current,{throwIfNoEntry:false})){
   const stat=fs.lstatSync(current);need(stat.isDirectory()&&!stat.isSymbolicLink(),'Nonregular destination component');
   need(current!==path.join(destination,FIXTURE_DIRECTORY),'Fixture destination already exists');
  }
 }
 current=destination;
 for(const part of FIXTURE_DIRECTORY.split('/')){current=path.join(current,part);if(!fs.existsSync(current))fs.mkdirSync(current);}
 const fixture=current;
 for(const item of [...pending,{path:'manifest.json',bytes:Buffer.from(manifestText())},{path:'README.md',bytes:Buffer.from(README_TEXT)}]){
  const output=path.join(fixture,item.path);fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,item.bytes,{flag:'wx',mode:0o644});
 }
 return verifyHistoricalParserFixture(destination);
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const args=process.argv.slice(2);need(args.length===2,'Use SOURCE_WORKSPACE PUBLICATION_ROOT');
 console.log(JSON.stringify(exportHistoricalParserFixture(args[0],args[1]),null,2));
}
