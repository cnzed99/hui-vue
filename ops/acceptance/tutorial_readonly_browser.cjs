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
    const details = [
      ['复印（图片去灰底）', 'copy-clean-background', '2026-01-30 12:29:48', 'external', 0],
      ['文档打印', 'document-print', '2026-01-30 12:21:16', 'external', 0],
      ['证件照制作', 'id-photo', '2026-01-30 12:20:29', 'external', 0],
      ['图片拼版', 'image-collage', '2026-01-30 12:18:01', 'external', 0],
      ['身份证打印', 'identity-card', '2026-01-30 12:16:56', 'external', 0],
      ['电子发票打印', 'invoice', '2021-03-09 15:29:15', 'steps', 5],
      ['照片纸打印', 'photo-paper', '2021-03-09 15:24:19', 'steps', 4]
    ]
    for (const [title, id, publishedAt, kind, stepCount] of details) {
      await page.getByText(title, { exact: true }).click()
      await page.waitForURL(new RegExp(`pages/tutorial-detail/index\\?article=${id}`))
      await page.getByText(`发布日期：${publishedAt}`, { exact: true }).waitFor()
      if (kind === 'external') {
        await page.getByText('原版为公众号外链视频教程', { exact: true }).waitFor()
        assert.equal(await page.locator('.step').count(), 0)
      } else {
        assert.equal(await page.locator('.step').count(), stepCount)
      }
      assert.equal((await page.locator('body').innerText()).includes('1.00元'), false)
      await page.locator('.back').click()
      await page.waitForURL(/pages\/tutorial-list\/index/)
    }
    result = { pages: 8, articles: 7, externalVideoArticles: 5, stepArticles: 2, steps: 9 }
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
