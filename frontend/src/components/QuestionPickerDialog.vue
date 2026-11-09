<template>
  <el-dialog v-model="visible" :title="title" width="920px" top="6vh" append-to-body>
    <el-form inline size="small">
      <el-form-item label="关键词">
        <el-input v-model="q.keyword" style="width:180px" clearable placeholder="题干关键词" @keyup.enter="load(1)" />
      </el-form-item>
      <el-form-item label="学科">
        <el-select v-model="q.subject" style="width:110px" clearable placeholder="全部">
          <el-option v-for="s in subjects" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
      <el-form-item label="年级">
        <el-select v-model="q.grade" style="width:110px" clearable placeholder="全部">
          <el-option v-for="g in grades" :key="g" :label="g" :value="g" />
        </el-select>
      </el-form-item>
      <el-form-item label="题型">
        <el-select v-model="q.qType" style="width:110px" clearable placeholder="全部">
          <el-option v-for="(l, k) in Q_TYPE_LABELS" :key="k" :label="l" :value="k" />
        </el-select>
      </el-form-item>
      <el-form-item label="难度">
        <el-select v-model="q.difficulty" style="width:90px" clearable placeholder="全部">
          <el-option v-for="n in 5" :key="n" :label="n + ' ★'" :value="n" />
        </el-select>
      </el-form-item>
      <el-form-item><el-button type="primary" size="small" @click="load(1)">查询</el-button></el-form-item>
    </el-form>

    <el-table ref="tableRef" :data="rows" size="small" v-loading="loading" max-height="420" row-key="id"
              @selection-change="onSelectionChange">
      <el-table-column type="selection" width="44" :selectable="selectable" />
      <el-table-column prop="id" label="ID" width="64" />
      <el-table-column label="题型" width="90">
        <template #default="{ row }"><el-tag size="small">{{ Q_TYPE_LABELS[row.qType] || row.qType }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="stem" label="题干" show-overflow-tooltip />
      <el-table-column label="难度" width="66">
        <template #default="{ row }">{{ row.difficulty }}★</template>
      </el-table-column>
      <el-table-column label="状态" width="76">
        <template #default="{ row }">
          <el-tag v-if="excludeSet.has(Number(row.id))" size="small" type="info">已在卷</el-tag>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top:8px" layout="total, prev, pager, next" :total="total"
                   :page-size="q.size" v-model:current-page="q.page" @current-change="load" />

    <template #footer>
      <span style="float:left;font-size:12px;color:#909399;line-height:32px">
        已选 {{ selected.length }} 题{{ multiple ? '（可多选）' : '（单选）' }}
      </span>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!selected.length" @click="confirmPick">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, watch, nextTick } from 'vue'
import http, { Q_TYPE_LABELS } from '../api'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '从题库选择题目' },
  multiple: { type: Boolean, default: true },
  excludeIds: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue', 'confirm'])

const visible = computed({ get: () => props.modelValue, set: v => emit('update:modelValue', v) })
const excludeSet = computed(() => new Set(props.excludeIds.map(Number)))
const subjects = ['数学', '语文', '英语', '物理', '化学', '生物', '科学', '历史', '地理', '道德与法治']
const grades = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级', '七年级', '八年级', '九年级', '高一', '高二', '高三']

const q = reactive({ page: 1, size: 10, keyword: '', subject: '', grade: '', qType: '', difficulty: null })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const selected = ref([])
const tableRef = ref(null)
let syncing = false

watch(() => props.modelValue, v => {
  if (v) {
    selected.value = []
    q.page = 1
    load()
  }
})

async function load(page) {
  if (typeof page === 'number') q.page = page
  loading.value = true
  try {
    const d = await http.post('/question/search', {
      page: q.page,
      size: q.size,
      keyword: q.keyword || null,
      subject: q.subject || null,
      grade: q.grade || null,
      qType: q.qType || null,
      difficulty: q.difficulty || null,
    })
    rows.value = d.records || []
    total.value = Number(d.total || 0)
    selected.value = []
    await nextTick()
    tableRef.value?.clearSelection()
  } finally {
    loading.value = false
  }
}

const selectable = row => !excludeSet.value.has(Number(row.id))

/** 单选模式：不论用户怎么勾选，始终只保留最后一次勾选的那道题 */
function onSelectionChange(sel) {
  if (syncing) return
  if (!props.multiple && sel.length > 1) {
    const last = sel[sel.length - 1]
    syncing = true
    tableRef.value?.clearSelection()
    tableRef.value?.toggleRowSelection(last, true)
    syncing = false
    selected.value = [last]
    return
  }
  selected.value = sel
}

function confirmPick() {
  if (!selected.value.length) return
  emit('confirm', selected.value.map(x => ({ ...x })))
  visible.value = false
}
</script>
