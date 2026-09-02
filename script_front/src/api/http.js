// 统一请求封装：所有接口 POST，响应体固定 {code, message, data}
import { ElMessage } from 'element-plus'

/**
 * 发一个 POST 请求并解包 data。
 * @param {string} path 以 /api 开头的路径（开发期由 Vite 代理到 8080）
 * @param {object} body 请求体
 * @returns {Promise<any>} data 字段
 * @throws {Error} message 为可直接展示的中文提示
 */
export async function post(path, body = {}) {
  let response
  try {
    response = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
  } catch (networkError) {
    throw new Error('无法连接后端服务，请确认 script_back 已在 8080 端口启动')
  }
  if (!response.ok) {
    throw new Error(`服务异常（HTTP ${response.status}），请稍后重试`)
  }
  let payload
  try {
    payload = await response.json()
  } catch (parseError) {
    throw new Error('后端返回内容无法解析，请确认接口地址是否正确')
  }
  if (payload.code !== 0) {
    throw new Error(payload.message || '请求失败')
  }
  return payload.data
}

/**
 * 把异常翻译成中文文本，弹一次错误提示，并把文本返回给调用方用于界面展示。
 */
export function reportError(error) {
  const text = error instanceof Error && error.message ? error.message : '操作失败，请稍后重试'
  ElMessage.error(text)
  return text
}
