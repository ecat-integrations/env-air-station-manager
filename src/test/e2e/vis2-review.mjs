/**
 * ASM 历史数据页 v2 视觉复审脚本（只采集，不断言失败退出）。
 * 输出 /tmp/vis2/hist_{empty,dialog,list,chart,timepicker}.png + 控制台度量值
 * （一屏 scrollHeight/innerHeight、表头、回显、时间输入框 clientWidth vs scrollWidth）。
 * 前提：8081 在线、登录态 test-results/.asm-auth.json。reload 经 CDP Network.setCacheDisabled 禁缓存。
 */
import { chromium } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BASE = process.env.BASE_URL || 'http://localhost:8081';
const ROUTE = BASE + '/#/ecat-integrations/integration-env-air-station-manager/air-station-manager/index/history_data';
const STATE = path.join(__dirname, 'test-results/.asm-auth.json');
const OUT = '/tmp/vis2';

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 }, storageState: STATE });
  const page = await ctx.newPage();
  const cdp = await ctx.newCDPSession(page);
  await cdp.send('Network.setCacheDisabled', { cacheDisabled: true }); // reload ignoreCache

  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });

  await page.goto(ROUTE);
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => localStorage.removeItem('asm-history-checked'));
  await page.reload({ waitUntil: 'networkidle' });
  await page.locator('.asm-page').first().waitFor({ timeout: 30000 }).catch(async (e) => {
    console.log('reload 后 URL:', page.url());
    console.log('reload 后 BODY:', (await page.evaluate(() => document.body.innerText.slice(0, 300))).replace(/\n/g, ' | '));
    await page.screenshot({ path: OUT + '/debug_reload.png' });
    throw e;
  });
  await page.waitForTimeout(1200);

  // a. 初始空态（未选参数 → 无表格主体）
  await page.screenshot({ path: OUT + '/hist_empty.png' });
  const emptyState = await page.evaluate(() => {
    const empty = document.querySelector('.el-empty');
    const table = document.querySelector('.el-table');
    const pager = document.querySelector('.asm-pager');
    return { emptyText: empty ? empty.innerText.replace(/\n/g, ' ') : null, hasTable: !!table, hasPager: !!pager };
  });
  console.log('[a] 空态:', JSON.stringify(emptyState));

  // b. 参数 dialog
  const paramInput = page.locator('.asm-filter input[placeholder="选择参数..."]');
  await paramInput.waitFor({ timeout: 15000 });
  await paramInput.click();
  const dialog = page.locator('.el-dialog .asm-params');
  await dialog.waitFor({ timeout: 10000 });
  await page.waitForTimeout(800);
  await page.screenshot({ path: OUT + '/hist_dialog.png' });
  const groupHeads = await dialog.locator('.asm-param-head').allInnerTexts();
  console.log('[b] dialog 组列表:', groupHeads.map((t) => t.replace(/\n/g, ' ')).join(' || '));
  const firstHead = dialog.locator('.asm-param-head').first();
  await firstHead.locator('.el-checkbox').first().click();
  await page.waitForTimeout(400);
  await page.locator('.el-dialog__footer button', { hasText: '确 定' }).click();
  await page.waitForTimeout(500);
  const summary = await paramInput.inputValue();
  console.log('[b] 参数回显:', JSON.stringify(summary));
  // 回显框是否单行（输入框高度 vs 内容）
  const echoBox = await page.evaluate(() => {
    const el = document.querySelector('.asm-param-input');
    if (!el) return null;
    return { clientHeight: el.clientHeight, tag: el.tagName + '.' + el.className };
  });
  console.log('[b] 回显触发框:', JSON.stringify(echoBox));

  // c. 搜索 → 等 4s → 列表态
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await page.waitForTimeout(4000);
  await page.screenshot({ path: OUT + '/hist_list.png' });
  const m = await page.evaluate(() => ({
    sh: Math.max(document.documentElement.scrollHeight, document.body.scrollHeight),
    ih: window.innerHeight,
  }));
  console.log(`[c] 一屏: scrollHeight=${m.sh} innerHeight=${m.ih} diff=${m.sh - m.ih}`);
  const headers = (await page.locator('.el-table__header-wrapper thead tr:first-child th').allInnerTexts())
    .map((t) => t.trim()).filter(Boolean);
  console.log(`[c] 表头 ${headers.length} 列:`, headers.map((h) => h.replace(/\n/g, ' ')).join(' | '));
  const firstRow = (await page.locator('.el-table__body-wrapper tbody tr:first-child td').allInnerTexts())
    .map((t) => t.trim());
  console.log('[c] 首行单元格:', firstRow.join(' | '));
  const dashCount = firstRow.filter((c) => c === '--').length;
  console.log(`[c] 首行 '--' 单元格数: ${dashCount}/${firstRow.length}`);
  const pagerText = await page.evaluate(() => {
    const p = document.querySelector('.asm-pager');
    return p ? p.innerText.replace(/\n/g, ' ') : null;
  });
  console.log('[c] 分页:', pagerText);
  // ruoyi 形态度量：搜索区是否 el-form inline、有无页面大标题、搜索区有无边框盒子
  const styleMetrics = await page.evaluate(() => {
    const form = document.querySelector('.asm-filter');
    const inline = form ? form.classList.contains('el-form--inline') : false;
    const formItems = form ? form.querySelectorAll(':scope > .el-form-item').length : 0;
    const filterBox = form ? getComputedStyle(form).backgroundColor + ' / bd:' + getComputedStyle(form).border : '';
    const bigTitle = document.querySelector('.asm-page h1, .asm-page h2, .asm-page h3, .asm-page .el-page-header, .asm-page .el-card');
    const searchRowH = form ? form.getBoundingClientRect().height : 0;
    const headRow = document.querySelector('.asm-result-head');
    const headRowH = headRow ? headRow.getBoundingClientRect().height : 0;
    const tableWrap = document.querySelector('.asm-table-wrap');
    const tableWrapH = tableWrap ? Math.round(tableWrap.getBoundingClientRect().height) : 0;
    return { inline, formItems, filterBox, hasBigTitle: !!bigTitle, searchRowH: Math.round(searchRowH), headRowH: Math.round(headRowH), tableWrapH };
  });
  console.log('[3] ruoyi 形态度量:', JSON.stringify(styleMetrics));

  // e. 时间选择器两输入框：值 + clientWidth vs scrollWidth（裁剪判定）
  const timeMetrics = await page.evaluate(() => {
    const ed = document.querySelector('.asm-filter .el-date-editor');
    if (!ed) return null;
    return [...ed.querySelectorAll('input')].map((i) => ({
      value: i.value, clientWidth: i.clientWidth, scrollWidth: i.scrollWidth,
      clipped: i.scrollWidth > i.clientWidth,
    }));
  });
  console.log('[e] 时间输入框:', JSON.stringify(timeMetrics));
  await page.locator('.asm-filter .el-date-editor').first()
    .screenshot({ path: OUT + '/hist_timepicker.png' })
    .catch((e) => console.log('[e] 时间选择器截图失败:', e.message));

  // d. 切曲线（默认分图）→ 等渲染
  await page.locator('.asm-result-head .el-radio-button', { hasText: '曲线' }).click();
  await page.waitForTimeout(3000);
  await page.screenshot({ path: OUT + '/hist_chart.png' });
  const chartMetrics = await page.evaluate(() => {
    const wrap = document.querySelector('.asm-chart-wrap');
    const canvases = document.querySelectorAll('.asm-chart-wrap canvas');
    const m = { sh: Math.max(document.documentElement.scrollHeight, document.body.scrollHeight), ih: window.innerHeight };
    return {
      oneScreen: `${m.sh} vs ${m.ih} (diff ${m.sh - m.ih})`,
      chartWrapH: wrap ? Math.round(wrap.getBoundingClientRect().height) : null,
      canvasCount: canvases.length,
      canvasSizes: [...canvases].slice(0, 12).map((c) => `${c.width}x${c.height}`),
    };
  });
  console.log('[d] 曲线(分图)度量:', JSON.stringify(chartMetrics));
  // 分图图例/标题（y 轴单位）抽样
  const chartTitles = await page.evaluate(() => {
    // echarts 分图标题不在 DOM，canvas 内；改抓页面文本兜底
    const txt = document.querySelector('.asm-chart-wrap') ? '' : '';
    return txt;
  });
  console.log('[d] 页面 JS 错误:', errors.length ? errors.join(' | ') : '无');

  await browser.close();
  console.log('DONE. 截图目录:', OUT);
})().catch((e) => { console.error('SCRIPT ERROR:', e); process.exit(2); });
