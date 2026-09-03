/**
 * 占位符填值的格式校验（需求 4.3.3）。
 *
 * 纯函数，不依赖 Vue 也不依赖 Element Plus —— 表单组件只负责渲染，
 * 「能不能提交」的判定全在这里，这样分支可以单测。
 *
 * 值一律按字符串处理：后端 RunRequest.params 是 Map<String,String>，
 * 由 GroovyEngineService 按推断出的类型转成字面量（任务 8、11）。
 */

export const INT_PATTERN = /^-?\d+$/
export const DOUBLE_PATTERN = /^-?\d+(\.\d+)?([eE][-+]?\d+)?$/
export const INT_MIN = -2147483648
export const INT_MAX = 2147483647

/** 该类型用什么控件 */
export function controlOf(type) {
  return type === 'boolean' ? 'select' : 'input'
}

export function defaultValueFor(type) {
  return type === 'boolean' ? 'false' : ''
}

/**
 * 校验单个填值。
 * @returns {string|null} null 表示通过，否则是可直接展示的中文提示
 */
export function checkValue(placeholder, raw) {
  const name = placeholder?.name ?? '该参数'
  const text = normalize(raw)

  if (text === '') return `请填写 ${name}`

  switch (placeholder?.type) {
    case 'int': {
      if (!INT_PATTERN.test(text)) return `${name} 要填整数，例如 28`
      const n = Number(text)
      if (n < INT_MIN || n > INT_MAX) return `${name} 超出 int 范围（${INT_MIN} ~ ${INT_MAX}）`
      return null
    }
    case 'long':
      // 只校验格式不校验范围：JS Number 的安全整数只到 2^53，
      // 再长就失真了，硬拦会误伤合法值，交给后端 Long.parseLong
      if (!INT_PATTERN.test(text)) return `${name} 要填整数，例如 1000000`
      return null
    case 'double':
      if (!DOUBLE_PATTERN.test(text)) return `${name} 要填数字，例如 3.14`
      return null
    case 'boolean':
      if (text !== 'true' && text !== 'false') return `${name} 只能是 true 或 false`
      return null
    default:
      return null   // String 与未知类型不校验格式
  }
}

/**
 * 批量校验。任一格不合法就整体不放行（需求：前端校验不通过不允许提交）。
 * @returns {{ok:boolean, message:string, values:Record<string,string>}}
 */
export function checkAll(placeholders, params) {
  const values = {}
  for (const p of placeholders ?? []) {
    const err = checkValue(p, params?.[p.name])
    if (err) return { ok: false, message: err, values: {} }
    values[p.name] = normalize(params?.[p.name])
  }
  return { ok: true, message: '', values }
}

function normalize(raw) {
  if (raw === null || raw === undefined) return ''
  return String(raw).trim()
}
