// Cloud-only protocol and domain tests. No deployed API, WeChat, money or printer used.
const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict'), ts = require('typescript')
const uuid = '11111111-1111-4111-8111-111111111111'
const session = { sessionId: 'session-a', principalId: 'principal-a', accessToken: 'test-only', expiresAtMs: Date.now()+3600000 }
const file = { id: uuid, displayName: 'photo.pdf', sizeBytes: 100, pageCount: 1, status: 'READY', failureCode: null, ready: true, createdAtMs: Date.now(), expiresAtMs: Date.now()+3600000 }
function load(path, context, requireFn=()=>{throw Error('unexpected require')}) {
  let source=fs.readFileSync(path,'utf8').replace(/import\.meta\.env\.VITE_APP_API_BASE/g, "'https://example.test/app-api'")
  let active=true
  source=source.split('\n').filter(line=>{
    const begin=line.match(/\/\/ #ifdef (\S+)/); if(begin){active=begin[1]==='MP-WEIXIN';return false}
    if(line.includes('// #endif')){active=true;return false};return active
  }).join('\n')
  const exports={}; const code=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText
  vm.runInNewContext(code,{exports,require:requireFn,...context,Date,Promise,Set,Math});return exports
}
function fixture(options={}) {
  const calls=[], storage=new Map(); let activeSession=session, remote=null
  let lostCreate=!!options.lostCreate, lostPart=!!options.lostPart, lostComplete=!!options.lostComplete, blocked=false
  const view=()=>({uploadId:uuid,state:remote.state,variant:remote.variant,fileCount:remote.count,uploadedIndexes:[...remote.uploaded],expiresAtMs:Date.now()+900000,result:remote.state==='COMPLETE'?file:null})
  const send=(opts, body)=>queueMicrotask(()=>opts.success({statusCode:200,data:{code:0,data:body}}))
  const uni={getStorageSync:key=>storage.get(key), setStorageSync:(key,value)=>storage.set(key,JSON.parse(JSON.stringify(value))),removeStorageSync:key=>storage.delete(key),
    request(opts){calls.push(opts);assert.equal(opts.header.Origin,undefined)
      if(!opts.url.endsWith('/complete') && opts.method==='POST') {
        if(!remote)remote={variant:opts.data.variant,count:opts.data.fileCount,uploaded:new Set(),state:'OPEN'}
        else assert.equal(JSON.stringify(opts.data),JSON.stringify({variant:remote.variant,fileCount:remote.count}))
        if(lostCreate){lostCreate=false;queueMicrotask(()=>opts.fail({}));return {}}
      } else if(opts.url.endsWith('/complete')) {
        assert.equal(remote.uploaded.size,remote.count);remote.state='COMPLETE'
        if(options.finalizing && !blocked){blocked=true;remote.state='FINALIZING'}
        if(lostComplete){lostComplete=false;queueMicrotask(()=>opts.fail({}));return {}}
      }
      if(options.expired){queueMicrotask(()=>opts.success({statusCode:410,data:{}}));return {}}
      send(opts,view());return {}
    },
    uploadFile(opts){calls.push(opts);assert.equal(opts.files,undefined);assert.equal(opts.name,'file');remote.uploaded.add(Number(opts.url.split('/').pop()))
      if(options.hideOnPart)options.hideOnPart()
      if(lostPart){lostPart=false;queueMicrotask(()=>opts.fail({}));return {}}
      queueMicrotask(()=>opts.success({statusCode:200,data:JSON.stringify({code:0,data:view()})}));return {}
    }}
  const files=load('src/api/demoFiles.ts',{uni})
  const selections=load('src/domain/photoSelection.ts',{uni})
  const deps=name=>name.includes('demoFiles')?files:name.includes('demoSession')?{getActiveDemoSession:()=>activeSession}:selections
  const api=load('src/api/demoPhotoFiles.ts',{uni},deps)
  const draft=load('src/domain/printDraft.ts',{uni},deps)
  const record=api.newPhotoUpload(session,options.variant||'PHOTO_6IN',Array(options.count||2).fill('temp-path'),3)
  let current=true
  return {api,draft,record,uni,files,storage,calls,selections,getSession:()=>activeSession,remote:()=>remote,changeOwner:()=>{activeSession={...session,sessionId:'other'}},hide:()=>{current=false},run:()=>api.continuePhotoUpload(session,record,()=>current,()=>{})}
}
// Execute the real setup script with Vue's actual ref/reactive proxy semantics.
function photoPage(f) {
  const hooks={}, navigations=[]; let sessionChanged=()=>{}
  f.storage.clear(); f.selections.choosePhotoSpecification('PHOTO_6IN')
  const source=fs.readFileSync('src/pages/photo-upload/index.vue','utf8').match(/<script setup lang="ts">([^]*?)<\/script>/)[1]
    + '\nexports.page={submit,photos,pending,busy,issue,view};'
  const code=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText
  const exports={}
  const uni={...f.uni,navigateTo:args=>navigations.push(args.url),redirectTo:args=>navigations.push(args.url)}
  const deps=name=>{
    if(name==='vue')return require('vue')
    if(name==='@dcloudio/uni-app')return Object.fromEntries(['onLoad','onShow','onHide','onUnload'].map(name=>[name,callback=>{hooks[name]=callback}]))
    if(name.includes('demoSession'))return {ensureDemoSession:()=>Promise.resolve(f.getSession()),getActiveDemoSession:f.getSession,watchDemoSession:callback=>{sessionChanged=callback}}
    if(name.includes('demoPhotoFiles'))return f.api
    if(name.includes('photoSelection'))return f.selections
    if(name.includes('printDraft'))return f.draft
    if(name.includes('demoFiles'))return f.files
    throw Error('unexpected page dependency '+name)
  }
  vm.runInNewContext(code,{exports,require:deps,uni,Date,Promise,Math})
  hooks.onLoad();hooks.onShow();exports.page.photos.value=['temp-a','temp-b']
  return {...exports.page,navigations,hide:()=>hooks.onHide(),show:()=>hooks.onShow(),changeOwner:()=>{f.changeOwner();sessionChanged()}}
}
let passed=0
async function test(name,run){await run();passed++;console.log('PASS '+name)}
async function main(){
  await test('real Vue page first click sends create and uploads without a second click',async()=>{
    const f=fixture(),page=photoPage(f);await page.submit()
    assert.equal(f.calls.filter(call=>call.data).length,1,'the first click must create the upload session')
    assert.equal(f.calls.filter(call=>call.filePath).length,2)
    assert.equal(page.navigations[0],'/pages/order-confirm/index')
  })
  await test('real Vue page unknown recovery retains one upload key and resumes on next click',async()=>{
    const f=fixture({lostComplete:true}),page=photoPage(f);await page.submit()
    const key=page.pending.value.key;assert.match(page.issue.value,/结果暂不可确认/);assert.equal(page.navigations.length,0)
    await page.submit();assert.equal(page.navigations[0],'/pages/order-confirm/index')
    assert.equal(f.calls.filter(call=>call.data).length,1);assert.ok(f.calls.every(call=>call.header['Idempotency-Key']===key))
    assert.equal(f.calls.filter(call=>call.filePath).length,2)
  })
  await test('real Vue page hide blocks late results, then resumes the original upload',async()=>{
    let page,hidden=false;const f=fixture({hideOnPart:()=>{if(!hidden){hidden=true;page.hide()}}});page=photoPage(f);await page.submit()
    const key=page.pending.value.key;assert.equal(f.calls.filter(call=>call.filePath).length,1);assert.equal(page.navigations.length,0)
    // Only the first request hides this page; restoring it can finish the original session.
    const count=f.calls.length;page.show();await page.submit()
    assert.ok(f.calls.length>count);assert.equal(f.calls.filter(call=>call.data).length,1)
    assert.ok(f.calls.every(call=>call.header['Idempotency-Key']===key));assert.equal(page.navigations[0],'/pages/order-confirm/index')
  })
  await test('real Vue page subject change invalidates old pending state and drops late response',async()=>{
    let page;const f=fixture({hideOnPart:()=>page.changeOwner()});page=photoPage(f);await page.submit()
    assert.equal(page.pending.value,null);assert.equal(page.photos.value.length,0);assert.equal(page.navigations.length,0)
    assert.equal(f.calls.filter(call=>call.filePath).length,1);assert.equal(f.calls.filter(call=>call.data).length,1)
    await page.submit();assert.equal(f.calls.filter(call=>call.data).length,1)
  })
  await test('30 photos upload serially and create once; repeated complete returns same file',async()=>{
    const f=fixture({count:30});assert.equal((await f.run()).view.result.id,uuid);assert.equal((await f.run()).view.result.id,uuid)
    assert.equal(f.calls.filter(x=>x.data).length,1);assert.equal(f.calls.filter(x=>x.filePath).length,30)
    assert.deepEqual(f.calls.filter(x=>x.filePath).map(x=>Number(x.url.split('/').pop())),Array.from({length:30},(_,i)=>i))
  })
  await test('9-image collage freezes variant and retains copies',async()=>{const f=fixture({variant:'PHOTO_6IN_COLLAGE',count:9});assert.equal((await f.run()).kind,'ok');assert.equal(f.api.loadPhotoUpload().copies,3);assert.equal(f.remote().variant,'PHOTO_6IN_COLLAGE')})
  for(const loss of ['lostCreate','lostPart','lostComplete'])await test(loss+' retains original key and upload identity',async()=>{
    const f=fixture({[loss]:true});assert.equal((await f.run()).kind,'unknown');const key=f.record.key
    assert.equal((await f.run()).view.result.id,uuid);assert.ok(f.calls.every(x=>x.header['Idempotency-Key']===key))
    assert.equal(f.calls.filter(x=>x.filePath).length,2)
    if(loss!=='lostCreate')assert.equal(f.calls.filter(x=>x.data).length,1)
  })
  await test('FINALIZING retries same complete domain without uploading again',async()=>{const f=fixture({finalizing:true});assert.equal((await f.run()).view.state,'FINALIZING');assert.equal((await f.run()).view.result.id,uuid);assert.equal(f.calls.filter(x=>x.filePath).length,2)})
  await test('expired session is rejected and never silently replaced',async()=>{const f=fixture({expired:true});assert.equal((await f.run()).kind,'rejected');assert.equal((await f.run()).kind,'rejected');assert.equal(new Set(f.calls.map(x=>x.header['Idempotency-Key'])).size,1)})
  await test('hidden page stops before next part and retains recoverable upload id',async()=>{let f;f=fixture({hideOnPart:()=>f.hide()});assert.equal((await f.run()).kind,'unknown');assert.equal(f.calls.filter(x=>x.filePath).length,1);assert.equal(f.api.loadPhotoUpload().uploadId,uuid)})
  await test('changed subject cannot load, resume or reuse old photo draft',async()=>{const f=fixture();await f.run();f.changeOwner();assert.equal(f.api.loadPhotoUpload(),null);assert.equal((await f.run()).kind,'unknown');assert.equal(f.draft.createPhotoDraft(file,session,1),null)})
  await test('photo draft uses authoritative page count, selected copies and session owner',()=>{const f=fixture();const d=f.draft.createPhotoDraft({...file,pageCount:30},session,4);assert.equal(d.pages,30);assert.equal(d.copies,4);assert.equal(d.paper,'PHOTO_6IN');assert.equal(d.color,'COLOR');assert.equal(d.archiveOwner.sessionId,session.sessionId);assert.equal(f.draft.createPhotoDraft({...file,pageCount:31},session,1),null)})
  await test('specification handoff is single-use and time bounded',()=>{const f=fixture();f.selections.choosePhotoSpecification('PHOTO_5IN');assert.equal(f.selections.takePhotoSpecification(),'PHOTO_5IN');assert.equal(f.selections.takePhotoSpecification(),null);f.uni.setStorageSync('miniapp-photo-selection-v1',{specification:'PHOTO_6IN',createdAtMs:Date.now()-300001});assert.equal(f.selections.takePhotoSpecification(),null)})
  await test('malformed upload identity and mismatched variant fail closed',()=>{const f=fixture();const view={uploadId:'invalid',variant:'PHOTO_6IN',state:'OPEN',fileCount:2,uploadedIndexes:[],expiresAtMs:Date.now()+100000,result:null};assert.equal(f.api.parsePhotoUpload(200,{code:0,data:view},f.record).kind,'unknown');view.uploadId=uuid;view.variant='PHOTO_3IN';assert.equal(f.api.parsePhotoUpload(200,{code:0,data:view},f.record).kind,'unknown')})
  await test('all photo previews use contain and certificate continues to shared confirmation',()=>{const photo=fs.readFileSync('src/pages/photo-upload/index.vue','utf8'),id=fs.readFileSync('src/pages/id-photo-preview/index.vue','utf8');assert.match(photo,/mode="aspectFit"/);assert.doesNotMatch(photo,/aspectFill/);assert.match(id,/createPhotoDraft\(finalFile, session, copies.value\)/);assert.match(id,/pages\/order-confirm\/index/)})
  await test('v1 document and v2 photo quote contracts; strict request and stable retry key',async()=>{
    const calls=[]
    let snapshot={schemaVersion:2,mode:'DEMO',currency:'CNY',aggregationPolicy:'PER_FILE_ALL_COPIES',fileId:uuid,
      paper:'PHOTO_6IN',color:'COLOR',side:'SIMPLEX',copies:'3',sourceKind:'PHOTO',sourceVariant:'PHOTO_6IN',
      pricingMode:'PHOTO_SHEET',pagesPerCopy:2,totalSheets:'6',pricePerSheet:'1.00',totalAmount:'6.00',expiresAtMs:Date.now()+10000}
    const api=load('src/api/demoQuotes.ts',{uni:{request(opts){calls.push(opts);queueMicrotask(()=>opts.success({statusCode:200,data:{code:0,data:{id:uuid,status:'VALID',snapshot}}}))}}})
    const run=()=>api.createDemoQuote(session,uuid,'PHOTO_6IN','COLOR',3,'SIMPLEX',1)
    assert.equal((await run()).kind,'ok');assert.equal((await run()).kind,'ok')
    assert.equal(calls[0].header['Idempotency-Key'],calls[1].header['Idempotency-Key'])
    assert.equal(JSON.stringify(calls[0].data),JSON.stringify({fileId:uuid,paper:'PHOTO_6IN',color:'COLOR',copies:3,side:'SIMPLEX'}))
    for(const variant of ['ONE_INCH','TWO_INCH','THREE_INCH']){snapshot={...snapshot,sourceKind:'ID_PHOTO',sourceVariant:variant,pagesPerCopy:1};assert.equal((await run()).kind,'ok')}
    snapshot={...snapshot,schemaVersion:1};assert.equal((await run()).kind,'failed')
    snapshot={...snapshot,schemaVersion:2,sourceVariant:'INVALID'};assert.equal((await run()).kind,'failed')
    snapshot={...snapshot,sourceVariant:'ONE_INCH',pricePerSheet:'1.0'};assert.equal((await run()).kind,'failed')
    snapshot={...snapshot,pricePerSheet:'1.00',fileId:'wrong-file'};assert.equal((await run()).kind,'failed')
    snapshot={...snapshot,schemaVersion:1,fileId:uuid,paper:'A4',color:'BLACK_WHITE',pricePerSheet:'0.2',totalAmount:'1.2'}
    assert.equal((await api.createDemoQuote(session,uuid,'A4','BLACK_WHITE',3,'SIMPLEX',1)).kind,'ok')
    const count=calls.length;assert.equal((await api.createDemoQuote(session,uuid,'PHOTO_6IN','COLOR',3,'DUPLEX',1)).kind,'failed');assert.equal(calls.length,count)
  })
  console.log(JSON.stringify({status:'PASS_PROTOCOL_DOMAIN_ONLY',checks:passed,realApi:'NOT_RUN',wechatDevice:'NOT_RUN',payment:'SIMULATED_ONLY',printer:'NOT_RUN'}))
}
main().catch(error=>{console.error(error);process.exitCode=1})
