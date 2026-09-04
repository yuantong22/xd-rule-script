<script setup>
/**
 * 规则工作台总装页。
 *
 * 这里是「编辑器当前内容」的唯一持有者：校验、运行、AI 对话、AI 审查
 * 用的都是这份内容（含未保存的修改），不是数据库里的已保存版本 ——
 * 需求文档交互规则 #3。
 *
 * 校验/运行的全部判定在 useValidationState 里，本组件只做装配。
 * 右侧对话面板见任务 19。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ScriptEditor from '../components/ScriptEditor.vue'
import TopBar from '../components/TopBar.vue'
import ValidationTabs from '../components/ValidationTabs.vue'
import ApplyScriptDialog from '../components/ApplyScriptDialog.vue'
import ChatPanel from '../components/ChatPanel.vue'
import { getRuleDetail, updateRule } from '../api/rule'
import { reportError } from '../api/http'
import { useValidationState } from '../composables/useValidationState'
import { useApplyScript } from '../composables/useApplyScript'
import { checkAll } from '../composables/paramRules'
import { loadCachedParams, saveCachedParams } from '../composables/paramsCache'
import { isSaveDisabled } from '../composables/validationState'

const props = defineProps({ id: { type: String, required: true } })

const router = useRouter()
const ruleId = computed(() => Number(props.id))
const ruleName = ref('')
const updatedAt = ref('')
const scriptContent = ref('')
const loading = ref(true)
const saving = ref(false)
const loadFailed = ref(false)

/** 已保存的脚本快照，用来判断「有未保存的修改」 */
const savedScript = ref('')
const dirtyForSave = computed(() => scriptContent.value !== savedScript.value)

/**
 * 保存按钮锁定（交互规则 #9）：必须校验通过 + 有未保存修改。
 * 判定收口在 validationState.isSaveDisabled，TopBar 和 handleSave（拦 Ctrl+S）共用同一份逻辑。
 */
const saveDisabled = computed(() => isSaveDisabled(v.state.value, dirtyForSave.value))

const v = useValidationState(() => scriptContent.value)

// 内容一变就同步给状态机；内容真变了才作废（交互规则 #2）
watch(scriptContent, (next) => v.syncScript(next))

/**
 * 从 localStorage 回填上次的填值（需求 4.3.3 / 交互规则 #8）：
 * 用户反馈「每次刷新都变初始值」很烦。在页面初始化 / 切换规则时读一次缓存，
 * 直接注入 params。**不需要在这里显式过滤脚本漂移**：随后用户点【校验】时，
 * validationState 的 reduce 里 pickParams 会按新的占位符列表自动重建
 * （保留仍存在的、丢弃已删的、新增的给默认值），语义天然正确。
 */
function hydrateParamsFromCache() {
  const cached = loadCachedParams(ruleId.value)
  // 空缓存不去动 params，保留 reduce 给的默认值（boolean 预填 false 等）
  if (Object.keys(cached).length) v.setParams(cached)
}

// params 一变就写缓存，下次进来 hydrateParamsFromCache 能读回。
// deep 因为 setParams 是整对象替换，但 ParamsForm 里也可能就地改属性，两手准备
watch(() => v.params.value, (p) => saveCachedParams(ruleId.value, p), { deep: true })

// vue-router 切换同一 route 的 params 时组件会被复用，onMounted 不会重跑，
// 靠这个 watch 保证切换规则时也重新读缓存
watch(ruleId, () => hydrateParamsFromCache())

const apply = useApplyScript({
  getScript: () => scriptContent.value,
  setScript: (next) => { scriptContent.value = next },
  // 替换后校验必然作废（交互规则 #2），主动问一句要不要立刻重校验，
  // 别让用户对着变灰的运行按钮猜原因
  onApplied: async () => {
    try {
      await ElMessageBox.confirm(
        '脚本已替换，需要重新校验才能运行。现在校验吗？（会调用一次大模型审查）',
        '重新校验',
        { confirmButtonText: '现在校验', cancelButtonText: '稍后', type: 'info' },
      )
      await v.validate()
      if (v.errorMessage.value) reportError(new Error(v.errorMessage.value))
    } catch {
      // 用户点「稍后」或关掉弹窗，什么都不做
    }
  },
})

/** AI 审查卡片与 AI 对话面板（任务 19）共用的应用入口 */
function handleApplySuggested(script) {
  apply.requestApply(script, 'AI 审查')
}

/** 对话里的代码块应用到编辑器，与 AI 审查卡片共用同一套确认流程（任务 15） */
function handleApplyFromChat(script) {
  apply.requestApply(script, 'AI 对话')
}

onMounted(() => {
  loadDetail()
  // 与 loadDetail 并行：hydrate 只依赖 ruleId，不需要等详情拉回
  hydrateParamsFromCache()
})

