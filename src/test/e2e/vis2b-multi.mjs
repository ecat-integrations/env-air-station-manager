/**
 * 补充采集：多参数组（O3滤膜 + PM10纸带记录仪，复现用户验收契约的两组）——
 * 覆盖「首项 等 N 项」回显形态、多参数列头完整性、无数据列 '--'、多序列分图。
 * 输出 /tmp/vis2/hist2_{list,chart}.png + 度量。
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
const GROUPS = ['O3滤膜', 'PM10纸带记录仪'];

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 }, storageState: STATE });
  const page = await ctx.newPage();
  const cdp = await ctx.newCDPSession(page);
  await cdp.send('Network.setCacheDisabled', { cacheDisabled: true });
  await page.goto(ROUTE);
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => localStorage.removeItem('asm-history-checked'));
  await page.reload({ waitUntil: 'networkidle' });
  await page.locator('.asm-page').first().waitFor({ timeout: 30000 });

  const paramInput = page.locator('.asm-filter input[placeholder="选择参数..."]');
  await paramInput.waitFor({ timeout: 15000 });
  await paramInput.click();
  const dialog = page.locator('.el-dialog .asm-params');
  await dialog.waitFor({ timeout: 10000 });
  // 组头 vs 参数项 计算样式（视觉层级是否可辨：粗细/颜色/背景）
  const headStyle = await page.evaluate(() => {
    const head = document.querySelector('.asm-param-head');
    const item = document.querySelector('.asm-param-cb');
    const gs = (el) => {
      const s = getComputedStyle(el);
      return { fontWeight: s.fontWeight, fontSize: s.fontSize, color: s.color, bg: s.backgroundColor };
    };
    const groups = document.querySelector('.asm-param-groups');
    return {
      head: head ? gs(head) : null,
      item: item ? gs(item) : null,
      groupsDisplay: groups ? getComputedStyle(groups).display : null,
      groupCount: document.querySelectorAll('.asm-param-group').length,
    };
  });
  console.log('[dialog 样式]', JSON.stringify(headStyle));
  for (const g of GROUPS) {
    await dialog.locator('.asm-param-head .el-checkbox', { hasText: g }).first().click();
  }
  await page.waitForTimeout(400);
  await page.screenshot({ path: OUT + '/hist2_dialog.png' });
  await page.waitForTimeout(400);
  await page.locator('.el-dialog__footer button', { hasText: '确 定' }).click();
  await page.waitForTimeout(500);
  console.log('[回显]', JSON.stringify(await paramInput.inputValue()));
  // 回显框单行溢出检查（scrollWidth vs clientWidth）
  const echoClip = await page.evaluate(() => {
    const i = document.querySelector('.asm-param-input input');
    return i ? { clientWidth: i.clientWidth, scrollWidth: i.scrollWidth, clipped: i.scrollWidth > i.clientWidth } : null;
  });
  console.log('[回显裁剪]', JSON.stringify(echoClip));

  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await page.waitForTimeout(4000);
  await page.screenshot({ path: OUT + '/hist2_list.png' });
  const m = await page.evaluate(() => ({
    sh: Math.max(document.documentElement.scrollHeight, document.body.scrollHeight), ih: window.innerHeight,
  }));
  console.log(`[一屏] scrollHeight=${m.sh} innerHeight=${m.ih} diff=${m.sh - m.ih}`);
  const headers = (await page.locator('.el-table__header-wrapper thead tr:first-child th').allInnerTexts())
    .map((t) => t.trim()).filter(Boolean);
  console.log(`[表头 ${headers.length} 列]`, headers.map((h) => h.replace(/\n/g, ' ')).join(' | '));
  const firstRow = (await page.locator('.el-table__body-wrapper tbody tr:first-child td').allInnerTexts()).map((t) => t.trim());
  console.log('[首行]', firstRow.join(' | '));
  console.log(`[首行 '--' 数] ${firstRow.filter((c) => c === '--').length}/${firstRow.length}`);

  await page.locator('.asm-result-head .el-radio-button', { hasText: '曲线' }).click();
  await page.waitForTimeout(3000);
  await page.screenshot({ path: OUT + '/hist2_chart.png' });
  const cm = await page.evaluate(() => ({
    sh: Math.max(document.documentElement.scrollHeight, document.body.scrollHeight), ih: window.innerHeight,
    canvas: [...document.querySelectorAll('.asm-chart-wrap canvas')].map((c) => `${c.width}x${c.height}`),
  }));
  console.log('[曲线] oneScreen diff=', cm.sh - cm.ih, 'canvas=', cm.canvas.join(','));

  await browser.close();
  console.log('DONE');
})().catch((e) => { console.error('SCRIPT ERROR:', e); process.exit(2); });
