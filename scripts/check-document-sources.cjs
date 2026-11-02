const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const ts = require('typescript')
const root = path.resolve(__dirname, '..')
function compile(source, requireModule) {
  const output = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS }, reportDiagnostics: true })
  assert.equal((output.diagnostics || []).filter(x => x.category === ts.DiagnosticCategory.Error).length, 0)
  const exports = {}
  new Function('exports', 'require', output.outputText)(exports, requireModule)
  return exports
}
const source = compile(fs.readFileSync(path.join(root, 'src/domain/documentSource.ts'), 'utf8'))
for (const value of [undefined, null, '', 'unknown', '../invoice-file', ['wechat-chat']]) assert.equal(source.resolveDocumentSource(value), 'local-document')
for (const value of ['wechat-chat', 'invoice-file']) assert.equal(source.documentUploadUrl(value), '/pages/document-upload/index?source=' + value)
assert.equal(source.validateSelectedPdf({ name: 'invoice.PDF', size: 2 * 1024 * 1024, path: '/fixture.pdf' }, 2 * 1024 * 1024), '')
for (const file of [{ name: 'file.docx', size: 10, path: '/file' }, { name: 'file.pdf', size: 2 * 1024 * 1024 + 1, path: '/file' }, { name: 'file.pdf', size: 0, path: '/file' }, { name: 'file.pdf', size: 10 }]) assert.notEqual(source.validateSelectedPdf(file, 2 * 1024 * 1024), '')
function checkEntries() {
  let navigation = '', toast = 0
  global.uni = { navigateTo: options => { navigation = options.url }, showToast: () => { toast++ } }
  function page(file, entry) {
    const raw = fs.readFileSync(path.join(root, 'src/pages', file, 'index.vue'), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
    return compile(raw + '\nexport { ' + entry + ' }', name => {
      if (name === 'vue') return { ref: value => ({ value }), computed: getter => ({ get value() { return getter() } }), onMounted: () => {} }
      if (name === '@/domain/documentSource') return source
      if (name === '@/api/catalog') return { localCatalog: { methods: [] } }
      throw new Error('unexpected import ' + name)
    })[entry]
  }
  const method = page('document-method', 'openMethod'), invoice = page('invoice-print', 'openSource')
  method({ id: 'wechat-chat' }); assert.equal(navigation, source.documentUploadUrl('wechat-chat'))
  method({ id: 'local-document' }); assert.equal(navigation, source.documentUploadUrl('local-document'))
  invoice('invoice-file'); assert.equal(navigation, source.documentUploadUrl('invoice-file'))
  navigation = ''; invoice('wallet'); assert.equal(navigation, ''); assert.equal(toast, 1)
  delete global.uni
}
checkEntries()
function platformSource(text, platform) {
  const output = [], include = []
  for (const line of text.split('\n')) {
    const directive = line.match(/\/\/ #(ifdef|ifndef|endif)\s*([\w-]*)/)
    if (directive) {
      if (directive[1] === 'endif') include.pop()
      else include.push(directive[1] === 'ifdef' ? directive[2] === platform : directive[2] !== platform)
    } else if (include.every(Boolean)) output.push(line)
  }
  return output.join('\n')
}
async function checkUpload(platform) {
  let picker, uploads = 0, drafts = 0, navigation = '', toast = 0
  const uni = {
    chooseFile: options => { assert.equal(platform, 'H5'); picker = options },
    chooseMessageFile: options => { assert.equal(platform, 'MP-WEIXIN'); picker = options },
    showToast: () => { toast++ }, navigateTo: options => { navigation = options.url }, navigateBack() {}
  }
  const raw = fs.readFileSync(path.join(root, 'src/pages/document-upload/index.vue'), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const compiled = compile(platformSource(raw, platform) + '\nexport { chooseFile, uploadSelected, continueToSettings, state, source, failureText }', name => {
    if (name === 'vue') return { ref: value => ({ value }), computed: getter => ({ get value() { return getter() } }), onMounted: () => {} }
    if (name === '@dcloudio/uni-app') return { onLoad: callback => callback({ source: 'invoice-file' }) }
    if (name === '@/api/catalog') return { localCatalog: { demoLimits: { maxFileBytes: 2 * 1024 * 1024 } } }
    if (name === '@/api/demoSession') return { ensureDemoSession: async () => ({ fixture: true }) }
    if (name === '@/api/demoFiles') return { uploadDemoPdf: async () => { uploads++; return { id: 'fixture' } }, waitForDemoFile: async () => ({ id: 'fixture', status: 'READY', pageCount: 2 }) }
    if (name === '@/domain/printDraft') return { createDraft: (...args) => args, saveDraft: () => { drafts++ } }
    if (name === '@/domain/documentSource') return source
    throw new Error('unexpected import ' + name)
  })
  global.uni = uni
  assert.equal(compiled.source.value, 'invoice-file')
  compiled.chooseFile()
  assert.deepEqual(picker.extension, ['pdf'])
  picker.fail({ errMsg: 'chooseFile:fail cancel' })
  await compiled.uploadSelected()
  assert.equal(uploads, 0); assert.equal(compiled.state.value, 'empty'); assert.equal(toast, 0)
  for (const file of [{ name: 'file.docx', size: 10, path: '/file' }, { name: 'file.pdf', size: 2097153, path: '/file' }]) {
    picker.success({ tempFiles: [file] }); await compiled.uploadSelected(); assert.equal(uploads, 0)
  }
  picker.success({ tempFiles: [{ name: 'invoice.pdf', size: 100, path: '/fixture.pdf' }] })
  const first = compiled.uploadSelected(), repeated = compiled.uploadSelected()
  await Promise.all([first, repeated]); assert.equal(uploads, 1); assert.equal(compiled.state.value, 'ready')
  compiled.continueToSettings(); assert.equal(drafts, 1); assert.equal(navigation, '/pages/document-settings/index')
  delete global.uni
}
Promise.resolve().then(() => checkUpload('H5')).then(() => checkUpload('MP-WEIXIN')).then(() => console.log(JSON.stringify({ status: 'PASS_DOCUMENT_SOURCE_ROUTING', sourceFallback: 'PASS', pdfSizeValidation: 'PASS', h5AndWeixinPickerMocks: 'PASS', cancelNoUpload: 'PASS', invalidNoUpload: 'PASS', parsedPdfToSettings: 'PASS', realWechatPlatform: 'NOT_RUN' }))).catch(error => { console.error(error); process.exitCode = 1 })
