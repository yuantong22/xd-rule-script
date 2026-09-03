/**
 * 手写 SSE 解析器。
 *
 * 为什么不用 EventSource：它只支持 GET，本项目所有接口一律 POST（Global Constraints）。
 * 所以只能 fetch + ReadableStream 自己按 SSE 规范解析。
 *
 * 核心是 buffer：网络分片不按语义边界走，一个事件可能被切成多个 chunk，
 * 必须累积到出现空行（事件结束标志）才能吐出，残料留到下一次 push。
 */

/** 事件结束标志。CRLF 也要认，所以两种都作为分隔依据 */
const BOUNDARY = /\r?\n\r?\n/

/**
 * @returns {{push: (chunk: string) => Array<{event: string, data: string}>}}
 */
export function createSseParser() {
  let buffer = ''

  function push(chunk) {
    if (!chunk) return []
    buffer += chunk

    const events = []
    let match
    // 循环切出所有已完整的事件；lastIndex 由 replace 手动推进
    while ((match = BOUNDARY.exec(buffer)) !== null) {
      const rawEvent = buffer.slice(0, match.index)
      buffer = buffer.slice(match.index + match[0].length)
      const parsed = parseEvent(rawEvent)
      if (parsed) events.push(parsed)
      BOUNDARY.lastIndex = 0        // 全局正则不必用，但显式归零避免 lastIndex 残留
    }
    return events
  }

  return { push }
}

/**
 * 解析一个完整事件的文本块。
 * 全是注释或没有 data 字段时返回 null（不该往 UI 推空事件）。
 */
function parseEvent(rawEvent) {
  let event = 'message'          // SSE 规范：event 字段缺省时事件类型为 message
  const dataLines = []
  let hasData = false

  for (const line of rawEvent.split(/\r?\n/)) {
    if (line === '') continue
    if (line.startsWith(':')) continue        // 注释行（常用于心跳保活）

    const colon = line.indexOf(':')
    if (colon < 0) continue                   // 没有冒号的行按规范应视为 field=整行、value=空，实践中是脏数据，跳过

    const field = line.slice(0, colon)
    // 规范：冒号后若有空格，只去掉**一个**
    let value = line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)

    if (field === 'event') {
      event = value
    } else if (field === 'data') {
      hasData = true
      dataLines.push(value)
    }
    // id / retry 等字段本项目用不到，忽略
  }

  if (!hasData) return null
  return { event, data: dataLines.join('\n') }
}
