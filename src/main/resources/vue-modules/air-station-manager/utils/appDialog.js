/**
 * 页面内确认/提示，避免 Electron 下原生 confirm/alert 关闭后焦点丢失。
 */

function showDialog({ title, message, showCancel }) {
  return new Promise((resolve) => {
    const overlay = document.createElement('div')
    overlay.className = 'ecat-confirm-overlay'
    overlay.innerHTML = `
      <div class="ecat-confirm-box" role="alertdialog" aria-modal="true">
        <div class="ecat-confirm-title"></div>
        <div class="ecat-confirm-body"></div>
        <div class="ecat-confirm-actions"></div>
      </div>
    `
    const style = document.createElement('style')
    style.textContent = `
      .ecat-confirm-overlay{
        position:fixed;inset:0;z-index:10050;display:flex;align-items:center;justify-content:center;
        background:rgba(15,23,42,.45);font-family:system-ui,-apple-system,"Segoe UI",sans-serif;
      }
      .ecat-confirm-box{
        background:#fff;border-radius:10px;width:min(420px,92vw);
        box-shadow:0 18px 40px rgba(0,0,0,.22);overflow:hidden;
      }
      .ecat-confirm-title{padding:16px 18px 0;font-size:16px;font-weight:600;color:#111827}
      .ecat-confirm-body{
        padding:10px 18px 8px;font-size:14px;line-height:1.55;color:#374151;
        white-space:pre-wrap;word-break:break-word;max-height:60vh;overflow:auto;
      }
      .ecat-confirm-actions{display:flex;justify-content:flex-end;gap:8px;padding:12px 14px 14px}
      .ecat-confirm-btn{
        min-width:72px;padding:8px 14px;border-radius:6px;border:1px solid #d1d5db;
        background:#fff;color:#374151;font-size:14px;cursor:pointer;
      }
      .ecat-confirm-btn.primary{background:#2563eb;border-color:#2563eb;color:#fff}
      .ecat-confirm-btn.primary:hover{background:#1d4ed8}
      .ecat-confirm-btn:hover{background:#f9fafb}
      .ecat-confirm-btn.primary:hover{background:#1d4ed8;color:#fff}
    `
    overlay.appendChild(style)
    overlay.querySelector('.ecat-confirm-title').textContent = title
    overlay.querySelector('.ecat-confirm-body').textContent = message

    const actions = overlay.querySelector('.ecat-confirm-actions')
    const okBtn = document.createElement('button')
    okBtn.type = 'button'
    okBtn.className = 'ecat-confirm-btn primary'
    okBtn.textContent = '确定'

    let cancelBtn = null
    if (showCancel) {
      cancelBtn = document.createElement('button')
      cancelBtn.type = 'button'
      cancelBtn.className = 'ecat-confirm-btn'
      cancelBtn.textContent = '取消'
      actions.appendChild(cancelBtn)
    }
    actions.appendChild(okBtn)

    const previouslyFocused = document.activeElement
    const finish = (value) => {
      document.removeEventListener('keydown', onKey)
      overlay.remove()
      if (previouslyFocused && typeof previouslyFocused.focus === 'function') {
        try { previouslyFocused.focus() } catch (_) { /* ignore */ }
      }
      resolve(value)
    }
    const onKey = (e) => {
      if (e.key === 'Escape' && showCancel) {
        e.preventDefault()
        finish(false)
      } else if (e.key === 'Enter') {
        e.preventDefault()
        finish(true)
      }
    }

    okBtn.addEventListener('click', () => finish(true))
    if (cancelBtn) cancelBtn.addEventListener('click', () => finish(false))
    overlay.addEventListener('click', (e) => {
      if (e.target === overlay && showCancel) finish(false)
    })
    document.addEventListener('keydown', onKey)
    document.body.appendChild(overlay)
    okBtn.focus()
  })
}

export function appConfirm(message, title = '确认') {
  return showDialog({ title, message: String(message ?? ''), showCancel: true })
}

export async function appAlert(message, title = '提示') {
  await showDialog({ title, message: String(message ?? ''), showCancel: false })
}
