<script setup>
/**
 * 规则工作台总装页。
 *
 * 这里是「编辑器当前内容」的唯一持有者：校验、运行、AI 对话、AI 审查
 * 用的都是这份内容（含未保存的修改），不是数据库里的已保存版本 ——
 * 需求文档交互规则 #3。
 *
 * 本任务只装编辑器 + 保存。校验/运行面板见任务 14，AI 审查卡片见任务 15，
 * 右侧对话面板见任务 19。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ScriptEditor from '../components/ScriptEditor.vue'
import { getRuleDetail, updateRule } from '../api/rule'
import { reportError } from '../api/http'

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
</script>

<template>
  <div class="workbench" v-loading="loading">
    <!-- topbar-slot：任务 14 换成 <TopBar>，这里先内联一个只有保存的简版 -->
    <div class="topbar">
      <div class="logo">脚本规则工作台</div>
      <div class="rule-name">{{ ruleName || '加载中…' }}</div>
      <div class="spacer"></div>
      <div class="updated" v-if="updatedAt">更新于 {{ updatedAt }}</div>
      <el-button class="btn-save" :loading="saving" :disabled="!dirtyForSave" @click="handleSave">
        保存<span class="kbd">⌘S</span>
      </el-button>
    </div>

    <div class="body">
      <div class="left">
        <div class="editor-slot">
          <ScriptEditor v-model="scriptContent" :error-line="null" @save="handleSave" />
        </div>
        <!-- bottom-slot：任务 14 放「校验结果 / 填值 / 运行结果」标签页 -->
        <div class="bottom-slot">
          <el-empty description="校验与运行面板待接入（任务 14）" :image-size="72" />
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
.btn-save { border-radius: 20px; }
.kbd { margin-left: 6px; font-size: 11px; opacity: .6; }

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
