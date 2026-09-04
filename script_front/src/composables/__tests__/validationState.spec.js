import { describe, it, expect } from 'vitest'
import {
  PHASE, initialState, reduce, isRunDisabled, isSaveDisabled, errorLineOf,
} from '../validationState'

const OK_RESULT = {
  syntaxOk: true,
  errorLine: null,
  errorMessage: null,
  placeholders: [{ name: 'age', type: 'int' }, { name: 'level', type: 'String' }],
  aiReview: { text: '没问题', suggestedScript: null, available: true },
}
const BAD_RESULT = {
  syntaxOk: false,
  errorLine: 3,
  errorMessage: '第 3 行括号不匹配',
  placeholders: [],
  aiReview: { text: '语法未通过，已跳过 AI 审查', suggestedScript: null, available: false },
}

const SCRIPT = 'int age = ${age}'

/** 走完一次成功校验，返回该状态 */
function passed(script = SCRIPT, result = OK_RESULT) {
  let s = reduce(initialState(), { type: 'VALIDATE_START' })
  return reduce(s, { type: 'VALIDATE_SUCCESS', token: s.pendingToken, script, result })
}

describe('运行按钮锁定规则（交互规则 #1、#5）', () => {
  it('初始未校验时运行锁定', () => {
    const s = initialState()
    expect(s.phase).toBe(PHASE.IDLE)
    expect(isRunDisabled(s)).toBe(true)
  })

  it('校验中运行锁定', () => {
    const s = reduce(initialState(), { type: 'VALIDATE_START' })
    expect(s.phase).toBe(PHASE.VALIDATING)
    expect(isRunDisabled(s)).toBe(true)
  })

  it('语法通过则解锁运行', () => {
    const s = passed()
    expect(s.phase).toBe(PHASE.PASSED)
    expect(isRunDisabled(s)).toBe(false)
  })

  it('语法失败则锁定，并给出要标红的行号', () => {
    let s = reduce(initialState(), { type: 'VALIDATE_START' })
    s = reduce(s, { type: 'VALIDATE_SUCCESS', token: s.pendingToken, script: SCRIPT, result: BAD_RESULT })
    expect(s.phase).toBe(PHASE.SYNTAX_FAILED)
    expect(isRunDisabled(s)).toBe(true)
    expect(errorLineOf(s)).toBe(3)
  })

  it('CR 不可用不影响解锁（交互规则 #5：AI 审查是建议性质）', () => {
    const s = passed(SCRIPT, { ...OK_RESULT, aiReview: { text: 'AI 未接入', suggestedScript: null, available: false } })
    expect(s.phase).toBe(PHASE.PASSED)
    expect(isRunDisabled(s)).toBe(false)
  })

  it('非语法失败状态下不标红任何行', () => {
    expect(errorLineOf(passed())).toBeNull()
    expect(errorLineOf(initialState())).toBeNull()
  })
})

describe('脚本改动使结果作废（交互规则 #2）', () => {
  it('通过后改了脚本 → stale，结果与运行结果都清空，运行重新锁定', () => {
    const s = passed()
    const next = reduce(s, { type: 'SCRIPT_CHANGED', script: SCRIPT + '\nreturn age' })
    expect(next.phase).toBe(PHASE.STALE)
    expect(next.result).toBeNull()
    expect(isRunDisabled(next)).toBe(true)
  })

  it('语法失败后改了脚本 → stale，错误行标红被清掉', () => {
    let s = reduce(initialState(), { type: 'VALIDATE_START' })
    s = reduce(s, { type: 'VALIDATE_SUCCESS', token: s.pendingToken, script: SCRIPT, result: BAD_RESULT })
    const next = reduce(s, { type: 'SCRIPT_CHANGED', script: 'int a = 1' })
    expect(next.phase).toBe(PHASE.STALE)
    expect(errorLineOf(next)).toBeNull()
  })

  it('内容完全相同时不作废（保存回填、重复 setScript 不能误伤）', () => {
    const s = passed()
    const next = reduce(s, { type: 'SCRIPT_CHANGED', script: SCRIPT })
    expect(next).toBe(s)          // 原样返回，连引用都不变
    expect(next.phase).toBe(PHASE.PASSED)
  })

  it('从没校验过时改脚本仍是 idle，不该报「结果已作废」', () => {
    const next = reduce(initialState(), { type: 'SCRIPT_CHANGED', script: 'anything' })
    expect(next.phase).toBe(PHASE.IDLE)
  })

  it('作废后再次校验能重新解锁', () => {
    let s = reduce(passed(), { type: 'SCRIPT_CHANGED', script: 'int b = 2' })
    s = reduce(s, { type: 'VALIDATE_START' })
    s = reduce(s, { type: 'VALIDATE_SUCCESS', token: s.pendingToken, script: 'int b = 2', result: OK_RESULT })
    expect(s.phase).toBe(PHASE.PASSED)
  })
})

