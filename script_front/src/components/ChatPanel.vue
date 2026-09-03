<script setup>
/**
 * AI 对话面板（需求 4.3.4）。
 *
 * 一条规则固定一个会话，所以这里没有会话列表、不能新建/删除会话，只有 [清空对话]。
 * 每次发消息自动带上编辑器当前内容（含未保存修改）—— 由父组件传入的 getScript 取。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearChat, getChatHistory, sendChatStream } from '../api/chat'
import { splitBlocks } from '../api/messageBlocks'
import { reportError } from '../api/http'

const props = defineProps({
  ruleId: { type: Number, required: true },
  /** 取编辑器当前内容，发消息时作为上下文一并带上 */
  getScript: { type: Function, required: true },
})
const emit = defineEmits(['apply-script'])

/** {role:'user'|'assistant', content:string, streaming?:boolean} */
const messages = ref([])
const input = ref('')
const sending = ref(false)
const loadingHistory = ref(true)
const scroller = ref(null)

let stream = null

const canSend = computed(() => !sending.value && input.value.trim().length > 0)

onMounted(loadHistory)
onBeforeUnmount(() => stream?.abort())

// 切换规则时必须中断上一个流并重新加载，否则两个会话的内容会串在一起
watch(() => props.ruleId, async () => {
  stream?.abort()
  stream = null
  sending.value = false
  messages.value = []
  await loadHistory()
})

async function loadHistory() {
  loadingHistory.value = true
  try {
    const list = await getChatHistory(props.ruleId)
    messages.value = (list ?? []).map((m) => ({ role: m.role, content: m.content }))
    await scrollToBottom()
  } catch (e) {
    reportError(e)
  } finally {
    loadingHistory.value = false
  }
}

async function handleSend() {
  if (!canSend.value) return
  const text = input.value.trim()
  input.value = ''

  messages.value.push({ role: 'user', content: text })
  // 先占一个空的 assistant 气泡，流式内容往里追加，打字机效果才连续
  messages.value.push({ role: 'assistant', content: '', streaming: true })
  sending.value = true
  await scrollToBottom()

  stream = sendChatStream(
    { ruleId: props.ruleId, message: text, scriptContent: props.getScript() },
    {
      onMessage: (chunk) => {
        const last = messages.value[messages.value.length - 1]
        if (last && last.role === 'assistant') last.content += chunk
        scrollToBottom()
      },
      onDone: () => finishStream(),
      onError: (msg) => {
        const last = messages.value[messages.value.length - 1]
        if (last && last.role === 'assistant' && !last.content) {
          // 一个字都没收到就失败：把气泡改成错误提示，别留个空气泡
          last.content = msg
          last.isError = true
        } else {
          ElMessage.error(msg)
        }
        finishStream()
      },
    },
  )
}

function finishStream() {
  const last = messages.value[messages.value.length - 1]
  if (last && last.role === 'assistant') {
    last.streaming = false
    // 完全没内容的助手气泡直接移除，历史回显时也不该有空气泡
    if (!last.content.trim() && !last.isError) messages.value.pop()
  }
  sending.value = false
  stream = null
  scrollToBottom()
}