async function loadDetail() {
  loading.value = true
  loadFailed.value = false
  try {
    const detail = await getRuleDetail(ruleId.value)
    ruleName.value = detail.name
    updatedAt.value = detail.updatedAt
    scriptContent.value = detail.scriptContent ?? ''
    savedScript.value = scriptContent.value
  } catch (e) {
    loadFailed.value = true
    reportError(e)
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  if (saving.value) return
  // 交互规则 #9：未校验通过不允许保存。按钮已置灰，但 Ctrl+S 能绕过按钮直接触发这里，
  // 所以必须再拦一道，并区分「没改动」和「未校验」两种原因给不同提示
  if (saveDisabled.value) {
    if (!dirtyForSave.value) {
      ElMessage.info('脚本没有变化，无需保存')
    } else {
      ElMessage.warning('请先点击【校验】，校验通过后才能保存')
    }
    return
  }
  saving.value = true
  try {
    const detail = await updateRule({ ruleId: ruleId.value, scriptContent: scriptContent.value })
    savedScript.value = detail.scriptContent ?? scriptContent.value
    updatedAt.value = detail.updatedAt
    ElMessage.success('已保存')
  } catch (e) {
    reportError(e)
  } finally {
    saving.value = false
  }
}

async function handleRun() {
  // 需求 4.3.3：前端校验不通过不允许提交
  const checked = checkAll(v.placeholders.value, v.params.value)
  if (!checked.ok) {
    ElMessage.warning(checked.message)
    return
  }
  await v.run(checked.values)
}

async function handleValidate() {
  await v.validate()
  if (v.errorMessage.value) reportError(new Error(v.errorMessage.value))
}

/**
 * 顶栏 [← 返回]：回列表页。需求文档交互规则 #7：
 * 编辑器有未保存修改时弹二次确认，避免误点丢代码。
 * 取消 / 关掉弹窗都停留在当前页，什么都不做。
 */
async function handleBack() {
  if (!dirtyForSave.value) {
    router.push('/')
    return
  }
  try {
    await ElMessageBox.confirm(
      '编辑器有未保存的修改，离开后这些修改会丢失。确定返回列表页吗？',
      '有未保存的修改',
      { confirmButtonText: '离开', cancelButtonText: '取消', type: 'warning' },
    )
    router.push('/')
  } catch {
    // 用户点「取消」或关掉弹窗，什么都不做
  }
}
</script>

<template>
  <div class="workbench" v-loading="loading">
    <TopBar
      :rule-name="ruleName"
      :updated-at="updatedAt"
      :dirty-for-save="dirtyForSave"
      :saving="saving"
      :phase="v.phase.value"
      :validating="v.validating.value"
      :save-disabled="saveDisabled"
      @save="handleSave"
      @validate="handleValidate"
      @back="handleBack"
    />

    <div class="body">
      <div class="left">
        <div class="editor-slot">
          <ScriptEditor
            v-model="scriptContent"
            :error-line="v.errorLine.value"
            @save="handleSave"
          />
        </div>
        <div class="bottom-slot">
          <ValidationTabs
            :rule-id="ruleId"
            :phase="v.phase.value"
            :result="v.result.value"
            :placeholders="v.placeholders.value"
            :ai-review="v.aiReview.value"
            :run-result="v.runResult.value"
            :error-message="v.errorMessage.value"
            :validating="v.validating.value"
            :running="v.running.value"
            :run-disabled="v.runDisabled.value"
            :params="v.params.value"
            @update:params="v.setParams"
            @run="handleRun"
            @apply-suggested="handleApplySuggested"
          />
        </div>
      </div>
      <div class="right">
        <ChatPanel
          :rule-id="ruleId"
          :get-script="() => scriptContent"
          @apply-script="handleApplyFromChat"
        />
      </div>
    </div>

    <el-dialog v-model="loadFailed" title="加载失败" width="420px" :close-on-click-modal="false">
      <p>规则详情没能加载出来，可能是规则已被删除。</p>
      <template #footer>
        <el-button @click="loadDetail">重试</el-button>
        <el-button type="primary" @click="$router.push('/')">回列表页</el-button>
      </template>
    </el-dialog>

    <ApplyScriptDialog
      v-model:visible="apply.dialogVisible.value"
      :script="apply.pendingScript.value"
      :source="apply.pendingSource.value"
      :summary="apply.summary.value"
      @confirm="apply.confirmApply"
      @cancel="apply.cancelApply"
    />
  </div>
</template>

<style scoped>
.workbench { height: 100%; display: flex; flex-direction: column; }

/* 顶栏样式（.topbar / .logo / .badge / .btn 等）已随 <TopBar> 抽到 TopBar.vue 自己的 scoped style，
   这里只保留布局类（.workbench/.body/.left/.editor-slot/.bottom-slot/.right），任务 13 调好的 flex 别动 */

.body {
  flex: 1;
  display: flex;
  gap: 16px;
  padding: 16px 20px 20px;
  min-height: 0;          /* 允许子项收缩，否则编辑器会把页面撑出滚动条 */
  overflow: hidden;
}
.left {
  flex: 1;
  min-width: 0;          /* 关键：不给 0 的话 CodeMirror 长行会撑破 flex，把右侧面板挤没 */
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.editor-slot { flex: 1; min-height: 0; }
.bottom-slot {
  height: 280px;
  flex-shrink: 0;
  background: var(--panel-bg);
  border-radius: var(--panel-radius);
  box-shadow: var(--panel-shadow);
  padding: 16px;
  overflow: auto;
}
.right {
  width: 380px;
  flex-shrink: 0;
  background: var(--panel-bg);
  border-radius: var(--panel-radius);
  box-shadow: var(--panel-shadow);
  padding: 16px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
</style>
