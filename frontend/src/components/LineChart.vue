<template>
  <div class="chart-box" :style="{ height: height + 'px' }">
    <svg :viewBox="`0 0 ${W} ${H}`" width="100%" height="100%" preserveAspectRatio="xMidYMid meet">
      <defs>
        <linearGradient :id="areaId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#409EFF" stop-opacity="0.28" />
          <stop offset="100%" stop-color="#409EFF" stop-opacity="0.02" />
        </linearGradient>
      </defs>

      <g v-for="(t, i) in ticks" :key="i">
        <line :x1="PAD_L" :y1="t.y" :x2="W - PAD_R" :y2="t.y" class="grid-line" />
        <text :x="PAD_L - 8" :y="t.y + 4" text-anchor="end" class="axis-text">{{ t.label }}</text>
      </g>

      <path v-if="areaPath" :d="areaPath" :fill="`url(#${areaId})`" />
      <path v-if="linePath" :d="linePath" fill="none" stroke="#409EFF" stroke-width="2.5"
            stroke-linejoin="round" stroke-linecap="round" />

      <line :x1="PAD_L" :y1="avgY" :x2="W - PAD_R" :y2="avgY"
            stroke="#E6A23C" stroke-width="1.5" stroke-dasharray="6 5" />
      <text :x="W - PAD_R" :y="avgY - 7" text-anchor="end" class="avg-text">
        平均 {{ fmt(avg) }} 分
      </text>

      <g v-for="(p, i) in points" :key="i">
        <circle :cx="p.x" :cy="p.y" r="4" fill="#fff" stroke="#409EFF" stroke-width="2" class="dot" />
        <text v-if="points.length <= 12" :x="p.x" :y="p.y - 11" text-anchor="middle" class="value-text">
          {{ p.value }}
        </text>
        <title>第 {{ i + 1 }} 份 · {{ p.label }}：{{ p.value }} 分</title>
      </g>

      <line :x1="PAD_L" :y1="H - PAD_B" :x2="W - PAD_R" :y2="H - PAD_B" class="axis-line" />
      <text v-if="!points.length" :x="W / 2" :y="H / 2" text-anchor="middle" class="empty-text">暂无数据</text>
    </svg>
  </div>
</template>

<script setup>
import { computed, useId } from 'vue'

const props = defineProps({
  values: { type: Array, default: () => [] },
  labels: { type: Array, default: () => [] },
  avg: { type: Number, default: 0 },
  max: { type: Number, default: 100 },
  height: { type: Number, default: 260 },
})

const W = 720, H = 260, PAD_L = 46, PAD_R = 16, PAD_T = 26, PAD_B = 30
const areaId = 'line-area-' + useId().replace(/[^a-zA-Z0-9_-]/g, '')
const fmt = v => Number(Number(v || 0).toFixed(1))

const maxY = computed(() => Math.max(1, Number(props.max) || 100))
const plotH = H - PAD_T - PAD_B
const toY = v => PAD_T + plotH - Math.min(1, Math.max(0, Number(v || 0) / maxY.value)) * plotH

const points = computed(() => {
  const n = props.values.length
  if (!n) return []
  const plotW = W - PAD_L - PAD_R
  const stepX = n > 1 ? plotW / (n - 1) : 0
  return props.values.map((v, i) => ({
    x: PAD_L + (n > 1 ? i * stepX : plotW / 2),
    y: toY(v),
    value: fmt(v),
    label: props.labels[i] || `第${i + 1}份`,
  }))
})

const linePath = computed(() => points.value
  .map((p, i) => `${i ? 'L' : 'M'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' '))

const areaPath = computed(() => {
  const ps = points.value
  if (!ps.length) return ''
  const base = H - PAD_B
  return `${linePath.value} L${ps[ps.length - 1].x.toFixed(1)} ${base} L${ps[0].x.toFixed(1)} ${base} Z`
})

const avgY = computed(() => toY(props.avg))

const ticks = computed(() => Array.from({ length: 5 }, (_, i) => ({
  y: PAD_T + plotH * i / 4,
  label: fmt(maxY.value * (4 - i) / 4),
})))
</script>

<style scoped>
.chart-box { width: 100%; }
.grid-line { stroke: #ebeef5; stroke-width: 1; stroke-dasharray: 4 4; }
.axis-line { stroke: #dcdfe6; stroke-width: 1; }
.axis-text { fill: #909399; font-size: 11px; }
.value-text { fill: #606266; font-size: 11px; font-weight: 600; }
.avg-text { fill: #e6a23c; font-size: 11px; font-weight: 600; }
.empty-text { fill: #c0c4cc; font-size: 14px; }
.dot { cursor: pointer; transition: r .15s; }
.dot:hover { r: 6; }
</style>
