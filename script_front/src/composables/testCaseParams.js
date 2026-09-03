/**
 * 测试用例回填的取值逻辑（需求 4.3.3）。
 *
 * 语义是**完全覆盖**而不是合并：需求原文是「选中后一键回填所有输入框，再点运行即可重跑」，
 * 「重跑」意味着要恢复到保存用例那一刻的状态。若只覆盖用例里有的键、其余保留用户当前输入，
 * 跑出来的结果会与当初保存时不同，用例就失去意义了。
 *
 * 与 validationState.js 里的 pickParams 语义一致，但没有直接复用它，两个原因：
 * 1. pickParams 是 reducer 的模块私有函数，导出它等于把状态机的内部实现变成公共 API；
 * 2. 它不报告「哪些键被丢弃了」，而这里必须报告 —— 用例是快照、脚本会漂移，
 *    保存后用户可能删掉了某个占位符。静默丢弃会让用户以为回填成功、实际少填一个值。
 */
import { defaultValueFor } from './paramRules'

/**
 * @param {Array<{name:string, type:string}>} placeholders 当前脚本的占位符（校验通过后才有）
 * @param {Object<string,string>|null} savedParams 用例里存的快照
 * @returns {{values: Object<string,string>, dropped: string[]}}
 *          values 可直接交给 useValidationState 的 setParams；
 *          dropped 是用例里有、但当前脚本已没有的键，调用方应提示用户
 */
export function refillParams(placeholders, savedParams) {
  const list = placeholders ?? []
  const saved = savedParams ?? {}

  const values = {}
  for (const p of list) {
    // 用 hasOwnProperty 而不是 ?? ：用例里存了空串是合法值，不能被当成「没有」而落到默认值
    values[p.name] = Object.prototype.hasOwnProperty.call(saved, p.name)
      ? saved[p.name]
      : defaultValueFor(p.type)
  }

  const known = new Set(list.map((p) => p.name))
  const dropped = Object.keys(saved).filter((key) => !known.has(key))

  return { values, dropped }
}
