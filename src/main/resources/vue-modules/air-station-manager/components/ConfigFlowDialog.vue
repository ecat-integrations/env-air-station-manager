<template>
  <!-- 配置/更换/改连接 flow 对话框（镜像 ADM ConfigFlowDialog，复用 lit <flow-form>）。
       自管 flow 步进：父级只传 open/mode/param，监听 close/completed，不逐 step 介入。
       mode='add'       选 vendor（+可选 SN/名称）→ provision(operation=add) → flow
       mode='replace'   选 vendor → provision(operation=replace,带 oldDeviceId) → flow
       mode='reconnect' 跳过 vendor → reconfigure(启动 RECONFIGURE flow)→ flow
       CREATE_ENTRY 即完成，emit completed（后端原子收口，前端只管关弹窗刷新）。 -->
  <div v-if="open" class="dlg-overlay" @click.self="$emit('close')">
    <div class="dlg-box">
      <div class="dlg-head">
        <span class="dlg-title">{{ title }}</span>
        <button class="dlg-x" @click="$emit('close')" :disabled="busy">×</button>
      </div>
      <p v-if="error" class="dlg-err">{{ error }}</p>

      <!-- ① 选厂家/型号（add/replace），附可选 SN/设备名（IMPORT_FLOW profile 消费；USER_FLOW 在 flow 内填） -->
      <div v-if="state === 'vendor'" class="dlg-body">
        <div class="fg">
          <label>厂家 / 型号 <span class="req">*</span></label>
          <select v-model="vendorKey" :disabled="busy">
            <option value="" disabled>请选择...</option>
            <option v-for="v in vendors" :key="vKey(v)" :value="vKey(v)">
              {{ v.label }}({{ v.coordinate }})
            </option>
          </select>
          <p v-if="!vendors.length" class="dlg-hint">该类型暂无已注册厂家 profile（ELECTRONIC_FENCE/CLEANLINESS 如实为空）。</p>
          <p v-else class="dlg-hint">选定后进入设备配置向导,填写序列号 / 名称 / 连接等设备信息。</p>
        </div>
        <div class="fg">
          <label>序列号 SN（可选）</label>
          <input v-model="sn" :disabled="busy" placeholder="无 SN 的集成可留空" />
        </div>
        <div class="fg">
          <label>设备名称（可选）</label>
          <input v-model="name" :disabled="busy" placeholder="留空使用默认名" />
        </div>
        <div class="dlg-actions">
          <button class="btn" @click="$emit('close')" :disabled="busy">取消</button>
          <button class="btn primary" :disabled="!vendorKey || busy" @click="doProvision">
            {{ busy ? '启动中…' : '下一步' }}
          </button>
        </div>
      </div>

      <!-- ② config flow 步（lit <flow-form>，配置/更换/改连接共用） -->
      <div v-else-if="state === 'flow'" class="dlg-body">
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
    </div>
  </div>
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
.dlg-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: flex-start; justify-content: center; padding: 40px 0; z-index: 2000;
  overflow: auto;
}
.dlg-box { background: #fff; border-radius: 10px; width: 600px; max-width: 94vw; box-shadow: 0 8px 30px rgba(0,0,0,.2); }
.dlg-head { display: flex; justify-content: space-between; align-items: center; padding: 14px 18px; border-bottom: 1px solid #e5e7eb; }
.dlg-title { font-weight: 600; font-size: 16px; }
.dlg-x { border: none; background: none; font-size: 22px; cursor: pointer; color: #6b7280; line-height: 1; }
.dlg-err { color: #dc2626; font-size: 13px; margin: 8px 18px 0; }
.dlg-body { padding: 18px; }
.dlg-hint { color: #6b7280; font-size: 13px; }
.fg { margin-bottom: 14px; }
.fg label { display: block; font-size: 13px; font-weight: 500; margin-bottom: 5px; color: #374151; }
.fg .req { color: #ef4444; }
.fg select, .fg input { width: 100%; padding: 8px 10px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 14px; box-sizing: border-box; }
.dlg-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
.btn { font-size: 13px; padding: 6px 14px; border: 1px solid #d1d5db; border-radius: 4px; background: #fff; cursor: pointer; }
.btn.primary { background: #2563eb; color: #fff; border-color: #2563eb; }
.btn:hover { opacity: .85; }
.btn:disabled { opacity: .5; cursor: not-allowed; }

/* lit <flow-form> 在 shadow DOM 自带样式,外层不干扰 */
</style>
