// Isolated executable API/editor checks. No real HTTP, database writes or deployment.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { createRequire } = require('node:module')
const compiler = createRequire(require.resolve('vue-tsc/package.json'))('@vue/compiler-sfc')
const base = 'src/print-admin/orders/photo-price/'
let checks = 0
let response
let transportError
let pending
const requests = []
const request = Object.fromEntries(
  ['get', 'put'].map((method) => [
    method,
    async (options) => {
      requests.push({ method, ...options })
      if (pending) {
        const run = pending
        pending = null
        return run()
      }
      if (transportError) throw transportError
      return options.transformResponse[0](JSON.stringify(response)).data
    }
  ])
)
function load(file, imports) {
  const output = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.CommonJS,
      esModuleInterop: true
    }
  }).outputText
  const exports = {}
  vm.runInNewContext(
    output,
    {
      exports,
      require: (id) => {
        if (!(id in imports)) throw Error('Unexpected import ' + id)
        return imports[id]
      },
      Error,
      Number,
      BigInt
    },
    { filename: file }
  )
  return exports
}
const api = load(base + 'api.ts', {
  '@/config/axios': request,
  axios: { isAxiosError: (error) => !!error?.isAxiosError }
})
const check = (name, run) => {
  run()
  checks++
  console.log('PASS ' + name)
}
const fixture = {
  profileId: '12345678-1234-4234-8234-123456789012',
  profileKey: 'MINIAPP_DEMO_PHOTO_6IN',
  profileVersion: '1',
  revision: '1',
  currency: 'CNY',
  pricePerSheet: '1.00',
  quoteEnabled: false
}
const ok = (data) => {
  transportError = null
  response = { code: 0, data }
}
const flush = () => new Promise((resolve) => setImmediate(resolve))
async function fails(name, kind, run) {
  let caught
  try {
    await run()
  } catch (error) {
    caught = error
  }
  check(name, () => {
    assert.ok(caught)
    assert.equal(api.classifyPhotoPriceError(caught).kind, kind)
  })
}
async function renderCard(permissions) {
  const name = base + 'PhotoPriceCard.vue'
  const { descriptor } = compiler.parse(fs.readFileSync(name, 'utf8'), { filename: name })
  const compiled = compiler.compileScript(descriptor, { id: 'photo-price', inlineTemplate: true })
  const output = ts.transpileModule(compiled.content, {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.CommonJS,
      esModuleInterop: true
    }
  }).outputText
  const exports = {}
  let actualAccess
  const imports = {
    vue,
    '@/store/modules/user': { useUserStore: () => ({ getUser: { id: 1 } }) },
    '@/directives/permission/hasPermi': {
      hasPermission: (keys) => keys.some((key) => permissions.includes(key))
    },
    '../../contextStore': {
      usePrintContextStore: () => ({
        status: 'ready',
        context: { userId: 1, tenantId: 1, storeId: 1 }
      })
    },
    './editor': {
      usePhotoPriceEditor: (access) => {
        actualAccess = access
        return {
          price: vue.ref(fixture),
          draft: vue.ref('1.00'),
          loading: vue.ref(false),
          saving: vue.ref(false),
          failure: vue.ref(null),
          notice: vue.ref(''),
          inputError: vue.ref(''),
          load: () => {},
          save: () => {}
        }
      }
    }
  }
  vm.runInNewContext(
    output,
    {
      exports,
      require: (id) => {
        if (!(id in imports)) throw Error('Unexpected UI import ' + id)
        return imports[id]
      }
    },
    { filename: name }
  )
  const html = await require('vue/server-renderer').renderToString(
    vue.createSSRApp(exports.default)
  )
  return { html, access: actualAccess }
}
async function main() {
  for (const [input, expected] of [
    ['1', '1.00'],
    ['1.5', '1.50'],
    ['0.01', '0.01'],
    ['999', '999.00']
  ]) {
    check('normalize ' + input, () => assert.equal(api.normalizePhotoPrice(input), expected))
  }
  for (const input of ['0', '-1', '1000', '999.01', '1.001', ' 1.00', '1.00 ', '1e0', '01.00']) {
    check('reject invalid money ' + JSON.stringify(input), () =>
      assert.equal(api.normalizePhotoPrice(input), null)
    )
  }
  ok(fixture)
  const first = await api.getPhotoPrice()
  check('GET retains initial price and closed gate; no scope/query input', () => {
    assert.equal(first.pricePerSheet, '1.00')
    assert.equal(first.quoteEnabled, false)
    assert.equal(requests.at(-1).url, '/print/miniapp-demo/photo-price')
    assert.equal(requests.at(-1).params, undefined)
    assert.equal(requests.at(-1).data, undefined)
  })
  ok({ ...fixture, revision: '2', profileVersion: '2', pricePerSheet: '2.00' })
  await api.updatePhotoPrice({
    expectedRevision: '1',
    pricePerSheet: '2.00',
    storeId: 999,
    quoteEnabled: true
  })
  check('PUT only sends strict CAS price fields', () => {
    assert.equal(requests.at(-1).method, 'put')
    assert.equal(
      JSON.stringify(requests.at(-1).data),
      '{"expectedRevision":"1","pricePerSheet":"2.00"}'
    )
  })
  const before = requests.length
  await fails('noncanonical PUT blocked before transport', 'invalid-input', () =>
    api.updatePhotoPrice({ expectedRevision: '1', pricePerSheet: '2' })
  )
  check('invalid PUT never sends', () => assert.equal(requests.length, before))
  for (const [name, change] of [
    ['numeric revision', { revision: 1 }],
    ['missing gate', { quoteEnabled: undefined }],
    ['wrong currency', { currency: 'USD' }],
    ['noncanonical server money', { pricePerSheet: '1' }],
    ['mismatched version', { profileVersion: '2' }]
  ]) {
    ok({ ...fixture, ...change })
    await fails(name, 'invalid-response', api.getPhotoPrice)
  }
  for (const [code, kind] of [
    [43101, 'conflict'],
    [43102, 'unavailable'],
    [43100, 'invalid-input'],
    [403, 'forbidden']
  ]) {
    response = { code }
    await fails('business code ' + code, kind, api.getPhotoPrice)
  }
  transportError = { isAxiosError: true, response: { status: 409 } }
  await fails('HTTP 409 maps conflict', 'conflict', api.getPhotoPrice)
  transportError = { isAxiosError: true }
  await fails('network failure identified', 'network', api.getPhotoPrice)
  ok(fixture)
  let dispose
  const editorModule = load(base + 'editor.ts', {
    vue: {
      ...vue,
      onBeforeUnmount: (fn) => {
        dispose = fn
      }
    },
    './api': api
  })
  const scope = vue.effectScope()
  const access = {
    key: vue.ref('user1:store1:writer'),
    ready: vue.ref(true),
    canUpdate: vue.ref(true)
  }
  const editor = scope.run(() => editorModule.usePhotoPriceEditor(access))
  await flush()
  check('editor loads server baseline', () => assert.equal(editor.price.value.revision, '1'))
  editor.draft.value = '2'
  ok({ ...fixture, revision: '2', profileVersion: '2', pricePerSheet: '2.00' })
  await editor.save()
  check('save uses loaded revision and normalized money', () => {
    assert.equal(requests.at(-1).data.expectedRevision, '1')
    assert.equal(requests.at(-1).data.pricePerSheet, '2.00')
    assert.equal(editor.price.value.revision, '2')
    assert.ok(editor.notice.value)
  })
  editor.draft.value = '3.00'
  response = { code: 43101 }
  await editor.save()
  const afterConflict = requests.length
  await editor.save()
  check('conflict clears baseline and blocks blind retry', () => {
    assert.equal(editor.failure.value.kind, 'conflict')
    assert.equal(editor.price.value, null)
    assert.equal(requests.length, afterConflict)
  })
  ok(fixture)
  await editor.load()
  access.canUpdate.value = false
  editor.draft.value = '2.00'
  const afterRead = requests.length
  await editor.save()
  check('reader never submits', () => assert.equal(requests.length, afterRead))
  access.canUpdate.value = true
  transportError = { isAxiosError: true }
  await editor.save()
  const afterUnknown = requests.length
  await editor.save()
  check('unknown write result requires GET before retry', () => {
    assert.equal(editor.price.value, null)
    assert.equal(requests.length, afterUnknown)
  })
  ok(fixture)
  let resolveOld
  pending = () =>
    new Promise((resolve) => {
      resolveOld = resolve
    })
  const old = editor.load()
  access.ready.value = false
  access.key.value = 'logged-out'
  resolveOld(fixture)
  await old
  check('scope switch ignores old GET and clears draft', () => {
    assert.equal(editor.price.value, null)
    assert.equal(editor.draft.value, '1.00')
  })
  access.ready.value = true
  access.key.value = 'user2:store2:writer'
  await flush()
  editor.draft.value = '2.00'
  let resolveSave
  pending = () =>
    new Promise((resolve) => {
      resolveSave = resolve
    })
  const oldSave = editor.save()
  access.ready.value = false
  access.key.value = 'logged-out-again'
  resolveSave({ ...fixture, revision: '2', profileVersion: '2', pricePerSheet: '2.00' })
  await oldSave
  check('scope switch ignores old PUT response', () => {
    assert.equal(editor.price.value, null)
    assert.equal(editor.notice.value, '')
  })
  dispose()
  scope.stop()
  for (const name of [base + 'PhotoPriceCard.vue', 'src/print-admin/orders/DemoOrders.vue']) {
    const source = fs.readFileSync(name, 'utf8')
    const { descriptor, errors } = compiler.parse(source, { filename: name })
    assert.equal(errors.length, 0)
    const script = compiler.compileScript(descriptor, { id: 'photo-price' })
    const template = compiler.compileTemplate({
      source: descriptor.template.content,
      filename: name,
      id: 'photo-price',
      compilerOptions: { bindingMetadata: script.bindings }
    })
    check('Vue SFC compiles ' + name, () => assert.equal(template.errors.length, 0))
  }
  const hidden = await renderCard([])
  check('no-query user sees no card and editor access is not ready', () => {
    assert.ok(!hidden.html.includes('photo-price-card'))
    assert.equal(hidden.access.ready.value, false)
  })
  const reader = await renderCard(['print:photo-price:query'])
  check('reader sees price and disabled quote notice without form', () => {
    assert.ok(reader.html.includes('¥1.00'))
    assert.ok(reader.html.includes('已禁用照片新报价'))
    assert.ok(!reader.html.includes('<form'))
    assert.equal(reader.access.canUpdate.value, false)
  })
  const writer = await renderCard(['print:photo-price:query', 'print:photo-price:update'])
  check('authorized admin sees versioned price editor without quote toggle', () => {
    assert.ok(writer.html.includes('<form'))
    assert.ok(writer.html.includes('保存为新价格版本'))
    assert.ok(!writer.html.includes('type="checkbox"'))
    assert.equal(writer.access.canUpdate.value, true)
  })
  console.log(
    `PASS ${checks} isolated API/editor/SFC checks; real backend/browser integration NOT_RUN.`
  )
}
main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
