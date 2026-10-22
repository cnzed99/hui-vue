<script setup lang="ts">
import { ElButton } from 'element-plus'

defineOptions({ name: 'PrintTouchAdSample' })
</script>

<template>
  <section class="touch-ad" aria-labelledby="touch-ad-title" data-source-id="P47">
    <header class="touch-ad__heading">
      <h1 id="touch-ad-title">触屏终端广告</h1>
    </header>
    <form class="touch-ad__form" @submit.prevent>
      <p class="touch-ad__hint">最多可上传5张图片，图片尺寸720*240像素，分辨率96</p>
      <span class="touch-ad__label">广告：</span>
      <div class="touch-ad__upload" aria-label="上传占位，接口未接入，不接收文件">
        <div class="touch-ad__square" aria-hidden="true"><span>＋</span></div>
        <small>720*240像素</small>
      </div>
      <div
        class="touch-ad__illustration"
        role="img"
        aria-label="原站说明示意图区域，图片素材尚未取得，未复制二维码"
      ><span>说明图素材待补</span></div>
      <ElButton
        class="touch-ad__save"
        type="primary"
        native-type="button"
        disabled
        title="接口未接入，不能保存"
      >保存</ElButton>
    </form>
  </section>
</template>

<style scoped>
.touch-ad { color: #475569; }
.touch-ad__heading { position: relative; height: 71px; background: #fff; }
.touch-ad__heading h1 { position: absolute; top: 32px; left: 19px; margin: 0; padding: 0 0 12px; color: #263445; font-size: 16px; font-weight: 500; line-height: 22px; }
.touch-ad__heading h1::after { position: absolute; bottom: 0; left: 0; width: 94px; height: 3px; background: #0284c7; content: ''; }
.touch-ad__form { position: relative; height: 437px; margin: 12px 0 0; background: #fff; }
.touch-ad__hint { position: absolute; top: 35px; left: 265px; margin: 0; color: #888; font-size: 12px; line-height: 20px; white-space: nowrap; }
.touch-ad__label { position: absolute; top: 106px; left: 195px; color: #555; font-size: 13px; line-height: 22px; }
.touch-ad__upload { position: absolute; top: 88px; left: 261px; text-align: left; }
.touch-ad__square { display: flex; align-items: center; justify-content: center; width: 50px; height: 50px; background: #f1f1f1; }
.touch-ad__square span { display: flex; align-items: center; justify-content: center; width: 23px; height: 23px; border: 1px solid #aaa; border-radius: 50%; color: #aaa; font-size: 20px; font-weight: 300; line-height: 1; }
.touch-ad__upload small { display: block; margin-top: 7px; color: #aaa; font-size: 11px; line-height: 16px; }
.touch-ad__illustration { position: absolute; top: 17px; left: 568px; display: flex; align-items: center; justify-content: center; width: 480px; height: 300px; color: #aaa; font-size: 11px; }
.touch-ad__save { position: absolute; top: 346px; left: 265px; width: 60px; height: 35px; padding: 0; border-radius: 3px; }
</style>
