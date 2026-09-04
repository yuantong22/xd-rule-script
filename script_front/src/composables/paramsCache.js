/**
 * 占位符填值的 localStorage 缓存（按 ruleId 隔离，需求 4.3.3 / 交互规则 #8）。
 *
 * 用户反馈：每次刷新页面填值都丢了，要重填一遍很烦。这里做一层薄缓存：
 * 用户改一次就存一次，下次进同一条规则自动回填。
 *
 * 设计要点：
 * 1. key 按 ruleId 隔离（workbench:params:<id>），规则之间互不干扰；
 * 2. 本模块只管存取，不管过滤：脚本漂移后占位符列表可能变，
 *    过滤逻辑复用 testCaseParams.js 的 refillParams —— 那份实现已经处理了
 *    「缓存里有但当前占位符没有」和「当前占位符有但缓存里没有」两种边界；
 * 3. localStorage 抛错时静默降级：隐私模式、配额满、SSR/node 环境都可能不可用，
 *    缓存只是锦上添花，绝不能因为缓存失败把主流程带崩。
 */

const KEY_PREFIX = 'workbench:params:'

/** 拼缓存 key。单独导出是为了让单测能直接命中同一条 key 做验证 */
export function storageKey(ruleId) {
  return `${KEY_PREFIX}${ruleId}`
}

/**
 * 从 localStorage 读指定规则的填值缓存。
 * 无缓存 / 坏 JSON / 非对象 / 存储不可用一律返回 {}
 */
export function loadCachedParams(ruleId) {
  try {
    if (typeof localStorage === 'undefined') return {}
    const raw = localStorage.getItem(storageKey(ruleId))
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    // 只接受纯对象：数组、null、字符串、数字都当没有，避免下游把非对象结构塞进 params
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

/**
 * 把当前填值写入 localStorage。
 * 失败静默：缓存只是便利功能，写入失败绝不能抛异常打扰用户。
 */
export function saveCachedParams(ruleId, params) {
  try {
    if (typeof localStorage === 'undefined') return
    localStorage.setItem(storageKey(ruleId), JSON.stringify(params || {}))
  } catch {
    // 配额满 / 隐私模式 / 循环引用 —— 都当没这功能
  }
}

/**
 * 清掉指定规则的缓存。当前 UI 没暴露入口，但删除规则时可能会用到，
 * 先导出避免以后再加一遍。
 */
export function clearCachedParams(ruleId) {
  try {
    if (typeof localStorage === 'undefined') return
    localStorage.removeItem(storageKey(ruleId))
  } catch {
    // 同上，静默
  }
}
