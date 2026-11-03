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
let toast
const home = load(script('home') + '\nexport { openTutorial }', {
  '@/components/BottomNav.vue': {}, '@/data/fixtures': {}
}, { navigateTo(options) { route = options.url } })
home.openTutorial()
assert.equal(route, '/pages/tutorial-list/index')

const list = load(script('tutorial-list') + '\nexport { articles, openArticle }', {}, {
  navigateTo(options) { route = options.url }, showToast(options) { toast = options.title }
})
assert.equal(list.articles.length, 7)
assert.equal(new Set(list.articles.map(article => article.id)).size, 7)
assert.deepEqual(list.articles.map(article => article.title), [
  '复印（图片去灰底）', '文档打印', '证件照制作', '图片拼版', '身份证打印', '电子发票打印', '照片纸打印'
])
list.openArticle('photo-paper', '照片纸打印')
assert.equal(route, '/pages/tutorial-detail/index?article=photo-paper')
list.openArticle('document-print', '文档打印')
assert.match(toast, /待继续只读观察/)

let onLoadHandler
const detail = load(script('tutorial-detail') + '\nexport { steps }', {
  '@dcloudio/uni-app': { onLoad(handler) { onLoadHandler = handler } }
}, { redirectTo(options) { route = options.url } })
assert.equal(detail.steps.length, 4)
onLoadHandler({ article: 'unexpected' })
assert.equal(route, '/pages/tutorial-list/index')
assert.match(read('src/pages/tutorial-detail/index.vue'), /旧金额不作为新系统价格/)
assert.doesNotMatch(read('src/pages/tutorial-detail/index.vue'), /1\.00元|uploadFile|request\s*\(/)
console.log('PASS_TUTORIAL_READONLY_ROUTES_AND_BOUNDARIES')
