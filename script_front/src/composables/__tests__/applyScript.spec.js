import { describe, it, expect } from 'vitest'
import { canApply, diffSummary } from '../applyScript'

describe('canApply：能不能替换', () => {
  it('正常情况放行', () => {
    expect(canApply('int a = 1', 'int a = 2').ok).toBe(true)
  })

  it('AI 没给出脚本时拦住，并说明原因', () => {
    expect(canApply('int a = 1', null).ok).toBe(false)
    expect(canApply('int a = 1', '').ok).toBe(false)
    expect(canApply('int a = 1', '   \n  ').ok).toBe(false)
    expect(canApply('int a = 1', null).reason).toContain('没有给出')
  })

  it('内容与当前完全一致时拦住（点了等于白点，别触发作废）', () => {
    const r = canApply('int a = 1', 'int a = 1')
    expect(r.ok).toBe(false)
    expect(r.reason).toContain('一致')
  })

  it('只有首尾空白差异时也算一致（避免无意义的作废）', () => {
    const r = canApply('int a = 1', '  int a = 1\n')
    expect(r.ok).toBe(false)
    expect(r.reason).toContain('一致')
  })

  it('当前脚本为空时允许应用（等于首次填入）', () => {
    expect(canApply('', 'int a = 1').ok).toBe(true)
  })

  it('reason 是可直接展示的中文', () => {
    const r = canApply('x', 'x')
    expect(r.reason).not.toMatch(/[A-Za-z]{4,}/)   // 不该夹英文异常词
  })
})

describe('diffSummary：给确认框展示的变化量', () => {
  it('统计行数与字符数', () => {
    const s = diffSummary('a\nb', 'a\nb\nc')
    expect(s.currentLines).toBe(2)
    expect(s.nextLines).toBe(3)
    expect(s.lineDelta).toBe(1)
    expect(s.currentChars).toBe(3)
    expect(s.nextChars).toBe(5)
    expect(s.charDelta).toBe(2)
  })

  it('变少时 delta 为负', () => {
    const s = diffSummary('a\nb\nc', 'a')
    expect(s.lineDelta).toBe(-2)
    expect(s.charDelta).toBe(-4)
  })

  it('空串按 0 行 0 字符算，不按 1 行算', () => {
    const s = diffSummary('', 'a')
    expect(s.currentLines).toBe(0)
    expect(s.currentChars).toBe(0)
    expect(s.nextLines).toBe(1)
  })

  it('current 为 null 时不抛异常', () => {
    expect(() => diffSummary(null, 'a')).not.toThrow()
    expect(diffSummary(null, 'a').currentLines).toBe(0)
  })

  it('末尾换行不多算一行', () => {
    // 'a\nb\n' 在编辑器里是 2 行内容，不是 3 行
    expect(diffSummary('a\nb\n', 'x').currentLines).toBe(2)
  })
})
