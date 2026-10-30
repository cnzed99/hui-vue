// Source/SFC and in-memory interaction checks only; no HTTP or database writes.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vue = require('vue')
const ts = require('typescript')
const { parse, compileScript, compileTemplate, compileStyle } = require('vue/compiler-sfc')
const read = (name) => fs.readFileSync(path.join(__dirname, name), 'utf8')
const scripts = {}
for (const name of ['AdvertisingPage', 'SingleImageDraftEditor']) {
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
assert.ok(parent.includes('<section v-else class="advertising-page">'))
assert.ok(parent.includes('if (ticket !== generation) return'))
assert.ok(parent.includes('if (ticket === generation) apply(data)'))
assert.ok(parent.includes('expectedVersion: draft.value.version, customEnabled: false'))
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
  console.log('PASS two Vue SFC/script/template/style compilations; P52/P53 specs; local-only preview, stale decode, reset/revoke, readonly/save locks; existing API/version/generation contract')
}
main().catch((error) => { console.error(error); process.exitCode = 1 }).finally(() => {
  scope.stop(); URL.createObjectURL = originalCreate; URL.revokeObjectURL = originalRevoke; global.Image = originalImage
})
