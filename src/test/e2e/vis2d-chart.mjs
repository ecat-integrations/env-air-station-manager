/**
 * 曲线可渲染性判定：48h 窗口 + 小时粒度（多点）下曲线是否出现。
 * 区分「单点+showSymbol:false 导致空白」（🟡 边界缺陷）vs「曲线从不渲染」（🔴 功能坏）。
 * 输出 /tmp/vis2/hist3_chart.png + 透视行数。
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

  // 参数：两组全选
  const paramInput = page.locator('.asm-filter input[placeholder="选择参数..."]');
  await paramInput.waitFor({ timeout: 15000 });
  await paramInput.click();
  const dialog = page.locator('.el-dialog .asm-params');
  await dialog.waitFor({ timeout: 10000 });
  for (const g of GROUPS) {
    await dialog.locator('.asm-param-head .el-checkbox', { hasText: g }).first().click();
  }
  await page.locator('.el-dialog__footer button', { hasText: '确 定' }).click();
  await page.waitForTimeout(500);

  // 时间窗拉宽到 48h（09-06 00:00 → 09-08 23:00），制造多点
  const startInput = page.locator('.asm-filter .el-date-editor input').first();
  const endInput = page.locator('.asm-filter .el-date-editor input').nth(1);
  await startInput.click();
  await startInput.fill('2026-09-06 00:00:00');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(300);
  await endInput.click();
  await endInput.fill('2026-09-08 23:00:00');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(600);
  const timeVals = await page.evaluate(() =>
    [...document.querySelectorAll('.asm-filter .el-date-editor input')].map((i) => i.value));
  console.log('[时间窗]', JSON.stringify(timeVals));

  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await page.waitForTimeout(4000);
  const rowCount = await page.locator('.el-table__body-wrapper tbody tr').count();
  console.log(`[透视行数] ${rowCount}`);
  const pagerText = await page.evaluate(() => {
    const p = document.querySelector('.asm-pager');
    return p ? p.innerText.replace(/\n/g, ' ') : null;
  });
  console.log('[分页]', pagerText);

  await page.locator('.asm-result-head .el-radio-button', { hasText: '曲线' }).click();
  await page.waitForTimeout(3000);
  await page.screenshot({ path: OUT + '/hist3_chart.png' });
  const m = await page.evaluate(() => ({
    sh: Math.max(document.documentElement.scrollHeight, document.body.scrollHeight), ih: window.innerHeight,
  }));
  console.log(`[曲线态一屏] diff=${m.sh - m.ih}`);
  await browser.close();
  console.log('DONE');
})().catch((e) => { console.error('SCRIPT ERROR:', e); process.exit(2); });
