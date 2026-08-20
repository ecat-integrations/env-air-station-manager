// value_change 渲染器：el-input-number 步进 + 行内编辑（min/max/step/precision 校验内建）。
// 改动只发 change 事件进 dirty 模型（不立即提交）；changeType=text 时无数值语义，退化为只读文本。
import { defineComponent, h } from 'vue'
import { ElInputNumber } from 'element-plus'

export default defineComponent({
  name: 'AsmCtlValueChange',
  props: {
    cmd: { type: Object, required: true },   // 归一化控件项（catalog.normalizeCommand）
    value: { type: String, default: '' },     // 当前显示值（dirty 或 live）
    disabled: { type: Boolean, default: false },
  },
  emits: ['change'],
  setup(props, { emit }) {
    return () => {
      const cmd = props.cmd
      // text 类型无 min/max/step 数值语义（catalog 不保证边界存在），只读展示
      if (cmd.changeType === 'text') {
        return h('span', { class: 'asmc-readonly', 'data-asm': `vc-value-${cmd.attributeId}` },
          [props.value || '-', cmd.liveUnit ? h('small', { class: 'asmc-unit' }, cmd.liveUnit) : null])
      }
      const num = parseFloat(props.value)
      return h('div', { class: 'asmc-vc' }, [
        h(ElInputNumber, {
          modelValue: isNaN(num) ? undefined : num,
          size: 'small',
          min: cmd.changeNumberMin,
          max: cmd.changeNumberMax,
          step: cmd.changeStep,
          precision: cmd.changeType === 'float' ? (cmd.changeNumberDecimal || 0) : 0,
          disabled: props.disabled,
          'data-asm': `vc-value-${cmd.attributeId}`,
          'onUpdate:modelValue': v => { if (v == null) return; emit('change', String(v)) },
        }),
        cmd.liveUnit ? h('span', { class: 'asmc-unit' }, cmd.liveUnit) : null,
      ])
    }
  },
})
