const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')

const root = path.join(__dirname, '..')
const source = fs.readFileSync(path.join(root, 'src/pages/photo-upload/index.vue'), 'utf8')
  .match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]

function ref(value) { return { value } }
function computed(getter) { return { get value() { return getter() } } }

let picker
let toast
const { outputText } = ts.transpileModule(
  source + '\nexport { MAX_PHOTOS, photos, remaining, addPhoto, submit }',
  { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } }
)
const exportsObject = {}
new Function('exports', 'require', 'uni', outputText)(exportsObject, name => {
  assert.equal(name, 'vue')
  return { computed, ref }
}, {
  chooseImage(options) { picker = options },
  showToast(options) { toast = options.title }
})

assert.equal(exportsObject.MAX_PHOTOS, 30)
assert.equal(exportsObject.photos.value.length, 0)
exportsObject.submit()
assert.equal(toast, '请先添加照片')

toast = undefined
exportsObject.addPhoto()
assert.equal(picker.count, 30)
picker.fail({ errMsg: 'chooseImage:fail cancel' })
assert.equal(toast, undefined)
picker.success({ tempFilePaths: ['wxfile://one', '', null] })
assert.deepEqual(exportsObject.photos.value, ['wxfile://one'])
assert.equal(exportsObject.remaining.value, 29)

exportsObject.addPhoto()
assert.equal(picker.count, 29)
picker.success({ tempFilePaths: Array.from({ length: 40 }, (_, index) => `wxfile://${index + 2}`) })
assert.equal(exportsObject.photos.value.length, 30)
assert.equal(exportsObject.remaining.value, 0)

picker = undefined
exportsObject.addPhoto()
assert.equal(picker, undefined)
assert.equal(toast, '最多选择30张照片')
exportsObject.submit()
assert.match(toast, /仅在本地预览/)

assert.doesNotMatch(source, /uploadFile|request\s*\(/)
console.log('PASS_PHOTO_LOCAL_PICKER_MOCKS')
