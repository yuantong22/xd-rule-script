// 规则相关接口。校验（validateScript）与运行（runScript）在任务 12 补上
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
