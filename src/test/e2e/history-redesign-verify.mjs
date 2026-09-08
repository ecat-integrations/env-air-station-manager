/**
 * ASM 历史数据页重设计验收脚本（node history-redesign-verify.mjs [before|after]，默认 after）。
 *
 * 复现契约（用户验收反馈）：勾选 O3滤膜 全组 + PM10纸带记录仪 全组查询，表格列头必须=两组全部勾选参数
 * （stat-params 元数据核对），无数据列照常占列、单元格 '--'；页面 1600×900 一屏（scrollHeight ≤ innerHeight+24）。
 *
 * - before：旧版交互（页面内面板直点组级全选 + 「查询」按钮）——采集修复前列头数（bug 证据）。
 * - after ：新版交互（点参数输入框 → dialog 组级全选 → 确定 → 「搜索」）——断言列头完整 + '--' + 一屏 + 截图。
 *
 * 运行前提：8081 在线且已部署对应版本前端；登录态 test-results/.asm-auth.json（缺失时自动登录生成）。
 */
import { chromium } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BASE = process.env.BASE_URL || 'http://localhost:8081';
const ROUTE = BASE + '/#/ecat-integrations/integration-env-air-station-manager/air-station-manager/index/history_data';
const STATE = path.join(__dirname, 'test-results/.asm-auth.json');
const OUT_DIR = path.join(__dirname, 'test-results/history-redesign');
const GROUPS = ['O3滤膜', 'PM10纸带记录仪'];
const VIEWPORT = { width: 1600, height: 900 };

const failures = [];
function check(cond, msg) {
  console.log((cond ? '  ✓ ' : '  ✗ ') + msg);
  if (!cond) failures.push(msg);
}

async function ensureAuth() {
  if (fs.existsSync(STATE)) return;
  fs.mkdirSync(path.dirname(STATE), { recursive: true });
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: VIEWPORT });
  const page = await ctx.newPage();
  await page.goto(BASE + '/#/login');
  await page.fill('input[placeholder*="账号"]', process.env.ASM_USER || 'Admin7s9k2G5');
  await page.fill('input[placeholder*="密码"]', process.env.ASM_PASS || '7sK2pG9dR3tQ');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await page.waitForURL((u) => !u.href.includes('/login'), { timeout: 30000 });
  await ctx.storageState({ path: STATE });
  await browser.close();
  console.log('登录态已生成:', STATE);
}

