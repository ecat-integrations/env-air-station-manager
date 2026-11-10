<template>
  <!-- 传感器轮廓渲染器：把 stationGlyphDefs.js 里的图元数据画成 24×24 描边图标。
       新增设备类型只改数据文件（stationGlyphDefs.js），本组件不用动。
       图元用显式 SVG 标签渲染（不用动态 <component>）：SVG 子元素必须在 SVG 命名空间里创建，
       动态标签在部分运行时会被当 HTML 未知元素 → 图形不显示。 -->
  <svg
    class="glyph"
    viewBox="0 0 24 24"
    fill="none"
    :stroke-width="strokeWidth"
    stroke="currentColor"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <template v-for="(item, i) in figures" :key="i">
      <path
        v-if="item.t === 'p'"
        :d="item.d"
        :stroke-width="item.sw"
        :transform="item.transform"
      />
      <line
        v-else-if="item.t === 'l'"
        :x1="item.x1" :y1="item.y1" :x2="item.x2" :y2="item.y2"
        :stroke-width="item.sw"
        :transform="item.transform"
      />
      <circle
        v-else-if="item.t === 'c'"
        :cx="item.cx" :cy="item.cy" :r="item.r"
        :stroke-width="item.sw"
        :transform="item.transform"
      />
      <ellipse
        v-else-if="item.t === 'e'"
        :cx="item.cx" :cy="item.cy" :rx="item.rx" :ry="item.ry"
        :stroke-width="item.sw"
        :transform="item.transform"
      />
      <rect
        v-else-if="item.t === 'r'"
        :x="item.x" :y="item.y" :width="item.w" :height="item.h" :rx="item.rx"
        :stroke-width="item.sw"
        :transform="item.transform"
      />
    </template>
  </svg>
</template>

<script setup>
defineOptions({ name: 'StationGlyph' })
import { computed } from 'vue'
import { glyphDefOf } from './stationGlyphDefs.js'

const props = defineProps({
  /** STATION_GLYPHS 的 kind（由 glyphKind.js 推断） */
  kind: { type: String, default: 'generic' },
  strokeWidth: { type: [Number, String], default: 1.5 },
})

const def = computed(() => glyphDefOf(props.kind))

/**
 * 把 `g`（旋转分组）摊平成带 transform 的图元列表：
 * SVG 图元各自支持 transform，摊平后模板不需要递归，也不必嵌套 <g>。
 */
const figures = computed(() => {
  const out = []
  for (const item of def.value.items) {
    if (item.t === 'g' && Array.isArray(item.items)) {
      const transform = rotateTransform(item.rotate)
      for (const sub of item.items) out.push({ ...sub, transform })
    } else {
      out.push(item)
    }
  }
  return out
})

function rotateTransform(rotate) {
  if (!Array.isArray(rotate) || rotate.length !== 3) return undefined
  const [deg, cx, cy] = rotate
  return `rotate(${deg} ${cx} ${cy})`
}
</script>

<style scoped>
.glyph {
  width: 25px;
  height: 25px;
  display: block;
}
</style>
