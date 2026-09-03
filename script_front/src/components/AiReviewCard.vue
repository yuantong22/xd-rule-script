<script setup>
/**
 * AI 审查卡片（需求 4.3.2 第 3 步）。
 *
 * 三种形态：
 *   available=false → 降级说明（无 Key / Key 失效 / 语法未通过跳过 CR）
 *   available=true 且 suggestedScript 为空 → 只有审查意见
 *   available=true 且 suggestedScript 非空 → 意见 + [应用到编辑器]
 *
 * 交互规则 #5：CR 是建议性质。所以这张卡片永远不出现「阻断运行」的字样，
 * 即使意见里写了「有严重问题」，也不影响运行按钮（那只由 syntaxOk 决定）。
 *
 * text 按纯文本渲染（pre + pre-wrap），不引 markdown 库：
 * CR 提示词（任务 16）会约束模型输出朴素文本，引依赖不值得。
 */
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { PHASE } from '../composables/validationState'

const props = defineProps({
  /** {text, suggestedScript, available} | null */
  aiReview: { type: Object, default: null },
  phase: { type: String, default: PHASE.IDLE },
})
const emit = defineEmits(['apply'])

const review = computed(() => props.aiReview)
const hasSuggestion = computed(() => !!review.value?.suggestedScript?.trim())

/** 语法没过时后端会跳过 CR，卡片要给对应的说法，别让用户以为是 AI 坏了 */
const skippedBySyntax = computed(() => props.phase === PHASE.SYNTAX_FAILED)

async function copySuggestion() {
  try {
    await navigator.clipboard.writeText(review.value.suggestedScript)
    ElMessage.success('建议脚本已复制到剪贴板')
  } catch {
    // 非 https 或用户拒权时 clipboard API 会失败，退化成提示手动选
    ElMessage.warning('浏览器不允许自动复制，请在下方预览框中手动选中复制')
  }
}
</script>

<template>
  <div class="ai-card" :class="{ 'is-off': !review?.available }">
    <div class="head">
      <span class="title">AI 审查</span>
      <el-tag v-if="!review?.available" size="small" type="info" effect="plain">
        {{ skippedBySyntax ? '已跳过' : '不可用' }}
      </el-tag>
      <el-tag v-else size="small" type="success" effect="plain">qwen-plus</el-tag>
      <span class="spacer"></span>
      <span class="note">建议性质，不阻断运行</span>
    </div>

    <el-empty
      v-if="!review"
      description="校验完成后这里会显示 AI 的审查意见"
      :image-size="52"
    />

    <template v-else>
      <pre class="text">{{ review.text }}</pre>

      <!-- 建议脚本预览：折叠起来，别把卡片撑得比编辑器还高 -->
      <el-collapse v-if="hasSuggestion" class="suggest">
        <el-collapse-item name="s">
          <template #title>
            <span class="suggest-title">AI 给出了修改后的完整脚本</span>
          </template>
          <pre class="code">{{ review.suggestedScript }}</pre>
          <div class="actions">
            <el-button type="primary" size="small" @click="emit('apply', review.suggestedScript)">
              应用到编辑器
            </el-button>
            <el-button size="small" @click="copySuggestion">复制</el-button>
          </div>
        </el-collapse-item>
      </el-collapse>
    </template>
  </div>
</template>

<style scoped>
.ai-card {
  margin-top: 12px;
  border-radius: 12px;
  border: 1px solid var(--border-light);
  /* 渐变软底，跟风格三的主色呼应，让 AI 区块与普通信息区分开 */
  background: var(--brand-gradient-soft);
  padding: 12px 14px;
}
.ai-card.is-off { background: var(--input-bg); }

.head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.title { font-size: 13px; font-weight: 600; color: var(--text-main); }
.spacer { flex: 1; }
.note { font-size: 11px; color: var(--text-muted); }

.text {
  margin: 0;
  font-size: 12px;
  line-height: 1.75;
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 130px;
  overflow: auto;
  font-family: inherit;      /* 意见是自然语言，用正文字体比等宽好读 */
}

.suggest { margin-top: 10px; border: none; }
.suggest :deep(.el-collapse-item__header) {
  height: 30px;
  line-height: 30px;
  border: none;
  background: transparent;
  font-size: 12px;
}
.suggest :deep(.el-collapse-item__wrap) { border: none; background: transparent; }
.suggest-title { color: var(--chip-fg); font-weight: 600; }

.code {
  margin: 0;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid var(--border-light);
  font-family: ui-monospace, Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 180px;
  overflow: auto;
}
.actions { display: flex; gap: 8px; margin-top: 8px; }
.actions :deep(.el-button) { border-radius: 16px; }
</style>
