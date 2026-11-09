<template>
  <el-dialog v-model="visible" :title="title" width="720px" append-to-body
             :close-on-click-modal="false">
    <el-alert v-if="draft" type="info" :closable="false" show-icon style="margin-bottom:10px"
              title="正在编辑识别草稿：保存只更新本页草稿，点「确认入库」后才写入题库；可在此上传配图。" />
    <el-form v-if="form.id || draft" label-position="top" size="small">
      <el-form-item label="题干">
        <el-input v-model="form.stem" type="textarea" :rows="5" placeholder="题目正文" />
      </el-form-item>

      <el-form-item label="选项（选择题 / 判断题填写，其他题型可留空）">
        <div v-for="(o, i) in optRows" :key="i" class="opt-row">
          <el-input v-model="o.key" style="width:56px" />
          <el-input v-model="o.content" placeholder="选项内容" />
          <el-button type="danger" plain size="small" @click="optRows.splice(i, 1)">删除</el-button>
        </div>
        <el-button size="small" @click="addOpt">+ 添加选项</el-button>
      </el-form-item>

      <el-row :gutter="12">
        <el-col :span="8">
          <el-form-item label="题型">
            <el-select v-model="form.qType" style="width:100%">
              <el-option v-for="(l, k) in Q_TYPE_LABELS" :key="k" :label="l" :value="k" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="难度">
            <el-rate v-model="form.difficulty" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="知识点">
            <el-input v-model="form.knowledge" placeholder="如：分数加减法" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="配图">
        <div v-if="images.length" class="img-list">
          <div v-for="(u, i) in images" :key="u + i" class="img-item">
            <el-image :src="u" :preview-src-list="images" :initial-index="i" preview-teleported
                      fit="cover" style="width:88px;height:88px;border-radius:4px;border:1px solid #eee" />
            <el-button type="danger" text size="small" @click="images.splice(i, 1)">移除</el-button>
          </div>
        </div>
        <el-upload :show-file-list="false" :http-request="uploadImage"
                   accept="image/png,image/jpeg,image/gif,image/bmp">
          <el-button size="small" :loading="uploading">+ 上传配图</el-button>
        </el-upload>
        <div class="hint">配图会随题干显示并插入导出的 Word；移除仅取消引用，不删除原文件。</div>
      </el-form-item>

      <el-form-item :label="form.qType === 'CHOICE' ? '选择答案' : '答案'">
        <!-- 选择题：按选项点选答案 -->
        <el-radio-group v-if="form.qType === 'CHOICE'" v-model="form.answer" size="small">
          <el-radio-button v-for="k in choiceKeys" :key="k" :value="k">{{ k }}</el-radio-button>
          <el-radio-button value="">空</el-radio-button>
        </el-radio-group>
        <el-radio-group v-else-if="form.qType === 'JUDGE'" v-model="form.answer" size="small">
          <el-radio-button value="√">√ 对</el-radio-button>
          <el-radio-button value="×">× 错</el-radio-button>
          <el-radio-button value="">空</el-radio-button>
        </el-radio-group>
        <el-input v-else v-model="form.answer" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="解析">
        <el-input v-model="form.analysis" type="textarea" :rows="3" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ draft ? '保存草稿' : '保存到题库' }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http, { Q_TYPE_LABELS } from '../api'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  question: { type: Object, default: null },
  // 扫描结果的题目草稿没有数据库 id：表单照常渲染，但保存只回传草稿、不写题库
  draft: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'saved'])

const visible = computed({
  get: () => props.modelValue,
  set: v => emit('update:modelValue', v),
})
const title = computed(() => props.draft
  ? '编辑题目草稿'
  : (form.value.id ? `编辑题目 #${form.value.id}` : '编辑题目'))

const form = ref({})
const optRows = ref([])
const images = ref([])
const saving = ref(false)
const uploading = ref(false)

const imgList = p => (p || '').split(',').map(s => s.trim()).filter(Boolean)

function parseOpts(raw) {
  // 题库实体给的是 JSON 字符串，识别草稿给的是数组，两种都要能编辑
  let arr = raw
  if (typeof raw === 'string') {
    try { arr = JSON.parse(raw || '[]') } catch (e) { return [] }
  }
  if (Array.isArray(arr)) return arr.map(o => ({ key: o?.key || '', content: o?.content || '' }))
  if (arr && typeof arr === 'object') return Object.entries(arr).map(([k, v]) => ({ key: k, content: String(v) }))
  return []
}

function init() {
  const q = props.question
  if (!q) return
  form.value = {
    id: q.id,
    stem: q.stem || '',
    qType: q.qType || 'CHOICE',
    difficulty: Number(q.difficulty) || 3,
    knowledge: q.knowledge || '',
    answer: q.answer || '',
    analysis: q.analysis || '',
    // lessonOrder 在实体里是 IGNORED 更新策略，必须回传原值，否则会被清空
    lessonOrder: q.lessonOrder == null ? null : Number(q.lessonOrder),
  }
  optRows.value = parseOpts(q.options)
  images.value = imgList(q.imagePath)
}

watch(() => props.modelValue, v => { if (v) init() })
watch(() => props.question, () => { if (props.modelValue) init() })

function addOpt() {
  optRows.value.push({ key: String.fromCharCode(65 + optRows.value.length), content: '' })
}

/* 选择题答案按钮按实际选项生成；没有选项时退回 A-D */
const choiceKeys = computed(() => {
  const ks = optRows.value.map(o => (o.key || '').trim().toUpperCase()).filter(Boolean)
  return ks.length ? ks : ['A', 'B', 'C', 'D']
})

async function uploadImage(opt) {
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', opt.file)
    const r = await http.post('/file/upload', fd)
    images.value.push(r.url)
    ElMessage.success('配图已上传')
  } finally {
    uploading.value = false
  }
}

async function save() {
  if (!(form.value.stem || '').trim()) {
    ElMessage.warning('题干不能为空')
    return
  }
  saving.value = true
  try {
    const opts = optRows.value.filter(o => (o.content || '').trim())
    const imagePath = images.value.join(',')
    if (props.draft) {
      // 草稿：保留题号/大题/序号/分值等切分字段，options 维持数组，
      // 只把本弹窗可编辑的字段与配图合并后回传，由「确认入库」统一写库
      emit('saved', {
        ...(props.question || {}),
        stem: form.value.stem.trim(),
        qType: form.value.qType,
        difficulty: form.value.difficulty,
        knowledge: form.value.knowledge,
        answer: form.value.answer,
        analysis: form.value.analysis,
        options: opts,
        imagePath,
        hasImage: images.value.length ? 1 : 0,
        lessonOrder: form.value.lessonOrder,
      })
      ElMessage.success('草稿已更新，确认入库后写入题库')
      visible.value = false
      return
    }
    const body = {
      id: form.value.id,
      stem: form.value.stem.trim(),
      qType: form.value.qType,
      difficulty: form.value.difficulty,
      knowledge: form.value.knowledge,
      answer: form.value.answer,
      analysis: form.value.analysis,
      options: JSON.stringify(opts),
      imagePath,
      hasImage: images.value.length ? 1 : 0,
      lessonOrder: form.value.lessonOrder,
    }
    const saved = await http.post('/question', body)
    ElMessage.success('已保存到题库')
    emit('saved', saved)
    visible.value = false
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.opt-row { display: flex; gap: 6px; margin-bottom: 6px; width: 100%; }
.img-list { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 10px; }
.img-item { display: flex; flex-direction: column; align-items: center; }
.hint { font-size: 12px; color: #909399; margin-top: 6px; }
</style>