(async () => {
  const mode = process.argv.includes('before') ? 'before' : 'after';
  fs.mkdirSync(OUT_DIR, { recursive: true });
  await ensureAuth();

  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: VIEWPORT, storageState: STATE });
  const page = await ctx.newPage();
  // inject 模式（过渡验证）：把模块脚本路由到本地 scratch dev 构建（真实宿主+真实后端，不碰共享部署）
  if (process.argv.includes('inject')) {
    const scratchBundle = path.join(OUT_DIR, 'dist-scratch/air-station-manager.js');
    if (!fs.existsSync(scratchBundle)) throw new Error('scratch 构建不存在: ' + scratchBundle);
    await page.route('**/ecat-integrations/integration-env-air-station-manager/air-station-manager.js', (route) =>
      route.fulfill({ path: scratchBundle, contentType: 'application/javascript' }));
    console.log('[inject] 模块脚本已路由到 scratch dev 构建（最终以正式部署产物复验）');
  }
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });

  await page.goto(ROUTE);
  await page.waitForLoadState('networkidle');
  // 清勾选记忆后 reload：每次运行从空勾选态出发（首访面板默认展开/对话框未开，行为确定）
  await page.evaluate(() => localStorage.removeItem('asm-history-checked'));
  await page.reload();
  await page.waitForLoadState('networkidle');
  await page.locator('.asm-page').first().waitFor({ timeout: 30000 });

  // 页内取 stat-params（与页面同源同鉴权：Admin-Token cookie → Authorization 头，宿主 request 同口径）→ 两组期望参数名全集
  const expected = await page.evaluate(async (groups) => {
    const token = (document.cookie.split('; ').find((c) => c.startsWith('Admin-Token=')) || '').split('=').slice(1).join('=');
    const res = await fetch('/dev-api/asm-monitor/stat-params', {
      credentials: 'include',
      headers: { Authorization: 'Bearer ' + decodeURIComponent(token) },
    });
    const j = await res.json();
    const rows = (j && j.data) || [];
    return rows
      .filter((r) => groups.includes(r.device_label))
      .map((r) => ({ name: r.paramDisplayName || r.attrId, key: r.logicDeviceUniqueId + ':' + r.attrId }));
  }, GROUPS);
  console.log(`[${mode}] stat-params 两组共 ${expected.length} 参数:`, expected.map((e) => e.name).join('、'));

  // 抓最终一次 history 响应 → 实际有数据的参数键（区分「无数据列」）
  let historyRows = [];
  page.on('response', async (r) => {
    if (r.url().includes('/asm-monitor/history')) {
      try { const j = await r.json(); historyRows = (j && j.data && j.data.rows) || []; } catch (e) { /* 非 JSON 忽略 */ }
    }
  });

  if (mode === 'after') {
    // 新交互：点参数输入框 → dialog
    const paramInput = page.locator('.asm-filter input[placeholder="选择参数..."]');
    await paramInput.waitFor({ timeout: 15000 });
    await paramInput.click();
    const dialog = page.locator('.el-dialog .asm-params');
    await dialog.waitFor({ timeout: 10000 });
    for (const g of GROUPS) {
      await dialog.locator('.asm-param-head .el-checkbox', { hasText: g }).first().click();
    }
    await page.screenshot({ path: path.join(OUT_DIR, 'dialog-selected.png') });
    await page.locator('.el-dialog__footer button', { hasText: '确 定' }).click();
    await page.waitForTimeout(500);
    // 单行回显断言：首项中文 + 等 N 项
    const summary = await paramInput.inputValue();
    console.log(`[${mode}] 参数回显（单行）: "${summary}"`);
    check(summary.length > 0 && summary.includes('等') && summary.includes(String(expected.length) + ' 项'),
      `参数输入框单行回显 = 首项 + 等 ${expected.length} 项`);
    await page.getByRole('button', { name: '搜索', exact: true }).click();
  } else {
    // 旧交互：面板直点组级全选 + 查询
    for (const g of GROUPS) {
      await page.locator('.asm-param-head .el-checkbox', { hasText: g }).first().click();
    }
    await page.getByRole('button', { name: '查询', exact: true }).click();
  }

  // 等最终 history 响应落地再取表头（_body-wrapper 排除 fixed 列的复制表）
  await page.locator('.el-table__header-wrapper thead tr').first().waitFor({ timeout: 20000 });
  await page.waitForTimeout(1500);
  const headerTexts = (await page.locator('.el-table__header-wrapper thead tr:first-child th').allInnerTexts())
    .map((t) => t.trim()).filter(Boolean);
  const paramCols = headerTexts.slice(1); // 首列=时刻（fixed）
  console.log(`[${mode}] 表头总列=${headerTexts.length} 参数列=${paramCols.length}:`, paramCols.map((t) => t.replace(/\n/g, ' ')).join(' | '));
  if (mode === 'before') {
    console.log(`[before] 勾选 ${expected.length} 项、实际列头 ${paramCols.length} 列 → ${paramCols.length < expected.length ? '列头缺失（bug 复现）' : '列头完整'}`);
  } else {
    check(paramCols.length === expected.length,
      `列头数=${paramCols.length} 应=勾选数 ${expected.length}（含无数据列）`);
    const missing = expected.filter((e) => !paramCols.some((h) => h.includes(e.name)));
    check(missing.length === 0, `全部勾选参数名出现在列头（缺失: ${missing.map((m) => m.name).join('、') || '无'}）`);
    // 无数据列 '--'：按响应实际行集判定，无行参数列的首行单元格必须 '--'
    const keysWithRows = new Set(historyRows.map((r) => r.logicDeviceUniqueId + ':' + r.attrId));
    const noData = expected.filter((e) => !keysWithRows.has(e.key));
    console.log(`[after] 响应 ${historyRows.length} 行，覆盖 ${keysWithRows.size} 参数；无数据列 ${noData.length} 个:`, noData.map((e) => e.name).join('、'));
    if (noData.length) {
      const firstRowCells = await page.locator('.el-table__body-wrapper tbody tr:first-child td').allInnerTexts();
      for (const e of noData) {
        const idx = paramCols.findIndex((h) => h.includes(e.name));
        const cell = (idx >= 0 ? firstRowCells[idx + 1] : '').trim(); // +1=时刻列
        check(cell === '--', `无数据列「${e.name}」单元格显示 '--'（实际 "${cell}"）`);
      }
    }
  }

  // 一屏断言（页面级不滚动，表格内部滚动）
  const m = await page.evaluate(() => ({
    sh: Math.max(document.documentElement.scrollHeight, document.body.scrollHeight),
    ih: window.innerHeight,
  }));
  console.log(`[${mode}] 一屏实测: scrollHeight=${m.sh} innerHeight=${m.ih} diff=${m.sh - m.ih}（容差 +24）`);
  if (mode === 'after') check(m.sh <= m.ih + 24, `一屏约束 scrollHeight(${m.sh}) ≤ innerHeight(${m.ih})+24`);

  await page.screenshot({ path: path.join(OUT_DIR, `${mode}-list.png`) });
  check(errors.length === 0, `无 console error/pageerror${errors.length ? '：' + errors.join(' | ') : ''}`);
  await browser.close();

  console.log(failures.length ? `\nFAILED (${failures.length})` : `\n${mode.toUpperCase()} ALL PASS`);
  process.exit(failures.length ? 1 : 0);
})().catch((e) => { console.error('SCRIPT ERROR:', e); process.exit(2); });