async function handleClear() {
  try {
    await ElMessageBox.confirm(
      '会清除这条规则的全部对话记录与模型记忆，且无法恢复。确定继续吗？',
      '清空对话',
      { confirmButtonText: '确定清空', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return                      // 用户取消
  }
  try {
    stream?.abort()
    stream = null
    sending.value = false
    await clearChat(props.ruleId)
    messages.value = []
    ElMessage.success('对话已清空')
  } catch (e) {
    reportError(e)
  }
}

function handleKeydown(e) {
  // Enter 发送，Shift+Enter 换行（与主流对话产品一致）
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    handleSend()
  }
}

async function scrollToBottom() {
  await nextTick()
  const el = scroller.value
  if (el) el.scrollTop = el.scrollHeight
}
</script>

<template>
  <div class="chat-panel">
    <div class="head">
      <span class="title">AI 对话助手</span>
      <span class="spacer"></span>
      <el-button
        link
        size="small"
        :disabled="loadingHistory || !messages.length"
        @click="handleClear"
      >清空对话</el-button>
    </div>

    <div ref="scroller" class="scroller" v-loading="loadingHistory">
      <div v-if="!messages.length && !loadingHistory" class="empty">
        <p class="empty-title">让 AI 帮你写规则脚本</p>
        <p class="empty-hint">每次提问会自动带上编辑器里的当前脚本作为上下文</p>
        <div class="suggests">
          <span class="suggest" @click="input = '帮我写一个判断年龄是否成年的规则'">判断年龄是否成年</span>
          <span class="suggest" @click="input = '这个脚本有什么潜在问题'">检查当前脚本的问题</span>
          <span class="suggest" @click="input = '给这个脚本加上空值保护'">加上空值保护</span>
        </div>
      </div>

      <!--
        v-memo 是必需的，不是优化洁癖：流式期间每个 chunk 都会触发整个 v-for 重渲染，
        而每条消息都要跑一次 splitBlocks（带正则的全文扫描）。消息多了会明显卡顿。
        加了 memo 后只有内容真的变了的那一条（即最后一条）会重算。
        数组里必须列全所有影响渲染的字段：role 决定气泡左右与配色，
        而 :key 用的是下标，loadHistory 整体替换后同一下标可能换了角色。
      -->
      <div
        v-for="(m, i) in messages"
        :key="i"
        v-memo="[m.role, m.content, m.streaming, m.isError]"
        class="msg"
        :class="[m.role, { 'is-error': m.isError }]"
      >
        <div class="bubble">
          <template v-for="(b, j) in splitBlocks(m.content)" :key="j">
            <pre v-if="b.type === 'text'" class="text">{{ b.content }}</pre>
            <div v-else class="code-block">
              <div class="code-head">
                <span class="lang">{{ b.lang || 'code' }}</span>
                <span class="spacer"></span>
                <!-- 流式未结束时不给应用按钮：此时脚本还是半截，替换进编辑器会毁掉用户内容 -->
                <el-button
                  v-if="!m.streaming && b.content.trim()"
                  link
                  size="small"
                  type="primary"
                  @click="emit('apply-script', b.content)"
                >应用到编辑器</el-button>
                <span v-else class="generating">生成中…</span>
              </div>
              <pre class="code">{{ b.content }}</pre>
            </div>
          </template>

          <span v-if="m.streaming" class="cursor"></span>
        </div>
      </div>
    </div>

    <div class="composer">
      <el-input
        v-model="input"
        type="textarea"
        :rows="3"
        resize="none"
        :disabled="sending"
        placeholder="问点什么…（Enter 发送，Shift+Enter 换行）"
        @keydown="handleKeydown"
      />
      <div class="actions">
        <span class="hint" v-if="sending">AI 正在回复…</span>
        <span class="spacer"></span>
        <el-button type="primary" :loading="sending" :disabled="!canSend" @click="handleSend">
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-panel { height: 100%; display: flex; flex-direction: column; min-height: 0; }

.head { display: flex; align-items: center; gap: 8px; padding-bottom: 10px; flex-shrink: 0; }
.title { font-size: 14px; font-weight: 600; color: var(--text-main); }
.spacer { flex: 1; }

.scroller { flex: 1; overflow-y: auto; min-height: 0; padding-right: 4px; }

.empty { padding: 24px 8px; text-align: center; }
.empty-title { margin: 0 0 6px; font-size: 13px; font-weight: 600; color: var(--text-main); }
.empty-hint { margin: 0 0 14px; font-size: 12px; color: var(--text-muted); line-height: 1.6; }
.suggests { display: flex; flex-direction: column; gap: 6px; }
.suggest {
  padding: 6px 10px;
  border-radius: 12px;
  background: var(--chip-bg);
  color: var(--chip-fg);
  font-size: 12px;
  cursor: pointer;
  transition: opacity .15s;
}
.suggest:hover { opacity: .78; }

.msg { display: flex; margin-bottom: 10px; }
.msg.user { justify-content: flex-end; }
.msg.assistant { justify-content: flex-start; }

.bubble {
  max-width: 92%;
  padding: 8px 12px;
  border-radius: 14px;
  font-size: 13px;
  line-height: 1.7;
}
.user .bubble {
  background: var(--brand-gradient);
  color: #fff;
  border-bottom-right-radius: 4px;
}
.assistant .bubble {
  background: var(--input-bg);
  border: 1px solid var(--border-light);
  color: var(--text-main);
  border-bottom-left-radius: 4px;
}
.is-error .bubble { background: var(--danger-bg); border-color: #f6d3d3; color: var(--danger); }

.text { margin: 0; font-family: inherit; white-space: pre-wrap; word-break: break-word; }

.code-block {
  margin: 8px 0;
  border-radius: 10px;
  background: #fff;
  border: 1px solid var(--border-light);
  overflow: hidden;
}
.code-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px;
  background: var(--brand-gradient-soft);
  border-bottom: 1px solid var(--border-light);
}
.lang { font-size: 11px; color: var(--text-muted); font-family: ui-monospace, Menlo, monospace; }
.generating { font-size: 11px; color: var(--text-muted); }
.code {
  margin: 0;
  padding: 8px 10px;
  font-family: ui-monospace, Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow: auto;
}

/* 打字机光标 */
.cursor {
  display: inline-block;
  width: 6px;
  height: 14px;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--brand-from);
  animation: blink 1s steps(2, start) infinite;
}
@keyframes blink { to { visibility: hidden; } }

.composer { flex-shrink: 0; padding-top: 10px; }
.composer :deep(.el-textarea__inner) {
  border-radius: 12px;
  background: var(--input-bg);
  font-size: 13px;
}
.actions { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.hint { font-size: 12px; color: var(--text-muted); }
.actions :deep(.el-button) { border-radius: 18px; }
</style>
