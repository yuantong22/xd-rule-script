/**
 * 校验 / 运行状态机（纯 reducer，不依赖 Vue）。
 *
 * 需求文档交互规则 #1、#2、#4、#5 的全部判定都收口在这里，组件只负责渲染：
 *   #1 未校验或校验未通过 → 运行按钮锁定
 *   #2 脚本内容被修改 → 校验结果作废，运行重新锁定
 *   #4 校验（含大模型 CR）同步等待，全部完成才解锁
 *   #5 AI 审查是建议性质，只看 syntaxOk 决定解锁，CR 报错不阻断
 *
 * 刻意拆成「纯 reducer + 薄 Vue 包装」两层：reducer 不 import vue，
 * 单测可以直接跑分支，不需要 mount 组件、不需要 jsdom。
 */

export const PHASE = {
  IDLE: 'idle',                      // 从没校验过
  VALIDATING: 'validating',          // 校验中（CR 可能十几秒）
  PASSED: 'passed',                  // 语法通过，运行已解锁
  SYNTAX_FAILED: 'syntax-failed',    // 语法错误，运行锁定，编辑器标红 errorLine
  STALE: 'stale',                    // 校验过但脚本又改了，结论作废
}

export function initialState() {
  return {
    phase: PHASE.IDLE,
    /** 上次校验用的脚本快照，判定「内容是否被修改」的唯一依据 */
    validatedScript: null,
    /** 已发放的请求序号 */
    token: 0,
    /** 当前有效请求的序号；不匹配的响应一律丢弃 */
    pendingToken: 0,
    result: null,       // ValidateResponse
    runResult: null,    // RunResponse
    errorMessage: null,
    /** 占位符填值，键为占位符名，值一律是字符串（后端按字符串收） */
    params: {},
  }
}

/** 运行按钮是否锁定：只有 PASSED 解锁（交互规则 #1、#5） */
export function isRunDisabled(state) {
  return state.phase !== PHASE.PASSED
}

/**
 * 保存按钮是否锁定（交互规则 #9）：必须校验通过 + 有未保存修改。
 *
 * dirtyForSave 由 View 层维护（scriptContent !== savedScript），不在 state 里 ——
 * state 只管校验/运行的状态机，保存的「有没有东西可存」是编辑器关注点，两者不耦合。
 *
 * 这里跟 isRunDisabled 的区别：运行只要 PASSED（无论有没有未保存修改），
 * 保存额外要求 dirtyForSave（没改动就不需要存）。
 */
export function isSaveDisabled(state, dirtyForSave) {
  return state.phase !== PHASE.PASSED || !dirtyForSave
}

/** 编辑器要标红的行号，只有语法失败时有值 */
export function errorLineOf(state) {
  if (state.phase !== PHASE.SYNTAX_FAILED) return null
  return state.result?.errorLine ?? null
}

export function reduce(state, action) {
  switch (action.type) {
    case 'VALIDATE_START': {
      const token = state.token + 1
      return {
        ...state,
        token,
        pendingToken: token,
        phase: PHASE.VALIDATING,
        errorMessage: null,
        // 重新校验意味着上一轮运行结果已经不能代表当前脚本了
        runResult: null,
      }
    }

    case 'VALIDATE_SUCCESS': {
      // 过期响应：等待期间用户又改了脚本，这份结论对应的已不是当前内容
      if (action.token !== state.pendingToken) return state
      const { script, result } = action
      return {
        ...state,
        pendingToken: 0,
        // 语法失败也记快照。不记的话用户不改脚本、光重复点校验会被 SCRIPT_CHANGED 误判成 stale
        validatedScript: script,
        result,
        phase: result.syntaxOk ? PHASE.PASSED : PHASE.SYNTAX_FAILED,
        params: pickParams(state.params, result.placeholders),
        errorMessage: null,
      }
    }

    case 'VALIDATE_FAILURE': {
      if (action.token !== state.pendingToken) return state
      // 刻意不回退 phase：脚本没变，上一次的成功结论仍然可信，
      // 不该因为一次网络抖动就把用户已经拿到的「校验通过」抹掉
      return { ...state, pendingToken: 0, errorMessage: action.message }
    }

    case 'SCRIPT_CHANGED': {
      // 内容与上次校验的快照一致（保存回填、重复 setScript），别误伤 —— 连引用都不变，Vue 可跳过重渲染
      if (action.script === state.validatedScript) return state

      // 到这里内容确实变了。要分开判断两件独立的事：有没有「历史结论」、有没有「在途请求」。
      const wasValidated = state.validatedScript !== null  // 之前校验出过结论（成功或失败都算）
      const hasPending = state.pendingToken !== 0          // 有一个请求还在飞

      // 从没校验过、也没有在途请求：纯粹是 idle 状态下打字，无事发生，原样返回
      if (!wasValidated && !hasPending) return state

      // 有在途请求就作废它：内容都变了，迟到的响应对应的已不是当前脚本，靠 token 归零让它对不上号而被丢弃。
      // 首次校验途中打字也必须走这条 —— 这正是计划书原实现漏掉的坑：它先判 validatedScript===null 就 return，
      // 而此时 pendingToken 已经是新号却没被清，过期响应回来时 token 仍对得上，会把界面错误地推到 PASSED。
      const invalidated = { ...state, pendingToken: 0 }

      // 首次校验途中（还没结论）：仅丢弃在途请求，phase 维持原样（validating），不该谎报「结果已作废」
      if (!wasValidated) return invalidated

      // 之前有结论 → 作废它：标 stale，清空校验结果与运行结果，运行重新锁定
      return {
        ...invalidated,
        phase: PHASE.STALE,
        result: null,
        runResult: null,
        validatedScript: null,
      }
    }

    case 'RUN_SUCCESS':
      return { ...state, runResult: action.result, errorMessage: null }

    case 'RUN_FAILURE':
      return { ...state, runResult: null, errorMessage: action.message }

    case 'RESET_RUN':
      return { ...state, runResult: null }

    default:
      return state
  }
}

/**
 * 按新的占位符列表重建填值：仍存在的保留用户已填的值，新增的给类型默认值，
 * 被删掉的键直接丢弃（不然会带着脏键提交给后端）。
 */
function pickParams(oldParams, placeholders) {
  const next = {}
  for (const p of placeholders ?? []) {
    next[p.name] = oldParams?.[p.name] ?? defaultValueFor(p.type)
  }
  return next
}

/** boolean 用下拉，给个默认值；其余留空强制用户填 */
function defaultValueFor(type) {
  return type === 'boolean' ? 'false' : ''
}
