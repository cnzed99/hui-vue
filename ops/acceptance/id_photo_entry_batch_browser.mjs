import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { readFile, writeFile } from 'node:fs/promises'
import { extname, join, resolve } from 'node:path'

const [dir, reportPath, sourceCommit] = process.argv.slice(2)
assert.ok(dir && reportPath && sourceCommit, 'H5_DIR REPORT_PATH COMMIT required')
const dist = resolve(dir)
const runtime = '/home/ubuntu/print-system/runtime/browser-tools'
Object.assign(process.env, {
  PLAYWRIGHT_BROWSERS_PATH: join(runtime, 'browsers'),
  LD_LIBRARY_PATH: join(runtime, 'sysroot/usr/lib/x86_64-linux-gnu'),
  FONTCONFIG_FILE: join(runtime, 'fonts.conf'),
  XDG_CACHE_HOME: join(runtime, 'cache')
})
const { chromium } = createRequire(join(runtime, 'package.json'))('playwright')
const checks = []
const requests = []
const endpoint = 'http://127.0.0.1'
const report = { sourceCommit, h5IndexSha256: createHash('sha256').update(await readFile(join(dist, 'index.html'))).digest('hex'),
  realWeChat: 'NOT_RUN', originalSystem: 'NOT_RUN', checks, requests }
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' }
const server = createServer(async (req, res) => {
  const url = new URL(req.url, endpoint)
  const prefix = '/miniapp/'
  const target = resolve(dist, '.' + url.pathname.slice(prefix.length - 1))
  if (!url.pathname.startsWith(prefix) || (target !== dist && !target.startsWith(dist + '/'))) return res.writeHead(404).end()
  try {
    const file = target === dist ? join(dist, 'index.html') : target
    res.writeHead(200, { 'Content-Type': mime[extname(file)] || 'application/octet-stream' }).end(await readFile(file))
  } catch {
    if (!res.headersSent) res.writeHead(404)
    res.end()
  }
})
await new Promise(done => server.listen(0, '127.0.0.1', done))
const origin = 'http://127.0.0.1:' + server.address().port
let browser
let stage = 'browser_launch'
let page
try {
  browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'] })
  const context = await browser.newContext({ serviceWorkers: 'block' })
  await context.route('**/*', route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.origin !== origin || request.method() !== 'GET' || !url.pathname.startsWith('/miniapp/')) {
      requests.push({ method: request.method(), path: url.pathname, sameOrigin: url.origin === origin })
      return route.abort()
    }
    return route.continue()
  })
  page = await context.newPage()
  await page.addInitScript(() => {
    window.__cameraCalls = 0
    if (navigator.mediaDevices) navigator.mediaDevices.getUserMedia = () => {
      window.__cameraCalls++
      return Promise.reject(new Error('camera blocked by test'))
    }
  })
  page.setDefaultTimeout(10000)
  let pickerEvents = 0
  page.on('filechooser', () => { pickerEvents++ })
  const pass = name => checks.push({ name, passed: true })
  const label = text => page.getByText(text, { exact: true })
  const catalog = '/pages/id-photo/index'
  await page.goto(origin + '/miniapp/#' + catalog)
  async function enter(title, mode) {
    await label(title).click()
    await page.waitForURL(url => url.hash.includes('/pages/id-photo-' + mode + '/index'))
  }
  async function safeClick(locator, toast) {
    await locator.click()
    await label(toast).waitFor({ state: 'visible' })
  }
  async function back() {
    await page.locator('.page .back[aria-label]').click()
    await page.waitForURL(url => url.hash.includes(catalog))
  }
  async function waitSizeCount(count) {
    await page.waitForFunction(count => document.querySelectorAll('.sizes .size').length === count, count, { timeout: 6000 })
  }

  stage = 'smart'
  await enter('智能证件照', 'smart')
  await waitSizeCount(9)
  const expected = [
    ['教师资格证', '25×35mm'], ['一寸', '25×35mm'], ['二寸', '35×49mm'],
    ['计算机等级', '33×48mm'], ['大一寸', '33×48mm'], ['社保证', '30×40mm'],
    ['小二寸', '35×45mm'], ['小一寸', '22×32mm'], ['简历照片', '25×35mm']
  ]
  assert.equal(await page.locator('.size').count(), 9)
  for (const [index, [name, size]] of expected.entries()) {
    const row = page.locator('.size').nth(index)
    assert.equal(await row.getByText(name, { exact: true }).count(), 1)
    assert.equal(await row.getByText(size, { exact: true }).count(), 1)
  }
  const search = page.locator('.search input')
  await search.fill('资格')
  await waitSizeCount(1)
  await label('教师资格证').waitFor()
  assert.equal(await page.locator('.size').count(), 1)
  await search.fill('35x45')
  await waitSizeCount(1)
  await label('小二寸').waitFor()
  assert.equal(await page.locator('.size').count(), 1)
  await search.fill('未收录')
  await label('没有匹配的规格').waitFor()
  await label('清空').click()
  await page.waitForFunction(() => document.querySelector('.search input')?.value === '')
  await waitSizeCount(9)
  await label('教师资格证').waitFor()
  assert.equal(await search.inputValue(), '')
  assert.equal(await page.locator('.size').count(), 9)
  await safeClick(label('热门规格'), '热门规格尚未接入')
  await safeClick(page.locator('.size').first(), '智能证件照制作尚未接入')
  pass('smart_entry_nine_specs_and_local_filter')

  stage = 'custom_sizes'
  const custom = () => page.locator('.tabs').getByText('自定义规格', { exact: true }).click()
  const panel = page.locator('.custom-panel')
  const width = panel.locator('input').nth(0)
  const height = panel.locator('input').nth(1)
  await custom()
  await panel.getByText('最大不能超过100mm×100mm', { exact: true }).waitFor()
  assert.equal(await panel.getByText('毫米(mm)', { exact: true }).count(), 2)
  await width.fill('0')
  await height.fill('100')
  await safeClick(panel.getByText('确定', { exact: true }), '宽和高须大于0且不超过100mm')
  await width.fill('100.1')
  await safeClick(panel.getByText('确定', { exact: true }), '宽和高须大于0且不超过100mm')
  await panel.getByText('取消', { exact: true }).click()
  await custom()
  assert.equal(await width.inputValue(), '')
  assert.equal(await height.inputValue(), '')
  await width.fill('100')
  await height.fill('100')
  await safeClick(panel.getByText('确定', { exact: true }), '智能证件照制作尚未接入')
  await panel.waitFor({ state: 'hidden' })
  await custom()
  await width.fill('35')
  await panel.getByText('关闭', { exact: true }).click()
  await custom()
  assert.equal(await width.inputValue(), '')
  await panel.getByText('关闭', { exact: true }).click()
  pass('custom_sizes_boundary_cancel_and_close_clear_fields')

  stage = 'guide'
  await label('操作指南').click()
  await page.waitForURL(url => url.hash.includes('/pages/id-photo-guide/index'))
  const guideSteps = ['1. 选择证件照规格', '2. 拍摄/上传照片', '3. 十秒生成证件照', '4. 下载/冲印证件照']
  await label(guideSteps[3]).waitFor()
  assert.deepEqual(await page.locator('.step-label').allTextContents(), guideSteps)
  await safeClick(label('我知道了,去拍证件照'), '相机和证件照制作尚未接入')
  assert.equal(await page.evaluate(() => window.__cameraCalls), 0)
  await page.locator('.guide-back').click()
  await page.waitForURL(url => url.hash.includes('/pages/id-photo-smart/index'))
  await search.fill('社保')
  await back()
  await enter('智能证件照', 'smart')
  assert.equal(await search.inputValue(), '')
  await back()
  pass('guide_four_steps_and_unload_resets_search')

  stage = 'marriage'
  await enter('结婚证件照', 'marriage')
  await label('上传结婚照').waitFor()
  await label('纯色背景处理效果最佳。').waitFor()
  await label('建议照片铺满边框，无空白，并尽量将人像对准虚线。').waitFor()
  assert.equal(await page.locator('.marriage .person').count(), 2)
  assert.equal(await page.locator('.background').count(), 4)
  for (const name of ['原图', '红色一', '红色二', '红色三']) {
    const target = page.locator('.background').filter({ hasText: name })
    await safeClick(target, '背景处理尚未接入')
  }
  await safeClick(page.locator('.frame.marriage'), '添加结婚照尚未接入')
  await safeClick(label('下一步'), '证件照制作尚未接入')
  await back()
  pass('marriage_two_portraits_four_backgrounds_and_closed_actions')

  stage = 'vehicle'
  await enter('车辆行驶证', 'vehicle')
  await label('行驶证制作').waitFor()
  for (const name of ['88×60mm', '车头线', '车尾线', '底盘线']) await label(name).waitFor()
  assert.equal(await page.locator('.vehicle-tips > *').count(), 3)
  await page.getByText(/前方 45 度/).waitFor()
  await page.getByText(/标准图片的三分之二/).waitFor()
  await page.getByText(/清晰辨别车牌和车身颜色/).waitFor()
  await safeClick(page.locator('.frame.vehicle'), '添加车辆照片尚未接入')
  await safeClick(label('下一步'), '证件照制作尚未接入')
  await back()
  pass('vehicle_dimensions_three_guides_and_closed_actions')

  stage = 'deep_link_and_privacy'
  await page.goto(origin + '/miniapp/#/pages/id-photo-guide/index')
  await page.reload()
  await page.locator('.guide-back').click()
  await page.waitForURL(url => url.hash.includes('/pages/id-photo-smart/index'))
  for (const mode of ['smart', 'marriage', 'vehicle']) {
    await page.goto(origin + '/miniapp/#/pages/id-photo-' + mode + '/index')
    await page.reload()
    await back()
  }
  assert.equal(pickerEvents, 0)
  assert.deepEqual(requests, [])
  assert.deepEqual(await page.evaluate(() => Object.keys(localStorage)), [])
  pass('three_deep_links_return_without_picker_requests_or_storage')
} catch (error) {
  report.incompleteAt = stage
  report.failure = error instanceof Error ? error.stack : String(error)
  if (page) {
    report.pageUrl = page.url()
    report.inputs = await page.locator('.search input, .custom-panel input').evaluateAll(inputs =>
      inputs.map(input => input.value)).catch(() => [])
  }
  process.exitCode = 1
} finally {
  await browser?.close()
  await new Promise(done => server.close(done))
  await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify(report, null, 2))
}
