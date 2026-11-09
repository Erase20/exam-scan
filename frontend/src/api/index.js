import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({ baseURL: '/api', timeout: 180000 })

http.interceptors.response.use(
  resp => {
    const d = resp.data
    if (d && typeof d.code === 'number') {
      if (d.code !== 0) {
        ElMessage.error(d.message || '请求失败')
        return Promise.reject(new Error(d.message))
      }
      return d.data
    }
    return d
  },
  err => {
    ElMessage.error(err.response?.data?.message || err.message || '网络错误')
    return Promise.reject(err)
  }
)

export default http

export const Q_TYPE_LABELS = {
  CHOICE: '选择题', FILL: '填空题', JUDGE: '判断题', CALC: '计算题',
  ANSWER: '解答题', DRAW: '作图题', EXPERIMENT: '实验探究题', OTHER: '其他',
}
export const DIFF_LABELS = { 1: '容易', 2: '较易', 3: '中等', 4: '较难', 5: '困难' }