describe('校验请求竞态（CR 要十几秒，用户会继续打字）', () => {
  it('等待期间改脚本，过期响应被丢弃', () => {
    let s = reduce(initialState(), { type: 'VALIDATE_START' })
    const staleToken = s.pendingToken
    s = reduce(s, { type: 'SCRIPT_CHANGED', script: '改过的内容' })
    // 旧请求姗姗来迟，必须被忽略
    const after = reduce(s, { type: 'VALIDATE_SUCCESS', token: staleToken, script: SCRIPT, result: OK_RESULT })
    expect(after).toBe(s)
    expect(isRunDisabled(after)).toBe(true)
  })

  it('连续两次校验，只有最后一次的响应生效', () => {
    let s = reduce(initialState(), { type: 'VALIDATE_START' })
    const firstToken = s.pendingToken
    s = reduce(s, { type: 'VALIDATE_START' })
    const secondToken = s.pendingToken
    expect(secondToken).not.toBe(firstToken)

    // 乱序返回：第二次先回，第一次后回
    s = reduce(s, { type: 'VALIDATE_SUCCESS', token: secondToken, script: 'B', result: OK_RESULT })
    expect(s.phase).toBe(PHASE.PASSED)
    expect(s.validatedScript).toBe('B')

    const late = reduce(s, { type: 'VALIDATE_SUCCESS', token: firstToken, script: 'A', result: BAD_RESULT })
    expect(late).toBe(s)
    expect(late.phase).toBe(PHASE.PASSED)
  })

  it('过期的失败响应同样被丢弃', () => {
    let s = reduce(initialState(), { type: 'VALIDATE_START' })
    const staleToken = s.pendingToken
    s = reduce(s, { type: 'SCRIPT_CHANGED', script: 'x' })
    const after = reduce(s, { type: 'VALIDATE_FAILURE', token: staleToken, message: '网络错误' })
    expect(after.errorMessage).toBeNull()
  })
})

describe('失败与运行不干扰校验结论', () => {
  it('请求失败保持原 phase，只记错误信息（脚本没变，旧结论仍可信）', () => {
    let s = passed()
    s = reduce(s, { type: 'VALIDATE_START' })
    const failed = reduce(s, { type: 'VALIDATE_FAILURE', token: s.pendingToken, message: '无法连接后端服务' })
    // phase 停在 VALIDATING 是有意为之：既不当成通过，也不把已有结论抹掉（权衡详见 Step 2）
    expect(failed.phase).toBe(PHASE.VALIDATING)
    expect(failed.errorMessage).toBe('无法连接后端服务')
    expect(failed.pendingToken).toBe(0)
  })

  it('运行成功不改变校验阶段', () => {
    let s = passed()
    s = reduce(s, { type: 'RUN_SUCCESS', result: { success: true, value: 'vip', errorMessage: null, timeout: false } })
    expect(s.phase).toBe(PHASE.PASSED)
    expect(s.runResult.value).toBe('vip')
  })

  it('运行失败把错误摆到界面上，但不作废校验', () => {
    let s = passed()
    s = reduce(s, { type: 'RUN_FAILURE', message: '脚本执行超过 5 秒，已自动中断' })
    expect(s.phase).toBe(PHASE.PASSED)
    expect(s.runResult).toBeNull()
    expect(s.errorMessage).toContain('5 秒')
  })

  it('清空运行结果', () => {
    let s = reduce(passed(), { type: 'RUN_SUCCESS', result: { success: true, value: '1', errorMessage: null, timeout: false } })
    expect(reduce(s, { type: 'RESET_RUN' }).runResult).toBeNull()
  })
})

