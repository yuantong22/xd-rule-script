/**
 * 「应用到编辑器」的统一入口。
 *
 * 流程：requestApply → 判定能不能替换 → 弹确认框（带变化量）→ confirmApply 真正替换。
 * 整篇替换脚本是破坏性操作（用户可能只想采纳其中几行），所以必须过一道确认，
 * 不做静默替换。
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { canApply, diffSummary } from './applyScript'

/**
 * @param {object}   opts
 * @param {() => string}          opts.getScript  取编辑器当前内容
 * @param {(next: string) => void} opts.setScript  写回编辑器（会触发状态机作废）
 * @param {() => void|Promise<void>} [opts.onApplied] 替换成功后的回调，通常用来问「是否重新校验」
 */
export function useApplyScript({ getScript, setScript, onApplied }) {
  const dialogVisible = ref(false)
  const pendingScript = ref('')
  /** 来源标签，显示在确认框标题上：'AI 审查' / 'AI 对话' */
  const pendingSource = ref('')

  const summary = computed(() => diffSummary(getScript(), pendingScript.value))

  function requestApply(script, source = 'AI') {
    const verdict = canApply(getScript(), script)
    if (!verdict.ok) {
      ElMessage.info(verdict.reason)
      return false
    }
    pendingScript.value = script
    pendingSource.value = source
    dialogVisible.value = true
    return true
  }

  async function confirmApply() {
    const script = pendingScript.value
    dialogVisible.value = false
    setScript(script)
    // 清空待定内容，否则下次打开确认框会闪一下上一次的脚本
    pendingScript.value = ''
    pendingSource.value = ''
    ElMessage.success('已替换编辑器内容，校验结果已作废')
    await onApplied?.()
  }

  function cancelApply() {
    dialogVisible.value = false
    pendingScript.value = ''
    pendingSource.value = ''
  }

  return { dialogVisible, pendingScript, pendingSource, summary, requestApply, confirmApply, cancelApply }
}
