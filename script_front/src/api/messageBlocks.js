/**
 * 把大模型回复切成「文本段」与「代码块」，供 ChatPanel 分别渲染。
 *
 * 关键取舍：**未闭合的代码块也算代码块**。
 * 流式输出时结尾的 ``` 还没到达，若按「必须闭合」判定，这段内容会先以普通文本渲染、
 * 下一帧再变成代码块，用户看到整块文字反复重排闪烁。当成代码块渲染则始终稳定。
 */

const FENCE_OPEN = /^[ \t]*(`{3,})[ \t]*([A-Za-z]*)[ \t]*$/

/**
 * @param {string|null} text
 * @returns {Array<{type:'text'|'code', lang:string, content:string}>}
 */
export function splitBlocks(text) {
  if (!text || !text.trim()) return []

  const lines = text.split('\n')
  const blocks = []
  let textBuf = []
  let codeBuf = null          // {lang, lines:[]}

  const flushText = () => {
    const content = textBuf.join('\n').trim()
    if (content) blocks.push({ type: 'text', lang: '', content })
    textBuf = []
  }
  const flushCode = () => {
    if (!codeBuf) return
    // 代码内容只去首尾空行，行内缩进原样保留（缩进对 Groovy 的可读性有意义）
    blocks.push({ type: 'code', lang: codeBuf.lang, content: codeBuf.lines.join('\n').replace(/^\n+|\n+$/g, '') })
    codeBuf = null
  }

  for (const line of lines) {
    const fence = FENCE_OPEN.exec(line)

    if (codeBuf) {
      // 已在代码块内：遇到同级别的闭合栅栏就结束，否则原样收集
      if (fence) {
        flushCode()
      } else {
        codeBuf.lines.push(line)
      }
      continue
    }

    if (fence) {
      flushText()
      codeBuf = { lang: fence[2] || '', lines: [] }
    } else {
      textBuf.push(line)
    }
  }

  // 走到这里 codeBuf 仍非空，说明代码块没闭合 —— 正是流式中途的常态
  flushText()
  flushCode()
  return blocks
}
