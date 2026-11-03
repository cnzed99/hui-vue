<script setup lang="ts">
import { ref, shallowRef } from 'vue'
import { onShow, onUnload } from '@dcloudio/uni-app'
import { ARCHIVE_DIGEST, inspectDemoArchive, MAX_ARCHIVE_BYTES, selectDemoArchiveFile } from '@/api/demoArchives'
import type { ArchiveCandidate, ArchiveInput, ArchiveInspection } from '@/api/demoArchives'
import { ensureDemoSession, getActiveDemoSession } from '@/api/demoSession'
import type { DemoSession } from '@/api/demoSession'
import { waitForDemoFile } from '@/api/demoFiles'
import type { DemoFileMetadata } from '@/api/demoFiles'
import { createDraft, loadDraft, saveDraft } from '@/domain/printDraft'
import { isFileSelectionCancelled } from '@/domain/documentSource'

interface Selection {
  sessionId: string
  principalId: string
  digest: string
  ordinal: number
  key: string
}

const storageKey = 'miniapp-archive-selection-v1'
const archive = shallowRef<ArchiveInput | null>(null)
const inspection = ref<ArchiveInspection | null>(null)
const selected = ref<number | null>(null)
const readyFile = ref<DemoFileMetadata | null>(null)
const phase = ref<'idle' | 'checking' | 'selecting'>('idle')
const message = ref('')
let owner: DemoSession | null = null
let epoch = 0
let picker: HTMLInputElement | null = null

function sameOwner(a: Pick<DemoSession, 'sessionId' | 'principalId'>, b: DemoSession) {
  return a.sessionId === b.sessionId && a.principalId === b.principalId
}

function restoreSelection(session: DemoSession): Selection | null {
  const stored = uni.getStorageSync(storageKey) as Partial<Selection> | null
  if (!stored) return null
  if (stored.sessionId !== session.sessionId || stored.principalId !== session.principalId
    || typeof stored.digest !== 'string' || !ARCHIVE_DIGEST.test(stored.digest)
    || typeof stored.ordinal !== 'number' || !Number.isInteger(stored.ordinal) || stored.ordinal < 0 || stored.ordinal >= 20
    || typeof stored.key !== 'string' || !/^miniapp-archive-[A-Za-z0-9-]{1,80}$/.test(stored.key)) {
    uni.removeStorageSync(storageKey)
    return null
  }
  return stored as Selection
}

function resetForSession() {
  ++epoch
  owner = null
  archive.value = null
  inspection.value = null
  selected.value = null
  readyFile.value = null
  phase.value = 'idle'
  uni.removeStorageSync(storageKey)
  loadDraft()
  message.value = '演示会话已变化，请重新选择 ZIP。'
}

function stillCurrent(request: number, session: DemoSession) {
  if (request !== epoch) return false
  const active = getActiveDemoSession()
  if (!active || !sameOwner(session, active)) {
    resetForSession()
    return false
  }
  return true
}

async function sessionFor(request: number) {
  const session = await ensureDemoSession()
  if (request !== epoch) return null
  if (!session) {
    phase.value = 'idle'
    message.value = '安全演示会话暂不可用，请稍后重试。'
    return null
  }
  if (owner && !sameOwner(owner, session)) {
    resetForSession()
    return null
  }
  if (!stillCurrent(request, session)) return null
  owner = session
  return session
}

onShow(() => {
  const active = getActiveDemoSession()
  if (owner && (!active || !sameOwner(owner, active))) resetForSession()
})

onUnload(() => {
  ++epoch
  // #ifdef H5
  picker?.remove()
  picker = null
  // #endif
})

function acceptArchive(input: ArchiveInput) {
  ++epoch
  phase.value = 'idle'
  archive.value = null
  inspection.value = null
  selected.value = null
  readyFile.value = null
  if (!/\.zip$/i.test(input.name) || !Number.isSafeInteger(input.size) || input.size <= 0 || input.size > MAX_ARCHIVE_BYTES) {
    message.value = '请选择一个不超过 2 MiB 的 ZIP 文件。'
    return
  }
  archive.value = input
  message.value = ''
}

