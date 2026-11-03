const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
const root = path.join(__dirname, '..')
const read = file => fs.readFileSync(path.join(root, file), 'utf8')

function load(source, imports = {}, uni = {}) {
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
  })
  const exports = {}
  new Function('exports', 'require', 'uni', outputText)(exports, name => {
    assert.ok(Object.hasOwn(imports, name), 'Unexpected import: ' + name)
    return imports[name]
  }, uni)
  return exports
}

const sizes = load(read('src/domain/idPhotoSizes.ts'))
const script = name => read('src/pages/' + name + '/index.vue').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
const domainImport = { '@/domain/idPhotoSizes': sizes }
let navigation
const list = load(script('id-photo-sizes') + '\nexport { chooseSize }', domainImport, {
  navigateTo(options) { navigation = options.url }
})
assert.equal(new Set(sizes.sizes.map(size => size.id)).size, 8)
for (const size of sizes.sizes) {
  list.chooseSize(size.id)
  assert.equal(navigation, '/pages/id-photo-upload/index?size=' + size.id)
  assert.equal(sizes.resolveSize(size.id), size)
}

function openPhoto(query) {
  let picker
  let toast
  let redirect
  const page = load(script('id-photo-upload') + '\nexport { choosePhoto, photo, next }', {
    ...domainImport,
    vue: { ref: value => ({ value }) },
    '@dcloudio/uni-app': { onLoad: run => run(query) }
  }, {
    chooseImage: options => { picker = options },
    showToast: options => { toast = options.title },
    redirectTo: options => { redirect = options.url }
  })
  return { page, picker: () => picker, toast: () => toast, redirect }
}

for (const size of [undefined, 'unlisted', '../home', ['large-one']]) {
  assert.equal(openPhoto({ size }).redirect, '/pages/id-photo-sizes/index')
}
const photo = openPhoto({ size: 'large-one' })
photo.page.choosePhoto()
photo.picker().fail({ errMsg: 'chooseImage:fail cancel' })
assert.equal(photo.toast(), undefined)
assert.equal(photo.page.photo.value, '')
photo.picker().success({ tempFilePaths: ['wxfile://photo'] })
assert.equal(photo.page.photo.value, 'wxfile://photo')
photo.page.next()
assert.match(photo.toast(), /排版和报价待接入/)

function openArchive(wechat) {
  let picker
  let toast
  let modal
  const source = script('home').replace(
    /\/\/ #(ifdef|ifndef) MP-WEIXIN\n([\s\S]*?)\/\/ #endif/g,
    (_, mode, body) => (mode === 'ifdef') === wechat ? body : ''
  )
  const page = load(source + '\nexport { openPrintEntry }', {
    '@/components/BottomNav.vue': {},
    '@/data/fixtures': {}
  }, {
    chooseMessageFile: options => { picker = options },
    showModal: options => { modal = options },
    showToast: options => { toast = options.title }
  })
  page.openPrintEntry('压缩包打印')
  return { picker, modal: () => modal, toast: () => toast }
}

const wechat = openArchive(true)
assert.equal(wechat.picker.count, 1)
assert.equal(wechat.picker.type, 'file')
assert.equal(wechat.picker.extension, undefined)
wechat.picker.fail({ errMsg: 'chooseMessageFile:fail cancel' })
assert.equal(wechat.toast(), undefined)
assert.equal(wechat.modal(), undefined)
wechat.picker.success({ tempFiles: [{ name: 'fixture.zip', size: 123 }] })
assert.match(wechat.modal().content, /fixture\.zip（123 字节）\n尚未上传或解压/)
const h5 = openArchive(false)
assert.equal(h5.picker, undefined)
assert.match(h5.toast(), /微信小程序/)
console.log('PASS_ORIGINAL_BATCH1_ROUTE_AND_LOCAL_PICKER_MOCKS')
