/**
 * AI 对话接口。
 *
 * send 是 SSE 流式，不能用 api/http.js 里的 post（那个会 await 整个响应体，
 * 流式效果就没了），必须自己读 ReadableStream。
 * history / clear 是普通 JSON 接口，复用 post。
 */
import { post } from './http'
import { createSseParser } from './sseParser'

/** 历史消息回显（需求 4.3.4：打开页面时加载） */
export function getChatHistory(ruleId) {
  return post('/api/chat/history', { ruleId })
}

/** 清空该会话的记忆与历史消息 */
export function clearChat(ruleId) {
  return post('/api/chat/clear', { ruleId })
}

/**
 * 发起流式对话。
 *
 * @param {{ruleId:number, message:string, scriptContent:string}} payload
 *        scriptContent 是编辑器当前内容（含未保存修改），需求 4.3.4 要求每次自动带上
 * @param {{onMessage?:(t:string)=>void, onDone?:()=>void, onError?:(msg:string)=>void}} handlers
 * @returns {{abort: () => void}} 调 abort 可中断（切换规则、离开页面时用）
 */
export function sendChatStream(payload, handlers = {}) {
  const controller = new AbortController()

  // 用 IIFE 跑异步流程，把 abort 句柄同步返回给调用方
  ;(async () => {
    let response
    try {
      response = await fetch('/api/chat/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify(payload),
        signal: controller.signal,
      })
    } catch (e) {
      if (e?.name === 'AbortError') return          // 主动中断，不算错误
      handlers.onError?.('无法连接后端服务，请确认 script_back 已在 8080 端口启动')
      return
    }

    if (!response.ok) {
      handlers.onError?.(`服务异常（HTTP ${response.status}），请稍后重试`)
      return
    }
    if (!response.body) {
      handlers.onError?.('当前浏览器不支持流式响应，请改用 Chrome 或 Edge')
      return
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    const parser = createSseParser()
    let finished = false

    try {
      while (!finished) {
        const { done, value } = await reader.read()
        if (done) break

        // stream:true 时中文可能被切在多字节中间，decoder 必须复用同一个实例
        // （它内部会缓存不完整的字节序列），不能每次 new
        for (const evt of parser.push(decoder.decode(value, { stream: true }))) {
          if (evt.event === 'message') {
            handlers.onMessage?.(evt.data)
          } else if (evt.event === 'done') {
            finished = true
            handlers.onDone?.()
            break
          } else if (evt.event === 'error') {
            finished = true
            handlers.onError?.(evt.data || 'AI 对话暂时不可用，请稍后重试')
            break
          }
        }
      }

      // 冲刷 decoder 里可能残留的最后一个多字节字符
      const tail = decoder.decode()
      if (tail && !finished) {
        for (const evt of parser.push(tail)) {
          if (evt.event === 'message') handlers.onMessage?.(evt.data)
        }
      }

      // 后端没发 done 就把流关了（异常情况），也要让 UI 停止转圈
      if (!finished) handlers.onDone?.()
    } catch (e) {
      if (e?.name === 'AbortError') return
      handlers.onError?.('读取回复时连接中断，请重试')
    }
  })()

  return {
    abort: () => controller.abort(),
  }
}
