const assert = require('node:assert/strict')
const { chromium } = require('/home/ubuntu/print-system/runtime/browser-tools/node_modules/playwright')

const browserPath = '/home/ubuntu/print-system/runtime/browser-tools/browsers/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell'
const profilePath = process.env.SMOKE_PROFILE
if (!profilePath) throw new Error('SMOKE_PROFILE is required')

;(async () => {
  let context
  const requests = []
  let result
  try {
    context = await chromium.launchPersistentContext(profilePath, {
      executablePath: browserPath,
      headless: true,
      viewport: { width: 414, height: 896 }
    })
    context.on('request', request => requests.push({ method: request.method(), url: request.url() }))
    const page = context.pages()[0]
    page.setDefaultTimeout(10000)
    await page.goto('http://127.0.0.1:18080/miniapp/#/', { waitUntil: 'networkidle' })
    await page.getByText('新手教程', { exact: true }).click()
    await page.waitForURL(/pages\/tutorial-list\/index/)
    const titles = await page.locator('.article').allTextContents()
    assert.deepEqual(titles.map(title => title.replace('›', '').trim()), [
      '复印（图片去灰底）', '文档打印', '证件照制作', '图片拼版', '身份证打印', '电子发票打印', '照片纸打印'
    ])
    await page.getByText('文档打印', { exact: true }).click()
    await page.getByText('文档打印内容待继续只读观察', { exact: true }).waitFor()
    await page.getByText('照片纸打印', { exact: true }).click()
    await page.waitForURL(/pages\/tutorial-detail\/index\?article=photo-paper/)
    await page.getByText('发布日期：2021-03-09 15:24:19', { exact: true }).waitFor()
    await page.getByText('历史指南仅用于还原页面结构，流程和截图可能已过时；其中展示的旧金额不作为新系统价格。', { exact: true }).waitFor()
    assert.equal(await page.locator('.step').count(), 4)
    assert.equal((await page.locator('body').innerText()).includes('1.00元'), false)
    result = { pages: 2, articles: 7, steps: 4 }
  } finally {
    if (context) await context.close()
  }
  const nonGet = requests.filter(request => request.method !== 'GET')
  const originalRequests = requests.filter(request => request.url.includes('youfan.iyint.com'))
  assert.deepEqual(nonGet, [])
  assert.deepEqual(originalRequests, [])
  console.log(JSON.stringify({ result: 'PASS', ...result, nonGet: 0, originalRequests: 0 }))
})().catch(error => {
  console.error(error)
  process.exitCode = 1
})
