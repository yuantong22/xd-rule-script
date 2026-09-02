import { describe, it, expect, vi, afterEach } from 'vitest'

// http.js 顶层 import 了 element-plus，测试里把它替换成空实现，避免拉入样式与 DOM 依赖
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), success: vi.fn() } }))

const { post } = await import('../http.js')

function stubFetch(payload, { ok = true, status = 200 } = {}) {
  return vi.fn(async () => ({
    ok,
    status,
    json: async () => payload
  }))
}

describe('post 解包统一响应', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('code=0 时返回 data 字段', async () => {
    vi.stubGlobal('fetch', stubFetch({ code: 0, message: 'success', data: { id: 7 } }))
    await expect(post('/api/rule/detail', { ruleId: 7 })).resolves.toEqual({ id: 7 })
  })

  it('请求体是 JSON 且方法固定为 POST', async () => {
    const fetchMock = stubFetch({ code: 0, message: 'success', data: null })
    vi.stubGlobal('fetch', fetchMock)
    await post('/api/rule/delete', { ruleId: 3 })
    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/rule/delete')
    expect(options.method).toBe('POST')
    expect(JSON.parse(options.body)).toEqual({ ruleId: 3 })
  })

  it('code 非 0 时抛出后端给的中文 message', async () => {
    vi.stubGlobal('fetch', stubFetch({ code: 1000, message: '规则不存在或已被删除', data: null }))
    await expect(post('/api/rule/detail', { ruleId: 999 }))
      .rejects.toThrow('规则不存在或已被删除')
  })

  it('HTTP 非 2xx 时抛服务异常提示', async () => {
    vi.stubGlobal('fetch', stubFetch({}, { ok: false, status: 502 }))
    await expect(post('/api/rule/list', {})).rejects.toThrow('服务异常（HTTP 502）')
  })

  it('网络不可达时提示确认后端已启动', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    await expect(post('/api/rule/list', {})).rejects.toThrow('无法连接后端服务')
  })

  it('响应不是合法 JSON 时给可读提示', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, json: async () => { throw new Error('bad json') } })))
    await expect(post('/api/rule/list', {})).rejects.toThrow('无法解析')
  })
})
