<script setup>
/**
 * 工作台顶栏：规则名 + 状态徽标 + [校验] [运行] [保存]。
 * 按钮的可用性完全由父组件传入的 phase / runDisabled 决定，本组件不做业务判定。
 */
import { computed } from 'vue'
import { PHASE } from '../composables/validationState'

const props = defineProps({
  ruleName: { type: String, default: '' },
  updatedAt: { type: String, default: '' },
  dirtyForSave: { type: Boolean, default: false },
  saving: { type: Boolean, default: false },
  phase: { type: String, default: PHASE.IDLE },
  validating: { type: Boolean, default: false },
  running: { type: Boolean, default: false },
  runDisabled: { type: Boolean, default: true },
})
const emit = defineEmits(['save', 'validate', 'run'])

/** 状态徽标：文案 + 配色 class */
const badge = computed(() => {
  switch (props.phase) {
    case PHASE.VALIDATING:
      return { text: '校验中…（含 AI 审查，可能要十几秒）', cls: 'is-loading' }
    case PHASE.PASSED:
      return { text: '校验通过，可运行', cls: 'is-pass' }
    case PHASE.SYNTAX_FAILED:
      return { text: '语法错误，请修正后重新校验', cls: 'is-fail' }
    case PHASE.STALE:
      return { text: '脚本已修改，校验结果已作废', cls: 'is-stale' }
    default:
      return { text: '尚未校验，运行按钮已锁定', cls: 'is-idle' }
  }
})
</script>

<template>
  <div class="topbar">
    <div class="logo">脚本规则工作台</div>
    <div class="rule-name">{{ ruleName || '加载中…' }}</div>
    <div class="badge" :class="badge.cls">{{ badge.text }}</div>
    <div class="spacer"></div>
    <div class="updated" v-if="updatedAt">更新于 {{ updatedAt }}</div>

    <el-button class="btn" :loading="validating" @click="emit('validate')">校验</el-button>
    <!-- 运行锁定原因由徽标说明，按钮上不再堆 tooltip（交互规则 #1） -->
    <el-button
      class="btn"
      type="primary"
      :loading="running"
      :disabled="runDisabled"
      @click="emit('run')"
    >运行</el-button>
    <el-button class="btn" :loading="saving" :disabled="!dirtyForSave" @click="emit('save')">
      保存<span class="kbd">⌘S</span>
    </el-button>
  </div>
</template>

<style scoped>
.topbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  height: 56px;
  flex-shrink: 0;
  background: var(--brand-gradient);
  box-shadow: var(--topbar-shadow);
  color: #fff;
}
.logo { font-size: 15px; font-weight: 600; opacity: .92; }
.rule-name { font-size: 15px; font-weight: 600; }
.spacer { flex: 1; }
.updated { font-size: 12px; opacity: .75; }
.btn { border-radius: 20px; }
.kbd { margin-left: 6px; font-size: 11px; opacity: .6; }

/* 徽标：半透明白底，四种状态靠左侧小圆点区分色 */
.badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 12px;
  background: rgba(255, 255, 255, .18);
  white-space: nowrap;
}
.badge::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.is-idle    { color: rgba(255, 255, 255, .85); }
.is-loading { color: #ffe9a8; }
.is-pass    { color: #c9f7d4; }
.is-fail    { color: #ffd2d2; }
.is-stale   { color: #ffe0b8; }
</style>
