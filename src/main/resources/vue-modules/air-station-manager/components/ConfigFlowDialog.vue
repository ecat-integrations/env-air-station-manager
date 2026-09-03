<template>
  <!-- 配置/更换/改连接 flow 对话框（镜像 ADM ConfigFlowDialog，复用 lit <flow-form>）。
       自管 flow 步进：父级只传 open/mode/param，监听 close/completed，不逐 step 介入。
       mode='add'       选 vendor（+可选 SN/名称）→ provision(operation=add) → flow
       mode='replace'   选 vendor → provision(operation=replace,带 oldDeviceId) → flow
       mode='reconnect' 跳过 vendor → reconfigure(启动 RECONFIGURE flow)→ flow
       CREATE_ENTRY 即完成，emit completed（后端原子收口，前端只管关弹窗刷新）。 -->
  <el-dialog
    :model-value="open"
    :title="title"
    width="600px"
    :close-on-click-modal="true"
    destroy-on-close
    @update:model-value="onVisible"
  >
    <p v-if="error" class="dlg-err">{{ error }}</p>

    <!-- ① 选厂家/型号（add/replace），附可选 SN/设备名（IMPORT_FLOW profile 消费；USER_FLOW 在 flow 内填） -->
    <div v-if="state === 'vendor'">
      <div class="fg">
        <label>厂家 / 型号 <span class="req">*</span></label>
        <el-select v-model="vendorKey" :disabled="busy" placeholder="请选择...">
          <el-option
            v-for="v in vendors"
            :key="vKey(v)"
            :label="`${v.label}(${v.coordinate})`"
            :value="vKey(v)"
          />
        </el-select>
        <p v-if="!vendors.length" class="dlg-hint">该类型暂无已注册厂家 profile（ELECTRONIC_FENCE/CLEANLINESS 如实为空）。</p>
        <p v-else class="dlg-hint">选定后进入设备配置向导,填写序列号 / 名称 / 连接等设备信息。</p>
      </div>
      <div class="fg">
        <label>序列号 SN（可选）</label>
        <el-input v-model="sn" :disabled="busy" placeholder="无 SN 的集成可留空" />
      </div>
      <div class="fg">
        <label>设备名称（可选）</label>
        <el-input v-model="name" :disabled="busy" placeholder="留空使用默认名" />
      </div>
    </div>

    <!-- ② config flow 步（lit <flow-form>，配置/更换/改连接共用） -->
    <div v-else-if="state === 'flow'">
      <flow-form
        v-if="libLoaded"
        :flowId="flowId"
        :stepId="stepId"
        :schema="schema"
        :data="flowData"
        :errors="flowErrors"
        :loading="busy"
        :navigation="{ isFirstStep: stepId === initialStepId, isLastStep: false }"
        @flow-submit="onFlowSubmit"
        @flow-previous="onFlowPrevious"
      ></flow-form>
      <p v-else class="dlg-hint">配置组件加载中…</p>
    </div>

    <!-- 底部动作条仅 vendor 步存在（flow 步的上一步/下一步由 <flow-form> 自带） -->
    <template #footer v-if="state === 'vendor'">
      <el-button :disabled="busy" @click="$emit('close')">取消</el-button>
      <el-button type="primary" :disabled="!vendorKey || busy" @click="doProvision">
        {{ busy ? '启动中…' : '下一步' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
defineOptions({ name: 'StationConfigFlowDialog' })
import { ref, computed, watch } from 'vue'
import {
  listVendors, provision, submitStep, previousStep, reconfigure,
} from '../api/device'
import { labelOf, typeOf } from '../stationParamMeta'
import { loadConfigFlowLib } from '../utils/configFlowLib'

const props = defineProps({
  open: { type: Boolean, default: false },
  mode: { type: String, default: 'add' },     // add | replace | reconnect
  param: { type: String, default: null },     // 槽枚举名（StationParamMeta 枚举）
  oldDeviceId: { type: String, default: null },
})
const emit = defineEmits(['close', 'completed'])

// el-dialog 关闭请求（X/遮罩点击）统一转 close 上抛，开关真相在父级 open prop；
// busy 期间是否放行由父级 close 处理器守卫（本组件不拦）。
function onVisible(v) {
  if (!v) emit('close')
}

// flow 内部状态（自管步进）
const state = ref('vendor')            // vendor | flow
const vendors = ref([])
const vendorKey = ref('')
const sn = ref('')
const name = ref('')
const flowId = ref('')
const stepId = ref('')
const initialStepId = ref('')
const schema = ref({})
const flowData = ref({})
const flowErrors = ref({})
const busy = ref(false)
const error = ref('')

const title = computed(() => {
  const p = props.param ? labelOf(props.param) : ''
  if (props.mode === 'replace') return p + ' — 更换设备'
  if (props.mode === 'reconnect') return p + ' — 修改配置'
  return p + ' — 配置新设备'
})

const vKey = (v) => v.coordinate + '|' + v.model

// lit config-flow lib 加载（自定义元素 <flow-form> 全局注册，只加载一次）
const libLoaded = ref(false)
function loadLib() {
  return loadConfigFlowLib().then(() => { libLoaded.value = true })
}

// 弹窗打开时按 mode 初始化：vendor 步拉厂家；reconnect 直接启动 RECONFIGURE flow
watch(() => props.open, async (open) => {
  if (!open) return
  error.value = ''
  busy.value = false
  loadLib()
  if (props.mode === 'reconnect') {
    state.value = 'flow'
    await startReconnect()
  } else {
    state.value = 'vendor'
    vendors.value = []
    vendorKey.value = ''
    sn.value = ''
    name.value = ''
    await loadVendorList()
  }
})

async function loadVendorList() {
  try {
    const res = await listVendors(typeOf(props.param))
    vendors.value = res.data || []
  } catch (e) {
    error.value = '加载厂家失败:' + String(e.message || e)
  }
}

async function doProvision() {
  const [coordinate, model] = (vendorKey.value || '').split('|')
  if (!coordinate || !model) { error.value = '请选择厂家/型号'; return }
  busy.value = true; error.value = ''
  try {
    const res = await provision(typeOf(props.param), {
      coordinate, model,
      sn: sn.value || '',
      name: name.value || '',
      operation: props.mode === 'replace' ? 'replace' : 'add',
      oldDeviceId: props.mode === 'replace' ? (props.oldDeviceId || null) : null,
    })
    const r = res.data || {}
    if (r.status === 'COMPLETED') {
      busy.value = false
      emit('completed')
    } else {
      flowId.value = r.flowId
      stepId.value = r.stoppedStepId
      initialStepId.value = r.stoppedStepId
      schema.value = r.schema || {}
      flowData.value = { step_inputs: r.stepInputs || {} }
      flowErrors.value = {}
      state.value = 'flow'
      busy.value = false
    }
  } catch (e) {
    busy.value = false
    error.value = '启动配置失败:' + String(e.message || e)
  }
}

// 改连接：对已绑物理设备启动 RECONFIGURE flow（首步 schema 随 flowId 一并带出，
// 不可经空 submit 再拉——handleStep 不认 null stepId 会 ABORT）
async function startReconnect() {
  busy.value = true; error.value = ''
  try {
    const res = await reconfigure(typeOf(props.param))
    const r = res.data || {}
    flowId.value = r.flowId
    stepId.value = r.stepId
    initialStepId.value = r.stepId
    schema.value = r.schema || {}
    flowData.value = { step_inputs: r.stepInputs || {} }
    busy.value = false
    // 极少数情况后端未返 schema（首步非 SHOW_FORM）→ 兜底拉一次；正常路径不依赖此
    if ((!schema.value || !schema.value.fields) && flowId.value) {
      await pullFirstStep()
    }
  } catch (e) {
    busy.value = false
    error.value = '启动修改配置失败:' + String(e.message || e)
  }
}

async function pullFirstStep() {
  try {
    const res = await submitStep(flowId.value, { stepId: stepId.value, userInput: {} })
    applyShowForm(res.data)
  } catch (e) {
    error.value = '加载配置步失败:' + String(e.message || e)
  }
}

function applyShowForm(r) {
  stepId.value = r.stepId
  schema.value = r.schema || {}
  flowData.value = { step_inputs: r.stepInputs || {} }
  flowErrors.value = r.errors || {}
}

async function onFlowSubmit(e) {
  const { stepId: sid, userInput } = e.detail || {}
  busy.value = true; error.value = ''
  try {
    const res = await submitStep(flowId.value, { stepId: sid, userInput })
    const r = res.data || {}
    if (r.type === 'CREATE_ENTRY') {
      busy.value = false
      emit('completed')
    } else if (r.type === 'SHOW_FORM') {
      applyShowForm(r); busy.value = false
    } else {
      busy.value = false
      error.value = '配置被拒绝:' + (r.type || '未知')
    }
  } catch (e) {
    busy.value = false
    error.value = '提交失败:' + String(e.message || e)
  }
}

async function onFlowPrevious() {
  busy.value = true; error.value = ''
  try {
    const res = await previousStep(flowId.value)
    const r = res.data || {}
    if (r.type === 'SHOW_FORM') { applyShowForm(r) }
    else { error.value = '无法返回:' + (r.type || '未知') }
  } catch (e) {
    error.value = '返回失败:' + String(e.message || e)
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.dlg-err { color: #dc2626; font-size: 13px; margin: 0 0 12px; }
.dlg-hint { color: #6b7280; font-size: 13px; }
.fg { margin-bottom: 14px; }
.fg label { display: block; font-size: 13px; font-weight: 500; margin-bottom: 5px; color: #374151; }
.fg .req { color: #ef4444; }
.fg :deep(.el-select) { width: 100%; }

/* lit <flow-form> 在 shadow DOM 自带样式,外层不干扰 */
</style>
