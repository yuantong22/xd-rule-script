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
import { ElMessage } from 'element-plus'
import ScriptEditor from '../components/ScriptEditor.vue'
import TopBar from '../components/TopBar.vue'
import ValidationTabs from '../components/ValidationTabs.vue'
import { getRuleDetail, updateRule } from '../api/rule'
import { reportError } from '../api/http'
import { useValidationState } from '../composables/useValidationState'
import { checkAll } from '../composables/paramRules'

const props = defineProps({ id: { type: String, required: true } })

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

const v = useValidationState(() => scriptContent.value)

// 内容一变就同步给状态机；内容真变了才作废（交互规则 #2）
watch(scriptContent, (next) => v.syncScript(next))

onMounted(loadDetail)

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
      :running="v.running.value"
      :run-disabled="v.runDisabled.value"
      @save="handleSave"
      @validate="handleValidate"
      @run="handleRun"
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
            :phase="v.phase.value"
            :result="v.result.value"
            :placeholders="v.placeholders.value"
            :ai-review="v.aiReview.value"
            :run-result="v.runResult.value"
            :error-message="v.errorMessage.value"
            :validating="v.validating.value"
            :running="v.running.value"
            :params="v.params.value"
            @update:params="v.setParams"
            @run="handleRun"
          />
        </div>
      </div>
      <!-- right-slot：任务 19 放 ChatPanel -->
      <div class="right">
        <el-empty description="AI 对话面板待接入（任务 19）" :image-size="72" />
      </div>
    </div>

    <el-dialog v-model="loadFailed" title="加载失败" width="420px" :close-on-click-modal="false">
      <p>规则详情没能加载出来，可能是规则已被删除。</p>
      <template #footer>
        <el-button @click="loadDetail">重试</el-button>
        <el-button type="primary" @click="$router.push('/')">回列表页</el-button>
      </template>
    </el-dialog>
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
