import { describe, it, expect } from 'vitest'
import { splitBlocks } from '../messageBlocks'

describe('splitBlocks：把回复切成文本段与代码块', () => {
  it('纯文本返回一个 text 块', () => {
    expect(splitBlocks('这段脚本没问题')).toEqual([{ type: 'text', lang: '', content: '这段脚本没问题' }])
  })

  it('含一个代码块时切成 text/code/text 三段', () => {
    const blocks = splitBlocks('建议改成：\n```groovy\nreturn 1\n```\n以上。')
    expect(blocks).toHaveLength(3)
    expect(blocks[0]).toMatchObject({ type: 'text' })
    expect(blocks[0].content).toContain('建议改成')
    expect(blocks[1]).toMatchObject({ type: 'code', lang: 'groovy', content: 'return 1' })
    expect(blocks[2].content).toContain('以上')
  })

  it('多个代码块全部识别', () => {
    const blocks = splitBlocks('```groovy\nA\n```\n中间\n```groovy\nB\n```')
    expect(blocks.filter((b) => b.type === 'code')).toHaveLength(2)
    expect(blocks.filter((b) => b.type === 'code')[1].content).toBe('B')
  })

  it('未闭合的代码块也当成 code（流式打字机的关键）', () => {
    // 模型正在输出，结尾的 ``` 还没到；此时若当普通文本渲染，
    // 下一帧变成代码块会导致整块内容重排闪烁
    const blocks = splitBlocks('脚本如下：\n```groovy\nint a = 1\nreturn a')
    expect(blocks).toHaveLength(2)
    expect(blocks[1]).toMatchObject({ type: 'code', lang: 'groovy' })
    expect(blocks[1].content).toBe('int a = 1\nreturn a')
  })

  it('无语言标记的代码块 lang 为空串', () => {
    expect(splitBlocks('```\nreturn 1\n```')[0]).toMatchObject({ type: 'code', lang: '' })
  })

  it('代码块内的占位符原样保留', () => {
    const blocks = splitBlocks('```groovy\nint age = ${age}\n```')
    expect(blocks[0].content).toContain('${age}')
  })

  it('空文本返回空数组（流刚开始时不该渲染空气泡）', () => {
    expect(splitBlocks('')).toEqual([])
    expect(splitBlocks(null)).toEqual([])
  })

  it('只有空白时返回空数组', () => {
    expect(splitBlocks('   \n  ')).toEqual([])
  })

  it('代码块内容为空时仍保留该块（模型刚打出 ``` 的瞬间）', () => {
    const blocks = splitBlocks('```groovy\n')
    expect(blocks).toHaveLength(1)
    expect(blocks[0]).toMatchObject({ type: 'code', lang: 'groovy', content: '' })
  })

  it('文本段首尾空白被 trim 但代码块内容不动', () => {
    const blocks = splitBlocks('  说明  \n```groovy\n  return 1  \n```')
    expect(blocks[0].content).toBe('说明')
    // 代码里的缩进是有意义的，不能 trim 掉行首空格
    expect(blocks[1].content).toContain('  return 1  ')
  })

  it('行内单反引号不被当成代码块', () => {
    const blocks = splitBlocks('建议把 `level` 改名')
    expect(blocks).toHaveLength(1)
    expect(blocks[0].type).toBe('text')
  })
})
