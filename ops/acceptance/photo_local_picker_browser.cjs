const assert = require('node:assert/strict')
const fs = require('node:fs')
const { chromium } = require('/home/ubuntu/print-system/runtime/browser-tools/node_modules/playwright')

const browserPath = '/home/ubuntu/print-system/runtime/browser-tools/browsers/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell'
const profilePath = process.env.SMOKE_PROFILE
const failureDir = process.env.SMOKE_FAILURE_DIR
if (!profilePath || !failureDir) throw new Error('SMOKE_PROFILE and SMOKE_FAILURE_DIR are required')

function bounded(promise, milliseconds, label) {
  let timer
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`${label} timed out after ${milliseconds}ms`)), milliseconds)
    })
  ]).finally(() => clearTimeout(timer))
}

;(async () => {
  let context
  let page
  const requests = []
  let entry = 'UNVERIFIED'
  let failure
  let decoded
  try {
    context = await bounded(chromium.launchPersistentContext(profilePath, {
      executablePath: browserPath,
      headless: true,
      viewport: { width: 414, height: 896 }
    }), 10000, 'browser launch')
    context.on('request', request => requests.push({ method: request.method(), url: new URL(request.url()).pathname }))
    page = context.pages()[0] || await bounded(context.newPage(), 5000, 'new page')
    page.setDefaultTimeout(10000)
    await bounded(page.goto('http://127.0.0.1:18080/miniapp/#/pages/photo-upload/index', { waitUntil: 'networkidle' }), 15000, 'navigation')
    await page.getByText('3寸照片上传', { exact: true }).waitFor()
    await page.getByText('暂无照片哦~', { exact: true }).waitFor()
    await page.getByText('上传照片(0/30)', { exact: true }).waitFor()

    const chooserAttempt = page.waitForEvent('filechooser', { timeout: 10000 })
      .then(value => ({ kind: 'chooser', value }))
      .catch(error => ({ kind: 'missing', error: String(error) }))
    await page.getByText('上传照片(0/30)', { exact: true }).click()
    let chooserResult = await chooserAttempt

    if (chooserResult.kind === 'missing') {
      const sourceLabels = ['从相册选择', '手机相册', '相册', '选择图片']
      let chosenLabel
      for (const label of sourceLabels) {
        const candidate = page.getByText(label, { exact: true })
        if (await candidate.isVisible().catch(() => false)) {
          chosenLabel = label
          const menuChooser = page.waitForEvent('filechooser', { timeout: 10000 })
            .then(value => ({ kind: 'chooser', value }))
            .catch(error => ({ kind: 'missing', error: String(error) }))
          await candidate.click()
          chooserResult = await menuChooser
          break
        }
      }
      if (chooserResult.kind === 'missing') {
        const inputs = await page.locator('input[type=file]').count()
        throw new Error(`ENTRY_UNVERIFIED: no bounded filechooser; sourceLabel=${chosenLabel || 'none'}; fileInputs=${inputs}; ${chooserResult.error}`)
      }
    }

    entry = 'VERIFIED_FILECHOOSER'
    await bounded(chooserResult.value.setFiles({
      name: 'synthetic-one-pixel.png',
      mimeType: 'image/png',
      buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9WlZ0mQAAAAASUVORK5CYII=', 'base64')
    }), 10000, 'synthetic file selection')

    await page.getByText('上传照片(1/30)', { exact: true }).waitFor()
    const preview = page.locator('.photo-grid > .photo')
    assert.equal(await preview.count(), 1)
    await preview.waitFor({ state: 'visible' })
    const box = await preview.boundingBox()
    assert.ok(box && box.width > 0 && box.height > 0, 'preview box must be visible and non-zero')
    const image = preview.locator('img').first()
    await image.waitFor({ state: 'visible' })
    decoded = await image.evaluate(element => ({
      complete: element.complete,
      naturalWidth: element.naturalWidth,
      naturalHeight: element.naturalHeight
    }))
    assert.equal(decoded.complete, true)
    assert.ok(decoded.naturalWidth > 0 && decoded.naturalHeight > 0, 'preview image must decode')

    await page.getByText('提交打印', { exact: true }).click()
    await page.getByText('照片仅在本地预览，排版和计价待接入', { exact: true }).waitFor()

  } catch (error) {
    failure = error
    fs.mkdirSync(failureDir, { recursive: true })
    if (page) {
      await page.screenshot({ path: `${failureDir}/failure.png`, fullPage: true }).catch(() => {})
      fs.writeFileSync(`${failureDir}/failure.html`, await page.content().catch(() => ''))
    }
  } finally {
    if (context) {
      try {
        await bounded(context.close(), 5000, 'context close')
      } catch (error) {
        failure = failure || error
      }
    }
  }
  if (failure) {
    console.error(JSON.stringify({ result: 'FAIL', entry, error: String(failure), requests }))
    throw failure
  }
  const nonGet = requests.filter(request => request.method !== 'GET')
  const apiRequests = requests.filter(request => request.url.startsWith('/app-api/') || request.url.startsWith('/admin-api/'))
  assert.deepEqual(nonGet, [])
  assert.deepEqual(apiRequests, [])
  console.log(JSON.stringify({ result: 'PASS', entry, selected: 1, preview: decoded, nonGet: 0, apiRequests: 0 }))
})().catch(error => {
  console.error(error)
  process.exitCode = 1
})
