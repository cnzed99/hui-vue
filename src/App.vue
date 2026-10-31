<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'

onLaunch(() => {
  // 外部能力尚未接入；启动阶段不请求登录、上传或订单接口。
})
</script>

<style lang="scss">
page {
  min-height: 100%;
  background: #f6f6f6;
  color: #2f2f2f;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
}

view,
text,
input,
button {
  box-sizing: border-box;
}

button::after {
  border: 0;
}
</style>
