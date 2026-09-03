<script setup>
/**
 * 「应用到编辑器」的确认框。
 *
 * 整篇替换会丢掉当前内容，所以先给用户看两样东西再确认：
 *   1. 变化量（行数 / 字符数增减）—— 一眼看出是大改还是小改
 *   2. 新脚本全文 —— 想只采纳几行的用户可以自己复制
 * 替换后当前内容仍可从编辑器的撤销历史里退回来（任务 13 用 dispatch changes 而非重建，
 * 就是为了保住这个撤销栈），确认框里也提示了这一点。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  script: { type: String, default: '' },
  source: { type: String, default: 'AI' },
  summary: {
    type: Object,
    default: () => ({ currentLines: 0, nextLines: 0, currentChars: 0, nextChars: 0, lineDelta: 0, charDelta: 0 }),
  },
})
const emit = defineEmits(['update:visible', 'confirm', 'cancel'])

function signed(n) {
  return n > 0 ? `+${n}` : String(n)
}
function close() {
  emit('update:visible', false)
  emit('cancel')
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="`采纳脚本建议（来源：${source}）`"
    width="640px"
    :close-on-click-modal="false"
    @update:model-value="(v) => !v && close()"
  >
    <div class="stat">
      <div class="item">
        <span class="k">行数</span>
        <span class="v">{{ summary.currentLines }} → {{ summary.nextLines }}</span>
        <em :class="summary.lineDelta >= 0 ? 'up' : 'down'">{{ signed(summary.lineDelta) }}</em>
      </div>
      <div class="item">
        <span class="k">字符</span>
        <span class="v">{{ summary.currentChars }} → {{ summary.nextChars }}</span>
        <em :class="summary.charDelta >= 0 ? 'up' : 'down'">{{ signed(summary.charDelta) }}</em>
      </div>
    </div>

    <div class="label">替换后的脚本</div>
    <pre class="code">{{ script }}</pre>

    <p class="tip">
      这会替换编辑器里的全部内容。替换后可以在编辑器里按 ⌘Z 撤回，
      但校验结果会作废（脚本内容变了），需要重新校验才能运行。
    </p>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" @click="emit('confirm')">确认替换</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.stat {
  display: flex;
  gap: 20px;
  padding: 10px 14px;
  border-radius: 12px;
  background: var(--input-bg);
  margin-bottom: 14px;
}
.item { display: flex; align-items: baseline; gap: 8px; }
.k { font-size: 12px; color: var(--text-muted); }
.v { font-size: 13px; color: var(--text-main); font-family: ui-monospace, Menlo, monospace; }
em { font-style: normal; font-size: 12px; font-weight: 600; }
.up { color: var(--chip-fg); }
.down { color: #f59e0b; }

.label { font-size: 12px; font-weight: 600; color: var(--text-muted); margin-bottom: 6px; }
.code {
  margin: 0;
  padding: 12px 14px;
  border-radius: 10px;
  background: #fafbff;
  border: 1px solid var(--border-light);
  font-family: ui-monospace, Menlo, monospace;
  font-size: 12px;
  line-height: 1.65;
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 260px;
  overflow: auto;
}
.tip { margin: 12px 0 0; font-size: 12px; line-height: 1.7; color: var(--text-muted); }
</style>
