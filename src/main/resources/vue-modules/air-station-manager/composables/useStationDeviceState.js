// ASM 站房设备配置的数据 composable（镜像 ADM useAirDeviceState，去掉 NOx 归并——ASM 槽各自独立）。
// 持有：37 类型槽绑定状态(paramBindings)/选中态/详情 dump/加载态；提供 sidebar 分组计算、引用标注；
// 动作：刷新、选中槽、拉详情。弹窗编排由 StationDevicePanel 与各对话框分管。
import { ref, computed } from 'vue'
import { listParamBindings, getDeviceDetail, getCommBriefs } from '../api/device'
import { GROUP_ORDER, groupOf, labelOf, typeOf } from '../stationParamMeta'

export function useStationDeviceState() {
  const paramBindings = ref([])
  const loaded = ref(false)
  const loadError = ref('')
  const selected = ref(null)   // 当前选中 ParamBinding
  const detailData = ref(null) // 选中槽绑定设备的 entry.data 对象（ConfigDisplay 友好展示）
  // 连接摘要 { deviceId → {coordinate, className, commSettings} }：null = 尚未加载（拓扑链路节点显骨架）。
  // 与 detailData 分开：前者是全站「每台设备接哪个口」，后者只描述当前选中那一台。
  const commBriefs = ref(null)

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

  // 引用标注——全站唯一推导点（设计统一，不分叉）：某物理设备被哪些类型槽引用是 params 数据
  // （boundDeviceId）的纯函数，此处算一次并转中文标签；详情页 referencedBy 与更换设备对话框
  // 的 refs 都消费它（后端不下发 referencingParams——曾前后端各算一遍属分叉复制，已收口）。
  const referencingLabels = (boundDeviceId, excludeParam) => paramBindings.value
    .filter(p => p.boundDeviceId === boundDeviceId && p.param !== excludeParam)
    .map(p => labelOf(p.param))

  // 引用标注（前端纯计算）：同 boundDeviceId 的其他类型槽（一台物理设备可被多槽复用）
  const referencedBy = computed(() => {
    if (!selected.value || !selected.value.boundDeviceId) return []
    return referencingLabels(selected.value.boundDeviceId, selected.value.param)
  })

  async function refresh() {
    try {
      // 绑定列表与连接摘要并发：拓扑要「始终显示所有已绑设备的链路」，
      // 但两者缓存失效时机不同（摘要随设备改连接变，绑定随槽增删变），故分开取、并行跑。
      const [res] = await Promise.all([
        listParamBindings(),
        loadCommBriefs(),
      ])
      paramBindings.value = res.data || []
      loaded.value = true
      // 保留选中态：刷新后用同 param 的最新行替换
      if (selected.value) {
        const cur = paramBindings.value.find(p => p.param === selected.value.param)
        if (cur) {
          selected.value = cur
          if (cur.configured) loadDetail(cur)
          else detailData.value = null
        }
      }
    } catch (e) {
      loadError.value = String(e.message || e)
    }
  }

  async function selectParam(p) {
    selected.value = p
    detailData.value = null
    if (p.configured) loadDetail(p)
  }

  async function loadDetail(p) {
    try {
      const res = await getDeviceDetail(typeOf(p.param))
      // 保留 entry.data 对象：详细配置要按连接类型拆分 KV，原始配置再在展示层 stringify
      detailData.value = res.data || {}
    } catch (e) {
      detailData.value = null
    }
  }

  /**
   * 拉连接摘要（/asm-monitor/device/comm-briefs）。失败不阻断主流程：
   * 拓扑的链路节点降级为「—」，槽 / 设备列照常显示（用户仍能看绑定关系并操作）。
   */
  async function loadCommBriefs() {
    try {
      const res = await getCommBriefs()
      commBriefs.value = res.data || {}
    } catch (e) {
      // 保持 null → 拓扑链路节点显骨架，而不是误显示「未知连接」
      commBriefs.value = null
      console.error('[asm] 加载连接摘要失败', e)
    }
  }

  return {
    paramBindings, loaded, loadError, selected, detailData, commBriefs,
    groups, referencedBy, referencingLabels,
    refresh, selectParam, loadDetail, loadCommBriefs,
  }
}
