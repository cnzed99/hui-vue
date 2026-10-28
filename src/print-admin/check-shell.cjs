/** Isolated component integration: real shell, Vue router and cache; no auth/API calls. */
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const Module = require('node:module')
const ts = require('typescript')
const { parse, compileScript } = require('vue/compiler-sfc')
const vue = require('vue')
const { createRouter, createMemoryHistory } = require('vue-router')
const { createRenderer, defineComponent, h, ref, reactive, nextTick, onBeforeUnmount } = vue
const checks = []
const storage = new Map()
global.sessionStorage = {
  getItem: key => storage.get(key) || null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: key => storage.delete(key)
}
const record = (name, run) => { run(); checks.push(name) }
const cache = new Map()
const context = reactive({ status: 'ready', context: { userId: 1 }, load() {}, clear() {} })
const user = reactive({ getUser: { id: 1, nickname: 'fixture' }, loginOut: async () => {} })
function load(filename) {
  filename = path.resolve(filename)
  if (cache.has(filename)) return cache.get(filename)
  let source = fs.readFileSync(filename, 'utf8')
  if (filename.endsWith('.vue')) {
    const { descriptor, errors } = parse(source, { filename })
    assert.deepEqual(errors, [])
    source = compileScript(descriptor, { id: 'shell-integration', inlineTemplate: true }).content
  }
  const code = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022
  }, reportDiagnostics: true })
  assert.equal(code.diagnostics.length, 0)
  const mod = new Module(filename, module)
  mod.filename = filename
  mod.paths = module.paths
  mod.require = (id) => {
    if (id === '@/store/modules/user') return { useUserStore: () => user }
    if (id === '@/store/modules/tagsView') return { useTagsViewStore: () => ({ delAllViews() {} }) }
    if (id === './contextStore') return { usePrintContextStore: () => context }
    if (id.startsWith('.')) return load(path.resolve(path.dirname(filename), id + '.ts'))
    return require(id)
  }
  mod._compile(code.outputText, filename)
  cache.set(filename, mod.exports)
  return mod.exports
}
const node = (type, text = '') => ({ type, text, props: {}, children: [], parent: null, scrollTop: 0 })
const host = {
  createElement: type => node(type), createText: text => node('#text', text), createComment: text => node('#comment', text),
  setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] },
  parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] || null,
  patchProp: (el, key, _old, value) => { el.props[key] = value },
  insert(el, parent, anchor = null) {
    if (el.parent) host.remove(el)
    const i = anchor ? parent.children.indexOf(anchor) : -1
    parent.children.splice(i < 0 ? parent.children.length : i, 0, el); el.parent = parent
  },
  remove(el) { if (el.parent) { const list = el.parent.children; list.splice(list.indexOf(el), 1); el.parent = null } }
}
const root = node('root')
const all = (el = root) => [el, ...el.children.flatMap(child => all(child))]
const text = el => el.text + el.children.map(text).join('')
const find = predicate => { const result = all().find(predicate); assert.ok(result, 'element missing'); return result }
const cls = (el, name) => String(el.props.class || '').split(' ').includes(name)
const moduleButton = title => find(el => el.type === 'button' && text(el) === title && 'aria-pressed' in el.props)
const tabButton = title => find(el => el.type === 'button' && cls(el, 'print-admin__tab-label') && text(el) === title)
const unmounted = {}
const page = name => defineComponent({ name, setup() {
  const value = ref('')
  onBeforeUnmount(() => { unmounted[name] = (unmounted[name] || 0) + 1 })
  return () => h('input', { 'data-page': name, value: value.value, onInput: event => { value.value = event.target.value } })
} })
const tick = async () => { await nextTick(); await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
async function main() {
  const Shell = load(path.join(__dirname, 'PrintAdminShell.vue')).default
  const makeRouter = () => createRouter({ history: createMemoryHistory(), routes: [{ path: '/print', component: Shell, children: [
    { path: 'workbench', component: page('Workbench'), meta: { title: '工作台' } },
    { path: 'store/profile', component: page('Profile'), meta: { title: '门店详情' } },
    { path: 'store/password', component: page('Password'), meta: { title: '密码管理' } },
    { path: 'library/settings', component: page('Library'), meta: { title: '文库设置' } },
    { path: 'demo-orders', component: page('Orders'), meta: { title: '演示订单' } }
  ] }, { path: '/login', component: defineComponent({ render: () => h('div', 'Login') }) }] })
  let router = makeRouter()
  await router.push('/print/store/profile?from=deep#details')
  let app = createRenderer(host).createApp({ render: () => h(require('vue-router').RouterView) })
  app.use(router); app.mount(root); await tick()
  record('deep-link module and title', () => {
    assert.equal(moduleButton('门店').props['aria-pressed'], true)
    assert.equal(tabButton('门店详情').props['aria-current'], 'page')
  })
  const profile = () => find(el => el.props['data-page'] === 'Profile')
  profile().props.onInput({ target: { value: 'unsaved draft' } })
  find(el => cls(el, 'print-admin__content')).scrollTop = 137
  await router.push('/print/library/settings?section=upload'); await tick()
  find(el => el.props['data-page'] === 'Library').props.onInput({ target: { value: 'library draft' } })
  await tabButton('门店详情').props.onClick(); await tick()
  record('switch preserves query hash draft and scroll', () => {
    assert.equal(router.currentRoute.value.fullPath, '/print/store/profile?from=deep#details')
    assert.equal(profile().props.value, 'unsaved draft')
    assert.equal(find(el => cls(el, 'print-admin__content')).scrollTop, 137)
    assert.equal(unmounted.Profile || 0, 0)
  })
  await find(el => el.props['aria-label'] === '刷新当前页面').props.onClick(); await tick()
  record('refresh resets only current page and scroll', () => {
    assert.equal(profile().props.value, '')
    assert.equal(find(el => cls(el, 'print-admin__content')).scrollTop, 0)
    assert.equal(unmounted.Profile, 1)
    assert.equal(unmounted.Library || 0, 0)
  })
  await tabButton('文库设置').props.onClick(); await tick()
  assert.equal(find(el => el.props['data-page'] === 'Library').props.value, 'library draft')
  await tabButton('门店详情').props.onClick(); await tick()
  await find(el => el.props['aria-label'] === '关闭文库设置标签').props.onClick(); await tick()
  record('inactive close keeps current page and evicts cache', () => {
    assert.equal(router.currentRoute.value.path, '/print/store/profile')
    assert.equal(unmounted.Library, 1)
  })
  moduleButton('终端').props.onClick(); await tick()
  await tabButton('门店详情').props.onClick(); await tick()
  record('same-page tab restores primary module', () => assert.equal(moduleButton('门店').props['aria-pressed'], true))
  await find(el => el.props['aria-label'] === '关闭门店详情标签').props.onClick(); await tick()
  record('active close returns to workbench and unmounts page', () => {
    assert.equal(router.currentRoute.value.path, '/print/workbench')
    assert.equal(unmounted.Profile, 2)
    assert.equal(all().filter(el => cls(el, 'print-admin__tab')).length, 1)
  })
  await router.push('/print/store/profile'); await tick()
  record('reopen starts with fresh page state', () => assert.equal(profile().props.value, ''))
  await router.push('/print/demo-orders'); await tick()
  record('demo route selected module follows current route', () => assert.equal(moduleButton('订单').props['aria-pressed'], true))
  router.back(); await tick()
  record('browser back restores active tab and primary module', () => {
    assert.equal(router.currentRoute.value.path, '/print/store/profile')
    assert.equal(tabButton('门店详情').props['aria-current'], 'page')
    assert.equal(moduleButton('门店').props['aria-pressed'], true)
  })
  router.forward(); await tick()
  assert.equal(moduleButton('订单').props['aria-pressed'], true)
  await router.push('/print/store/password'); await tick()
  const password = () => find(el => el.props['data-page'] === 'Password')
  password().props.onInput({ target: { value: 'fixture-password-input' } })
  await tabButton('门店详情').props.onClick(); await tick()
  await tabButton('密码管理').props.onClick(); await tick()
  record('password page unmounts and clears on tab return and refresh', () => assert.equal(password().props.value, ''))
  password().props.onInput({ target: { value: 'fixture-password-input' } })
  await find(el => el.props['aria-label'] === '刷新当前页面').props.onClick(); await tick()
  assert.equal(password().props.value, '')
  const currentPath = router.currentRoute.value.fullPath
  const saved = storage.get('print-admin:tabs:v1:1')
  assert.deepEqual(JSON.parse(saved), ['/print/workbench', '/print/store/profile', '/print/store/password'])
  app.unmount()
  router = makeRouter()
  await router.push(currentPath)
  app = createRenderer(host).createApp({ render: () => h(require('vue-router').RouterView) })
  app.use(router); app.mount(root); await tick()
  record('browser reload restores open static tabs and URL-selected page without inputs', () => {
    assert.equal(tabButton('密码管理').props['aria-current'], 'page')
    assert.equal(all().filter(el => cls(el, 'print-admin__tab')).length, 3)
    assert.equal(password().props.value, '')
    assert.equal(moduleButton('门店').props['aria-pressed'], true)
  })
  await find(el => el.type === 'button' && text(el) === '退出').props.onClick(); await tick()
  record('logout clears current user tab storage', () => assert.equal(storage.has('print-admin:tabs:v1:1'), false))
  app.unmount()
  console.log(JSON.stringify({ result: 'PASS', scope: 'isolated-real-shell-and-router-integration', checks,
    liveAuthentication: false, liveApi: false, visualAcceptance: false }, null, 2))
}
main().catch(error => { console.error(error); process.exitCode = 1 })
