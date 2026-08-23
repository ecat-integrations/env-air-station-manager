// ASM 站房设备配置的数据 composable（镜像 ADM useAirDeviceState，去掉 NOx 归并——ASM 槽各自独立）。
// 持有：37 类型槽绑定状态(paramBindings)/选中态/详情 dump/加载态；提供 sidebar 分组计算、引用标注；
// 动作：刷新、选中槽、拉详情。弹窗编排由 StationDevicePanel 与各对话框分管。
import { ref, computed } from 'vue'
import { listParamBindings, getDeviceDetail } from '../api/device'
import { GROUP_ORDER, groupOf, labelOf, typeOf } from '../stationParamMeta'

export function useStationDeviceState() {
  const paramBindings = ref([])
  const loaded = ref(false)
  const loadError = ref('')
  const selected = ref(null)   // 当前选中 ParamBinding
  const detailData = ref('')   // 选中槽绑定设备的 entry.data 原样 JSON

  // sidebar 分组：按 GROUP_ORDER 聚合，组内按后端返回顺序（StationParamMeta 枚举序）。
  const groups = computed(() => {
    const byGroup = {}
    for (const p of paramBindings.value) {
      const g = groupOf(p.param)
      if (!byGroup[g]) byGroup[g] = []
      byGroup[g].push(p)
    }
    return GROUP_ORDER
      .map(name => ({ name, items: byGroup[name] || [] }))
      .filter(g => g.items.length > 0)
  })

  // 引用标注（前端纯计算）：同 boundDeviceId 的其他类型槽（一台物理设备可被多槽复用）
  const referencedBy = computed(() => {
    if (!selected.value || !selected.value.boundDeviceId) return []
    return paramBindings.value
      .filter(p => p.param !== selected.value.param && p.boundDeviceId === selected.value.boundDeviceId)
      .map(p => labelOf(p.param))
  })

  async function refresh() {
    try {
      const res = await listParamBindings()
      paramBindings.value = res.data || []
      loaded.value = true
      // 保留选中态：刷新后用同 param 的最新行替换
      if (selected.value) {
        const cur = paramBindings.value.find(p => p.param === selected.value.param)
        if (cur) {
          selected.value = cur
          if (cur.configured) loadDetail(cur)
          else detailData.value = ''
        }
      }
    } catch (e) {
      loadError.value = String(e.message || e)
    }
  }

  async function selectParam(p) {
    selected.value = p
    detailData.value = ''
    if (p.configured) loadDetail(p)
  }

  async function loadDetail(p) {
    try {
      const res = await getDeviceDetail(typeOf(p.param))
      detailData.value = JSON.stringify(res.data || {}, null, 2)
    } catch (e) {
      detailData.value = ''
    }
  }

  return {
    paramBindings, loaded, loadError, selected, detailData,
    groups, referencedBy,
    refresh, selectParam, loadDetail,
  }
}
