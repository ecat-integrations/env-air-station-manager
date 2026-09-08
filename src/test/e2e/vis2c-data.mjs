/**
 * 数据口径核查（只读采集）：复现 vis2b 多组查询，抓 /asm-monitor/history 响应原始行，
 * 回答三个疑点：1) 原始行数 vs 分页 total（共 N 条）；2) 各参数是否有数据（'--' 列应=无行参数）；
 * 3) 曲线空白根因（每 series 非 null 点数；showSymbol:false 下单点不可见）。
 */
import { chromium } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BASE = process.env.BASE_URL || 'http://localhost:8081';
const ROUTE = BASE + '/#/ecat-integrations/integration-env-air-station-manager/air-station-manager/index/history_data';
const STATE = path.join(__dirname, 'test-results/.asm-auth.json');
const GROUPS = ['O3滤膜', 'PM10纸带记录仪'];

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 }, storageState: STATE });
  const page = await ctx.newPage();
  let historyRows = [];
  page.on('response', async (r) => {
    if (r.url().includes('/asm-monitor/history')) {
      try { const j = await r.json(); historyRows = (j && j.data && j.data.rows) || []; } catch (e) { /* 非 JSON 忽略 */ }
    }
  });
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
  for (const g of GROUPS) {
    await dialog.locator('.asm-param-head .el-checkbox', { hasText: g }).first().click();
  }
  await page.locator('.el-dialog__footer button', { hasText: '确 定' }).click();
  await page.waitForTimeout(500);
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await page.waitForTimeout(4000);

  console.log(`[原始响应] ${historyRows.length} 行:`);
  for (const r of historyRows) {
    console.log(`  ${r.dataTime} | ${r.deviceLabel || r.device_label || '?'} · ${r.attrId} = ${r.value}`);
  }
  const pagerText = await page.evaluate(() => {
    const p = document.querySelector('.asm-pager');
    return p ? p.innerText.replace(/\n/g, ' ') : null;
  });
  console.log('[分页]', pagerText);
  const rowCount = await page.locator('.el-table__body-wrapper tbody tr').count();
  console.log(`[表格透视行数] ${rowCount}`);
  await browser.close();
  console.log('DONE');
})().catch((e) => { console.error('SCRIPT ERROR:', e); process.exit(2); });
