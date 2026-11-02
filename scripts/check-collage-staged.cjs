// Cloud-only deterministic protocol mock. Does not claim WeChat device acceptance.
const fs = require('node:fs')
const vm = require('node:vm')
const assert = require('node:assert/strict')
const ts = require('typescript')
const source = fs.readFileSync('src/api/demoImageCollageStaged.ts', 'utf8')
const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText
const uuid = '11111111-1111-4111-8111-111111111111'
const token = { accessToken: 'test-only-token', expiresAtMs: Date.now() + 3600000 }
const metadata = { id: uuid, displayName: 'image-collage.pdf', sizeBytes: 100, pageCount: null, status: 'UPLOADED',
  failureCode: null, createdAtMs: Date.now(), expiresAtMs: Date.now() + 3600000, ready: false }
let passed = 0
function fixture(options = {}) {
  const calls = []; const progress = []; const sessions = new Map(); let pending = null
  let losePart = Boolean(options.losePart); let rateLimit = Boolean(options.rateLimit)
  const hashes = { a: 'a'.repeat(64), b: 'b'.repeat(64) }
  const view = value => ({ uploadId: uuid, fileCount: value.files.length, state: value.complete ? 'COMPLETE' : 'OPEN',
    uploadedIndexes: [...value.uploaded], expiresAtMs: Date.now() + 900000, result: value.complete ? metadata : null })
  const send = (callback, value) => queueMicrotask(() => callback(value))
  const uni = {
    getFileSystemManager: () => ({ getFileInfo(opts) {
      if (options.digestFailure) return send(opts.fail, {})
      send(opts.success, { size: 100, digest: hashes[opts.filePath] })
    } }),
    request(opts) {
      calls.push({ kind: 'request', ...opts })
      assert.equal(opts.header.Origin, undefined); assert.equal(opts.header.Referer, undefined)
      let session = sessions.get(opts.header['Idempotency-Key'])
      if (opts.method === 'DELETE') { if (session) session.deleted = true; send(opts.success, { statusCode: 200, data: { code: 0 } }); return {} }
      if (opts.data) {
        if (options.createFailure) { send(opts.fail, {}); return { abort() {} } }
        const manifest = JSON.stringify(opts.data)
        if (session && session.manifest !== manifest) {
          send(opts.success, { statusCode: 409, data: { code: 1935004 } }); return {}
        }
        if (!session || session.expired) {
          session = { manifest, files: opts.data.files, uploaded: new Set(), complete: false }
          sessions.set(opts.header['Idempotency-Key'], session)
        }
        const reply = () => opts.success({ statusCode: 200, data: { code: 0, data: view(session) } })
        if (options.delayedCreate) pending = reply
        else queueMicrotask(reply)
      } else {
        assert.ok(session && session.uploaded.size === session.files.length)
        session.complete = true
        send(opts.success, { statusCode: 200, data: { code: 0, data: view(session) } })
      }
      return { abort() {} }
    },
    uploadFile(opts) {
      calls.push({ kind: 'upload', ...opts })
      assert.equal(opts.files, undefined)
      assert.equal(opts.name, 'file')
      assert.equal(opts.header.Origin, undefined); assert.equal(opts.header.Referer, undefined)
      const session = sessions.get(opts.header['Idempotency-Key'])
      const index = Number(opts.url.split('/').pop())
      const task = { abort() { send(opts.fail, {}) }, onProgressUpdate(callback) { callback({ progress: 50 }) } }
      const response = () => {
        if (rateLimit) { rateLimit = false; opts.success({ statusCode: 429, data: '{}' }); return }
        session.uploaded.add(index)
        if (losePart) { losePart = false; opts.fail({}); return }
        opts.success({ statusCode: 200, data: JSON.stringify({ code: 0, data: view(session) }) })
      }
      if (options.delayedPart) pending = response
      else queueMicrotask(response)
      return task
    }
  }
  const exports = {}
  vm.runInNewContext(code, { exports, uni, Date, Promise, Set })
  return { calls, sessions, hashes, progress, flush: () => pending?.(),
    start: (paths = ['a', 'b']) => exports.uploadStagedImageCollage('https://example.test/app-api/print/miniapp/demo/image-collage-upload-sessions',
      token, paths, 'original-key', value => progress.push(value), value => value.code === 0 ? value.data : null) }
}
async function check(name, action) { await action(); passed++; console.log('PASS ' + name) }
async function tick() { for (let i = 0; i < 8; i++) await Promise.resolve() }
async function main() {
  await check('ordered manifest and single-file upload, aggregate progress, complete', async () => {
    const f = fixture(); const result = await f.start().promise
    assert.equal(result.id, uuid)
    assert.deepEqual(f.calls.filter(x => x.kind === 'upload').map(x => x.filePath), ['a', 'b'])
    assert.equal(f.calls[0].data.renderVersion, 'IMAGE_COLLAGE_A4_PORTRAIT_GRID_V1')
    assert.deepEqual(Array.from(f.calls[0].data.files, x => x.sha256), ['a'.repeat(64), 'b'.repeat(64)])
    assert.deepEqual(f.progress, [0, 25, 50, 75, 100])
  })
  await check('nine images are uploaded serially using nine single-file requests', async () => {
    const f = fixture(); assert.equal((await f.start(Array(9).fill('a')).promise).id, uuid)
    assert.equal(f.calls.filter(x => x.kind === 'upload').length, 9)
    assert.equal(f.calls[0].data.files.length, 9)
  })
  await check('completed replay compares freshly recomputed manifest before returning result', async () => {
    const f = fixture(); await f.start().promise
    assert.equal((await f.start().promise).id, uuid)
    assert.equal(f.calls.filter(x => x.kind === 'upload').length, 2)
    f.hashes.a = 'c'.repeat(64)
    assert.equal(await f.start().promise, null)
    assert.equal(f.calls.filter(x => x.kind === 'upload').length, 2)
  })
  await check('lost part response resumes same key and skips only server-confirmed index', async () => {
    const f = fixture({ losePart: true }); assert.equal(await f.start().promise, null)
    assert.equal((await f.start().promise).id, uuid)
    assert.deepEqual(f.calls.filter(x => x.kind === 'upload').map(x => x.filePath), ['a', 'b'])
  })
  await check('429 preserves original key and permits explicit retry', async () => {
    const f = fixture({ rateLimit: true }); assert.equal(await f.start().promise, null)
    assert.equal((await f.start().promise).id, uuid)
    assert.ok(f.calls.every(x => x.header['Idempotency-Key'] === 'original-key'))
  })
  await check('expired temporary session recreates with original manifest and final key', async () => {
    const f = fixture({ losePart: true }); await f.start().promise
    f.sessions.get('original-key').expired = true
    assert.equal((await f.start().promise).id, uuid)
    assert.deepEqual(f.calls.filter(x => x.kind === 'upload').map(x => x.filePath), ['a', 'a', 'b'])
  })
  await check('page invalidation cancels old uploadId when retry create loses its response', async () => {
    const page = fs.readFileSync('src/pages/image-collage/index.vue', 'utf8')
    assert.match(page, /uploadHandles\.add\(handle\)/)
    assert.match(page, /function invalidateSubmission\(\)[\s\S]*?cancelUploads\(\)/)
    assert.match(page, /function disposePage\(\)[\s\S]*?cancelUploads\(\)/)
    assert.match(page, /function resetForSessionChange[^]*?invalidateSubmission\(\)/)
    const cleanup = page.match(/function cancelUploads\(\) \{[\s\S]*?\n\}/)?.[0]
    assert.ok(cleanup)
    const registry = {}
    vm.runInNewContext('const uploadHandles = new Set();' + cleanup
      + ';exports.add = handle => uploadHandles.add(handle); exports.cancel = cancelUploads; exports.size = () => uploadHandles.size;',
      { Set, exports: registry })
    const opts = { losePart: true }; const f = fixture(opts)
    const first = f.start(); registry.add(first); assert.equal(await first.promise, null)
    assert.equal(f.sessions.get('original-key').deleted, undefined)
    opts.createFailure = true
    const retry = f.start(); registry.add(retry); assert.equal(await retry.promise, null)
    assert.equal(f.calls.filter(x => x.method === 'DELETE').length, 0)
    registry.cancel(); await tick()
    assert.equal(f.sessions.get('original-key').deleted, true)
    assert.ok(f.calls.some(x => x.method === 'DELETE' && x.url.endsWith(uuid)))
    assert.equal(registry.size(), 0)
  })
  await check('abort during upload deletes session, resolves null, discards late response', async () => {
    const f = fixture({ delayedPart: true }); const handle = f.start(); await tick()
    handle.abort(); assert.equal(await handle.promise, null); f.flush(); await tick()
    assert.ok(f.calls.some(x => x.method === 'DELETE'))
    assert.ok(!f.calls.some(x => x.url.endsWith('/complete')))
  })
  await check('abort before delayed create result cancels identified session on late response', async () => {
    const f = fixture({ delayedCreate: true }); const handle = f.start(); await tick()
    handle.abort(); assert.equal(await handle.promise, null); f.flush(); await tick()
    assert.ok(f.calls.some(x => x.method === 'DELETE'))
    assert.equal(f.calls.filter(x => x.kind === 'upload').length, 0)
  })
  await check('digest failure does not create remote session', async () => {
    const f = fixture({ digestFailure: true }); assert.equal(await f.start().promise, null); assert.equal(f.calls.length, 0)
  })
  await check('conditional source keeps H5 multi-file and excludes it from WeChat adapter', () => {
    const wrapper = fs.readFileSync('src/api/demoImageCollages.ts', 'utf8')
    function platform(name) {
      let active = true
      return wrapper.split('\n').filter(line => {
        const begin = line.match(/\/\/ #ifdef (\S+)/)
        if (begin) { active = begin[1] === name; return false }
        if (line.includes('// #endif')) { active = true; return false }
        return active
      }).join('\n')
    }
    assert.match(platform('H5'), /files: filePaths\.map/)
    assert.doesNotMatch(platform('MP-WEIXIN'), /files: filePaths\.map/)
    assert.match(platform('MP-WEIXIN'), /return uploadStagedImageCollage/)
    assert.doesNotMatch(source, /files:\s*paths\.map/)
  })
  console.log(JSON.stringify({ status: 'PASS_MOCK_AND_SOURCE_ONLY', checks: passed, wechatDevice: 'NOT_RUN', build: 'NOT_RUN' }))
}
main().catch(error => { console.error(error); process.exitCode = 1 })
