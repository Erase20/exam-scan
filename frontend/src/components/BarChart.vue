<template>
  <div class="chart-box" :style="{ height: height + 'px' }">
    <svg :viewBox="`0 0 ${W} ${H}`" width="100%" height="100%" preserveAspectRatio="xMidYMid meet">
      <defs>
        <linearGradient :id="gradId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" :stop-color="color" stop-opacity="0.95" />
          <stop offset="100%" :stop-color="color" stop-opacity="0.4" />
        </linearGradient>
      </defs>

      <g v-for="(t, i) in ticks" :key="i">
        <line :x1="PAD_L" :y1="t.y" :x2="W - PAD_R" :y2="t.y" class="grid-line" />
        <text :x="PAD_L - 8" :y="t.y + 4" text-anchor="end" class="axis-text">{{ t.label }}</text>
      </g>

      <g v-for="(b, i) in bars" :key="i">
        <rect :x="b.x" :y="b.y" :width="b.w" :height="b.h" rx="4"
              :fill="`url(#${gradId})`" class="bar-rect" />
        <text v-if="b.value > 0" :x="b.x + b.w / 2" :y="b.y - 6"
              text-anchor="middle" class="value-text">{{ b.value }}</text>
        <text :x="b.x + b.w / 2" :y="H - PAD_B + 19" text-anchor="middle" class="axis-text">
          {{ b.label }}
        </text>
        <title>{{ b.label }} 分：{{ b.value }} {{ unit }}</title>
      </g>

      <line :x1="PAD_L" :y1="H - PAD_B" :x2="W - PAD_R" :y2="H - PAD_B" class="axis-line" />
      <text v-if="!bars.length" :x="W / 2" :y="H / 2" text-anchor="middle" class="empty-text">暂无数据</text>
    </svg>
  </div>
</template>

<script setup>
import { computed, useId } from 'vue'

const props = defineProps({
  labels: { type: Array, default: () => [] },
  values: { type: Array, default: () => [] },
  color: { type: String, default: '#409EFF' },
  unit: { type: String, default: '人' },
  height: { type: Number, default: 260 },
})

const W = 720, H = 260, PAD_L = 46, PAD_R = 16, PAD_T = 22, PAD_B = 42
const gradId = 'bar-grad-' + useId().replace(/[^a-zA-Z0-9_-]/g, '')

const maxVal = computed(() => Math.max(0, ...props.values.map(v => Number(v) || 0)))
const niceMax = computed(() => (maxVal.value <= 4 ? 4 : Math.ceil(maxVal.value / 4) * 4))

const ticks = computed(() => Array.from({ length: 5 }, (_, i) => ({
  y: PAD_T + (H - PAD_T - PAD_B) * i / 4,
  label: Math.round(niceMax.value * (4 - i) / 4),
})))

const bars = computed(() => {
  const n = props.labels.length
  if (!n) return []
  const plotW = W - PAD_L - PAD_R
  const plotH = H - PAD_T - PAD_B
  const slot = plotW / n
  return props.labels.map((label, i) => {
    const value = Number(props.values[i]) || 0
    const h = value > 0 ? Math.max(2, value / niceMax.value * plotH) : 0
    return {
      label, value,
      x: PAD_L + i * slot + slot * 0.16,
      w: slot * 0.68,
      y: PAD_T + plotH - h,
      h,
    }
  })
})
</script>

<style scoped>
.chart-box { width: 100%; }
.grid-line { stroke: #ebeef5; stroke-width: 1; stroke-dasharray: 4 4; }
.axis-line { stroke: #dcdfe6; stroke-width: 1; }
.axis-text { fill: #909399; font-size: 11px; }
.value-text { fill: #606266; font-size: 11px; font-weight: 600; }
.empty-text { fill: #c0c4cc; font-size: 14px; }
.bar-rect { cursor: pointer; transition: opacity .2s; }
.bar-rect:hover { opacity: .72; }
</style>
