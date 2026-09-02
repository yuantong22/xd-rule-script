import { describe, it, expect, vi, afterEach } from 'vitest'

// rule.js → http.js 顶层 import 了 element-plus，测试里换成空实现，避开样式与 DOM 依赖
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), success: vi.fn() } }))

const { listRules, createRule, getRuleDetail, updateRule, deleteRule } = await import('../rule.js')

function stubFetch(payload = { code: 0, message: 'success', data: null }) {
  return vi.fn(async () => ({ ok: true, status: 200, json: async () => payload }))
}

// 取出真正发出去的请求体（JSON 序列化后的结果，所以 undefined 的键会消失）
function sentBody(fetchMock) {
  return JSON.parse(fetchMock.mock.calls[0][1].body)
}

describe('rule.js 组装请求体', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('listRules 原样传出有值的搜索词与分页参数', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await listRules('VIP', 2, 10)
    expect(f.mock.calls[0][0]).toBe('/api/rule/list')
    expect(sentBody(f)).toEqual({ name: 'VIP', page: 2, size: 10 })
  })

  it('listRules 把空串搜索词归一成 null，而不是发空串给后端做模糊匹配', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await listRules('', 1, 10)
    expect(sentBody(f).name).toBeNull()
  })

  it('listRules 把 undefined 搜索词也归一成 null', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await listRules(undefined, 1, 10)
    expect(sentBody(f).name).toBeNull()
  })

  it('createRule 透传名称与描述', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await createRule('VIP 客户折扣判定', '按年龄与会员等级判定')
    expect(f.mock.calls[0][0]).toBe('/api/rule/create')
    expect(sentBody(f)).toEqual({ name: 'VIP 客户折扣判定', description: '按年龄与会员等级判定' })
  })

  it('getRuleDetail 透传 ruleId', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await getRuleDetail(7)
    expect(f.mock.calls[0][0]).toBe('/api/rule/detail')
    expect(sentBody(f)).toEqual({ ruleId: 7 })
  })

  it('updateRule 四个字段都给了就都发出去', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await updateRule({ ruleId: 7, name: 'n', description: 'd', scriptContent: 'return 1' })
    expect(sentBody(f)).toEqual({ ruleId: 7, name: 'n', description: 'd', scriptContent: 'return 1' })
  })

  it('updateRule 只改名称时，请求体里根本不出现 description / scriptContent 这两个键', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await updateRule({ ruleId: 7, name: '新名字' })
    const body = sentBody(f)
    expect(body).toEqual({ ruleId: 7, name: '新名字' })
    // 要求是「键不存在」而不是「值为 null」：后端对 null 与缺字段的处理不一定相同，
    // 部分更新的契约是不发这个键
    expect('scriptContent' in body).toBe(false)
    expect('description' in body).toBe(false)
  })

  it('updateRule 把空串脚本内容当合法值发出去（清空脚本不能静默失效）', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await updateRule({ ruleId: 7, scriptContent: '' })
    expect(sentBody(f)).toEqual({ ruleId: 7, scriptContent: '' })
  })

  it('deleteRule 透传 ruleId', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await deleteRule(3)
    expect(f.mock.calls[0][0]).toBe('/api/rule/delete')
    expect(sentBody(f)).toEqual({ ruleId: 3 })
  })
})
