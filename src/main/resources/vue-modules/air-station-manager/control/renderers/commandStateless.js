// command_stateless 渲染器：无状态 el-button plain 组（不显当前值、无 active 态）。
// 点击即发 change（进 dirty 待执行）。
// 待执行反馈（pendingValue 非空且命中某选项）：该选项改 primary 实心 + 右下角对勾角标——
// stateless 无值展示，点击后无任何视觉反馈像「没点上」，角标告知已入待执行队列（确认/撤销后随 pending 消失）。
import { defineComponent, h } from 'vue'
import { ElButton } from 'element-plus'

// 对勾角标：主题色圆形底 + 白色 ✓（CSS 绘制，勿引图片）。绝对定位于按钮右下角。
const CHECK_STYLE = {
  position: 'absolute', right: '-5px', bottom: '-5px',
  width: '14px', height: '14px', borderRadius: '50%',
  background: '#409eff', color: '#fff', fontSize: '10px', lineHeight: '14px',
  textAlign: 'center', boxShadow: '0 0 0 1.5px #fff', pointerEvents: 'none',
}

export default defineComponent({
  name: 'AsmCtlCommandStateless',
  props: {
    cmd: { type: Object, required: true },
    value: { type: String, default: '' },
    // 当前待执行选项 key（页面 pending 中该 attr 的值；无待执行为 null）
    pendingValue: { type: String, default: null },
    disabled: { type: Boolean, default: false },
  },
  emits: ['change'],
  setup(props, { emit }) {
    return () => h('div', { class: 'asmc-btn-group', 'data-asm': `stateless-${props.cmd.attributeId}` },
      (props.cmd.options || []).map(opt => {
        const pending = props.pendingValue != null && props.pendingValue === opt.value
        return h('span', {
          key: opt.value,
          style: { position: 'relative', display: 'inline-flex' },
          class: pending ? 'asmc-stateless-pending' : null,
        }, [
          h(ElButton, {
            key: opt.value,
            plain: !pending,
            type: pending ? 'primary' : null,
            size: 'small',
            disabled: props.disabled,
            onClick: () => { if (!props.disabled) emit('change', opt.value) },
          }, () => opt.label),
          pending ? h('span', { class: 'asmc-pending-check', style: CHECK_STYLE, 'data-asm': `stateless-pending-${props.cmd.attributeId}` }, '✓') : null,
        ])
      }))
  },
})
