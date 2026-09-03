import { describe, it, expect } from 'vitest'
import { createSseParser } from '../sseParser'

/** 一次性喂入完整文本，返回解析出的事件 */
function parseAll(text) {
  const p = createSseParser()
  return p.push(text)
}

describe('完整事件的解析', () => {
  it('解析单个事件', () => {
    expect(parseAll('event:message\ndata:好的\n\n')).toEqual([{ event: 'message', data: '好的' }])
  })

  it('一个 chunk 里的多个事件全部解析出来，顺序保持', () => {
    const events = parseAll('event:message\ndata:一\n\nevent:message\ndata:二\n\nevent:done\ndata:\n\n')
    expect(events).toEqual([
      { event: 'message', data: '一' },
      { event: 'message', data: '二' },
      { event: 'done', data: '' },
    ])
  })

  it('event 字段缺失时按 SSE 规范默认为 message', () => {
    expect(parseAll('data:无事件名\n\n')).toEqual([{ event: 'message', data: '无事件名' }])
  })

  it('data 为空串时保留（done 事件就是这样）', () => {
    expect(parseAll('event:done\ndata:\n\n')).toEqual([{ event: 'done', data: '' }])
  })

  it('多个 data 行按规范用换行拼接', () => {
    expect(parseAll('data:第一行\ndata:第二行\n\n')).toEqual([{ event: 'message', data: '第一行\n第二行' }])
  })

  it('冒号后的一个前导空格被去掉，多余的保留', () => {
    expect(parseAll('data: 带一个空格\n\n')[0].data).toBe('带一个空格')
    expect(parseAll('data:不带空格\n\n')[0].data).toBe('不带空格')
    expect(parseAll('data:  两个空格留一个\n\n')[0].data).toBe(' 两个空格留一个')
  })

  it('以冒号开头的注释行被忽略', () => {
    expect(parseAll(':这是注释\ndata:正文\n\n')).toEqual([{ event: 'message', data: '正文' }])
  })

  it('未知字段被忽略不报错', () => {
    expect(parseAll('id:42\nretry:3000\ndata:正文\n\n')).toEqual([{ event: 'message', data: '正文' }])
  })

  it('CRLF 换行也能解析', () => {
    expect(parseAll('event:message\r\ndata:好的\r\n\r\n')).toEqual([{ event: 'message', data: '好的' }])
  })
})

describe('chunk 边界（最容易写错的地方）', () => {
  it('事件被切成两半时不丢字', () => {
    const p = createSseParser()
    expect(p.push('event:message\nda')).toEqual([])          // 还不完整，先不吐
    expect(p.push('ta:好的\n\n')).toEqual([{ event: 'message', data: '好的' }])
  })

  it('切在分隔符中间也能正确处理', () => {
    const p = createSseParser()
    expect(p.push('data:一\n')).toEqual([])
    expect(p.push('\ndata:二\n\n')).toEqual([
      { event: 'message', data: '一' },
      { event: 'message', data: '二' },
    ])
  })

  it('逐字符喂入最终结果与一次性喂入一致', () => {
    const text = 'event:message\ndata:你好\n\nevent:done\ndata:\n\n'
    const p = createSseParser()
    const collected = []
    for (const ch of text) collected.push(...p.push(ch))
    expect(collected).toEqual(parseAll(text))
  })

  it('一个 chunk 里既有完整事件又有残料', () => {
    const p = createSseParser()
    const events = p.push('data:完整\n\ndata:不完整')
    expect(events).toEqual([{ event: 'message', data: '完整' }])
    expect(p.push('\n\n')).toEqual([{ event: 'message', data: '不完整' }])
  })

  it('空 chunk 不产生事件也不破坏 buffer', () => {
    const p = createSseParser()
    p.push('data:一半')
    expect(p.push('')).toEqual([])
    expect(p.push('\n\n')).toEqual([{ event: 'message', data: '一半' }])
  })
})

describe('真实内容不被破坏', () => {
  it('中文、emoji、引号原样保留', () => {
    const text = '好的，「脚本」没问题 ✅ "引号"'
    expect(parseAll(`data:${text}\n\n`)[0].data).toBe(text)
  })

  it('脚本片段里的 ${} 与反引号原样保留', () => {
    const text = 'int age = ${age}\n```groovy\nreturn age\n```'
    // 脚本里的换行在 SSE 里必须被拆成多个 data 行，解析后要还原
    const sse = `data:int age = \${age}\ndata:\`\`\`groovy\ndata:return age\ndata:\`\`\`\n\n`
    expect(parseAll(sse)[0].data).toBe(text)
  })

  it('data 内容里含冒号不会被截断', () => {
    expect(parseAll('data:key:value:更多\n\n')[0].data).toBe('key:value:更多')
  })

  it('没有 event 字段的 done 也能被识别（前端靠 event 名分派）', () => {
    const events = parseAll('event:error\ndata:AI 对话暂时不可用\n\n')
    expect(events[0].event).toBe('error')
    expect(events[0].data).toContain('不可用')
  })
})
