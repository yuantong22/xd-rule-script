<script setup>
/**
 * 占位符填值表单（需求 4.3.3）：按推断出的类型渲染控件。
 * 「能不能提交」的判定在 composables/paramRules.js，本组件只做渲染与错误提示。
 */
import { computed } from 'vue'
import { controlOf, checkValue } from '../composables/paramRules'

const props = defineProps({
  /** Array<{name:string, type:string}> */
  placeholders: { type: Array, default: () => [] },
  /** Record<string,string> */
  modelValue: { type: Object, default: () => ({}) },
})
const emit = defineEmits(['update:modelValue'])

const rows = computed(() =>
  props.placeholders.map((p) => ({
    ...p,
    control: controlOf(p.type),
    // 边填边提示：只在该格已经有内容或已被碰过时才显示，避免一进页面满屏红
    error: checkValue(p, props.modelValue?.[p.name]),
  })),
)

function onInput(name, value) {
  emit('update:modelValue', { ...props.modelValue, [name]: value ?? '' })
}
</script>

<template>
  <div class="params-form">
    <el-empty v-if="!rows.length" description="这个脚本没有占位符，直接运行即可" :image-size="60" />

    <div v-for="row in rows" :key="row.name" class="row">
      <div class="label">
        <span class="name">{{ row.name }}</span>
        <span class="type">{{ row.type }}</span>
      </div>

      <el-select
        v-if="row.control === 'select'"
        class="control"
        :model-value="modelValue[row.name]"
        @update:model-value="(v) => onInput(row.name, v)"
      >
        <el-option label="true" value="true" />
        <el-option label="false" value="false" />
      </el-select>

      <el-input
        v-else
        class="control"
        :model-value="modelValue[row.name]"
        :placeholder="row.type === 'String' ? '任意文本' : '请输入' + row.type"
        :class="{ 'has-error': modelValue[row.name] && row.error }"
        @update:model-value="(v) => onInput(row.name, v)"
      />

      <div class="err" v-if="modelValue[row.name] && row.error">{{ row.error }}</div>
    </div>
  </div>
</template>

<style scoped>
.params-form { display: flex; flex-direction: column; gap: 10px; }
.row {
  display: grid;
  grid-template-columns: 160px 1fr;
  align-items: center;
  gap: 4px 12px;
}
.label { display: flex; align-items: center; gap: 8px; min-width: 0; }
.name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-main);
  font-family: ui-monospace, Menlo, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.type {
  flex-shrink: 0;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  background: var(--chip-bg);
  color: var(--chip-fg);
}
.control { width: 100%; }
.err {
  grid-column: 2;
  font-size: 12px;
  color: var(--danger);
}
:deep(.has-error .el-input__wrapper) { box-shadow: 0 0 0 1px var(--danger) inset; }
</style>