function chooseArchive() {
  // #ifdef MP-WEIXIN
  uni.chooseMessageFile({
    count: 1,
    type: 'file',
    extension: ['zip'],
    success: result => {
      const file = result.tempFiles[0]
      if (file) acceptArchive({ name: file.name, size: file.size, path: file.path })
    },
    fail: error => {
      if (!isFileSelectionCancelled(error)) uni.showToast({ title: '文件选择失败，请重试', icon: 'none' })
    }
  })
  // #endif
  // #ifdef H5
  picker?.remove()
  const input = document.createElement('input')
  picker = input
  input.type = 'file'
  input.accept = '.zip'
  input.hidden = true
  input.setAttribute('aria-label', '本地 ZIP 文件')
  input.addEventListener('change', () => {
    if (picker !== input) return
    const file = input.files?.[0]
    input.remove()
    picker = null
    if (file) acceptArchive({ name: file.name, size: file.size, file })
  }, { once: true })
  input.addEventListener('cancel', () => {
    if (picker === input) picker = null
    input.remove()
  }, { once: true })
  document.body.appendChild(input)
  input.click()
  // #endif
}

async function checkArchive() {
  const input = archive.value
  if (!input || phase.value !== 'idle') return
  const request = ++epoch
  phase.value = 'checking'
  message.value = ''
  const session = await sessionFor(request)
  if (!session) return
  const result = await inspectDemoArchive(session, input)
  if (!stillCurrent(request, session)) return
  phase.value = 'idle'
  if (result.kind !== 'ok') {
    message.value = result.kind === 'rejected'
      ? '无法检查此 ZIP，请确认压缩包格式和大小。'
      : '暂未收到检查结果，请重试。'
    return
  }
  inspection.value = result.data
  const previous = restoreSelection(session)
  if (previous?.digest === result.data.archiveSha256
    && result.data.candidates.some(candidate => candidate.ordinal === previous.ordinal)) selected.value = previous.ordinal
  if (!result.data.candidates.length) message.value = '未找到可供选择的 PDF 文件。'
}

function chooseCandidate(candidate: ArchiveCandidate) {
  if (selected.value === candidate.ordinal) return
  ++epoch
  phase.value = 'idle'
  selected.value = candidate.ordinal
  readyFile.value = null
  message.value = ''
}

async function extractCandidate() {
  const input = archive.value
  const digest = inspection.value?.archiveSha256
  const ordinal = selected.value
  if (!input || !digest || ordinal === null || phase.value !== 'idle' || readyFile.value) return
  const request = ++epoch
  phase.value = 'selecting'
  message.value = ''
  const session = await sessionFor(request)
  if (!session) return
  const existing = restoreSelection(session)
  const selection: Selection = existing?.digest === digest && existing.ordinal === ordinal
    ? existing
    : { sessionId: session.sessionId, principalId: session.principalId, digest, ordinal,
        key: 'miniapp-archive-' + Date.now() + '-' + Math.random().toString(36).slice(2) }
  try {
    // Keep the binding before sending any request whose commit result may be lost.
    uni.setStorageSync(storageKey, selection)
  } catch {
    phase.value = 'idle'
    message.value = '无法保存本次提交记录，请检查存储空间后重试。'
    return
  }
  const response = await selectDemoArchiveFile(session, input, digest, ordinal, selection.key)
  if (!stillCurrent(request, session)) return
  if (response.kind !== 'ok') {
    phase.value = 'idle'
    message.value = response.kind === 'rejected'
      ? '服务端未接受本次选择。可重试当前选择，或选择另一份 PDF。'
      : '提交结果暂未确认，请重试当前选择。'
    return
  }
  const result = await waitForDemoFile(session, response.data, () => stillCurrent(request, session))
  if (!stillCurrent(request, session)) return
  phase.value = 'idle'
  if (!result || result.id !== response.data.id) {
    message.value = '暂未收到解析结果，请重试当前选择。'
    return
  }
  if (result.status === 'READY' && result.ready && result.pageCount && result.expiresAtMs > Date.now()) {
    readyFile.value = result
  } else if (result.status === 'FAILED' || result.status === 'DELETED' || result.expiresAtMs <= Date.now()) {
    message.value = '此 PDF 无法使用或已经过期，请选择另一份文件。'
  } else {
    message.value = 'PDF 仍在解析，请稍后重试当前选择。'
  }
}

