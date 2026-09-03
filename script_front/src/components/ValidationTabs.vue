<script setup>
/**
 * 编辑器下方的三标签页：校验结果 / 占位符填值 / 运行结果（需求 4.3 线框图）。
 *
 * 标签会自动跳到当前最该看的那一页：语法失败跳「校验结果」，
 * 校验通过跳「填值」（没占位符则直接跳「运行结果」），运行完跳「运行结果」。
 * 用户手动切走后不再抢（只在状态跃迁的那一刻切一次）。
 *
 * AI 审查卡片（任务 15）与测试用例栏（任务 20）分别插在本组件标了 slot 注释的位置。
 */
import { computed, ref, watch } from 'vue'
import { PHASE } from '../composables/validationState'
import ParamsForm from './ParamsForm.vue'
import RunResultCard from './RunResultCard.vue'
import AiReviewCard from './AiReviewCard.vue'

const props = defineProps({
  phase: { type: String, default: PHASE.IDLE },
  result: { type: Object, default: null },
  placeholders: { type: Array, default: () => [] },
  aiReview: { type: Object, default: null },
  runResult: { type: Object, default: null },
  errorMessage: { type: String, default: null },
  validating: { type: Boolean, default: false },
  running: { type: Boolean, default: false },
  params: { type: Object, default: () => ({}) },
})
const emit = defineEmits(['update:params', 'run', 'apply-suggested'])

const active = ref('check')

const syntaxLine = computed(() => {
  if (props.phase === PHASE.SYNTAX_FAILED && props.result?.errorLine) {
    return `第 ${props.result.errorLine} 行：${props.result.errorMessage}`
  }
  return ''
})

// 状态跃迁时自动切页
watch(() => props.phase, (p) => {
  if (p === PHASE.SYNTAX_FAILED) active.value = 'check'
  else if (p === PHASE.PASSED) active.value = props.placeholders.length ? 'params' : 'run'
})
watch(() => props.runResult, (r) => { if (r) active.value = 'run' })
watch(() => props.running, (r) => { if (r) active.value = 'run' })
</script>

<template>
  <div class="tabs">
    <el-tabs v-model="active" class="inner">
      <!-- ---------- 校验结果 ---------- -->
      <el-tab-pane name="check">
        <template #label>
          <span>校验结果
            <el-badge v-if="phase === PHASE.SYNTAX_FAILED" is-dot type="danger" class="dot" />
          </span>
        </template>

        <div v-if="validating" class="loading">
          <!-- 图标改纯 CSS 转圈：本项目未在 main.js 全局注册 @element-plus/icons-vue，
               而它只是 element-plus 的传递依赖（package.json 未声明），不为一个 loading 图标引入幽灵依赖 -->
          <span class="spinner"></span>
          正在校验：语法 → 提取占位符 → AI 审查，全部完成才会解锁运行…
        </div>

        <template v-else-if="phase === PHASE.IDLE">
          <el-empty description="点右上角 [校验] 开始（校验通过前运行按钮锁定）" :image-size="60" />
        </template>

        <template v-else-if="phase === PHASE.STALE">
          <el-empty description="脚本已修改，校验结果作废，请重新校验" :image-size="60" />
        </template>

        <template v-else>
          <el-alert
            :type="phase === PHASE.PASSED ? 'success' : 'error'"
            :title="phase === PHASE.PASSED ? '语法校验通过' : '语法校验未通过'"
            :description="syntaxLine"
            :closable="false"
            show-icon
          />

          <div class="ph-block" v-if="placeholders.length">
            <div class="ph-title">识别到 {{ placeholders.length }} 个占位符</div>
            <div class="chips">
              <span v-for="p in placeholders" :key="p.name" class="chip">
                <code>${{ '{' }}{{ p.name }}{{ '}' }}</code>
                <em>{{ p.type }}</em>
              </span>
            </div>
          </div>
          <div class="ph-block" v-else-if="phase === PHASE.PASSED">
            <div class="ph-title">这个脚本没有占位符</div>
          </div>

          <AiReviewCard
            :ai-review="aiReview"
            :phase="phase"
            @apply="(s) => emit('apply-suggested', s)"
          />
        </template>
      </el-tab-pane>

      <!-- ---------- 占位符填值 ---------- -->
      <el-tab-pane label="占位符填值" name="params">
        <ParamsForm
          :placeholders="placeholders"
          :model-value="params"
          @update:model-value="(v) => emit('update:params', v)"
        />
        <!-- testcase-slot：任务 20 放 <TestCaseBar>（保存为用例 / 回填 / 删除） -->
      </el-tab-pane>

      <!-- ---------- 运行结果 ---------- -->
      <el-tab-pane label="运行结果" name="run">
        <RunResultCard :result="runResult" :running="running" :error-message="errorMessage" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.tabs { height: 100%; }
/* Element 的 tabs 自带上下 padding，压掉一些，280px 的底栏放不下太多留白 */
.inner :deep(.el-tabs__header) { margin-bottom: 10px; }
.inner :deep(.el-tabs__content) { overflow: visible; }
.dot { margin-left: 4px; }

.loading {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-muted);
}
/* 纯 CSS 转圈，替代未注册的 <Loading> 图标 */
.spinner {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  border: 2px solid var(--border-light);
  border-top-color: #8b5cf6;
  border-radius: 50%;
  animation: spin .8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.ph-block { margin-top: 12px; }
.ph-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: 12px;
  background: var(--chip-bg);
}
.chip code { font-size: 12px; color: var(--chip-fg); }
.chip em { font-size: 11px; color: var(--text-muted); font-style: normal; }
</style>
