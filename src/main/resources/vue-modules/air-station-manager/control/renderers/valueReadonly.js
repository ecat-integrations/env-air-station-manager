// value 渲染器：只读展示（值 + 单位；门禁 lock_status / 采样管实际温度等参照量）。
import { defineComponent, h } from 'vue'

export default defineComponent({
  name: 'AsmCtlValueReadonly',
  props: {
    cmd: { type: Object, required: true },
    value: { type: String, default: '' },
    disabled: { type: Boolean, default: false },
  },
  setup(props) {
    return () => h('span', {
      class: 'asmc-readonly', 'data-asm': `readonly-${props.cmd.attributeId}`,
    }, [
      props.value == null || props.value === '' ? '-' : String(props.value),
      props.cmd.liveUnit ? h('small', { class: 'asmc-unit' }, props.cmd.liveUnit) : null,
    ])
  },
})
