/**
 * 「应用到编辑器」的判定与统计（纯函数，不依赖 Vue）。
 *
 * 两处会用到：AI 审查卡片（需求 4.3.2）与 AI 对话面板（需求 4.3.4）。
 * 判定逻辑收在这里，两个入口的行为就必然一致。
 */

/**
 * 能不能用 next 替换 current。
 * @returns {{ok:boolean, reason:string}} reason 是可直接展示的中文
 */
export function canApply(current, next) {
  const target = (next ?? '').trim()
  if (target === '') {
    return { ok: false, reason: 'AI 没有给出可用的脚本内容' }
  }
  // 用 trim 后比较：只有首尾空白差异的替换没有意义，
  // 却会触发交互规则 #2 的作废，把用户已经拿到的「校验通过」弄没
  if ((current ?? '').trim() === target) {
    return { ok: false, reason: '建议内容与当前脚本一致，无需替换' }
  }
  return { ok: true, reason: '' }
}

/**
 * 变化量统计，给确认框看「这次替换动了多少」。
 * 整篇替换是破坏性操作，给用户一个量级判断再确认。
 */
export function diffSummary(current, next) {
  const cur = current ?? ''
  const nxt = next ?? ''
  const currentLines = countLines(cur)
  const nextLines = countLines(nxt)
  const currentChars = cur.length
  const nextChars = nxt.length
  return {
    currentLines,
    nextLines,
    currentChars,
    nextChars,
    lineDelta: nextLines - currentLines,
    charDelta: nextChars - currentChars,
  }
}

/** 空串算 0 行；末尾换行不多算一行（与编辑器的视觉行数一致） */
function countLines(text) {
  if (text === '') return 0
  return text.replace(/\n$/, '').split('\n').length
}
