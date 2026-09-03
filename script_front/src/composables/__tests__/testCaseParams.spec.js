import { describe, it, expect } from 'vitest'
import { refillParams } from '../testCaseParams'

const PH = [
  { name: 'age', type: 'int' },
  { name: 'vip', type: 'boolean' },
  { name: 'level', type: 'String' },
]

describe('refillParams：用例快照回填到当前占位符', () => {
  it('全部命中时值原样回填', () => {
    const { values, dropped } = refillParams(PH, { age: '28', vip: 'true', level: 'gold' })
    expect(values).toEqual({ age: '28', vip: 'true', level: 'gold' })
    expect(dropped).toEqual([])
  })

  it('用例里缺的占位符给类型默认值，而不是保留当前输入', () => {
    // 「重跑」要求恢复到保存那一刻的状态，所以要把用户后来填的清掉
    const { values } = refillParams(PH, { age: '28' })
    expect(values).toEqual({ age: '28', vip: 'false', level: '' })
  })

  it('用例里多出的键进 dropped 且不进 values', () => {
    const { values, dropped } = refillParams(PH, { age: '28', vip: 'true', level: 'gold', oldField: 'x' })
    expect(values).not.toHaveProperty('oldField')
    expect(dropped).toEqual(['oldField'])
  })

  it('占位符为空时 values 为空、用例的键全部进 dropped', () => {
    const { values, dropped } = refillParams([], { age: '28', level: 'gold' })
    expect(values).toEqual({})
    expect(dropped).toEqual(['age', 'level'])
  })

  it('用例参数为空或未传时全是默认值、dropped 为空', () => {
    expect(refillParams(PH, {}).values).toEqual({ age: '', vip: 'false', level: '' })
    expect(refillParams(PH, null).values).toEqual({ age: '', vip: 'false', level: '' })
    expect(refillParams(PH, undefined).dropped).toEqual([])
  })

  it('placeholders 为 null 时不抛异常', () => {
    expect(() => refillParams(null, { a: '1' })).not.toThrow()
    expect(refillParams(null, { a: '1' })).toEqual({ values: {}, dropped: ['a'] })
  })

  it('值不被 trim、不被转类型（存进去什么就回填什么）', () => {
    const { values } = refillParams(
      [{ name: 'code', type: 'String' }, { name: 'n', type: 'int' }],
      { code: '  007  ', n: '0' },
    )
    // 前后空格与首导零都是有意义的数据，动了就等于篡改用户的用例
    expect(values.code).toBe('  007  ')
    expect(values.n).toBe('0')
  })

  it('values 的键顺序跟 placeholders 走，不跟用例走', () => {
    const { values } = refillParams(PH, { level: 'gold', age: '28', vip: 'true' })
    expect(Object.keys(values)).toEqual(['age', 'vip', 'level'])
  })

  it('不修改入参（纯函数）', () => {
    const saved = { age: '28', gone: 'x' }
    const snapshot = JSON.stringify(saved)
    refillParams(PH, saved)
    expect(JSON.stringify(saved)).toBe(snapshot)
  })
})
