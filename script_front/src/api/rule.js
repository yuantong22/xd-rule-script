// 规则相关接口
import { post } from './http'

export function listRules(name, page, size) {
  return post('/api/rule/list', { name: name || null, page, size })
}

export function createRule(name, description) {
  return post('/api/rule/create', { name, description })
}

export function getRuleDetail(ruleId) {
  return post('/api/rule/detail', { ruleId })
}

/**
 * 部分更新：只把非 undefined 的字段发出去，后端对 null 字段保持原值
 */
export function updateRule({ ruleId, name, description, scriptContent }) {
  const body = { ruleId }
  if (name !== undefined) body.name = name
  if (description !== undefined) body.description = description
  if (scriptContent !== undefined) body.scriptContent = scriptContent
  return post('/api/rule/update', body)
}

export function deleteRule(ruleId) {
  return post('/api/rule/delete', { ruleId })
}

/**
 * 同步校验脚本：语法 + 占位符提取 + 大模型 CR，后端全部做完才返回。
 * 慢是常态（CR 要几秒到十几秒），调用方必须自己管加载态。
 * @returns {Promise<{syntaxOk:boolean, errorLine:number|null, errorMessage:string|null,
 *                    placeholders:Array<{name:string,type:string}>,
 *                    aiReview:{text:string, suggestedScript:string|null, available:boolean}}>}
 */
export function validateScript(scriptContent) {
  return post('/api/rule/validate', { scriptContent })
}

/**
 * 沙箱运行脚本。只传编辑器当前内容与填值，不需要 ruleId ——
 * 运行的永远是编辑器里的内容，含未保存的修改。
 * @returns {Promise<{success:boolean, value:string|null, errorMessage:string|null, timeout:boolean}>}
 */
export function runScript(scriptContent, params) {
  return post('/api/rule/run', { scriptContent, params })
}
