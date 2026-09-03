<script setup>
/**
 * Groovy 脚本编辑器（CodeMirror 6）。
 *
 * 三个能力：groovy 语法高亮、错误行标红、占位符 ${x} 高亮。
 * 颜色一律走 theme.css 里的 .cm-* 类，组件内不写死色值（风格三的统一要求）。
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EditorState, StateEffect, StateField } from '@codemirror/state'
import {
  Decoration,
  EditorView,
  ViewPlugin,
  highlightActiveLine,
  highlightActiveLineGutter,
  keymap,
  lineNumbers,
} from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { StreamLanguage, defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { groovy } from '@codemirror/legacy-modes/mode/groovy'

const props = defineProps({
  modelValue: { type: String, default: '' },
  /** 语法错误行号，1 起算；null 表示不标红 */
  errorLine: { type: Number, default: null },
})
const emit = defineEmits(['update:modelValue', 'save'])

const host = ref(null)
let view = null
/** 外部整篇覆盖内容时置 true，避免回写 emit 造成父子互相触发的死循环 */
let applyingExternal = false

// ---------- 错误行标红 ----------

const setErrorLine = StateEffect.define()

const errorLineField = StateField.define({
  create: () => Decoration.none,
  update(decorations, tr) {
    // 内容变了先让装饰跟着位移，否则行号会错位
    decorations = decorations.map(tr.changes)
    for (const effect of tr.effects) {
      if (effect.is(setErrorLine)) {
        const line = effect.value
        if (!line || line < 1 || line > tr.state.doc.lines) {
          return Decoration.none
        }
        const mark = Decoration.line({ class: 'cm-errorLine' })
        return Decoration.set([mark.range(tr.state.doc.line(line).from)])
      }
    }
    return decorations
  },
  provide: (field) => EditorView.decorations.from(field),
})

// ---------- 占位符高亮 ----------

const PLACEHOLDER_PATTERN = /\$\{\w+\}/g
const PLACEHOLDER_MARK = Decoration.mark({ class: 'cm-placeholder' })

const placeholderHighlighter = ViewPlugin.fromClass(
  class {
    constructor(v) {
      this.decorations = buildPlaceholderDecorations(v)
    }
    update(u) {
      if (u.docChanged || u.viewportChanged) {
        this.decorations = buildPlaceholderDecorations(u.view)
      }
    }
  },
  { decorations: (instance) => instance.decorations },
)

/** 只扫当前视口，超大脚本也不会卡 */
function buildPlaceholderDecorations(v) {
  const marks = []
  const { from, to } = v.visibleRanges.length
    ? { from: v.visibleRanges[0].from, to: v.visibleRanges[v.visibleRanges.length - 1].to }
    : { from: 0, to: v.state.doc.length }
  const text = v.state.sliceDoc(from, to)
  PLACEHOLDER_PATTERN.lastIndex = 0
  let match
  while ((match = PLACEHOLDER_PATTERN.exec(text)) !== null) {
    marks.push(PLACEHOLDER_MARK.range(from + match.index, from + match.index + match[0].length))
  }
  return Decoration.set(marks, true)
}

// ---------- 装配 ----------

function createView() {
  const saveKeymap = keymap.of([
    {
      key: 'Mod-s',
      preventDefault: true,
      run: () => {
        emit('save')
        return true
      },
    },
    indentWithTab,
    ...defaultKeymap,
    ...historyKeymap,
  ])

  const state = EditorState.create({
    doc: props.modelValue ?? '',
    extensions: [
      lineNumbers(),
      highlightActiveLine(),
      highlightActiveLineGutter(),
      history(),
      saveKeymap,
      StreamLanguage.define(groovy),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      errorLineField,
      placeholderHighlighter,
      EditorView.lineWrapping,
      EditorView.updateListener.of((update) => {
        if (update.docChanged && !applyingExternal) {
          emit('update:modelValue', update.state.doc.toString())
        }
      }),
    ],
  })

  return new EditorView({ state, parent: host.value })
}

onMounted(() => {
  view = createView()
  applyErrorLine(props.errorLine)
})

onBeforeUnmount(() => {
  view?.destroy()
  view = null
})

// 外部改内容（保存回填、AI 建议一键应用）→ 整篇替换。
// 用 changes 而不是重建编辑器，这样撤销历史还在，用户 Ctrl+Z 能退回去。
watch(
  () => props.modelValue,
  (next) => {
    if (!view) return
    const current = view.state.doc.toString()
    if (current === (next ?? '')) return
    applyingExternal = true
    view.dispatch({
      changes: { from: 0, to: current.length, insert: next ?? '' },
    })
    applyingExternal = false
  },
)

watch(() => props.errorLine, (line) => applyErrorLine(line))

function applyErrorLine(line) {
  if (!view) return
  view.dispatch({ effects: setErrorLine.of(line ?? null) })
}

function focus() {
  view?.focus()
}

defineExpose({ focus })
</script>

<template>
  <div ref="host" class="script-editor"></div>
</template>

<style scoped>
.script-editor {
  height: 100%;
  overflow: hidden;
  border: 1px solid var(--border-light);
  border-radius: var(--panel-radius);
  background: var(--panel-bg);
}
</style>
