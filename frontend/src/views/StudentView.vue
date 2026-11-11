<template>
  <div>
    <h2>考生管理</h2>
    <div class="page-card">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="搜索考号 / 姓名" clearable style="width:220px"
                  @keyup.enter="load" @clear="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <el-upload :show-file-list="false" :auto-upload="false" accept=".xlsx,.xls"
                   :on-change="onFileChange">
          <el-button type="success">导入名单 (Excel)</el-button>
        </el-upload>
        <el-button type="warning" plain @click="exportGrades" :loading="exporting">导出成绩汇总表</el-button>
        <el-button @click="openEdit(null)">新增考生</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" size="small" stripe>
        <el-table-column prop="studentNo" label="考号" width="140" />
        <el-table-column prop="name" label="姓名" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.name === '待确认'" type="warning" size="small">待确认</el-tag>
            <span v-else>{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="className" label="班级" width="140" />
        <el-table-column prop="grade" label="年级" width="120" />
        <el-table-column prop="createTime" label="登记时间" min-width="150" />
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑考生' : '新增考生'" width="420px">
      <el-form label-width="70px">
        <el-form-item label="考号" required>
          <el-input v-model="editForm.studentNo" placeholder="与答题卡填涂/书写考号一致" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="班级">
          <el-input v-model="editForm.className" />
        </el-form-item>
        <el-form-item label="年级">
          <el-input v-model="editForm.grade" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="save" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'

const rows = ref([]); const keyword = ref(''); const loading = ref(false)
const editVisible = ref(false); const saving = ref(false); const exporting = ref(false)
const editForm = ref({ id: null, studentNo: '', name: '', className: '', grade: '' })

async function load() {
  loading.value = true
  try {
    rows.value = await http.get('/student/list', { params: { keyword: keyword.value || undefined } })
  } finally { loading.value = false }
}

function openEdit(row) {
  editForm.value = row
    ? { id: row.id, studentNo: row.studentNo, name: row.name, className: row.className, grade: row.grade }
    : { id: null, studentNo: '', name: '', className: '', grade: '' }
  editVisible.value = true
}

async function save() {
  saving.value = true
  try {
    const f = editForm.value
    if (f.id) {
      await http.post(`/student/${f.id}`, f)
    } else {
      await http.post('/student', f)
    }
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
  } catch (e) { /* 拦截器提示 */ } finally { saving.value = false }
}

async function remove(row) {
  await ElMessageBox.confirm(`确定删除考生「${row.name}（${row.studentNo}）」？`, '提示', { type: 'warning' })
  await http.delete(`/student/${row.id}`)
  ElMessage.success('已删除')
  await load()
}

async function onFileChange(file) {
  const fd = new FormData()
  fd.append('file', file.raw)
  const r = await http.post('/student/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
  ElMessage.success(`导入完成：新增 ${r.inserted}，更新 ${r.updated}，跳过 ${r.skipped}`)
  await load()
}

async function exportGrades() {
  exporting.value = true
  try {
    const resp = await fetch('/api/student/export/grades')
    if (!resp.ok) {
      const msg = await resp.json().catch(() => null)
      ElMessage.error(msg?.message || '没有阅卷记录，请先完成阅卷')
      return
    }
    const blob = await resp.blob()
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = '考生成绩汇总表.xlsx'
    a.click()
  } finally { exporting.value = false }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 14px; flex-wrap: wrap; }
</style>