function continueToSettings() {
  const file = readyFile.value
  if (!file || !file.pageCount || !owner || !stillCurrent(epoch, owner)) return
  if (file.expiresAtMs <= Date.now()) {
    readyFile.value = null
    message.value = '此 PDF 已经过期，请选择另一份文件。'
    return
  }
  const draft = createDraft('SERVER_UPLOAD', file.displayName, file.sizeBytes, file.pageCount, file.id)
  saveDraft({ ...draft, archiveOwner: { sessionId: owner.sessionId, principalId: owner.principalId } })
  uni.navigateTo({ url: '/pages/document-settings/index' })
}

function goBack() { uni.navigateBack() }
</script>

<template>
  <view class="archive-page">
    <view class="header">
      <view class="status-space" />
      <view class="title-row"><text class="back" @click="goBack">‹</text><text>压缩包打印</text><text class="spacer" /></view>
    </view>
    <view class="body">
      <view class="card">
        <text class="heading">从 ZIP 中选择一份 PDF</text>
        <text class="note">单个 ZIP 不超过 2 MiB。点击检查后才会上传压缩包，检查只列出候选 PDF。选中的 PDF 还需通过服务端解析。</text>
        <!-- #ifdef H5 -->
        <button class="secondary" @click="chooseArchive">选择本地 ZIP</button>
        <!-- #endif -->
        <!-- #ifdef MP-WEIXIN -->
        <button class="secondary" @click="chooseArchive">选择微信聊天中的 ZIP</button>
        <!-- #endif -->
        <text v-if="archive" class="filename">{{ archive.name }}</text>
        <button v-if="archive && !inspection" class="primary" :disabled="phase !== 'idle'" @click="checkArchive">
          {{ phase === 'checking' ? '正在检查…' : '上传并检查 ZIP' }}
        </button>
      </view>
      <view v-if="inspection?.candidates.length" class="card">
        <text class="heading">候选 PDF</text>
        <button v-for="candidate in inspection.candidates" :key="candidate.ordinal"
          class="candidate" :class="{ selected: selected === candidate.ordinal }"
          @click="chooseCandidate(candidate)">
          <text class="filename">{{ candidate.displayName }}</text>
          <text class="note">{{ (candidate.declaredSizeBytes / 1024).toFixed(1) }} KB</text>
        </button>
        <button v-if="!readyFile" class="primary" :disabled="selected === null || phase !== 'idle'" @click="extractCandidate">
          {{ phase === 'selecting' ? '正在提取并解析…' : '提取并解析所选 PDF' }}
        </button>
        <view v-else class="ready">
          <text>服务端解析完成 · {{ readyFile.pageCount }} 页</text>
          <button class="primary" @click="continueToSettings">继续设置打印参数</button>
        </view>
      </view>
      <text v-if="message" class="message">{{ message }}</text>
      <text class="note">PDF 不超过 20 页，暂存最长 1 小时。</text>
    </view>
  </view>
</template>

<style scoped lang="scss">
.archive-page { min-height: 100vh; background: #f6f6f6; }
.header { color: #fff; background: linear-gradient(135deg, #ff9634, #ffad48); }
.status-space { height: var(--status-bar-height); min-height: 36rpx; }
.title-row { display: flex; align-items: center; justify-content: space-between; height: 86rpx; padding: 0 26rpx; font-size: 34rpx; font-weight: 600; }
.back, .spacer { width: 56rpx; }
.back { font-size: 56rpx; line-height: 56rpx; }
.body { padding: 24rpx 24rpx 60rpx; }
.card { margin-bottom: 22rpx; padding: 24rpx; background: #fff; border-radius: 16rpx; }
.heading, .note, .filename, .message { display: block; }
.heading { margin-bottom: 12rpx; font-size: 30rpx; font-weight: 700; }
.note { color: #6a6a6a; font-size: 23rpx; line-height: 36rpx; }
.filename { overflow-wrap: anywhere; margin-top: 10rpx; font-size: 27rpx; }
.message { margin-bottom: 18rpx; padding: 16rpx; color: #8e561d; background: #fff6df; border-radius: 12rpx; }
.primary, .secondary { min-height: 78rpx; margin-top: 18rpx; border-radius: 40rpx; font-size: 28rpx; }
.primary { color: #fff; background: #b6580d; }
.secondary { color: #9a480c; background: #fff1e4; }
.candidate { margin-top: 12rpx; padding: 12rpx 20rpx; color: #333; background: #f5f5f5; text-align: left; line-height: 1.5; }
.candidate.selected { color: #9a480c; background: #fff1e4; }
.ready { margin-top: 20rpx; color: #237a5a; }
</style>
