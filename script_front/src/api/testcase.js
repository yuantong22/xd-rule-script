// 测试用例接口（需求 4.3.3）。三个都是普通 JSON 接口，直接复用 post
import { post } from './http'

/** @returns {Promise<Array<{id:number, name:string, params:Object<string,string>}>>} */
export function listTestCases(ruleId) {
  return post('/api/testcase/list', { ruleId })
}

/**
 * @param {{ruleId:number, name:string, params:Object<string,string>}} payload
 * @returns {Promise<{id:number, name:string, params:Object<string,string}>} 新建的用例
 */
export function saveTestCase({ ruleId, name, params }) {
  return post('/api/testcase/save', { ruleId, name, params })
}

export function deleteTestCase(testCaseId) {
  return post('/api/testcase/delete', { testCaseId })
}
