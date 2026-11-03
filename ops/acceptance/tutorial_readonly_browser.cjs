const assert = require('node:assert/strict')
const { chromium } = require('/home/ubuntu/print-system/runtime/browser-tools/node_modules/playwright')

const browserPath = '/home/ubuntu/print-system/runtime/browser-tools/browsers/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell'
const profilePath = process.env.SMOKE_PROFILE
if (!profilePath) throw new Error('SMOKE_PROFILE is required')

;(async () => {
  let context
  const requests = []
  const blockedRequests = []
  let result
  try {
    context = await chromium.launchPersistentContext(profilePath, {
      executablePath: browserPath,
      headless: true,
      viewport: { width: 414, height: 896 },
      serviceWorkers: 'block'
    })
    context.on('request', request => requests.push({ method: request.method(), url: request.url() }))
    await context.route('**/*', async route => {
      const request = route.request()
      const url = new URL(request.url())
      const isHttp = url.protocol === 'http:' || url.protocol === 'https:'
      const isSameOrigin = url.origin === 'http://127.0.0.1:18080'
      if (request.method() !== 'GET' || (isHttp && !isSameOrigin)) {
        blockedRequests.push({ method: request.method(), url: request.url() })
        await route.abort('blockedbyclient')
        return
      }
      await route.continue()
    })
    const externalProbePage = await context.newPage()
    await externalProbePage.goto('https://external-boundary.invalid/tutorial-probe').catch(() => undefined)
    await externalProbePage.close()
    const postProbePage = await context.newPage()
    await postProbePage.setContent('<!doctype html><title>blocked-post-probe</title>')
    const postProbeResult = await postProbePage.evaluate(async () => {
      try {
        await fetch('http://127.0.0.1:18080/miniapp/__blocked_probe__', { method: 'POST' })
        return 'unexpected-success'
      } catch {
        return 'blocked'
      }
    })
    await postProbePage.close()
    assert.equal(postProbeResult, 'blocked')
    assert.deepEqual(blockedRequests.map(request => request.method), ['GET', 'POST'])
    assert.deepEqual(blockedRequests.map(request => request.url), [
      'https://external-boundary.invalid/tutorial-probe',
      'http://127.0.0.1:18080/miniapp/__blocked_probe__'
    ])
    blockedRequests.length = 0
    requests.length = 0
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
      await page.getByText('历史指南仅用于还原页面结构，流程和截图可能已过时；其中展示的旧金额不作为新系统价格。', { exact: true }).waitFor()
      if (kind === 'external') {
        await page.getByText('原版为公众号外链视频教程', { exact: true }).waitFor()
        await page.getByText(/不复用原公众号链接或视频/).waitFor()
        assert.equal(await page.locator('.step').count(), 0)
      } else {
        assert.equal(await page.locator('.step').count(), stepCount)
      }
      assert.equal((await page.locator('body').innerText()).includes('1.00元'), false)
      await page.locator('.back').click()
      await page.waitForURL(/pages\/tutorial-list\/index/)
    }
    const invalidColdPaths = [
      '/pages/tutorial-detail/index',
      '/pages/tutorial-detail/index?article=',
      '/pages/tutorial-detail/index?article=unexpected',
      '/pages/tutorial-detail/index?article=constructor',
      '/pages/tutorial-detail/index?article=__proto__',
      '/pages/tutorial-detail/index?article=toString'
    ]
    for (const path of invalidColdPaths) {
      const coldPage = await context.newPage()
      coldPage.setDefaultTimeout(10000)
      try {
        await coldPage.goto(`http://127.0.0.1:18080/miniapp/#${path}`, { waitUntil: 'networkidle' })
        await coldPage.waitForURL(/pages\/tutorial-list\/index/)
        await coldPage.getByText('用户指南-文章列表', { exact: true }).waitFor()
        assert.equal(await coldPage.locator('.article').count(), 7)
      } finally {
        await coldPage.close()
      }
    }
    const validColdPage = await context.newPage()
    validColdPage.setDefaultTimeout(10000)
    try {
      await validColdPage.goto('http://127.0.0.1:18080/miniapp/#/pages/tutorial-detail/index?article=invoice', { waitUntil: 'networkidle' })
      await validColdPage.getByText('发布日期：2021-03-09 15:29:15', { exact: true }).waitFor()
      assert.equal(await validColdPage.locator('.step').count(), 5)
    } finally {
      await validColdPage.close()
    }
    const warmDiagnosticPage = await context.newPage()
    warmDiagnosticPage.setDefaultTimeout(10000)
    let warmHashHotSwitch
    let warmHashDisplayedDefaultArticle
    try {
      await warmDiagnosticPage.goto('http://127.0.0.1:18080/miniapp/#/pages/tutorial-detail/index?article=', { waitUntil: 'networkidle' })
      await warmDiagnosticPage.waitForURL(/pages\/tutorial-list\/index/)
      await warmDiagnosticPage.goto('http://127.0.0.1:18080/miniapp/#/pages/tutorial-detail/index?article=constructor', { waitUntil: 'networkidle' })
      await warmDiagnosticPage.waitForTimeout(500)
      warmHashHotSwitch = /pages\/tutorial-list\/index/.test(warmDiagnosticPage.url()) ? 'RESOLVED' : 'KNOWN_UNRESOLVED_UNI_H5_CACHE'
      warmHashDisplayedDefaultArticle = (await warmDiagnosticPage.locator('body').innerText()).includes('发布日期：2021-03-09 15:24:19')
    } finally {
      await warmDiagnosticPage.close()
    }
    result = { pages: 8, articles: 7, externalVideoArticles: 5, stepArticles: 2, steps: 9, invalidColdRoutes: 6, validColdRoutes: 1, historyWarnings: 7, warmHashHotSwitch, warmHashDisplayedDefaultArticle }
  } finally {
    if (context) await context.close()
  }
  const nonGet = requests.filter(request => request.method !== 'GET')
  const externalHttpRequests = requests.filter(request => {
    const url = new URL(request.url)
    return (url.protocol === 'http:' || url.protocol === 'https:') && url.origin !== 'http://127.0.0.1:18080'
  })
  const originalRequests = requests.filter(request => request.url.includes('youfan.iyint.com') || request.url.includes('mp.weixin.qq.com'))
  assert.deepEqual(nonGet, [])
  assert.deepEqual(externalHttpRequests, [])
  assert.deepEqual(originalRequests, [])
  assert.deepEqual(blockedRequests, [])
  console.log(JSON.stringify({ result: 'PASS', ...result, nonGet: 0, externalHttpRequests: 0, originalRequests: 0, blockedRequests: 0 }))
})().catch(error => {
  console.error(error)
  process.exitCode = 1
})
