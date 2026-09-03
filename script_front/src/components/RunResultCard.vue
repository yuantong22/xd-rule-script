<script setup>
/**
 * 运行结果卡片。展示契约来自任务 11 的 GroovyEngineService.run：
 *   success=true            → 绿色，显示 value（空串显示「（无返回值）」）
 *   timeout=true            → 橙色，5 秒超时已中断
 *   success=false 带【安全拦截】→ 红色，编译期/运行期黑名单拦下
 *   success=false 其他       → 红色，中文原因（无堆栈、无英文异常类名）
 * errorMessage 一律是后端翻译好的中文，前端原样展示，不做二次加工。
 */
import { computed } from 'vue'

const props = defineProps({
  /** {success, value, errorMessage, timeout} | null */
  result: { type: Object, default: null },
  running: { type: Boolean, default: false },
  errorMessage: { type: String, default: null },
})

const SECURITY_MARKER = '【安全拦截】'

const kind = computed(() => {
  if (props.running) return 'running'
  if (props.result?.success) return 'success'
  if (props.result?.timeout) return 'timeout'
  if (props.result || props.errorMessage) return 'fail'
  return 'empty'
})

const isSecurity = computed(() =>
  (props.result?.errorMessage ?? props.errorMessage ?? '').includes(SECURITY_MARKER),
)

const text = computed(() => {
  if (kind.value === 'success') {
    const v = props.result.value
    // 后端把 null 与「脚本没写 return」都归一成空串（任务 11 的 stringify）
    return v === '' || v === null || v === undefined ? '（无返回值）' : v
  }
  if (kind.value === 'timeout') return props.result.errorMessage
  return props.result?.errorMessage ?? props.errorMessage ?? ''
})

const title = computed(() => ({
  running: '脚本运行中…',
  success: '运行成功',
  timeout: '执行超时',
  fail: isSecurity.value ? '被安全策略拦截' : '运行失败',
  empty: '还没运行过',
}[kind.value]))
</script>

<template>
  <div class="run-card" :class="kind">
    <div class="head">
      <span class="dot"></span>
      <span class="title">{{ title }}</span>
      <el-tag v-if="isSecurity" size="small" type="danger" effect="plain">安全拦截</el-tag>
    </div>

    <div v-if="kind === 'running'" class="body">
      <el-skeleton :rows="2" animated />
      <p class="hint">沙箱内执行，最长 5 秒，超时会自动中断</p>
    </div>

    <el-empty v-else-if="kind === 'empty'" description="填好参数后点右上角 [运行]" :image-size="60" />

    <pre v-else class="body value">{{ text }}</pre>
  </div>
</template>

<style scoped>
.run-card {
  border-radius: 12px;
  border: 1px solid var(--border-light);
  padding: 12px 14px;
  background: var(--input-bg);
}
.head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--text-muted); }
.title { font-size: 13px; font-weight: 600; color: var(--text-main); }

.success { border-color: #cdeed6; background: #f4fbf6; }
.success .dot { background: #22c55e; }
.timeout { border-color: #fbe6c8; background: #fffaf2; }
.timeout .dot { background: #f59e0b; }
.fail { border-color: #f6d3d3; background: var(--danger-bg); }
.fail .dot { background: var(--danger); }

.body {
  margin: 0;
  font-family: ui-monospace, Menlo, monospace;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-main);
  white-space: pre-wrap;      /* 结果可能多行，别挤成一行 */
  word-break: break-all;
  max-height: 150px;
  overflow: auto;
}
.hint { margin: 8px 0 0; font-size: 12px; color: var(--text-muted); }
</style>
