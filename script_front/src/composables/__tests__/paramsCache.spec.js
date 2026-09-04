import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { storageKey, loadCachedParams, saveCachedParams, clearCachedParams } from '../paramsCache'

/**
 * 在 node 环境里 mock 一个内存版 localStorage。
 *
 * 项目 vitest 用 environment: 'node'（dev-conventions 里明确不引 jsdom），
 * 全局没有 localStorage。这里挂一个符合 Web Storage API 的假实现，
 * afterEach 摘掉避免污染别的测试文件。
 *
 * overrides 用来注入抛错版本，验证 try/catch 兜底路径。
 */
function installFakeStorage(overrides = {}) {
  const store = new Map()
  globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k),
    clear: () => store.clear(),
    ...overrides,
  }
  return store
}

beforeEach(() => installFakeStorage())
afterEach(() => { delete globalThis.localStorage })

describe('storageKey：按 ruleId 隔离缓存 key', () => {
  it('数字 ruleId 拼进 key', () => {
    expect(storageKey(123)).toBe('workbench:params:123')
  })

  it('字符串 ruleId 也支持', () => {
    expect(storageKey('abc')).toBe('workbench:params:abc')
  })

  it('不同 ruleId 的 key 不冲突（避免规则之间串值）', () => {
    expect(storageKey(1)).not.toBe(storageKey(2))
  })
})

describe('loadCachedParams：从缓存读填值', () => {
  it('无缓存时返回空对象', () => {
    expect(loadCachedParams(1)).toEqual({})
  })

  it('有效对象原样返回', () => {
    saveCachedParams(1, { age: '28', level: 'vip' })
    expect(loadCachedParams(1)).toEqual({ age: '28', level: 'vip' })
  })

  it('不同 ruleId 之间互不干扰', () => {
    saveCachedParams(1, { a: '1' })
    saveCachedParams(2, { b: '2' })
    expect(loadCachedParams(1)).toEqual({ a: '1' })
    expect(loadCachedParams(2)).toEqual({ b: '2' })
  })

  it('坏 JSON 静默返回空对象（用户手改 / 版本升级导致的脏数据）', () => {
    localStorage.setItem(storageKey(1), '{not valid json')
    expect(loadCachedParams(1)).toEqual({})
  })

  it('JSON 是数组时返回空对象（只接受纯对象）', () => {
    localStorage.setItem(storageKey(1), '[1,2,3]')
    expect(loadCachedParams(1)).toEqual({})
  })

  it('JSON 是 null 时返回空对象', () => {
    localStorage.setItem(storageKey(1), 'null')
    expect(loadCachedParams(1)).toEqual({})
  })

  it('JSON 是字符串或数字时返回空对象', () => {
    localStorage.setItem(storageKey(1), '"hello"')
    expect(loadCachedParams(1)).toEqual({})
    localStorage.setItem(storageKey(1), '42')
    expect(loadCachedParams(1)).toEqual({})
  })

  it('getItem 抛错时返回空对象（隐私模式常见）', () => {
    installFakeStorage({ getItem: () => { throw new Error('boom') } })
    expect(loadCachedParams(1)).toEqual({})
  })

  it('localStorage 未定义时返回空对象（node/沙箱环境兜底）', () => {
    delete globalThis.localStorage
    expect(loadCachedParams(1)).toEqual({})
  })
})

describe('saveCachedParams：写填值到缓存', () => {
  it('写入后能读回同样的值', () => {
    saveCachedParams(1, { age: '28' })
    expect(loadCachedParams(1)).toEqual({ age: '28' })
  })

  it('params 是 null 时存空对象（不抛错）', () => {
    saveCachedParams(1, null)
    expect(loadCachedParams(1)).toEqual({})
  })

  it('params 是 undefined 时存空对象（不抛错）', () => {
    saveCachedParams(1, undefined)
    expect(loadCachedParams(1)).toEqual({})
  })

  it('setItem 抛错时静默不抛（配额满 / 隐私模式）', () => {
    installFakeStorage({ setItem: () => { throw new Error('quota exceeded') } })
    expect(() => saveCachedParams(1, { a: '1' })).not.toThrow()
  })

  it('localStorage 未定义时静默不抛', () => {
    delete globalThis.localStorage
    expect(() => saveCachedParams(1, { a: '1' })).not.toThrow()
  })
})

describe('clearCachedParams：清缓存', () => {
  it('清除后 load 返回空对象', () => {
    saveCachedParams(1, { a: '1' })
    clearCachedParams(1)
    expect(loadCachedParams(1)).toEqual({})
  })

  it('removeItem 抛错时静默不抛', () => {
    installFakeStorage({ removeItem: () => { throw new Error('boom') } })
    expect(() => clearCachedParams(1)).not.toThrow()
  })

  it('localStorage 未定义时静默不抛', () => {
    delete globalThis.localStorage
    expect(() => clearCachedParams(1)).not.toThrow()
  })
})
