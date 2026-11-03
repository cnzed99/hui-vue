const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')

const root = path.join(__dirname, '..')
const read = file => fs.readFileSync(path.join(root, file), 'utf8')
const script = name => read(`src/pages/${name}/index.vue`).match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
function load(source, imports = {}, uni = {}) {
  const { outputText } = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } })
  const exports = {}
  new Function('exports', 'require', 'uni', outputText)(exports, name => imports[name], uni)
  return exports
}

let route
const home = load(script('home') + '\nexport { openTutorial }', {
  '@/components/BottomNav.vue': {}, '@/data/fixtures': {}
}, { navigateTo(options) { route = options.url } })
home.openTutorial()
assert.equal(route, '/pages/tutorial-list/index')

const list = load(script('tutorial-list') + '\nexport { articles, openArticle }', {}, {
  navigateTo(options) { route = options.url }
})
assert.equal(list.articles.length, 7)
assert.equal(new Set(list.articles.map(article => article.id)).size, 7)
assert.deepEqual(list.articles.map(article => article.title), [
  '复印（图片去灰底）', '文档打印', '证件照制作', '图片拼版', '身份证打印', '电子发票打印', '照片纸打印'
])
list.openArticle('photo-paper')
assert.equal(route, '/pages/tutorial-detail/index?article=photo-paper')
list.openArticle('document-print')
assert.equal(route, '/pages/tutorial-detail/index?article=document-print')

let onLoadHandler
const detail = load(script('tutorial-detail') + '\nexport { tutorials, selectedArticle }', {
  '@dcloudio/uni-app': { onLoad(handler) { onLoadHandler = handler } },
  vue: { ref(value) { return { value } } }
}, { redirectTo(options) { route = options.url } })
assert.equal(Object.keys(detail.tutorials).length, 7)
assert.equal(detail.tutorials.invoice.steps.length, 5)
assert.equal(detail.tutorials['photo-paper'].steps.length, 4)
assert.equal(Object.values(detail.tutorials).filter(article => article.kind === 'external-video').length, 5)
onLoadHandler({ article: 'invoice' })
assert.equal(detail.selectedArticle.value.id, 'invoice')
onLoadHandler({ article: 'unexpected' })
assert.equal(route, '/pages/tutorial-list/index')
assert.match(read('src/pages/tutorial-detail/index.vue'), /旧金额不作为新系统价格/)
assert.match(read('src/pages/tutorial-detail/index.vue'), /不复用原公众号链接或视频/)
assert.doesNotMatch(read('src/pages/tutorial-detail/index.vue'), /https?:\/\/|1\.00元|uploadFile|request\s*\(/)
console.log('PASS_TUTORIAL_READONLY_ROUTES_AND_BOUNDARIES')