describe('占位符填值随校验结果重建', () => {
  it('校验通过后按占位符列表初始化填值，boolean 默认 false', () => {
    const s = passed(SCRIPT, {
      ...OK_RESULT,
      placeholders: [{ name: 'age', type: 'int' }, { name: 'vip', type: 'boolean' }],
    })
    expect(s.params).toEqual({ age: '', vip: 'false' })
  })

  it('重新校验后仍存在的占位符保留用户已填的值', () => {
    let s = passed(SCRIPT, { ...OK_RESULT, placeholders: [{ name: 'age', type: 'int' }] })
    s = { ...s, params: { age: '28' } }
    const next = reduce(s, { type: 'VALIDATE_START' })
    const after = reduce(next, {
      type: 'VALIDATE_SUCCESS', token: next.pendingToken, script: SCRIPT,
      result: { ...OK_RESULT, placeholders: [{ name: 'age', type: 'int' }, { name: 'level', type: 'String' }] },
    })
    expect(after.params).toEqual({ age: '28', level: '' })
  })

  it('被删掉的占位符不会留下脏键', () => {
    let s = passed(SCRIPT, { ...OK_RESULT, placeholders: [{ name: 'age', type: 'int' }, { name: 'gone', type: 'String' }] })
    s = { ...s, params: { age: '28', gone: 'x' } }
    const next = reduce(s, { type: 'VALIDATE_START' })
    const after = reduce(next, {
      type: 'VALIDATE_SUCCESS', token: next.pendingToken, script: SCRIPT,
      result: { ...OK_RESULT, placeholders: [{ name: 'age', type: 'int' }] },
    })
    expect(Object.keys(after.params)).toEqual(['age'])
  })
})

describe('保存按钮锁定规则（交互规则 #9：未校验成功不允许保存）', () => {
  it('IDLE + 有未保存修改 → 保存锁定（从没校验过）', () => {
    expect(isSaveDisabled(initialState(), true)).toBe(true)
  })

  it('IDLE + 无未保存修改 → 保存锁定', () => {
    expect(isSaveDisabled(initialState(), false)).toBe(true)
  })

  it('校验中 + 有未保存修改 → 保存锁定（校验未返回不算通过）', () => {
    const s = reduce(initialState(), { type: 'VALIDATE_START' })
    expect(isSaveDisabled(s, true)).toBe(true)
  })

  it('校验通过 + 有未保存修改 → 保存解锁（唯一可点场景）', () => {
    expect(isSaveDisabled(passed(), true)).toBe(false)
  })

  it('校验通过 + 无未保存修改 → 保存锁定（没东西可存）', () => {
    expect(isSaveDisabled(passed(), false)).toBe(true)
  })

  it('语法失败 + 有未保存修改 → 保存锁定', () => {
    let s = reduce(initialState(), { type: 'VALIDATE_START' })
    s = reduce(s, { type: 'VALIDATE_SUCCESS', token: s.pendingToken, script: SCRIPT, result: BAD_RESULT })
    expect(s.phase).toBe(PHASE.SYNTAX_FAILED)
    expect(isSaveDisabled(s, true)).toBe(true)
  })

  it('STALE（脚本已修改、校验作废）+ 有未保存修改 → 保存锁定，必须重新校验', () => {
    const stale = reduce(passed(), { type: 'SCRIPT_CHANGED', script: 'int a = 1' })
    expect(stale.phase).toBe(PHASE.STALE)
    expect(isSaveDisabled(stale, true)).toBe(true)
  })
})
