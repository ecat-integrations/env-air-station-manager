<template>
  <!--
    历史数据（路由 name=history_data）：ruoyi 原生形态——el-form :inline 搜索区（粒度/时间窗/区间/单位/
    参数触发）+ mb8 操作行（导出/视图切换）+ 结果主体（列表/曲线）占满剩余视口（页面级不滚动，表格内部滚）。
    参数选择走 dialog：搜索区只读输入框回显「首项中文 等 N 项」单行，点开弹窗内勾选（设备分组+组级全选+
    搜索过滤），确定才回填勾选并重查。
    列契约：表格/导出列头 = 勾选集全集（checkedKeys 顺序），无数据参数照常占列；网格格缺桶时
    数值参数显 '-'、非数值参数显 '--'；当前粒度不可物化的参数查询时跳过（提交集=可物化子集）但
    列头保留，悬浮「当前粒度不物化」。
    查询流（等间隔网格分页，前端主导）：GET /asm-monitor/history（降序桶行）→ 前端按 uid:attrId
    分组透视进「窗口网格」——tick 全集=窗口内按粒度对齐的全部时刻（统计桶标即对齐边界），
    total=tick 数（pager「共 N 条」=N 个时刻），每页固定 N 个 tick（200/500/1000）按时间段倒序切页：
    第 1 页=窗口末段最新段、翻页向更早、页内时刻亦降序（页序/页内/表格/CSV 同一降序口径）。每页
    请求以页 tick 区间为查询窗（pageNum=1、order=DESC、pageSize=页 tick 数×参数数且≥2000 护栏），
    响应行按 dataTime 对位进网格，无数据的 tick 照常成行——不再出现扁平行集分页下「参数历史起点
    异构 → 首页整页单列 / 后页页行数骤减」的形态（响应 total/count 字段保留但不再作为 pager 依据）。
    参数候选池来自 GET /asm-monitor/stat-params（与 Java SDK listStatParams 同源；
    按 applicableGranularityMask 置灰当前粒度不可物化的参数，勾选态保留但查询时跳过）。
    契约字段（后端并行追加，缺失回退）：stat-params 行 display_unit（缺→storageUnit）/
    device_label（缺→uniqueId）；history 行 display_unit（缺→unit）。响应 total（桶行计数）
    仅为兼容保留，网格分页的 pager 依据=前端窗口 tick 数（见上）。
    非数值 series（ALARM 报警/STATE 状态，白名单 attr）：history 行 value=null + value_text（alarm/normal/
    状态串）+ validCount/totalCount（桶内非空样本数/总样本数，接口字段保留）。列表只显 value_text 原文、
    不显计数（2026-09-09 拍板移除 W1 的 N/M 内联/计数悬浮——计数对运维无读数价值且挤占列宽）、
    alarm 值红（红=报警专属）；曲线按行值域自判分类（stat-params 出参无类别字段）：ALARM（值域仅
    normal/alarm）=红/绿/灰三色状态带子图（bar 逐点着色 + showBackground 灰底，粒度补空缺桶=灰即
    「无数据≠正常」）、STATE（状态串）不画（分类域无自然序，表格读）、数值=折线。合并模式仅数值
    （ALARM 带只在分图）；CSV 值列导 value_text，仅数值参数跟「有效/总数」计数列（非数值不跟）。
  -->
  <div class="asm-page asm-history">
    <!-- 搜索区：ruoyi 原生 el-form :inline 平铺；@submit.prevent 阻原生隐式提交（Enter 整页刷新） -->
    <el-form class="asm-filter" :inline="true" @submit.prevent>
      <el-form-item label="粒度">
        <el-radio-group v-model="filter.granularity" @change="search">
          <el-radio-button v-for="g in GRANULARITY" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="时间">
        <!--
          时间线上格式逐字节不变：value-format 'YYYY-MM-DDTHH:mm:ss'（含 T 含秒壁钟串，
          后端按原 datetime-local 同串解析）；range 双端数组经 timeRange computed 拆回
          filter.start/filter.end，提交代码路径零改动。默认窗=近 1 小时。
        -->
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          range-separator="~"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          :clearable="false"
          style="width: 360px"
        />
      </el-form-item>
      <el-form-item label="区间">
        <el-radio-group v-model="filter.mode" @change="search">
          <el-tooltip v-for="m in MODES" :key="m.value" :content="MODE_HINTS[m.value]" placement="top">
            <el-radio-button :value="m.value">{{ m.label }}</el-radio-button>
          </el-tooltip>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="单位">
        <el-radio-group v-model="filter.unit" @change="search">
          <el-radio-button v-for="u in UNITS" :key="u.value" :value="u.value">{{ u.label }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="参数">
        <!--
          只读触发框（ruoyi 树选择形态）：回显单行「首项中文 等 N 项」（单项直接显示），完整清单在弹窗/
          表格列头看。@click 经 attrs 落到内层 input（element-plus inheritAttrs:false），点输入区即弹窗；
          suffix 手工放清除（checked 非空才显，el-input readonly 下原生 clearable 图标不渲染）+ 下拉箭头。
        -->
        <el-input
          :model-value="paramSummaryText"
          readonly
          placeholder="选择参数..."
          class="asm-param-input"
          @click="openParamDialog"
        >
          <template #suffix>
            <el-icon v-if="checked.length" class="asm-param-clear" title="清空已选参数" @click.stop="clearAllChecked"><CircleClose /></el-icon>
            <el-icon class="asm-param-arrow" @click="openParamDialog"><ArrowDown /></el-icon>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" :loading="loading" @click="search">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 操作行（ruoyi mb8 风格）：导出 + 单位及修约 | 右侧视图切换（列表/曲线；曲线态再切分图/合并） -->
    <el-row :gutter="10" class="mb8 asm-result-head">
      <el-col :span="1.5">
        <el-button type="warning" plain icon="Download" :loading="exporting" :disabled="!submittableKeys.length" @click="exportCsv">导出</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button plain icon="Setting" @click="openUnitDialog">单位及修约</el-button>
      </el-col>
      <el-col :span="20" class="asm-view-col">
        <el-radio-group v-model="viewMode">
          <el-radio-button value="list">列表</el-radio-button>
          <el-radio-button value="chart">曲线</el-radio-button>
        </el-radio-group>
        <template v-if="viewMode === 'chart'">
          <el-radio-group v-model="chartLayout" class="asm-chart-layout">
            <el-radio-button value="split">分图</el-radio-button>
            <el-radio-button value="merge">合并</el-radio-button>
          </el-radio-group>
          <span v-if="sameUnit" class="asm-merge-hint">单位相同可合并</span>
          <!-- 不画数量提示按布局区分（消解「选了怎么没画」困惑）：分图 ALARM 已出三色带，只剩 STATE 不画；
               合并模式 ALARM/STATE 都不参与合并（限制不变），维持全量口径文案 -->
          <span v-if="chartLayout === 'split' && stateSeriesCount" class="asm-merge-hint">{{ stateSeriesCount }} 项状态参数不画曲线（列表可查）</span>
          <span v-else-if="chartLayout === 'merge' && nonNumericSeriesCount" class="asm-merge-hint">{{ nonNumericSeriesCount }} 项报警/状态参数不画曲线（列表可查）</span>
        </template>
      </el-col>
    </el-row>

    <el-alert v-if="errorMsg" type="error" :closable="true" :title="errorMsg" @close="errorMsg = ''" style="margin-bottom: 8px" />

    <div class="asm-result-body">
      <!-- 列表：v-if=series 非空（勾选集即列契约）——全窗无数据时表头仍完整、仅 0 数据行 -->
      <div v-show="viewMode === 'list'" v-loading="loading" class="asm-table-wrap">
        <el-table v-if="series.length" :data="tableRows" size="small" border height="100%">
          <el-table-column label="时刻" min-width="170" fixed="left">
            <template #default="{ row }">{{ formatLocalDateTime(row.dataTime) }}</template>
          </el-table-column>
          <el-table-column v-for="s in series" :key="s.key" min-width="150">
            <template #header>
              <!-- 不可物化列（当前粒度被提交集剔除、数据恒空）悬浮说明；可物化列不加 tooltip -->
              <el-tooltip :disabled="isSeriesMaterializable(s)" content="当前粒度不物化" placement="top">
                <div class="asm-col-head">
                  <div class="asm-col-name">{{ s.paramName }}</div>
                  <div class="asm-col-unit">{{ s.unit ? '(' + s.unit + ')' : '' }}</div>
                </div>
              </el-tooltip>
            </template>
            <template #default="{ row }">
              <template v-if="row.cells[s.key]">
                <span :class="{ 'asm-alarm-text': isAlarmCell(row.cells[s.key]) }">{{ cellText(row.cells[s.key]) }}</span>
                <!-- 非数值行（ALARM/STATE）不显计数（2026-09-09 拍板移除 W1 的 N/M 内联与计数悬浮）：
                     状态/报警串的读数就是值本身，N/M 对运维无读数价值且挤占列宽；计数呈现只保留数值行
                     既有「·仅局部有效才显」形态（悬浮 validHint，零改动） -->
                <el-tooltip
                  v-if="partialValid(row.cells[s.key])"
                  :content="validHint(row.cells[s.key])"
                  placement="top"
                ><span class="asm-valid-dot">·</span></el-tooltip>
              </template>
              <span v-else class="asm-muted">{{ emptyCellText(s) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!loading" description="无数据（选择参数后查询）" />
      </div>

      <div v-show="viewMode === 'chart'" v-loading="loading" class="asm-chart-wrap">
        <!--
          分图（ADM 形态）：每参数独立 echarts 实例（单 grid 单 series 简单 option）+ CSS grid 排列；
          跨子图 tooltip/axisPointer 联动由官方 echarts.connect 组队提供。（实例一律 markRaw 后入
          data——经 reactive Proxy 驱动 echarts 会破坏 tooltip 挂载，见 bug-record-20260908-233000）
        -->
        <div v-if="chartLayout === 'split'" class="asm-split-grid">
          <!-- 数值 series=折线格、ALARM series=三色带格（矮格），STATE 不出容器（不出空白格、不占 CSS grid 位）；
               参与格共用同一份补空时间轴——connect 联动按 category 值对齐（见 buildAxis） -->
          <div
            v-for="s in splitSeries"
            :key="s.key"
            :ref="(el) => setSplitRef(s.key, el)"
            class="asm-split-cell"
            :class="{ 'asm-split-cell--band': isBandSeries(s) }"
          ></div>
        </div>
        <!-- 合并：单实例多 series 单 grid（仅数值 series，ALARM 带只在分图）。v-if 全新挂载：持久 v-show 容器在
             display:none 期 init 会落 100x100 默认尺寸且无自适应（图缩左上角），全新元素+nextTick 免疫 -->
        <div v-if="chartLayout === 'merge'" ref="chartEl" class="asm-chart"></div>
        <div v-if="!series.length && !loading" class="asm-chart-empty">
          <el-empty description="无数据（选择参数后查询）" />
        </div>
        <!-- 当前布局无可画 series：区别于「无数据」的明确空态（文案按布局如实区分，见 emptyChartText） -->
        <div v-else-if="!drawableSeries.length && !loading" class="asm-chart-empty">
          <el-empty :description="emptyChartText" />
        </div>
      </div>
    </div>

    <!--
      分页（等间隔网格分页）：total=窗口内网格 tick 数（按粒度对齐、前端计算，与后端桶行数/数据点数
      无关）——「共 N 条」即 N 个时刻；每页 tick 数 200/500/1000，页序=时间段倒序（第 1 页=窗口末段）。
      后端响应 total/count 字段保留但不再作为 pager 依据（count 是桶行数口径，网格分页下页边界=网格
      边界、页大小恒定，前端按窗口算才是唯一真相）。
    -->
    <div class="asm-pager" v-if="gridReady && series.length && gridTotal">
      <el-pagination
        size="small"
        background
        layout="total, sizes, prev, pager, next, jumper"
        :total="gridTotal"
        :page-sizes="PAGE_SIZES"
        :page-size="filter.pageSize"
        :current-page="filter.pageNum"
        @current-change="turnPage"
        @size-change="changePageSize"
      />
    </div>

    <!-- 参数选择弹窗：草稿勾选（dialogChecked），确定才回填 checked 并重查；取消丢弃 -->
    <el-dialog v-model="paramDialogVisible" title="选择参数" width="720px" append-to-body>
      <div v-loading="metaLoading" class="asm-params">
        <!-- 设备下拉搜索（filterable 可输入过滤选项）+ 参数关键字两段筛选：设备靠选不靠敲，减少输入；
             两控件同 size="small"（高度一致，否则并排一矮一高错位） -->
        <div class="asm-param-filter-row">
          <el-select v-model="paramDeviceUid" filterable clearable placeholder="按设备筛选（可输入搜索）" size="small" class="asm-param-device">
            <el-option v-for="d in devices" :key="d.uid" :value="d.uid" :label="d.label" />
          </el-select>
          <el-input
            v-model="paramKeyword"
            clearable
            placeholder="按参数搜索"
            size="small"
            class="asm-param-search"
          />
        </div>
        <div class="asm-param-groups">
          <div v-for="d in filteredDevices" :key="d.uid" class="asm-param-group">
            <div class="asm-param-head">
              <el-checkbox
                :model-value="groupAllChecked(d)"
                :indeterminate="groupPartialChecked(d)"
                @change="toggleGroup(d, $event)"
              >{{ d.label }}</el-checkbox>
            </div>
            <el-checkbox-group v-model="dialogChecked" class="asm-param-attrs">
              <el-tooltip
                v-for="a in d.attrs"
                :key="a.attrId"
                :disabled="isAttrApplicable(a)"
                content="当前粒度不物化（勾选保留，查询时跳过）"
                placement="top"
              >
                <el-checkbox class="asm-param-cb" :value="d.uid + ':' + a.attrId" :disabled="!isAttrApplicable(a)">
                  {{ a.paramDisplayName || a.attrId }}{{ attrUnitLabel(a) ? ' (' + attrUnitLabel(a) + ')' : '' }}
                </el-checkbox>
              </el-tooltip>
            </el-checkbox-group>
          </div>
        </div>
        <div v-if="!devices.length && !metaLoading" class="asm-empty-inline">暂无可查参数（参数候选来自 stat-params，需先建聚合配置）</div>
        <div v-else-if="!filteredDevices.length && !metaLoading" class="asm-empty-inline">无匹配参数</div>
      </div>
      <template #footer>
        <el-button @click="paramDialogVisible = false">取 消</el-button>
        <el-button type="primary" @click="confirmParamDialog">确 定</el-button>
      </template>
    </el-dialog>

    <!-- 历史查询单位偏好（HISTORY purpose）：与配置页>单位偏好同源同端点（PUT config-unit），
         一处保存两处生效；编辑口径复用配置页单位 tab 行形态（设备选择 + 参数行单位下拉）。 -->
    <el-dialog v-model="unitDialogVisible" title="历史查询单位（HISTORY 偏好）" width="680px" append-to-body>
      <div v-loading="unitLoading">
        <el-alert v-if="unitError" type="error" :closable="false" :title="unitError" style="margin-bottom: 10px" />
        <el-select v-model="unitDeviceUid" filterable clearable placeholder="选择设备" size="small" style="width: 300px; margin-bottom: 10px">
          <el-option v-for="d in unitDevices" :key="d.uid" :value="d.uid" :label="d.label" />
        </el-select>
        <table v-if="unitRows.length" class="asm-unit-table">
          <thead>
            <tr><th>参数</th><th>历史查询单位</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in unitRows" :key="r.attrId">
              <td>{{ r.displayName }}</td>
              <td>
                <el-select v-if="r.unitOptions" v-model="r.historyUnit" size="small" style="width: 150px" @change="markUnitDirty(r)">
                  <el-option label="原生（不换算）" value="" />
                  <el-option-group v-for="g in r.unitOptions" :key="g.classLabel" :label="g.classLabel">
                    <el-option v-for="u in g.units" :key="u.key" :value="u.key" :label="u.symbol" />
                  </el-option-group>
                </el-select>
                <span v-else class="asm-muted">{{ r.unitSymbol || '-' }}</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else-if="!unitLoading" class="asm-empty-inline">{{ unitDeviceUid ? '该设备暂无可配单位的数值参数' : '选择设备后编辑其参数的历史查询单位' }}</div>
        <div class="asm-hint">
          单位候选与「配置页 &gt; 单位偏好」同源（一处保存两处生效）。历史数据出口不修约——小数位仅监控页生效，此处只编辑单位。
          保存后按上方「单位=自定义」口径生效，页面将自动切换并重查。
        </div>
      </div>
      <template #footer>
        <el-button size="small" @click="unitDialogVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="unitSaving" :disabled="!unitRows.some((r) => r._dirty)" @click="saveUnitPrefs">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { markRaw } from 'vue'
// keep-alive 契约：Options API 组件 name 必须等于路由 name（history_data），宿主 keep-alive 按组件名匹配缓存。

import { queryHistory, listStatParams, getSnapshot, getConfigUnit, putConfigUnit } from '@/api/asm'
import { formatLocalDateTime, formatLocalInputSeconds } from '@/utils/datetime'
import { downloadCsv, EXPORT_PAGE_TICKS, EXPORT_MAX_ROWS } from '@/utils/historyExport'

// 粒度 → applicableGranularityMask 位（与后端 AsmGranularityMask 同定义：bit0=minute/bit1=5min/bit2=hour）
const GRANULARITY_BIT = { MINUTE: 1, FIVE_MIN: 2, HOUR: 4 }

const GRANULARITY = [
  { value: 'MINUTE', label: '分钟' },
  { value: 'FIVE_MIN', label: '5分钟' },
  { value: 'HOUR', label: '小时' },
]
// 区间按钮文案简化为 后标/前标，原语义 (L,R]/[S,E) 经悬浮说明保留
const MODES = [
  { value: 'BACK', label: '后标' },
  { value: 'FRONT', label: '前标' },
]
const MODE_HINTS = {
  BACK: '后标 (L,R]：统计桶按闭合右端归属',
  FRONT: '前标 [S,E)：统计桶按闭合左端归属',
}
const UNITS = [
  { value: 'custom', label: '自定义' },
  { value: 'standard', label: '标准' },
]
// 每页 tick 数三档（el-pagination sizes 选择器）：默认 200=既有口径，1000 档让分钟粒度 1 天（1440
// 时刻）两页看完；导出循环按独立档位（EXPORT_PAGE_TICKS）切段，与页面浏览档位解耦
const PAGE_SIZES = [200, 500, 1000]

/**
 * 单页请求 pageSize 护栏下限（桶行数口径）：一页要装下「页 tick 数 × 参数数」的桶行（网格一行 =
 * 一时刻全参数），2000 起步保证小参数集时也不必靠后端分页；REST 侧另有 20000 上限护栏（超限被
 * 钳位时表现为部分格无数据，不崩页），10 参数×1000/页=10000 在护栏内。
 */
const GRID_MIN_QUERY_PAGE_SIZE = 2000

// 参数勾选记忆（uid:attrId 数组）：跨会话恢复用户粘性选择
const CHECKED_STORAGE_KEY = 'asm-history-checked'

/**
 * 桶行集 → series 分组。列基准=勾选集全集（checkedKeys 顺序）：勾选但窗口无数据的参数照常占列
 * （表头完整、网格格显占位符），不再随返回数据行裁剪列；meta 缺失回退 key 原文（uid/attrId 拆首个 ':'）。
 * unit 优先取行内 display_unit（随查询的 standard/custom 口径变化），行内无单位再回退
 * stat-params 元数据（display_unit→storageUnit）——无数据列只能取 meta 口径。
 */
function buildSeries(checkedKeys, rows, metaByKey) {
  const byKey = new Map()
  const out = []
  for (const key of checkedKeys) {
    const meta = metaByKey.get(key)
    const i = key.indexOf(':')
    const s = {
      key,
      deviceLabel: meta ? meta.deviceLabel : key.slice(0, i),
      paramName: meta ? meta.paramName : key.slice(i + 1),
      unit: '',
      points: [],
    }
    out.push(s)
    byKey.set(key, s)
  }
  for (const r of rows) {
    const s = byKey.get(r.logicDeviceUniqueId + ':' + r.attrId)
    // 行集键不在勾选集（查询在途时勾选被确认/清空改写的竞态）→ 以勾选集为列基准，无列可归则弃
    if (!s) continue
    s.points.push(r)
    if (!s.unit) s.unit = r.display_unit || r.unit || ''
  }
  for (const s of out) {
    if (!s.unit) {
      const meta = metaByKey.get(s.key)
      if (meta) s.unit = meta.unit
    }
    s.name = s.deviceLabel + '·' + s.paramName
  }
  return out
}

/**
 * 等间隔网格分页的窗口网格（本页唯一分页模型的纯函数）：tick 全集=窗口 [start,end] 内按粒度对齐的
 * 全部网格边界时刻（firstMs=进位对齐、lastMs=舍位对齐，两端含）。total=tick 数而非数据点数——
 * 无数据的 tick 照常成行（等间隔网格：时刻连续、缺数据显占位），不再随参数历史起点异构而塌缩。
 * 步长取自 GRANULARITY_STEP_MS（与后端 AsmStatGranularity.interval 同定义，桶标即对齐边界）。
 * @param {string} startStr 窗口起（datetime-local 线上串，本地壁钟）
 * @param {string} endStr 窗口止（同上）
 * @param {number} stepMs 粒度步长 ms
 * @param {number} pageSize 每页 tick 数
 * @returns {{firstMs: number, lastMs: number, stepMs: number, pageSize: number, total: number, pages: number}|null} null=窗口内无对齐时刻（不足一个步长）
 */
function buildGridWindow(startStr, endStr, stepMs, pageSize) {
  const startMs = new Date(startStr).getTime()
  const endMs = new Date(endStr).getTime()
  if (Number.isNaN(startMs) || Number.isNaN(endMs) || !(stepMs > 0) || !(pageSize > 0)) return null
  const firstMs = Math.ceil(startMs / stepMs) * stepMs
  const lastMs = Math.floor(endMs / stepMs) * stepMs
  if (lastMs < firstMs) return null
  const total = (lastMs - firstMs) / stepMs + 1
  // pageSize 入窗（非独立传参）：页切分档位与窗口绑定成单一真相——切页/请求窗/行展开必须用同一个
  // 窗口对象，混用两套档位（浏览档 vs 导出档）会切出越界段（start>end 请求，实测缺陷）
  return { firstMs, lastMs, stepMs, pageSize, total, pages: Math.ceil(total / pageSize) }
}

/**
 * 网格第 k 页（1 起，倒序切页）的 tick 序号区间 [fromIdx,toIdx]（全集升序 0 基含两端）：
 * fromIdx=total-k*N 截到 0（末页余量）、toIdx=total-(k-1)*N-1；页越界钳回末页（窗口/页大小
 * 变更后的陈旧页码）。页切分与请求窗、行展开共用本索引，保证「页=时间段」单一真相。
 */
function gridPageIndex(win, pageNum) {
  const page = Math.min(Math.max(pageNum, 1), win.pages)
  const n = win.pageSize
  return {
    fromIdx: Math.max(0, win.total - page * n),
    toIdx: Math.min(win.total - (page - 1) * n - 1, win.total - 1),
  }
}

/**
 * 网格第 k 页 → 后端查询窗：页 tick 区间的 [start,end) 表达——上限多让一个步长，后端「前闭后开」
 * 窗口才恰好含入段末 tick 的桶标（段边界=网格边界，页间不重不漏）。
 * @returns {{start: string, end: string}} datetime-local 线上串（对齐时刻秒位恒 00，无精度损失）
 */
function gridSegment(win, pageNum) {
  const { fromIdx, toIdx } = gridPageIndex(win, pageNum)
  const loMs = win.firstMs + fromIdx * win.stepMs
  const hiMs = win.firstMs + toIdx * win.stepMs
  return {
    start: formatLocalInputSeconds(new Date(loMs)),
    end: formatLocalInputSeconds(new Date(hiMs + win.stepMs)),
  }
}

/**
 * 网格透视图（降序 ticks × series → 行集）：每个 tick 恒生成一行，cells 按 dataTime(ms) 对位填充
 * （cells 缺键=该 series 此 tick 无桶，占位由调用方按 series 类别定）。表格/曲线/导出共用同一展开；
 * 桶行→tick 按 ms 精确对位的依据=物化桶标即网格边界（对齐写入），越界/错位行在契约外不存在。
 * @param {number[]} ticksDesc 网格 tick ms（降序）
 * @param {Array} seriesArr buildSeries 出参（points=桶行）
 */
function gridPivotRows(ticksDesc, seriesArr) {
  const cellByTick = new Map()
  for (const s of seriesArr) {
    for (const p of s.points) {
      const t = new Date(p.dataTime).getTime()
      let m = cellByTick.get(t)
      if (!m) {
        m = new Map()
        cellByTick.set(t, m)
      }
      m.set(s.key, p)
    }
  }
  return ticksDesc.map((t) => {
    const m = cellByTick.get(t)
    const cells = {}
    if (m) {
      for (const [k, v] of m) cells[k] = v
    }
    return { dataTime: t, cells }
  })
}

// ALARM 三色带配色：红=报警（#f56c6c，与表格 .asm-alarm-text / statusBadge danger 同源，报警专属不挪用）、
// 绿=正常（#67c23a=success）、灰=无数据（#909399=info，showBackground 底色）
const BAND_ALARM_COLOR = '#f56c6c'
const BAND_NORMAL_COLOR = '#67c23a'
const BAND_NONE_COLOR = '#909399'
// 三色图例语义用子图注文字承载：echarts 原生 legend 项=series，单 series 带分不出三色（实测结论）
const BAND_LEGEND_TEXT = '{alarm|■报警} {normal|■正常} {none|■无数据}'
const BAND_LEGEND_RICH = {
  alarm: { color: BAND_ALARM_COLOR, fontSize: 10 },
  normal: { color: BAND_NORMAL_COLOR, fontSize: 10 },
  none: { color: BAND_NONE_COLOR, fontSize: 10 },
}
// 粒度 → 桶步长 ms（与后端 AsmStatGranularity.interval 同定义：分钟/5分钟/小时）
const GRANULARITY_STEP_MS = { MINUTE: 60 * 1000, FIVE_MIN: 5 * 60 * 1000, HOUR: 3600 * 1000 }

/**
 * series 类别（行值域自判，stat-params 出参无类别字段——设计决策后端零改动）：
 * NUMERIC=数值折线；ALARM=值域仅 normal/alarm → 三色状态带；STATE=其余状态串 → 不画（表格读）。
 * 判定看该 series 页内行的 value_text 全集：空集=数值（含窗口无行的参数，维持折线空图现状）；
 * 出现二值域之外的串（on/off/cooling 等）即 STATE（该 series 不画，保守不猜测其余行的域）。
 */
function seriesKind(s) {
  let hasText = false
  for (const p of s.points) {
    if (p.value_text == null) continue
    hasText = true
    if (p.value_text !== 'normal' && p.value_text !== 'alarm') return 'STATE'
  }
  return hasText ? 'ALARM' : 'NUMERIC'
}

export default {
  name: 'history_data',
  data() {
    const now = new Date()
    return {
      GRANULARITY, MODES, MODE_HINTS, UNITS, PAGE_SIZES,
      filter: {
        granularity: 'HOUR',
        mode: 'BACK',
        unit: 'custom',
        start: formatLocalInputSeconds(new Date(now.getTime() - 3600 * 1000)),
        end: formatLocalInputSeconds(now),
        pageNum: 1,
        pageSize: 200,
      },
      checked: [],
      devices: [],
      paramKeyword: '',
      paramDeviceUid: '',
      metaLoading: false,
      loading: false,
      exporting: false,
      rows: [],
      // 当前筛选下已成功取数（网格行/分页器的渲染闸）：查询失败/清参时不渲染占位网格行，
      // 免「错误横幅下出现整页 '-' 空行」的误导形态
      gridReady: false,
      // series 类别跨页记忆（key → ALARM/STATE）：类别只能从行值域自判（stat-params 无类别字段），
      // 稀疏 series 在无行页会被判成数值 → 占位符同列跨页翻形；观测到非数值一次即记忆，翻页/换页稳定
      seriesKindMemo: {},
      errorMsg: '',
      viewMode: 'list',
      chartLayout: 'split',
      // echarts 实例必须 markRaw 入 data：Vue3 深度 reactive 会把实例包成 Proxy，经 Proxy 调
      // setOption 破坏 echarts 内部身份比较 → Tooltip 视图永不挂载（bug-record-20260908-233000 根因）。
      mergeChart: null,   // 合并图实例（每渲染重建，markRaw）
      splitCharts: {},    // 分图：series key → 独立实例（每渲染重建，markRaw）
      splitEls: {},       // 分图：series key → 容器元素（函数 ref 收集）
      // 参数选择弹窗：dialogChecked=草稿（打开时从 checked 拷贝，确定才回填并重查，取消丢弃）
      paramDialogVisible: false,
      dialogChecked: [],
      // 单位及修约弹窗（HISTORY purpose）：snapshot 供设备/参数中文名与单位候选，config-unit 供已存偏好回显
      unitDialogVisible: false,
      unitLoading: false,
      unitSaving: false,
      unitError: '',
      unitSnapDevices: [],
      unitPrefs: [],
      unitDeviceUid: null,
      unitRows: [],
    }
  },
  computed: {
    // el-date-picker(datetimerange) 双端数组 ↔ filter.start/end 单值串的桥；
    // 提交侧仍读 filter.start/filter.end（线上格式由 value-format 逐字节保证，queryHistory 调用点零改动）。
    timeRange: {
      get() {
        return [this.filter.start, this.filter.end]
      },
      set([start, end]) {
        this.filter.start = start
        this.filter.end = end
      },
    },
    applicableBit() {
      return GRANULARITY_BIT[this.filter.granularity] || 0
    },
    // 全参数索引 key(uid:attrId) → {deviceLabel, paramName, unit, mask}（回显/series 命名与勾选有效性共用）
    metaByKey() {
      const map = new Map()
      for (const d of this.devices) {
        for (const a of d.attrs) {
          map.set(d.uid + ':' + a.attrId, {
            key: d.uid + ':' + a.attrId,
            deviceLabel: d.label,
            paramName: a.paramDisplayName || a.attrId,
            unit: a.display_unit || a.storageUnit || '',
            mask: a.applicableGranularityMask || 0,
          })
        }
      }
      return map
    },
    // 弹窗搜索过滤：设备名/uid 命中→整组保留；否则只留参数名/attrId 命中的行
    filteredDevices() {
      // 两段筛选：设备下拉（精确选中，clearable 清空=全部）先行，参数关键字在剩余组内再滤参数名
      let pool = this.devices
      if (this.paramDeviceUid) {
        pool = pool.filter((d) => d.uid === this.paramDeviceUid)
      }
      const kw = this.paramKeyword.trim().toLowerCase()
      if (!kw) return pool
      return pool
        .map((d) => {
          if (String(d.label).toLowerCase().includes(kw) || d.uid.toLowerCase().includes(kw)) return d
          const attrs = d.attrs.filter((a) =>
            String(a.paramDisplayName || '').toLowerCase().includes(kw) || String(a.attrId).toLowerCase().includes(kw))
          return attrs.length ? { ...d, attrs } : null
        })
        .filter(Boolean)
    },
    // 参数触发框单行回显：单项直接显示中文名；多项=首项中文 + 「等 N 项」（完整清单见弹窗/表格列头）
    paramSummaryText() {
      if (!this.checked.length) return ''
      const m = this.metaByKey.get(this.checked[0])
      const label = m ? m.deviceLabel + '·' + m.paramName : this.checked[0]
      return this.checked.length > 1 ? `${label} 等 ${this.checked.length} 项` : label
    },
    // 提交集：勾选中当前粒度可物化的（不可物化的保留勾选态置灰、列头保留，仅查询时跳过）
    submittableKeys() {
      const bit = this.applicableBit
      return this.checked.filter((k) => {
        const m = this.metaByKey.get(k)
        return m && (m.mask & bit) !== 0
      })
    },
    series() {
      return buildSeries(this.checked, this.rows, this.metaByKey)
    },
    // 当前页网格（纯派生：窗口/粒度/页大小 → tick 全集）；null=窗口不足一个粒度步长
    gridWindow() {
      return buildGridWindow(
        this.filter.start, this.filter.end,
        GRANULARITY_STEP_MS[this.filter.granularity] || 0, this.filter.pageSize)
    },
    // pager total=窗口内网格 tick 数（N 个时刻），非后端桶行数
    gridTotal() {
      return this.gridWindow ? this.gridWindow.total : 0
    },
    // 当前页网格行（降序，表格直用不反转）：tick 恒成行，cells 缺键=该 series 此刻无桶
    tableRows() {
      if (!this.gridReady || !this.gridWindow || !this.series.length) return []
      const win = this.gridWindow
      const { fromIdx, toIdx } = gridPageIndex(win, this.filter.pageNum)
      const ticks = []
      for (let i = toIdx; i >= fromIdx; i--) ticks.push(win.firstMs + i * win.stepMs)
      return gridPivotRows(ticks, this.series)
    },
    sameUnit() {
      return this.series.length > 1 && this.series.every((s) => (s.unit || '') === (this.series[0].unit || ''))
    },
    // 曲线参与集=数值 series（seriesKind 行值域自判：value_text 空集=数值，stat-params 无类别字段拿到行才能判）
    chartSeries() {
      return this.series.filter((s) => seriesKind(s) === 'NUMERIC')
    },
    // STATE series（状态串）数 → 分图不画曲线提示文案用
    stateSeriesCount() {
      return this.series.filter((s) => seriesKind(s) === 'STATE').length
    },
    // 分图参与集：数值折线 + ALARM 三色带（保持勾选顺序混排）；STATE 不参与
    splitSeries() {
      return this.series.filter((s) => seriesKind(s) !== 'STATE')
    },
    // 当前布局可画 series（渲染清场与空态判定共用口径）：分图=数值+ALARM 带，合并=仅数值
    drawableSeries() {
      return this.chartLayout === 'split' ? this.splitSeries : this.chartSeries
    },
    // 无可画 series 的空态文案：分图全为 STATE / 合并全为非数值，两场景成因不同不共用一句
    emptyChartText() {
      return this.chartLayout === 'split'
        ? '所选参数均为状态类，不绘制曲线（见列表）'
        : '所选参数均为报警/状态类，不参与合并（见分图/列表）'
    },
    // 非数值 series 数（合并模式提示文案用）
    nonNumericSeriesCount() {
      return this.series.length - this.chartSeries.length
    },
    // 弹窗设备候选：仅含数值参数（attrGroup==2）的设备——单位只对数值行有意义（配置页同口径）
    unitDevices() {
      return this.unitSnapDevices
        .filter((d) => (d.attrs || []).some((a) => a.attrGroup === 2))
        .map((d) => ({ uid: d.logicDeviceUniqueId, label: d.displayName || d.logicDeviceUniqueId }))
        .sort((a, b) => String(a.label).localeCompare(String(b.label), 'zh-Hans-CN'))
    },
  },
  watch: {
    // 切到曲线：容器 v-show 生效后（nextTick）再 init/渲染，避免隐藏容器 0 尺寸初始化
    viewMode(v) {
      if (v === 'chart') this.$nextTick(() => this.renderChart())
    },
    chartLayout() {
      this.renderChart()
    },
    unitDeviceUid() {
      this.buildUnitRows()
    },
  },
  mounted() {
    this.loadMeta()
    window.addEventListener('resize', this.resize)
  },
  // keep-alive 复活：容器尺寸可能已变（离开期间窗口缩放/侧栏折叠），补一次 resize
  activated() {
    this.$nextTick(() => this.resize())
  },
  beforeUnmount() {
    window.removeEventListener('resize', this.resize)
    this.disposeAllCharts()
  },
  methods: {
    formatLocalDateTime,
    // —— 参数选择弹窗 ——
    attrUnitLabel(a) {
      return a.display_unit || a.storageUnit || ''
    },
    isAttrApplicable(a) {
      return ((a.applicableGranularityMask || 0) & this.applicableBit) !== 0
    },
    // 表列头是否当前粒度可物化（meta 缺失=曾被提交过，视为可物化，不加「不物化」悬浮）
    isSeriesMaterializable(s) {
      const m = this.metaByKey.get(s.key)
      return !m || (m.mask & this.applicableBit) !== 0
    },
    groupAllChecked(d) {
      const app = d.attrs.filter((a) => this.isAttrApplicable(a))
      return app.length > 0 && app.every((a) => this.dialogChecked.includes(d.uid + ':' + a.attrId))
    },
    groupPartialChecked(d) {
      const app = d.attrs.filter((a) => this.isAttrApplicable(a))
      const n = app.filter((a) => this.dialogChecked.includes(d.uid + ':' + a.attrId)).length
      return n > 0 && n < app.length
    },
    // 组级全选只作用于可物化参数；取消则清整组（含置灰勾选项——用户取消意图明确）。
    // 只改草稿 dialogChecked，确定才回填 checked 并重查。
    toggleGroup(d, val) {
      if (val) {
        const appKeys = d.attrs.filter((a) => this.isAttrApplicable(a)).map((a) => d.uid + ':' + a.attrId)
        this.dialogChecked = [...new Set([...this.dialogChecked, ...appKeys])]
      } else {
        const groupKeys = new Set(d.attrs.map((a) => d.uid + ':' + a.attrId))
        this.dialogChecked = this.dialogChecked.filter((k) => !groupKeys.has(k))
      }
    },
    openParamDialog() {
      this.dialogChecked = [...this.checked]
      this.paramKeyword = ''
      this.paramDeviceUid = ''
      this.paramDialogVisible = true
    },
    confirmParamDialog() {
      this.checked = [...this.dialogChecked]
      this.persistChecked()
      this.paramDialogVisible = false
      this.seriesKindMemo = {} // 列契约变更，类别记忆随勾选集重置
      this.search()
    },
    // 触发框清除按钮：清空全部勾选并清结果（不发起零参数查询——那只会得到必败错误提示）；
    // 勾选集是列契约与网格行的口径来源，一并归位页码与取数态，免残留上一次查询的网格
    clearAllChecked() {
      this.checked = []
      this.persistChecked()
      this.rows = []
      this.gridReady = false
      this.seriesKindMemo = {} // 勾选集清空，类别记忆一并归位
      this.filter.pageNum = 1
      this.errorMsg = ''
    },
    persistChecked() {
      try {
        localStorage.setItem(CHECKED_STORAGE_KEY, JSON.stringify(this.checked))
      } catch (e) {
        // 隐私模式写失败 → 仅内存态（刷新丢失，不崩）
      }
    },
    // 勾选记忆恢复：过滤掉当前 meta 已不存在的键（设备/参数下线后不留死勾选）
    restoreChecked() {
      let stored = []
      try {
        const raw = localStorage.getItem(CHECKED_STORAGE_KEY)
        const parsed = raw ? JSON.parse(raw) : null
        if (Array.isArray(parsed)) stored = parsed.filter((k) => typeof k === 'string')
      } catch (e) {
        stored = [] // 脏数据 → 空勾选
      }
      const valid = new Set(this.metaByKey.keys())
      this.checked = stored.filter((k) => valid.has(k))
      if (this.checked.length !== stored.length) this.persistChecked()
    },
    // 重置：恢复默认窗（近 1 小时）/粒度/区间/单位并清参数勾选与结果（零参数不发起查询）
    resetQuery() {
      const now = new Date()
      this.filter.granularity = 'HOUR'
      this.filter.mode = 'BACK'
      this.filter.unit = 'custom'
      this.filter.start = formatLocalInputSeconds(new Date(now.getTime() - 3600 * 1000))
      this.filter.end = formatLocalInputSeconds(now)
      this.filter.pageNum = 1
      this.checked = []
      this.persistChecked()
      this.rows = []
      this.gridReady = false
      this.seriesKindMemo = {} // 重置归位默认筛选与勾选集，类别记忆一并归位
      this.errorMsg = ''
    },
    // —— 查询 ——
    validate() {
      if (!this.filter.start || !this.filter.end) return '请选择开始和结束时间'
      const s = new Date(this.filter.start).getTime()
      const e = new Date(this.filter.end).getTime()
      if (Number.isNaN(s) || Number.isNaN(e)) return '时间格式无效'
      if (s >= e) return '开始时间须早于结束时间'
      return ''
    },
    queryBase() {
      return {
        granularity: this.filter.granularity,
        start: this.filter.start,
        end: this.filter.end,
        params: this.submittableKeys.join(','),
        mode: this.filter.mode,
        unit: this.filter.unit,
      }
    },
    async loadMeta() {
      this.metaLoading = true
      try {
        // stat-params（SDK 同源）：只列已建聚合配置的参数（有 stat 桶可查）；
        // device_label/display_unit 为并行追加契约字段，缺失回退 uniqueId/storageUnit
        const res = await listStatParams()
        const metas = (res && res.data) || []
        const map = new Map()
        for (const m of metas) {
          if (!map.has(m.logicDeviceUniqueId)) {
            map.set(m.logicDeviceUniqueId, {
              uid: m.logicDeviceUniqueId,
              label: m.device_label || m.logicDeviceUniqueId,
              attrs: [],
            })
          }
          map.get(m.logicDeviceUniqueId).attrs.push(m)
        }
        this.devices = [...map.values()]
        this.restoreChecked()
      } finally {
        this.metaLoading = false
      }
    },
    async load() {
      const err = this.validate()
      if (err) { this.errorMsg = err; return }
      if (!this.submittableKeys.length) { this.errorMsg = '请至少勾选一个当前粒度可物化的参数'; return }
      const win = this.gridWindow
      if (!win) { this.errorMsg = '时间窗内无对齐时刻（窗口不足一个粒度步长）'; return }
      // 窗口收窄/页大小变更后的陈旧页码先归位（pager 与请求共用同一页区间，避免两处各钳一次）
      if (this.filter.pageNum > win.pages) this.filter.pageNum = win.pages
      this.errorMsg = ''
      this.loading = true
      this.gridReady = false
      try {
        // 网格分页：pageNum 恒 1（分页由前端网格切，后端一次拉齐本页时间段）、order=DESC（DB 侧
        // 排好最新在前）、pageSize=页 tick 数×参数数且≥2000（REST 侧 20000 护栏内）
        const seg = gridSegment(win, this.filter.pageNum)
        const res = await queryHistory({
          ...this.queryBase(),
          start: seg.start,
          end: seg.end,
          pageNum: 1,
          pageSize: Math.max(GRID_MIN_QUERY_PAGE_SIZE, this.filter.pageSize * this.submittableKeys.length),
          order: 'DESC',
        })
        this.rows = ((res || {}).data || {}).rows || []
        this.gridReady = true
        this.rememberSeriesKinds()
        if (this.viewMode === 'chart') this.renderChart()
      } catch (e) {
        this.rows = []
        this.gridReady = false
        this.errorMsg = (e && e.message) || '查询失败'
        if (this.viewMode === 'chart') this.renderChart()
      } finally {
        this.loading = false
      }
    },
    search() {
      this.filter.pageNum = 1
      this.load()
    },
    turnPage(p) {
      this.filter.pageNum = p
      this.load()
    },
    // 每页条数变更：页码归 1 重查（行数口径变了，原页位无意义）。element-plus 经 props 驱动时
    // 只发 size-change（钳位检查读的是未刷新的旧 props，不发 current-change），无双查。
    changePageSize(size) {
      this.filter.pageSize = size
      this.filter.pageNum = 1
      this.load()
    },
    // —— 表格单元格 ——
    // 值槽取值（表格/导出共用口径）：非数值行=value_text 原文、数值行=value、无行/两槽皆空=null
    //（占位由调用方定：表格 '--'、导出空串）。value_text 判定先行——非数值行 value 恒 null。
    cellValue(p) {
      if (!p) return null
      if (p.value_text != null) return p.value_text
      return p.value
    },
    cellText(p) {
      const v = this.cellValue(p)
      return v == null ? '--' : v
    },
    /**
     * series 类别统一出口（占位形态与 CSV 计数列共用同一判定）：本行集有行按值域自判（stat-params
     * 无类别字段）；无行读跨页记忆（rememberSeriesKinds）；两处都没有退数值口径——不做猜测性兜底。
     */
    seriesKindOf(s) {
      return s.points.length ? seriesKind(s) : (this.seriesKindMemo[s.key] || 'NUMERIC')
    },
    /**
     * 空 tick 格占位（等间隔网格行恒在、该 series 此刻无桶）：数值 '-'、非数值（报警/状态）'--'。
     * 两形态区分「数值参数该时刻无样本」与「非数值参数该时刻无观测」——非数值沿既有 '--' 口径
     * （无观测不得被误读为正常态，与曲线灰底「无数据≠正常」同语义），数值 '-' 表纯缺桶。
     */
    emptyCellText(s) {
      return this.seriesKindOf(s) === 'NUMERIC' ? '-' : '--'
    },
    /** 记录本页观测到的非数值 series 类别（数值不记：缺省即数值口径）。 */
    rememberSeriesKinds() {
      for (const s of this.series) {
        if (s.points.length) {
          const kind = seriesKind(s)
          if (kind !== 'NUMERIC') this.seriesKindMemo[s.key] = kind
        }
      }
    },
    // 报警态专属红（#f56c6c，与 statusBadge danger 同源）：仅值串 'alarm' 命中；
    // normal/状态串（on/off/cooling 等）不占红——红保留给报警语义
    isAlarmCell(p) {
      return p.value_text === 'alarm'
    },
    // 非数值行（ALARM/STATE）：value=null + value_text 非空
    isNonNumericCell(p) {
      return p.value_text != null
    },
    hasCounts(p) {
      return p.validCount != null && p.totalCount != null
    },
    // 计数短文本（仅 CSV 数值参数计数列用）：「非空样本数/总样本数」，悬浮全称见 validHint
    countText(p) {
      return `${p.validCount}/${p.totalCount}`
    },
    partialValid(p) {
      return p.validCount != null && p.totalCount != null && p.validCount < p.totalCount
    },
    validHint(p) {
      return `有效 ${p.validCount}/共 ${p.totalCount}`
    },
    // —— 曲线 ——
    resize() {
      if (this.mergeChart) this.mergeChart.resize()
      Object.values(this.splitCharts).forEach((c) => c.resize())
    },
    /** 分图函数 ref 收集（v-for 渲染期逐个回调；卸载时 el=null 清键）。 */
    setSplitRef(key, el) {
      if (el) this.splitEls[key] = el
      else delete this.splitEls[key]
    },
    disposeSplit() {
      Object.values(this.splitCharts).forEach((c) => { try { c.dispose() } catch (e) { /* 已释放 */ } })
      this.splitCharts = {}
    },
    disposeAllCharts() {
      this.disposeSplit()
      if (this.mergeChart) { try { this.mergeChart.dispose() } catch (e) { /* 已释放 */ } this.mergeChart = null }
    },
    renderChart() {
      if (this.viewMode !== 'chart') return
      // 当前布局无可画 series：清场（空态提示由模板 .asm-chart-empty 承载）
      if (!this.drawableSeries.length) { this.disposeAllCharts(); return }
      // 分图/合并同一份页网格轴：缺数据槽位=null（ALARM 带露灰底承载「无数据≠正常」，数值折线
      // connectNulls 跨接视觉不变）——补空语义不再分布局，统一网格天然覆盖
      if (this.chartLayout === 'split') this.renderSplit(this.buildAxis())
      else this.renderMerge(this.buildAxis())
    },
    /**
     * 曲线时间轴=当前页网格升序（读图方向旧→新，与表格降序展示解耦；connect 联动按 category 值
     * 对齐的硬前提=各子图同一份轴）。页边界=网格边界，原 W3「页内观测跨度补空」被统一网格取代：
     * 轴恒为页内全部 tick、无切半段，rowByLabel 值=网格行（cells 缺键即缺桶）。
     * 'YYYY-MM-DD HH:mm' 定宽零填充，字典序即时间序。
     */
    buildAxis() {
      const times = []
      const rowByLabel = new Map()
      for (let i = this.tableRows.length - 1; i >= 0; i--) {
        const row = this.tableRows[i]
        const label = formatLocalDateTime(row.dataTime, true)
        times.push(label)
        rowByLabel.set(label, row)
      }
      return { times, rowByLabel }
    },
    // 模板格子形态标记（ALARM 带格=矮格样式）
    isBandSeries(s) {
      return seriesKind(s) === 'ALARM'
    },
    /**
     * 分图（ADM 形态）：v-for 容器挂载后逐参数全新实例，一次 setOption(notMerge)；
     * echarts.connect 组队提供官方跨子图联动（tooltip/axisPointer/dataZoom/图例同步）——联动按
     * category 值对齐，数值格与带格必须喂同一份补空时间轴（实测：异轴则兄弟格不同步）。
     * v-if 切换布局后容器是全新元素，实例重建天然完成旧图清理。
     * markRaw 后存 data（见 data() 注释）：实例读取恒为 raw，杜绝经 Proxy 调用。
     */
    renderSplit(axis) {
      this.disposeSplit()
      if (this.mergeChart) this.mergeChart.clear()
      this.$nextTick(() => {
        const charts = []
        for (const s of this.splitSeries) {
          const el = this.splitEls[s.key]
          if (!el) continue
          const inst = markRaw(echarts.init(el))
          inst.setOption(this.isBandSeries(s) ? this.splitBandOption(s, axis) : this.splitCellOption(s, axis), true)
          this.splitCharts[s.key] = inst
          charts.push(inst)
        }
        if (charts.length > 1) echarts.connect(charts)
      })
    },
    /** 分图数值格 option：单 grid 单 series 简单形态（教科书用法，宿主实证 Tooltip 正常）。 */
    splitCellOption(s, axis) {
      return {
        animation: false,  // 查询即重建的小图动画无收益，走同步渲染
        title: { text: s.name + (s.unit ? ' (' + s.unit + ')' : ''), left: 6, top: 2, textStyle: { fontSize: 12, fontWeight: 500 } },
        tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'line' } },
        grid: { left: 48, right: 14, top: 30, bottom: 26 },
        xAxis: { type: 'category', data: axis.times, axisLabel: { hideOverlap: true } },
        yAxis: { type: 'value', scale: true },
        // 单点序列必须显示符号：showSymbol:false 下单点无线段可画=子图空白（1h 窗口小时粒度常态）
        series: [{ name: s.name, type: 'line', showSymbol: s.points.length <= 1, symbolSize: 7, connectNulls: true, data: this.seriesData(s, axis) }],
      }
    },
    /**
     * 分图 ALARM 三色状态带格 option（单 series bar + showBackground，实测验证形态）：
     * 有行桶逐点 itemStyle 着色（alarm=红/normal=绿）、缺桶=null 槽位露灰底；y 轴隐藏（带只承载
     * 状态不承载量值）；tooltip 挂 formatter——bar 底层数值恒 1 无语义，默认渲染只会显「1」。
     */
    splitBandOption(s, axis) {
      // 逐槽取该 series 的行（缺桶=null）；cells 同时供着色与 formatter 取计数，一次遍历两用
      const cells = axis.times.map((label) => {
        const row = axis.rowByLabel.get(label)
        const p = row && row.cells[s.key]
        return p && p.value_text != null ? p : null
      })
      const stateText = (p) => (p.value_text === 'alarm' ? '报警' : '正常')
      return {
        animation: false,
        title: {
          text: s.name + (s.unit ? ' (' + s.unit + ')' : ''),
          left: 6, top: 2, textStyle: { fontSize: 12, fontWeight: 500 },
          // 三色图例语义=子图注小字（原生 legend 项=series，单 series 带分不出三色）
          subtext: BAND_LEGEND_TEXT,
          subtextStyle: { fontSize: 10, rich: BAND_LEGEND_RICH },
        },
        tooltip: {
          trigger: 'axis', confine: true, axisPointer: { type: 'line' },
          // 三态全覆盖：报警/正常带桶计数（有效 N/共 M），无数据不添计数（无行即无计数）
          formatter: (params) => {
            const q = Array.isArray(params) ? params[0] : params
            const p = cells[q.dataIndex]
            if (!p) return `${q.name}｜${s.name}：无数据`
            const counts = this.hasCounts(p) ? `（${this.validHint(p)}）` : ''
            return `${q.name}｜${s.name}：${stateText(p)}${counts}`
          },
        },
        // top 40 让出「标题+三色图例注」两行高度（图例注与带顶重叠过）；left 44 容下首桶居中标签
        //（与数值格绘图区左缘基本对齐），避免首/尾桶标签被画布裁切
        grid: { left: 44, right: 14, top: 40, bottom: 20 },
        xAxis: { type: 'category', data: axis.times, axisLabel: { hideOverlap: true, fontSize: 10 } },
        yAxis: { type: 'value', min: 0, max: 1, show: false },
        series: [{
          name: s.name,
          type: 'bar',
          barWidth: '100%',
          showBackground: true,
          backgroundStyle: { color: BAND_NONE_COLOR },
          data: cells.map((p) => (p ? { value: 1, itemStyle: { color: p.value_text === 'alarm' ? BAND_ALARM_COLOR : BAND_NORMAL_COLOR } } : null)),
        }],
      }
    },
    /**
     * 合并：每渲染 dispose 重建（同 ADM 用后即弃）。v-if 全新元素经 nextTick init（持久 v-show
     * 容器在 display:none 期 init 会落 100x100 默认尺寸且无自适应）。实例 markRaw 后再入 data
     * ——经 reactive Proxy 调 setOption 正是本页 tooltip 失效的根因（bug-record-20260908-233000），
     * markRaw 后与 ADM 同形（raw 实例上驱动），tooltip 原生正常，无需任何绕行。
     */
    renderMerge(axis) {
      this.disposeSplit()
      if (this.mergeChart) { this.mergeChart.dispose(); this.mergeChart = null }
      this.$nextTick(() => {
        if (!this.$refs.chartEl || this.viewMode !== 'chart' || this.chartLayout !== 'merge') return
        this.mergeChart = markRaw(echarts.init(this.$refs.chartEl))
        this.mergeChart.setOption(this.mergeChartOption(axis), true)
        // resize 容错：渲染 flush 期调用偶发抛异常，尺寸在 init/setOption 已对齐，此处只是兜底
        try { this.mergeChart.resize() } catch (e) { /* 尺寸已对齐，忽略 */ }
      })
    },
    /** 数值 series data：与轴逐槽对齐（缺桶/无值=null，折线 connectNulls 跨接视觉不变）。 */
    seriesData(s, axis) {
      return axis.times.map((label) => {
        const row = axis.rowByLabel.get(label)
        const p = row && row.cells[s.key]
        return p && p.value != null ? p.value : null
      })
    },
    // 合并：一图多 series，tooltip 逐 series 带 display_unit
    mergeChartOption(axis) {
      // 形态对齐 ADM buildMergeOption：legend 置顶显式 data、axisPointer cross、title 显式关闭。
      // series 名并入单位（legend 与默认 tooltip 渲染均带单位），不挂自定义 formatter——默认渲染
      // 已按时刻逐 series 出值，少一份函数少一分维护面。合并仅数值 series（y 轴 value 语义不被文本破坏）。
      const names = this.chartSeries.map((s) => s.name + (s.unit ? ' (' + s.unit + ')' : ''))
      return {
        title: { show: false },
        animation: false,
        tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
        legend: { show: true, type: 'scroll', orient: 'horizontal', top: 4, left: 8, right: 8, height: 28, itemWidth: 18, itemHeight: 10, itemGap: 12, selectedMode: true, data: names },
        grid: { left: 52, right: 16, top: 44, bottom: 32 },
        xAxis: { type: 'category', data: axis.times, axisLabel: { fontSize: 10, hideOverlap: true } },
        yAxis: { type: 'value', scale: true },
        series: this.chartSeries.map((s) => ({
          name: s.name + (s.unit ? ' (' + s.unit + ')' : ''),
          type: 'line',
          showSymbol: s.points.length <= 1,  // 单点序列显示符号（否则空白）
          symbolSize: 7,
          connectNulls: true,
          data: this.seriesData(s, axis),
        })),
      }
    },
    // —— 导出（CSV：本集成 vue-modules 无 xlsx 且禁新增依赖；全窗按网格切段拉取，上限防炸） ——
    async exportCsv() {
      const err = this.validate()
      if (err) { this.errorMsg = err; return }
      if (!this.submittableKeys.length) { this.errorMsg = '请至少勾选一个当前粒度可物化的参数'; return }
      // 导出窗口按导出档位（EXPORT_PAGE_TICKS/段）自建：切页档位必须与窗口绑定（buildGridWindow
      // 单一真相），不得复用浏览档窗口再按导出档切段——两套档位混用会切出越界段（start>end 请求）
      const stepMs = GRANULARITY_STEP_MS[this.filter.granularity] || 0
      const win = buildGridWindow(this.filter.start, this.filter.end, stepMs, EXPORT_PAGE_TICKS)
      if (!win) { this.errorMsg = '时间窗内无对齐时刻（窗口不足一个粒度步长）'; return }
      this.exporting = true
      this.errorMsg = ''
      try {
        const base = this.queryBase()
        // 单段 pageSize 同浏览侧口径（段 tick 数×参数数且≥2000，REST 侧 20000 护栏内）
        const guardPageSize = Math.max(GRID_MIN_QUERY_PAGE_SIZE, EXPORT_PAGE_TICKS * this.submittableKeys.length)
        const all = []
        let truncated = false
        // 全窗网格切段（段边界=网格边界不切半）逐段拉原始桶行，收齐后统一展开网格行——与页面浏览
        // 同一分页模型；上限按导出行（=tick 数）截断，段循环触及上限即止
        for (let k = 1; k <= win.pages; k++) {
          if (all.length >= EXPORT_MAX_ROWS) { truncated = true; break }
          const seg = gridSegment(win, k)
          const res = await queryHistory({
            ...base, start: seg.start, end: seg.end, pageNum: 1, pageSize: guardPageSize, order: 'DESC',
          })
          all.push(...(((res || {}).data || {}).rows || []))
        }
        if (truncated && this.$message) this.$message.warning(`数据量超过 ${EXPORT_MAX_ROWS} 行上限，仅导出前 ${EXPORT_MAX_ROWS} 行`)
        // 列基准与表格一致=勾选集全集（不可物化/无数据列头保留、值空）；行=全窗网格降序
        //（导出/表格/页序同一「最新在前」口径）。计数列仅数值参数跟「有效/总数」（桶内非空样本数/
        // 总样本数）——非数值参数不跟（2026-09-09 拍板与表格「不显计数」同一裁决），类别判定与
        // 表格占位同源 seriesKindOf（全窗行集自判，比单页判得更准）
        const sList = buildSeries(this.checked, all, this.metaByKey)
        const numericByKey = new Map(sList.map((s) => [s.key, this.seriesKindOf(s) === 'NUMERIC']))
        const ticks = []
        for (let t = win.lastMs; t >= win.firstMs; t -= win.stepMs) ticks.push(t)
        const piv = gridPivotRows(ticks, sList)
        const header = ['时刻', ...sList.flatMap((s) => {
          const nameCol = s.name + (s.unit ? ` (${s.unit})` : '')
          return numericByKey.get(s.key) ? [nameCol, s.name + ' 有效/总数'] : [nameCol]
        })]
        const dataRows = piv.map((r) => [
          formatLocalDateTime(r.dataTime),
          ...sList.flatMap((s) => {
            const p = r.cells[s.key]
            // 值列：非数值行导 value_text 原文（alarm/normal/状态串）、数值行导数值
            const v = this.cellValue(p)
            if (!numericByKey.get(s.key)) return [v == null ? '' : v]
            const c = p && this.hasCounts(p) ? this.countText(p) : ''
            return [v == null ? '' : v, c]
          }),
        ])
        const stamp = formatLocalInputSeconds(new Date()).replace(/\D/g, '')
        downloadCsv(`asm_history_${this.filter.granularity}_${stamp}.csv`, header, dataRows)
      } catch (e) {
        this.errorMsg = (e && e.message) || '导出失败'
      } finally {
        this.exporting = false
      }
    },
    // —— 单位及修约弹窗（HISTORY purpose，与配置页单位 tab 同后端同数据同端点） ——
    async openUnitDialog() {
      this.unitDialogVisible = true
      this.unitError = ''
      this.unitLoading = true
      try {
        // snapshot 与 config-unit 分属 monitor/config 权限域：一侧失败不拖 blank 另一侧（配置页 load 同口径）
        const [snap, prefs] = await Promise.allSettled([getSnapshot('custom'), getConfigUnit()])
        if (snap.status === 'fulfilled') this.unitSnapDevices = (snap.value && snap.value.data) || []
        else this.unitError = '设备清单加载失败（' + ((snap.reason && snap.reason.message) || '无权限或服务异常') + '）'
        if (prefs.status === 'fulfilled') this.unitPrefs = (prefs.value && prefs.value.data) || []
        else if (!this.unitError) this.unitError = '已存偏好加载失败，按原生单位回显（保存仍可用）'
        if (!this.unitDeviceUid && this.unitDevices.length) this.unitDeviceUid = this.unitDevices[0].uid
        this.buildUnitRows()
      } finally {
        this.unitLoading = false
      }
    },
    // 行编辑态重建：数值行（attrGroup==2）；HISTORY 回显=已存偏好（无行='' 即原生）
    buildUnitRows() {
      const dev = this.unitSnapDevices.find((d) => d.logicDeviceUniqueId === this.unitDeviceUid)
      if (!dev) { this.unitRows = []; return }
      const prefs = new Map(this.unitPrefs
        .filter((p) => p.logicDeviceUniqueId === dev.logicDeviceUniqueId && p.purpose === 'HISTORY')
        .map((p) => [p.attrId, p]))
      this.unitRows = (dev.attrs || [])
        .filter((a) => a.attrGroup === 2)
        .sort((a, b) => String(a.displayName || a.attrId).localeCompare(String(b.displayName || b.attrId), 'zh-Hans-CN'))
        .map((a) => {
          const p = prefs.get(a.attrId)
          return {
            attrId: a.attrId,
            displayName: a.displayName || a.attrId,
            unitOptions: a.unitOptions || null,
            unitSymbol: a.unit,
            historyUnit: p ? (p.unit || '') : '',
            _dirty: false,
          }
        })
    },
    markUnitDirty(r) {
      r._dirty = true
    },
    async saveUnitPrefs() {
      const uid = this.unitDeviceUid
      if (!uid) return
      this.unitSaving = true
      try {
        // 只写 HISTORY 单位——小数位历史出口不消费（AsmUnitContract.monitorDisplayPrecision 契约，配置页同语义不写无效字段）
        for (const r of this.unitRows.filter((x) => x._dirty)) {
          await putConfigUnit({ logicDeviceUniqueId: uid, attrId: r.attrId, purpose: 'HISTORY', unit: r.historyUnit || null })
        }
        this.unitDialogVisible = false
        this.$message && this.$message.success('历史查询单位已保存')
        const res = await getConfigUnit()
        this.unitPrefs = (res && res.data) || []
        this.buildUnitRows()
        // HISTORY 偏好仅在 unit=custom 口径生效：保存后切到自定义立即重查看到换算结果
        this.filter.unit = 'custom'
        this.search()
      } catch (e) {
        this.unitError = (e && e.message) || '保存失败'
      } finally {
        this.unitSaving = false
      }
    },
  },
}
</script>

<style scoped>
/* 整页列式布局（ruoyi 一屏范式）：搜索表单/操作行/分页固定高，结果主体 flex 占满剩余视口——
   84px = 宿主 navbar(50) + tags-view(34)；box-sizing 含 12px 内边距，页面级不滚动、表格内部滚 */
.asm-history { display: flex; flex-direction: column; height: calc(100vh - 84px); min-height: 480px; box-sizing: border-box; padding: 12px; }
.asm-filter { flex-shrink: 0; }

/* 参数触发框：清除/下拉箭头 suffix 图标（readonly 下原生 clearable 不渲染，手工置；样式对齐 el-input__clear） */
.asm-param-input { width: 320px; }
.asm-param-input :deep(.el-input__inner) { cursor: pointer; }
.asm-param-clear { color: #a8abb2; cursor: pointer; }
.asm-param-clear:hover { color: var(--el-color-info, #606266); }
.asm-param-arrow { color: #a8abb2; }

/* 操作行：右侧视图切换（列对齐用 flex 尾推，不依赖 span 求和） */
.asm-result-head { flex-shrink: 0; }
.asm-view-col { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.asm-chart-layout { margin-left: 0; }
.asm-merge-hint { font-size: 12px; color: #909399; }

/* 参数选择弹窗：搜索框 + 设备分组多列（组头中文+组级全选），分组区限高 60vh 内滚（弹窗自身不滚） */
.asm-param-filter-row { display: flex; gap: 8px; margin-bottom: 8px; }
.asm-param-device { width: 260px; }
.asm-param-search { width: 240px; }
/* 两列 grid（行序填充）：设备组为原子项不拆分、同组设备（空调1/2 等相邻命名）落在同一行相邻格、
   行高自动对齐无错落。multicol 与 flex-column-wrap 两案否决——受限高度下均横向溢出成 N 列 */
.asm-param-groups { display: grid; grid-template-columns: repeat(2, minmax(240px, 1fr)); gap: 8px 28px; align-items: start; align-content: start; max-height: 60vh; overflow-y: auto; }
.asm-param-group { min-width: 0; }
.asm-param-head { font-weight: 600; margin-bottom: 4px; background: #f5f7fa; border-radius: 4px; padding: 4px 10px; }
.asm-param-attrs { padding-left: 22px; }
.asm-param-attrs { display: flex; flex-direction: column; gap: 2px; align-items: flex-start; }
.asm-param-attrs :deep(.el-checkbox__label) { font-size: 13px; font-weight: 400; }
.asm-empty-inline { color: #909399; padding: 8px; width: 100%; }

/* 结果区 */
.asm-result-body { flex: 1; min-height: 0; display: flex; }
.asm-table-wrap { flex: 1; min-width: 0; position: relative; }
.asm-chart-wrap { flex: 1; min-width: 0; position: relative; }
.asm-chart { width: 100%; height: 100%; }
/* 分图排列：CSS grid 两列（奇数个末格跨整行，不留右半空白）——布局交给 CSS，删手工百分比数学 */
.asm-split-grid { display: grid; grid-template-columns: repeat(2, minmax(240px, 1fr)); gap: 6px 18px; align-content: start; height: 100%; overflow-y: auto; padding: 2px; }
.asm-split-cell { height: 158px; min-width: 0; }
.asm-split-cell:last-child:nth-child(odd) { grid-column: 1 / -1; }
/* ALARM 三色带格：矮于数值格（带是状态注记非主曲线）；align-self 免被同排数值格的行轨拉伸回 158px */
.asm-split-cell--band { height: 122px; align-self: start; }
.asm-chart-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; background: #fff; }
.asm-pager { display: flex; justify-content: flex-end; align-items: center; gap: 10px; margin-top: 8px; flex-shrink: 0; }

/* 表列头两行：参数中文 + (display_unit) */
.asm-col-head { cursor: default; }
.asm-col-name { font-weight: 600; }
.asm-col-unit { font-size: 12px; color: #909399; font-weight: 400; }
/* 有效性标记：值后 · 悬浮「有效 N/共 M」（validCount<totalCount 才显） */
.asm-valid-dot { color: #e6a23c; font-weight: 700; cursor: help; margin-left: 2px; }
/* 报警态值串专属红（#f56c6c 与 statusBadge danger 同源）：红=报警语义，normal/状态串不占用 */
.asm-alarm-text { color: #f56c6c; font-weight: 600; }
.asm-muted { color: #c0c4cc; }

/* 单位弹窗行表（配置页单位 tab 同形态） */
.asm-unit-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.asm-unit-table th, .asm-unit-table td { border-bottom: 1px solid #ebeef5; padding: 6px 8px; text-align: left; }
.asm-unit-table th { background: #fafafa; color: #606266; }
.asm-hint { margin-top: 8px; font-size: 12px; color: #909399; line-height: 1.6; }
</style>
