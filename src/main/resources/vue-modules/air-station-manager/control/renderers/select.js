// select 渲染器：el-select 下拉（element-plus 免 import 全局可用，此处 h() 渲染需显式引入外部依赖）。
import { defineComponent, h } from 'vue'
import { ElSelect, ElOption } from 'element-plus'

export default defineComponent({
  name: 'AsmCtlSelect',
  props: {
    cmd: { type: Object, required: true },
    value: { type: String, default: '' },
    disabled: { type: Boolean, default: false },
  },
  emits: ['change'],
  setup(props, { emit }) {
    return () => h(ElSelect, {
      modelValue: props.value,
      size: 'small',
      disabled: props.disabled,
      style: 'width: 160px',
      'data-asm': `select-${props.cmd.attributeId}`,
      'onUpdate:modelValue': v => { if (!props.disabled && v != null) emit('change', String(v)) },
    }, () => (props.cmd.options || []).map(opt => h(ElOption, { key: opt.value, value: opt.value, label: opt.label })))
  },
})
