import { describe, it, expect } from 'vitest'
import { checkValue, checkAll, defaultValueFor, controlOf } from '../paramRules'

describe('按类型校验填值格式（需求 4.3.3）', () => {
  it('int 接受负数与零', () => {
    expect(checkValue({ name: 'age', type: 'int' }, '28')).toBeNull()
    expect(checkValue({ name: 'age', type: 'int' }, '-3')).toBeNull()
    expect(checkValue({ name: 'age', type: 'int' }, '0')).toBeNull()
  })

  it('int 拒绝小数、非数字与空值', () => {
    expect(checkValue({ name: 'age', type: 'int' }, '3.5')).toContain('整数')
    expect(checkValue({ name: 'age', type: 'int' }, 'abc')).toContain('整数')
    expect(checkValue({ name: 'age', type: 'int' }, '')).toContain('请填写')
    expect(checkValue({ name: 'age', type: 'int' }, null)).toContain('请填写')
  })

  it('int 超范围给出可读提示', () => {
    expect(checkValue({ name: 'n', type: 'int' }, '2147483648')).toContain('超出 int 范围')
    expect(checkValue({ name: 'n', type: 'int' }, '2147483647')).toBeNull()
  })

  it('long 只校验整数格式，不做范围（JS Number 精度不够，交给后端）', () => {
    expect(checkValue({ name: 'ts', type: 'long' }, '9999999999999999999')).toBeNull()
    expect(checkValue({ name: 'ts', type: 'long' }, '1.5')).toContain('整数')
  })

  it('double 接受小数与科学计数法', () => {
    expect(checkValue({ name: 'r', type: 'double' }, '3.14')).toBeNull()
    expect(checkValue({ name: 'r', type: 'double' }, '-0.5')).toBeNull()
    expect(checkValue({ name: 'r', type: 'double' }, '1e5')).toBeNull()
    expect(checkValue({ name: 'r', type: 'double' }, '7')).toBeNull()
    expect(checkValue({ name: 'r', type: 'double' }, 'abc')).toContain('数字')
  })

  it('boolean 只认 true / false', () => {
    expect(checkValue({ name: 'v', type: 'boolean' }, 'true')).toBeNull()
    expect(checkValue({ name: 'v', type: 'boolean' }, 'false')).toBeNull()
    expect(checkValue({ name: 'v', type: 'boolean' }, 'TRUE')).toContain('true 或 false')
  })

  it('String 不校验格式，但空值仍要拦', () => {
    expect(checkValue({ name: 's', type: 'String' }, '任意内容 ${x} "引号"')).toBeNull()
    expect(checkValue({ name: 's', type: 'String' }, '   ')).toContain('请填写')
  })

  it('提示里带上占位符名，用户知道该改哪一格', () => {
    expect(checkValue({ name: 'level', type: 'int' }, 'vip')).toContain('level')
  })

  it('首尾空白被容忍并在校验前 trim', () => {
    expect(checkValue({ name: 'age', type: 'int' }, '  28  ')).toBeNull()
  })
})

describe('批量校验', () => {
  const PH = [{ name: 'age', type: 'int' }, { name: 'level', type: 'String' }]

  it('全部合法时返回 trim 过的字符串映射', () => {
    const r = checkAll(PH, { age: ' 28 ', level: 'vip' })
    expect(r.ok).toBe(true)
    expect(r.values).toEqual({ age: '28', level: 'vip' })
  })

  it('有一格不合法就整体不放行，并给出第一条中文原因', () => {
    const r = checkAll(PH, { age: 'x', level: 'vip' })
    expect(r.ok).toBe(false)
    expect(r.message).toContain('age')
    expect(r.values).toEqual({})
  })

  it('缺键按空值处理', () => {
    expect(checkAll(PH, { age: '28' }).ok).toBe(false)
  })

  it('没有占位符时直接放行，values 为空对象', () => {
    expect(checkAll([], {})).toEqual({ ok: true, message: '', values: {} })
  })

  it('values 全是字符串（后端 params 是 Map<String,String>）', () => {
    const r = checkAll([{ name: 'n', type: 'int' }], { n: 28 })
    expect(r.ok).toBe(true)
    expect(r.values.n).toBe('28')
    expect(typeof r.values.n).toBe('string')
  })
})

describe('控件与默认值', () => {
  it('boolean 用下拉，int/long/double 用数字框，String 用文本框（需求 L98）', () => {
    // D2 回归（验收项 13）：int 原本返回 'input'，ParamsForm 渲染成 <input type="text">，
    // 而需求 L98 要求 int/long/double 是「数字输入框」。数值类型应返回 'number'，
    // 让 ParamsForm 绑 type="number"；String 仍用普通文本框。
    expect(controlOf('boolean')).toBe('select')
    expect(controlOf('int')).toBe('number')
    expect(controlOf('long')).toBe('number')
    expect(controlOf('double')).toBe('number')
    expect(controlOf('String')).toBe('input')
  })

  it('boolean 默认 false，其余默认空串', () => {
    expect(defaultValueFor('boolean')).toBe('false')
    expect(defaultValueFor('int')).toBe('')
    expect(defaultValueFor('String')).toBe('')
  })
})
