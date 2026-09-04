<script setup>
/**
 * 工作台顶栏：[← 返回] + 规则名 + 状态徽标 + [校验] [保存]。
 * [运行] 按钮已下沉到「占位符填值」页底部（TestCaseBar），紧邻填值表单，缩短鼠标动线。
 * 按钮的可用性完全由父组件传入的 phase 决定，本组件不做业务判定。
 * 返回按钮只 emit('back')，「未保存修改弹二次确认」的脏数据拦截逻辑由父级
 * RuleWorkbenchView 拍板（因为只有它知道 dirtyForSave）。
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
  /** 保存按钮是否锁定，由父级调 isSaveDisabled(state, dirtyForSave) 算好传入（交互规则 #9） */
  saveDisabled: { type: Boolean, default: true },
})
const emit = defineEmits(['save', 'validate', 'back'])

/** [保存] 悬浮提示：区分「没改动」和「未校验通过」两种锁定原因，别让用户对着灰按钮猜 */
const saveTitle = computed(() => {
  if (!props.dirtyForSave) return '脚本没有变化，无需保存'
  if (props.phase !== PHASE.PASSED) return '请先点击【校验】，校验通过后才能保存'
  return '保存当前脚本（⌘S）'
})

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
    <!-- 返回列表按钮：胶囊形，与右侧 [校验][运行][保存] 形状统一，避免「孤立圆形图标钮」的零碎感；
         但颜色采用透底 + 白边 + 白字，视觉层级低于右侧白底实心行动钮（导航属于次要入口）。
         图标内联 SVG（path 取自 Element Plus 官方 ArrowLeft），项目刻意不引 @element-plus/icons-vue 幽灵依赖 -->
    <el-button
      class="back-btn"
      title="返回列表页"
      aria-label="返回列表页"
      @click="emit('back')"
    >
      <svg class="back-icon" viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
        <path fill="currentColor" d="M609.408 149.376 277.76 489.6a32 32 0 0 0 0 44.672l331.648 340.352a29.12 29.12 0 0 0 41.728 0 30.592 30.592 0 0 0 0-42.72L339.264 511.936l311.872-319.872a30.592 30.592 0 0 0 0-42.688 29.12 29.12 0 0 0-41.728 0z"/>
      </svg>
      <span>返回</span>
    </el-button>
    <div class="logo">脚本规则工作台</div>
    <div class="rule-name">{{ ruleName || '加载中…' }}</div>
    <div class="badge" :class="badge.cls">{{ badge.text }}</div>
    <div class="spacer"></div>
    <div class="updated" v-if="updatedAt">更新于 {{ updatedAt }}</div>

    <el-button class="btn" :loading="validating" @click="emit('validate')">校验</el-button>
    <!-- [运行] 已下沉到填值页底部（TestCaseBar），顶栏只保留 [校验] [保存] -->
    <el-button class="btn" :loading="saving" :disabled="saveDisabled" :title="saveTitle" @click="emit('save')">
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

/* 返回按钮：胶囊形（与右侧 .btn 的 border-radius: 20px 统一）+ 透底 + 白边 + 白字，
   形状上跟右侧三个行动钮成一组，颜色上层级低一档（导航是次要入口，不抢视觉重点） */
.back-btn {
  border-radius: 20px;
  padding: 8px 14px;
  height: auto;
  background: rgba(255, 255, 255, .12);
  border-color: rgba(255, 255, 255, .55);
  color: #fff;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.back-btn:hover,
.back-btn:focus {
  background: rgba(255, 255, 255, .22);
  border-color: #fff;
  color: #fff;
}
.back-icon {
  width: 14px;
  height: 14px;
  display: block;
}

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
