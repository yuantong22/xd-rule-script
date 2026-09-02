<template>
  <div class="page">
    <div class="topbar">
      <div class="logo">脚本规则工作台</div>
      <div class="tag">规则列表</div>
      <div class="spacer"></div>
      <el-button class="topbar-btn" round @click="openCreate">新建规则</el-button>
    </div>

    <div class="body">
      <div class="panel">
        <div class="panel-title">共 {{ total }} 条规则</div>

        <div class="toolbar">
          <el-input
            v-model="keyword"
            class="search"
            placeholder="按规则名称搜索"
            clearable
            @keyup.enter="search"
            @clear="search"
          />
          <el-button type="primary" round @click="search">搜索</el-button>
        </div>

        <el-table :data="items" v-loading="loading" empty-text="还没有规则，点右上角新建一个">
          <el-table-column prop="name" label="规则名称" min-width="180" show-overflow-tooltip />
          <el-table-column prop="description" label="描述" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description || '—' }}</template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="180" />
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openWorkbench(row)">打开</el-button>
              <el-button link type="primary" @click="openEdit(row)">编辑信息</el-button>
              <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            layout="prev, pager, next, total"
            :total="total"
            :page-size="size"
            :current-page="page"
            @current-change="onPageChange"
          />
        </div>
      </div>
    </div>

    <!-- 新建 / 编辑信息共用一个弹窗：列表页只改名称和描述，改脚本要进工作台 -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑规则信息' : '新建规则'" width="480px">
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="60" placeholder="例如：VIP 客户折扣判定" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="200" placeholder="一句话说明这条规则干什么" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createRule, deleteRule, listRules, updateRule } from '@/api/rule'
import { reportError } from '@/api/http'

const router = useRouter()

const items = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const loading = ref(false)

const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref(null)
const form = reactive({ name: '', description: '' })

async function load() {
  loading.value = true
  try {
    const data = await listRules(keyword.value.trim(), page.value, size.value)
    items.value = data.items
    total.value = data.total
  } catch (error) {
    reportError(error)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function onPageChange(next) {
  page.value = next
  load()
}

function openCreate() {
  editingId.value = null
  form.name = ''
  form.description = ''
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.name = row.name
  form.description = row.description || ''
  dialogVisible.value = true
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写规则名称')
    return
  }
  submitting.value = true
  try {
    if (editingId.value) {
      await updateRule({ ruleId: editingId.value, name: form.name.trim(), description: form.description.trim() })
      ElMessage.success('已保存')
      dialogVisible.value = false
      await load()
    } else {
      // 需求 4.2：创建后直接跳转工作台
      const created = await createRule(form.name.trim(), form.description.trim())
      dialogVisible.value = false
      router.push({ name: 'rule-workbench', params: { id: created.id } })
    }
  } catch (error) {
    reportError(error)
  } finally {
    submitting.value = false
  }
}

function openWorkbench(row) {
  router.push({ name: 'rule-workbench', params: { id: row.id } })
}

async function confirmDelete(row) {
  // 需求交互规则 #6：删除必须二次确认，且要告知会连带删掉会话与用例
  try {
    await ElMessageBox.confirm(
      `删除规则「${row.name}」后，它的 AI 对话历史与测试用例会一并清除，且无法恢复。确定删除？`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
    )
  } catch (cancelled) {
    return
  }
  try {
    await deleteRule(row.id)
    ElMessage.success('已删除')
    if (items.value.length === 1 && page.value > 1) page.value -= 1
    await load()
  } catch (error) {
    reportError(error)
  }
}

onMounted(load)
</script>

<style scoped>
.page { height: 100%; display: flex; flex-direction: column; }
.body { flex: 1; padding: 20px; overflow: auto; }
.toolbar { display: flex; gap: 10px; padding: 16px 20px; align-items: center; }
.search { width: 280px; }
.pager { display: flex; justify-content: flex-end; padding: 14px 20px; }
.topbar-btn { background: rgba(255, 255, 255, .92); border: none; color: var(--brand-from); font-weight: 500; }
</style>
