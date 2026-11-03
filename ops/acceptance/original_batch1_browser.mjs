import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'

const root = '/home/ubuntu/print-system'
const runtime = path.join(root, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const home = origin + '/miniapp/#/pages/home/index'
const output = process.argv[2]
const report = { checks: [], apiRequests: [], wechatDevice: 'NOT_RUN' }
function check(name, passed) {
  report.checks.push({ name, passed: Boolean(passed) })
  if (!passed) throw new Error(name)
}

let browser
try {
  process.env.PLAYWRIGHT_BROWSERS_PATH = path.join(runtime, 'browsers')
  process.env.LD_LIBRARY_PATH = path.join(runtime, 'sysroot/usr/lib/x86_64-linux-gnu')
  process.env.FONTCONFIG_FILE = path.join(runtime, 'fonts.conf')
  process.env.XDG_CACHE_HOME = path.join(runtime, 'cache')
  const { chromium } = createRequire(path.join(runtime, 'package.json'))('playwright')
  browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'], env: process.env })
  const context = await browser.newContext({ viewport: { width: 414, height: 780 }, locale: 'zh-CN', serviceWorkers: 'block' })
  const page = await context.newPage()
  page.setDefaultTimeout(12000)
  context.on('request', request => {
    const url = new URL(request.url())
    if (/^\/(app-api|admin-api)\//.test(url.pathname) || request.method() !== 'GET') {
      report.apiRequests.push(request.method() + ' ' + url.pathname)
    }
  })

  await page.goto(home)
  for (const [label, route, marker] of [
    ['照片冲印', 'photo-method', '6寸照片拼版打印'],
    ['扫描复印', 'scan-copy', '文件复印'],
    ['证件照', 'id-photo', '普通证件照']
  ]) {
    await page.getByText(label, { exact: true }).click()
    await page.waitForURL('**/#/pages/' + route + '/index')
    await page.getByText(marker, { exact: true }).first().waitFor()
    check('home_' + route, true)
    if (route !== 'id-photo') await page.goto(home)
  }
  await page.getByText('普通证件照', { exact: true }).click()
  const sizePage = origin + '/miniapp/#/pages/id-photo-sizes/index'
  await page.waitForURL(sizePage)
  for (const [id, name] of [
    ['one', '1寸'], ['two', '2寸'], ['three', '3寸'], ['small-one', '小1寸'],
    ['large-one', '大1寸'], ['small-two', '小2寸'], ['large-two', '大2寸'], ['four', '4寸']
  ]) {
    await page.getByText(name, { exact: true }).click()
    await page.waitForURL('**/pages/id-photo-upload/index?size=' + id)
    await page.locator('.name').getByText(name, { exact: true }).waitFor()
    check('size_' + id, true)
    if (id === 'large-one') {
      await page.screenshot({ path: path.join(output, 'id-photo-empty.png') })
      const [picker] = await Promise.all([page.waitForEvent('filechooser'), page.locator('.frame').click()])
      await picker.setFiles([])
      check('cancel_keeps_empty', await page.locator('.preview').count() === 0)
      const [second] = await Promise.all([page.waitForEvent('filechooser'), page.locator('.frame').click()])
      await second.setFiles({
        name: 'synthetic.png', mimeType: 'image/png',
        buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a8JkAAAAASUVORK5CYII=', 'base64')
      })
      await page.getByText('本地预览，点击图片可更换').waitFor()
      check('synthetic_photo_preview', await page.locator('.preview').count() === 1)
    }
    await page.goto(sizePage)
  }
  await page.goto(origin + '/miniapp/#/pages/id-photo-upload/index?size=invalid')
  await page.waitForURL(sizePage)
  await page.getByText('常用尺寸', { exact: true }).waitFor()
  check('invalid_size_redirect', true)
  await page.goto(home)
  await page.getByText('压缩包打印', { exact: true }).click()
  await page.getByText('请在微信小程序中使用文件选择器').waitFor()
  check('h5_archive_boundary', true)
  await page.screenshot({ path: path.join(output, 'h5-archive-boundary.png') })
  check('no_api_or_mutating_requests', report.apiRequests.length === 0)
  report.status = 'PASS'
} catch (error) {
  report.status = 'FAIL'
  report.error = String(error)
  process.exitCode = 1
} finally {
  await browser?.close()
  await fs.writeFile(path.join(output, 'browser-report.json'), JSON.stringify(report, null, 2) + '\n')
  console.log(JSON.stringify(report))
}
