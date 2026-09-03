/**
 * 状态机的 Vue 包装：把 reduce 挂到 ref 上，并把两个接口调用包进来。
 * 组件只用这一层，不直接碰 reducer。
 */
import { computed, ref } from 'vue'
import { runScript, validateScript } from '../api/rule'
import { PHASE, errorLineOf, initialState, isRunDisabled, reduce } from './validationState'

/**
 * @param {() => string} getScript 取编辑器当前内容（含未保存修改，交互规则 #3）
 */
export function useValidationState(getScript) {
  const state = ref(initialState())
  const running = ref(false)

  const dispatch = (action) => { state.value = reduce(state.value, action) }

  const phase = computed(() => state.value.phase)
  const validating = computed(() => state.value.phase === PHASE.VALIDATING)
  const runDisabled = computed(() => isRunDisabled(state.value))
  const errorLine = computed(() => errorLineOf(state.value))
  const result = computed(() => state.value.result)
  const placeholders = computed(() => state.value.result?.placeholders ?? [])
  const aiReview = computed(() => state.value.result?.aiReview ?? null)
  const runResult = computed(() => state.value.runResult)
  const errorMessage = computed(() => state.value.errorMessage)
  const params = computed(() => state.value.params)

  async function validate() {
    if (validating.value) return          // 防连点
    const script = getScript()
    dispatch({ type: 'VALIDATE_START' })
    const token = state.value.pendingToken
    try {
      const res = await validateScript(script)
      dispatch({ type: 'VALIDATE_SUCCESS', token, script, result: res })
    } catch (e) {
      const message = e instanceof Error && e.message ? e.message : '校验失败，请稍后重试'
      dispatch({ type: 'VALIDATE_FAILURE', token, message })
    }
  }

  /**
   * @param {Record<string,string>} values 已经过 paramRules.checkAll 校验的填值
   */
  async function run(values) {
    if (running.value || runDisabled.value) return
    running.value = true
    const script = getScript()
    try {
      const res = await runScript(script, values)
      dispatch({ type: 'RUN_SUCCESS', result: res })
    } catch (e) {
      const message = e instanceof Error && e.message ? e.message : '运行失败，请稍后重试'
      dispatch({ type: 'RUN_FAILURE', message })
    } finally {
      running.value = false
    }
  }

  /** 编辑器内容变化时调用；内容真变了才作废（交互规则 #2） */
  const syncScript = (current) => dispatch({ type: 'SCRIPT_CHANGED', script: current })

  const setParams = (next) => { state.value = { ...state.value, params: next } }

  const resetRun = () => dispatch({ type: 'RESET_RUN' })

  return {
    state, phase, validating, running, runDisabled, errorLine,
    result, placeholders, aiReview, runResult, errorMessage, params,
    validate, run, syncScript, setParams, resetRun,
  }
}
