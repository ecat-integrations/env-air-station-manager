// command 渲染器：2 选项 = el-switch 开关；≥3 选项 = el-radio-button 分段按钮组（选中态 is-active）。
// 改动只发 change 事件进 dirty 模型（不立即提交）。
import { defineComponent, h } from 'vue'
import { ElSwitch, ElRadioGroup, ElRadioButton } from 'element-plus'

export default defineComponent({
  name: 'AsmCtlCommand',
  props: {
    cmd: { type: Object, required: true },
    value: { type: String, default: '' },
    disabled: { type: Boolean, default: false },
  },
  emits: ['change'],
  setup(props, { emit }) {
    return () => {
      const opts = props.cmd.options || []
      if (opts.length === 2) {
        const cur = props.value
        const label = (opts.find(o => o.value === cur) || {}).label || cur
        return h('div', { class: 'asmc-toggle-wrap', 'data-asm': `cmd-toggle-${props.cmd.attributeId}` }, [
          h(ElSwitch, {
            modelValue: cur,
            activeValue: opts[0].value,
            inactiveValue: opts[1].value,
            disabled: props.disabled,
            'onUpdate:modelValue': v => { if (!props.disabled && v != null) emit('change', v) },
          }),
          h('span', { class: 'asmc-toggle-label' }, label),
        ])
      }
      return h(ElRadioGroup, {
        modelValue: props.value,
        size: 'small',
        disabled: props.disabled,
        'data-asm': `cmd-group-${props.cmd.attributeId}`,
        'onUpdate:modelValue': v => { if (!props.disabled && v != null) emit('change', String(v)) },
      }, () => opts.map(opt => h(ElRadioButton, { key: opt.value, value: opt.value, label: opt.label })))
    }
  },
})
