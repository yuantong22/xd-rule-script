<script setup>
/**
 * 测试用例栏（需求 4.3.3）：保存为用例 / 选中回填 / 删除。
 *
 * 插在 ValidationTabs 的「占位符填值」标签页里、ParamsForm 正下方 ——
 * 用例操作的对象就是上面那组输入框，放一起才符合直觉。
 *
 * 交互上刻意用「下拉选中 + [回填] 按钮」而不是「选中即回填」：
 * el-select 的 change 只在值**变化**时触发，选中即回填的话，用户改了填值想
 * 重新回填同一个用例，再点一次是不会有任何反应的（值没变），看起来像坏了。
 * 拆成两步反而没有这个陷阱，[回填] 按钮点几次都生效。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteTestCase, listTestCases, saveTestCase } from '../api/testcase'
import { reportError } from '../api/http'
import { refillParams } from '../composables/testCaseParams'

const props = defineProps({
  ruleId: { type: Number, required: true },
  /** 当前脚本的占位符，校验通过后才有值 */
  placeholders: { type: Array, default: () => [] },
  /** 上方表单里当前的填值，保存用例时拍快照 */
  params: { type: Object, default: () => ({}) },
  /** [运行] 按钮的置灰状态，由 RuleWorkbenchView 拍板（需校验全完成且语法通过），与之前顶栏版本的逻辑完全一致 */
  runDisabled: { type: Boolean, default: true },
  /** [运行] 按钮的 loading 状态（脚本正在执行中） */
  running: { type: Boolean, default: false },
})
const emit = defineEmits(['update:params', 'run'])

const cases = ref([])
const loading = ref(false)
const saving = ref(false)
const selected = ref(null)

/** 没有占位符时保存用例没意义：回填不出任何值，纯噪音，禁用并说明原因 */
const noPlaceholder = computed(() => props.placeholders.length === 0)

onMounted(load)

// 切换规则时重拉列表；本组件随 ValidationTabs 常驻，不 watch 会看到上一条规则的用例
watch(() => props.ruleId, async () => {
  selected.value = null
  await load()
})

async function load() {
  // ValidationTabs 的 ruleId 有默认值 0（父组件没传时的兜底），别为它白发一次请求
  if (!props.ruleId) return
  loading.value = true
  try {
    cases.value = (await listTestCases(props.ruleId)) ?? []
  } catch (e) {
    reportError(e)
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  if (saving.value || noPlaceholder.value) return

  let name
  try {
    const res = await ElMessageBox.prompt(
      '把当前这组填值存成用例，之后可以一键回填重跑。',
      '保存为用例',
      {
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputPlaceholder: '给用例起个名字，如「VIP 成年人」',
        inputValidator: (v) => (v && v.trim() ? true : '用例名称不能为空'),
      },
    )
    name = res.value
  } catch {
    return                        // 用户取消
  }

  saving.value = true
  try {
    // 拍快照：展开一份，否则存的是响应式对象的引用，用户接着改输入框会连带改掉它
    const created = await saveTestCase({
      ruleId: props.ruleId,
      name: name.trim(),
      params: { ...props.params },
    })
    cases.value = [...cases.value, created]
    selected.value = created.id
    ElMessage.success(`用例「${created.name}」已保存`)
  } catch (e) {
    reportError(e)                // 同名被拒时后端给的是可读中文，这里直接弹出
  } finally {
    saving.value = false
  }
}

function handleRefill() {
  const target = cases.value.find((c) => c.id === selected.value)
  if (!target) return

  const { values, dropped } = refillParams(props.placeholders, target.params)
  emit('update:params', values)

  if (dropped.length) {
    // 脚本漂移了：用例里的这些占位符已经不存在。必须说，否则用户以为回填完整了
    ElMessage.warning(`已回填「${target.name}」，但 ${dropped.join('、')} 已不在当前脚本中，已跳过`)
  } else {
    ElMessage.success(`已回填「${target.name}」，点 [运行] 即可重跑`)
  }
}

async function handleDelete() {
  const target = cases.value.find((c) => c.id === selected.value)
  if (!target) return

  try {
    await ElMessageBox.confirm(
      `确定删除用例「${target.name}」吗？删除后无法恢复。`,
      '删除用例',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  try {
    await deleteTestCase(target.id)
    cases.value = cases.value.filter((c) => c.id !== target.id)
    selected.value = null
    ElMessage.success('用例已删除')
  } catch (e) {
    reportError(e)
  }
}
</script>

<template>
  <div class="case-bar">
    <div class="row">
      <span class="label">测试用例</span>

      <el-select
        v-model="selected"
        class="select"
        size="small"
        placeholder="选择用例"
        :loading="loading"
        :disabled="loading || !cases.length"
        clearable
        no-data-text="还没保存过用例"
      >
        <el-option v-for="c in cases" :key="c.id" :label="c.name" :value="c.id">
          <span class="opt-name">{{ c.name }}</span>
          <span class="opt-meta">{{ Object.keys(c.params || {}).length }} 项填值</span>
        </el-option>
      </el-select>

      <el-button size="small" :disabled="!selected" @click="handleRefill">回填</el-button>
      <el-button size="small" :disabled="!selected" @click="handleDelete">删除</el-button>

      <span class="spacer"></span>

      <el-tooltip
        :disabled="!noPlaceholder"
        content="当前脚本没有占位符，没有可保存的填值"
        placement="top"
      >
        <!-- 外面包一层 span：disabled 的按钮不派发鼠标事件，tooltip 直接挂上去不会显示 -->
        <span>
          <el-button
            size="small"
            type="primary"
            plain
            :loading="saving"
            :disabled="noPlaceholder"
            @click="handleSave"
          >保存为用例</el-button>
        </span>
      </el-tooltip>

      <!-- [运行] 从顶栏下沉到这里：紧邻上方填值表单，鼠标不用跑右上角。
           置灰/loading 逻辑全部由父级传入的 runDisabled / running 控制，与原顶栏版本一致 -->
      <el-button
        size="small"
        type="primary"
        :loading="running"
        :disabled="runDisabled"
        @click="emit('run')"
      >运行</el-button>
    </div>

    <!-- 两个提示的顺序要紧：没占位符时 [保存为用例] 是禁用的，
         若还提示「填好值后点保存」就自相矛盾了，所以先判 noPlaceholder -->
    <div class="hint is-warn" v-if="noPlaceholder">
      当前脚本没有占位符，用例功能不可用（先点校验，在「校验结果」里确认占位符）。
    </div>
    <div class="hint" v-else-if="!cases.length && !loading">
      还没有用例。填好上面的值后点 [保存为用例]，之后可以一键回填重跑。
    </div>
  </div>
</template>

<style scoped>
.case-bar { margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--border-light); }

.row { display: flex; align-items: center; gap: 8px; }
.label { font-size: 12px; color: var(--text-muted); flex-shrink: 0; }
.select { width: 200px; }
.spacer { flex: 1; }

.opt-name { float: left; }
.opt-meta { float: right; font-size: 11px; color: var(--text-muted); margin-left: 16px; }

.hint { margin-top: 6px; font-size: 11px; color: var(--text-muted); line-height: 1.6; }
.hint.is-warn { color: var(--danger); }

.case-bar :deep(.el-button) { border-radius: 14px; }
.case-bar :deep(.el-select__wrapper) { border-radius: 12px; }
</style>
