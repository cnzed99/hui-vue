// Source/SFC and in-memory interaction checks only; no HTTP or database writes.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vue = require('vue')
const ts = require('typescript')
const { parse, compileScript, compileTemplate, compileStyle } = require('vue/compiler-sfc')
const read = (name) => fs.readFileSync(path.join(__dirname, name), 'utf8')
const scripts = {}
for (const name of ['AdvertisingPage', 'SingleImageDraftEditor', 'MultiImageDraftEditor']) {
  const filename = path.join(__dirname, `${name}.vue`)
  const { descriptor, errors } = parse(read(`${name}.vue`), { filename })
  assert.deepEqual(errors, [])
  const script = compileScript(descriptor, { id: name })
  scripts[name] = script.content
  const template = compileTemplate({ source: descriptor.template.content, filename, id: name, compilerOptions: { bindingMetadata: script.bindings } })
  assert.deepEqual(template.errors, [])
  for (const style of descriptor.styles) assert.deepEqual(compileStyle({ source: style.content, filename, id: `data-v-${name}`, scoped: style.scoped }).errors, [])
}
const compileModule = (source, dependencies = {}) => {
  const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } })
  const module = { exports: {} }
  new Function('require', 'module', 'exports', output.outputText)((name) => dependencies[name] || require(name), module, module.exports)
  return module.exports
}
const positions = compileModule(read('positions.ts'))
for (const [key, size] of [['COMPUTER_DESKTOP', '1920×1080像素'], ['MINI_SHARE', '627×486像素']]) {
  assert.equal(positions.findPosition(key).max, 1)
  assert.equal(positions.findPosition(key).size, size)
  assert.deepEqual(positions.slotsFor(key), ['IMAGE_1'])
}
const parent = read('AdvertisingPage.vue')
assert.ok(parent.includes('v-if="singleImage" :key="identity"'))
assert.ok(parent.includes('<MultiImageDraftEditor'))
assert.ok(parent.includes('v-else :key="identity"'))
assert.ok(parent.includes('@preview-change="hasLocalPreview = $event"'))
assert.ok(parent.includes('locked.value || hasLocalPreview.value || !draft.value'))
assert.ok(parent.includes('if (ticket !== generation) return'))
assert.ok(parent.includes('if (ticket === generation) apply(data)'))
assert.ok(parent.includes('expectedVersion: draft.value.version, customEnabled: false'))
for (const [sourceId, key, size] of [
  ['P46', 'COMPUTER_TERMINAL', '274×540像素'], ['P47', 'TOUCH_TERMINAL', '720×240像素'],
  ['P48', 'FULLSCREEN_TERMINAL', '755×585像素'], ['P49', 'PORTRAIT_TERMINAL', '1080×1241像素'],
  ['P50', 'CLOUD_BOX', '1920×1080像素'], ['P51', 'ORDER_PAGE', '正反面各一张；宽高待确认，图片大小不超过1M']
]) {
  const info = positions.findPosition(key)
  assert.equal(info.sourceId, sourceId)
  assert.equal(info.size, size)
  assert.equal(info.max, key === 'ORDER_PAGE' ? 2 : 5)
  assert.deepEqual(positions.slotsFor(key), key === 'ORDER_PAGE' ? ['FRONT', 'BACK'] : ['IMAGE_1', 'IMAGE_2', 'IMAGE_3', 'IMAGE_4', 'IMAGE_5'])
}
const multiSource = read('MultiImageDraftEditor.vue')
assert.ok(!/getAdDraft|saveAdDraft|fetch\(|request\.|FormData|FileReader|v-html/.test(multiSource))
assert.ok(multiSource.includes('accept="image/*"'))
assert.ok(multiSource.includes("position === 'COMPUTER_TERMINAL'"))
assert.ok(multiSource.includes('HTML源码'))
assert.ok(multiSource.includes('!editable || hasLocalPreview'))
const child = read('SingleImageDraftEditor.vue')
assert.ok(!/getAdDraft|saveAdDraft|fetch\(|request\.|FormData|FileReader|v-html/.test(child))
const originalCreate = URL.createObjectURL
const originalRevoke = URL.revokeObjectURL
const originalImage = global.Image
const images = [], revoked = [], cleanups = [], events = []
let nextUrl = 0
URL.createObjectURL = () => `blob:local-${++nextUrl}`
URL.revokeObjectURL = (url) => revoked.push(url)
global.Image = class { constructor() { images.push(this); this.naturalWidth = 1920; this.naturalHeight = 1080 } }
const scope = vue.effectScope()
async function main() {
  const component = compileModule(scripts.SingleImageDraftEditor, { vue: { ...vue, onBeforeUnmount: (callback) => cleanups.push(callback) }, './positions': positions }).default
  const props = vue.reactive({ position: 'COMPUTER_DESKTOP', modelValue: '', draft: { configured: false, assets: [] }, busy: false, locked: false, message: '', canQuery: true, canSave: true, contextReady: true })
  const state = scope.run(() => component.setup(props, { expose() {}, emit: (...event) => events.push(event) }))
  const select = (name) => state.selectFile({ target: { files: [{ name }], value: 'file' } })
  select('desktop.png')
  assert.equal(state.localUrl.value, 'blob:local-1')
  state.save()
  assert.deepEqual(events, []) // Local file is never persisted or emitted as an asset.
  images[0].onload()
  assert.equal(state.localDimensions.value, '1920 × 1080 像素')
  select('replacement.png')
  images[0].onerror() // A stale decode cannot clear a newer preview.
  assert.equal(state.localUrl.value, 'blob:local-2')
  images[1].onerror()
  assert.equal(state.localUrl.value, '')
  assert.ok(state.fileMessage.value.includes('无法预览'))
  select('draft-change.png')
  props.draft = { configured: false, assets: [] }
  await vue.nextTick()
  images[2].onload()
  assert.equal(state.localUrl.value, '')
  assert.equal(state.localDimensions.value, '')
  props.modelValue = 'https://assets.example/image.png'
  assert.equal(state.persistedUrl.value, props.modelValue)
  props.modelValue = 'https://name:secret@assets.example/image.png'
  assert.equal(state.persistedUrl.value, '')
  props.modelValue = 'javascript:alert(1)'
  assert.equal(state.persistedUrl.value, '')
  for (const key of ['busy', 'locked']) {
    props[key] = true; state.save(); select('blocked.png'); props[key] = false
  }
  for (const key of ['canSave', 'canQuery', 'contextReady']) {
    props[key] = false; state.save(); select('blocked.png'); props[key] = true
  }
  assert.deepEqual(events, [])
  state.save()
  assert.deepEqual(events, [['save']])
  select('unmount.png')
  cleanups.forEach((callback) => callback())
  assert.equal(state.localUrl.value, '')
  assert.ok(revoked.includes('blob:local-1') && revoked.includes('blob:local-4'))
  const multiEvents = []
  const multi = compileModule(scripts.MultiImageDraftEditor, { vue: { ...vue, onBeforeUnmount: (callback) => cleanups.push(callback) }, './positions': positions }).default
  const multiProps = vue.reactive({ position: 'ORDER_PAGE', urls: {}, successText: '', draft: { configured: false, assets: [] }, busy: false, locked: false, message: '', canQuery: true, canSave: true, contextReady: true })
  const editor = scope.run(() => multi.setup(multiProps, { expose() {}, emit: (...event) => multiEvents.push(event) }))
  const selectMulti = (slot, name = 'picture.png', type = 'image/png', size = 100) => editor.selectFile(slot, { target: { files: [{ name, type, size }], value: 'file' } })
  const saveCount = () => multiEvents.filter(([event]) => event === 'save').length
  selectMulti('FRONT', 'bad.txt', 'text/plain')
  assert.equal(editor.hasLocalPreview.value, false)
  assert.ok(editor.fileMessages.FRONT.includes('请选择图片'))
  selectMulti('FRONT', 'too-large.png', 'image/png', 1024 * 1024 + 1)
  assert.equal(editor.hasLocalPreview.value, false)
  assert.ok(editor.fileMessages.FRONT.includes('不得超过1MB'))
  const firstImage = images.length
  selectMulti('FRONT', 'front.png', 'image/png', 1024 * 1024)
  const firstUrl = editor.previews.FRONT.url
  selectMulti('BACK')
  const backUrl = editor.previews.BACK.url
  editor.save()
  assert.equal(saveCount(), 0)
  assert.deepEqual(multiEvents, [['preview-change', true]])
  editor.clearLocal('FRONT')
  assert.ok(revoked.includes(firstUrl))
  assert.equal(editor.hasLocalPreview.value, true)
  selectMulti('FRONT', 'replacement.png')
  const replacementUrl = editor.previews.FRONT.url
  images[firstImage].onerror()
  images[firstImage].onload()
  assert.equal(editor.previews.FRONT.url, replacementUrl)
  images[images.length - 1].onload()
  assert.equal(editor.previews.FRONT.dimensions, '1920 × 1080 像素')
  editor.clearAll()
  assert.equal(editor.hasLocalPreview.value, false)
  assert.ok(revoked.includes(backUrl) && revoked.includes(replacementUrl))
  assert.deepEqual(multiEvents.at(-1), ['preview-change', false])
  selectMulti('FRONT')
  const resetUrl = editor.previews.FRONT.url
  const oldDecode = images.at(-1)
  multiProps.draft = { configured: false, assets: [] }
  assert.equal(editor.hasLocalPreview.value, false) // synchronous reset
  assert.ok(revoked.includes(resetUrl))
  oldDecode.onerror(); oldDecode.onload()
  assert.equal(editor.hasLocalPreview.value, false)
  selectMulti('BACK')
  const routeUrl = editor.previews.BACK.url
  multiProps.position = 'CLOUD_BOX'
  assert.equal(editor.hasLocalPreview.value, false)
  assert.ok(revoked.includes(routeUrl))
  selectMulti('IMAGE_6')
  assert.equal(editor.hasLocalPreview.value, false)
  multiProps.urls.IMAGE_1 = 'https://assets.example/persisted.png'
  assert.equal(editor.persistedUrl('IMAGE_1'), multiProps.urls.IMAGE_1)
  for (const url of ['blob:local-only', 'http://assets.example/plain.png', 'https://name:password@assets.example/a.png', 'https://assets.example/a.png#fragment']) {
    multiProps.urls.IMAGE_1 = url
    assert.equal(editor.persistedUrl('IMAGE_1'), '')
  }
  for (const [key, blocked] of [['busy', true], ['locked', true], ['canSave', false], ['canQuery', false], ['contextReady', false]]) {
    const previous = multiProps[key]
    multiProps[key] = blocked; editor.save(); selectMulti('IMAGE_1'); multiProps[key] = previous
    assert.equal(editor.hasLocalPreview.value, false)
  }
  assert.equal(saveCount(), 0)
  selectMulti('IMAGE_1')
  images.at(-1).onerror()
  assert.equal(editor.hasLocalPreview.value, false)
  assert.ok(editor.fileMessages.IMAGE_1.includes('无法预览'))
  editor.save()
  assert.equal(saveCount(), 1)
  selectMulti('IMAGE_5')
  const unmountUrl = editor.previews.IMAGE_5.url
  cleanups.forEach((callback) => callback())
  assert.equal(editor.hasLocalPreview.value, false)
  assert.ok(revoked.includes(unmountUrl))

  // Exercise the actual parent save entry, not just child button conditions.
  const parentScope = vue.effectScope()
  const user = vue.reactive({ permissions: new Set(['print:advertising:query', 'print:advertising:update']), getUser: { id: 'local-test' } })
  const context = vue.reactive({ status: 'ready', context: { tenantId: 'tenant-test', storeId: 'store-test' } })
  const makeDraft = (position = 'CLOUD_BOX') => ({ position, version: '1', configured: true, customEnabled: false, successMessageHtml: '', assets: [], deliveryStatus: 'NOT_CONNECTED' })
  let writes = 0, failure = ''
  const parentComponent = compileModule(scripts.AdvertisingPage, {
    vue: { ...vue, onBeforeUnmount() {} }, './positions': positions,
    '@/store/modules/user': { useUserStore: () => user }, '../contextStore': { usePrintContextStore: () => context },
    './SingleImageDraftEditor.vue': {}, './MultiImageDraftEditor.vue': {},
    './api': { getAdDraft: async (position) => makeDraft(position), saveAdDraft: async (position, command) => {
      writes++; assert.equal(command.customEnabled, false); assert.equal(command.expectedVersion, '1')
      if (failure) throw failure
      return makeDraft(position)
    }, adFailure: (error) => error }
  }).default
  const parentProps = vue.reactive({ position: 'CLOUD_BOX' })
  const parentState = parentScope.run(() => parentComponent.setup(parentProps, { expose() {} }))
  await vue.nextTick()
  await Promise.resolve()
  parentState.hasLocalPreview.value = true
  await parentState.save()
  assert.equal(writes, 0)
  parentState.hasLocalPreview.value = false
  await parentState.save()
  assert.equal(writes, 1)
  for (const kind of ['conflict', 'service', 'forbidden']) {
    failure = kind
    await parentState.save()
    assert.equal(parentState.locked.value, true)
    const before = writes
    await parentState.save()
    assert.equal(writes, before)
    await parentState.load()
    assert.equal(parentState.locked.value, false)
  }
  failure = ''
  parentState.urls.IMAGE_1 = 'blob:local-only'
  const beforeInvalidUrl = writes
  await parentState.save()
  assert.equal(writes, beforeInvalidUrl)
  parentState.apply({ ...makeDraft(), assets: [{ slot: 'IMAGE_1', url: 'https://assets.example/old.png' }] })
  parentState.urls.IMAGE_1 = ''
  await parentState.save()
  assert.equal(writes, beforeInvalidUrl)
  parentScope.stop()
  console.log('PASS three Vue SFC/script/template/style compilations; P46-P53 specs/slots; image MIME and P51 1MB limit; local-only multi-preview parent/save guard; stale decode, undo/draft/route/unmount URL revoke; readonly/busy/conflict/uncertain-result locks; HTTPS/no-delete/version/customEnabled contract')
}
main().catch((error) => { console.error(error); process.exitCode = 1 }).finally(() => {
  scope.stop(); URL.createObjectURL = originalCreate; URL.revokeObjectURL = originalRevoke; global.Image = originalImage
})
