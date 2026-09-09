/**
 * ASM 历史页非数值展示回归（g10-table-*，2026-09-09 设计决策 A 档）：
 *  - 表格：非数值行（ALARM 报警 / STATE 状态）显 value_text 原文（现状恒 '--' 的缺陷修复）、
 *    不显计数（2026-09-09 夜拍板移除 W1 的 N/M 内联与计数悬浮——值串裸读，计数挤占列宽）
 *  - 报警红：值串 'alarm' 单元格红 #f56c6c；normal / 状态串不占红（红=报警专属语义）
 *  - 曲线：ALARM 参数出三色状态带子图（形态向断言见 asm-g10-band.spec.ts）；STATE 不出子图
 *    （分图/合并同限），数量提示按布局区分（分图=STATE、合并=ALARM+STATE）与明确空态
 *  - 导出：CSV 值列导 value_text 原文；仅数值参数跟「有效/总数」计数列（非数值参数不跟）
 *
 * 数据前提（环境常驻，分钟桶连续）：SecurityAlarm ir_alarm=alarm、water_leak=normal，
 * exhaust_fan speed=off，ac1 setpoint_temp 数值连续——参数选择按中文名锚定。
 *
 * ASM_BUNDLE_OVERRIDE（过渡验证缝）：部署 jar 落后于源码（npm run release 后未 install + 重启 core）时，
 * 把模块脚本路由到指定本地构建（生产 dist 字节）在真宿主+真后端上验证；正式套件与 W4 全量回归不设此
 * env，黑盒打部署产物——设了会在输出里显式标注，避免误当部署产物结论。
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import { AsmBasePage, attachErrorCollectors, toLocalInput } from '../helpers/page-objects/AsmBasePage';

const BUNDLE_OVERRIDE = process.env.ASM_BUNDLE_OVERRIDE || '';

/** 前置：分钟粒度 + 近 30min 窗（分钟桶延迟 +10s，必有数据），并注入本地构建（可选）。 */
async function openHistory(page: import('@playwright/test').Page) {
  if (BUNDLE_OVERRIDE) {
    await page.route('**/ecat-integrations/integration-env-air-station-manager/air-station-manager.js*', (route) =>
      route.fulfill({ path: BUNDLE_OVERRIDE, contentType: 'application/javascript' }));
    console.log(`[g10] ASM_BUNDLE_OVERRIDE 生效，模块脚本指向本地构建: ${BUNDLE_OVERRIDE}`);
  }
  const hp = new AsmBasePage(page, 'history_data');
  await hp.goto();
  // 正则全匹配：避免「分钟」子串命中「5分钟」
  await page.locator('.asm-filter .el-radio-button').filter({ hasText: /^分钟$/ }).click();
  const now = new Date();
  const times = page.locator('.asm-filter .el-range-input');
  await times.nth(0).fill(toLocalInput(new Date(now.getTime() - 30 * 60 * 1000)).replace('T', ' '));
  await times.nth(1).fill(toLocalInput(new Date(now.getTime() + 60 * 1000)).replace('T', ' '));
}

/** 参数弹窗勾选（中文名锚定）并确定（确定即触发重查）。 */
async function checkParams(page: import('@playwright/test').Page, names: string[]) {
  await page.locator('.asm-filter input[placeholder="选择参数..."]').click();
  const dialog = page.locator('.el-dialog').filter({ hasText: '选择参数' });
  await expect(dialog).toBeVisible({ timeout: 10_000 });
  for (const name of names) {
    const cb = dialog.locator('.asm-param-cb').filter({ hasText: name }).first();
    await expect(cb, `参数弹窗应含「${name}」`).toBeVisible({ timeout: 10_000 });
    await cb.click();
  }
  await page.getByRole('button', { name: '确 定' }).click();
}

/** 参数中文名 → 表体 1-based 列序号（表头=时刻 + 勾选集顺序，fixed 列同表渲染）。 */
async function colIndexOf(page: import('@playwright/test').Page, name: string) {
  const heads = await page.locator('.el-table__header-wrapper th').allInnerTexts();
  const i = heads.findIndex((h) => h.includes(name));
  expect(i, `表头应含「${name}」（实际：${heads.join(' | ')}）`).toBeGreaterThanOrEqual(0);
  return i + 1;
}

/** 某参数列全部单元格的值串 / 内联计数（el-table 单元格内容包在 td>div.cell 内，el-tooltip 不产生包装节点）。 */
async function columnCells(page: import('@playwright/test').Page, colIdx: number) {
  const td = `.el-table__body-wrapper .el-table__row td:nth-child(${colIdx})`;
  const values = await page.locator(`${td} span:not(.asm-cell-count)`).allInnerTexts();
  const counts = await page.locator(`${td} span.asm-cell-count`).allInnerTexts();
  return { values, counts };
}

test('g10-table-非数值单元格显 value_text 原文且无计数而非 -- @g10', async ({ page }) => {
  const errors: string[] = [];
  attachErrorCollectors(page, errors);
  await openHistory(page);
  await checkParams(page, ['红外报警', '漏水报警']);

  const rows = page.locator('.el-table__body-wrapper .el-table__row');
  await expect(rows.first()).toBeVisible({ timeout: 20_000 });
  // 等间隔网格：30min 窗逐分钟成行（含末尾未闭环 tick 的占位行），行数只由 tick 数决定
  expect(await rows.count(), '30min 分钟窗应逐分钟成行（等间隔网格）').toBeGreaterThanOrEqual(30);

  const irCol = await colIndexOf(page, '红外报警');
  const leakCol = await colIndexOf(page, '漏水报警');
  const ir = await columnCells(page, irCol);
  const leak = await columnCells(page, leakCol);
  // 网格占位格（数值 '-' / 非数值 '--'=该时刻无观测）与数据格分开判定
  const isPlaceholder = (v: string) => v === '-' || v === '--';
  const irData = ir.values.filter((v) => !isPlaceholder(v));
  const leakData = leak.values.filter((v) => !isPlaceholder(v));

  // 值串原文：数据格值域 normal/alarm（ALARM series 二值）——缺陷形态=文本全被 -- 吞掉（数据格为 0）
  expect(irData.length, '红外报警列应有数据格').toBeGreaterThan(0);
  for (const v of irData) expect(['alarm', 'normal'], `红外报警值应属 normal/alarm 值域（实际 ${v}）`).toContain(v);
  expect(ir.values.filter((v) => v === 'alarm').length, '环境前提：SecurityAlarm 红外在告警态，窗口内应有 alarm 行').toBeGreaterThan(0);

  expect(leakData.length, '漏水报警列应有数据格').toBeGreaterThan(0);
  for (const v of leakData) expect(v, '漏水报警应全 normal（未漏水）').toBe('normal');

  // 非数值格不显计数（2026-09-09 拍板移除 W1 的 N/M 内联）：值串裸读，占位格/数据格均无计数文本
  expect(ir.counts.length, '红外报警列不应有内联计数').toBe(0);
  expect(leak.counts.length, '漏水报警列不应有内联计数').toBe(0);
  expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
});

test('g10-table-alarm 值红色且 normal 不占红 @g10', async ({ page }) => {
  const errors: string[] = [];
  attachErrorCollectors(page, errors);
  await openHistory(page);
  await checkParams(page, ['红外报警', '漏水报警']);

  const rows = page.locator('.el-table__body-wrapper .el-table__row');
  await expect(rows.first()).toBeVisible({ timeout: 20_000 });
  const irCol = await colIndexOf(page, '红外报警');
  const leakCol = await colIndexOf(page, '漏水报警');

  // alarm 格=红（.asm-alarm-text → #f56c6c）；红=报警专属，normal 格不得命中该类/该色
  const alarmCell = page.locator(`.el-table__body-wrapper .el-table__row td:nth-child(${irCol})`).filter({ hasText: 'alarm' }).first();
  await expect(alarmCell.locator('span.asm-alarm-text')).toHaveClass(/asm-alarm-text/);
  expect(await alarmCell.locator('span.asm-alarm-text').evaluate((el) => getComputedStyle(el).color))
    .toBe('rgb(245, 108, 108)');

  const normalCell = page.locator(`.el-table__body-wrapper .el-table__row td:nth-child(${leakCol})`).filter({ hasText: 'normal' }).first();
  await expect(normalCell).toBeVisible();
  expect(await normalCell.locator('span.asm-alarm-text').count(), 'normal 格不应带报警红类').toBe(0);
  expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
});

test('g10-table-全为 STATE 参数不出子图（空态+数量提示，分图/合并同限） @g10', async ({ page }) => {
  const errors: string[] = [];
  attachErrorCollectors(page, errors);
  await openHistory(page);
  // W3 后 ALARM 参数已出三色带子图（见 asm-g10-band.spec.ts），不画曲线的只剩 STATE 状态串
  await checkParams(page, ['转速档位']);

  const rows = page.locator('.el-table__body-wrapper .el-table__row');
  await expect(rows.first()).toBeVisible({ timeout: 20_000 });

  // 分图：全为 STATE → 无子图无 canvas，空态文案区别于「无数据」，操作行数量提示只剩 STATE
  await page.locator('.asm-result-head .el-radio-button', { hasText: '曲线' }).click();
  await expect(page.locator('.asm-split-cell')).toHaveCount(0);
  await expect(page.locator('.asm-chart-wrap canvas')).toHaveCount(0);
  await expect(page.locator('.asm-chart-empty')).toContainText('所选参数均为状态类，不绘制曲线');
  await expect(page.locator('.asm-merge-hint').filter({ hasText: '项状态参数不画曲线' })).toHaveText('1 项状态参数不画曲线（列表可查）');

  // 合并：同一限制（无 series 可画，不出空图），空态文案按布局如实区分（STATE 也不参与合并）
  await page.locator('.asm-result-head .el-radio-button', { hasText: '合并' }).click();
  await expect(page.locator('.asm-chart-wrap canvas')).toHaveCount(0);
  await expect(page.locator('.asm-chart-empty')).toContainText('所选参数均为报警/状态类，不参与合并');
  expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
});

test('g10-table-混选分图出数值折线+ALARM 带子图 @g10', async ({ page }) => {
  const errors: string[] = [];
  attachErrorCollectors(page, errors);
  await openHistory(page);
  await checkParams(page, ['红外报警', '设定温度']);

  const rows = page.locator('.el-table__body-wrapper .el-table__row');
  await expect(rows.first()).toBeVisible({ timeout: 20_000 });

  await page.locator('.asm-result-head .el-radio-button', { hasText: '曲线' }).click();
  // 分图：ALARM（红外报警）出三色带格、数值（设定温度）出折线格，勾选顺序混排；无 STATE 无提示
  await expect(page.locator('.asm-split-cell')).toHaveCount(2);
  await expect(page.locator('.asm-split-cell canvas').first()).toBeVisible();
  const types = await page.evaluate(() => {
    const cells = [...document.querySelectorAll('.asm-split-cell')];
    return cells.map((dom) => {
      const inst = (window as any).echarts.getInstanceByDom(dom);
      return inst && !inst.isDisposed() ? inst.getOption().series[0].type : null;
    });
  });
  expect(types, `分图格形态应为 [带, 折线]（实际：${JSON.stringify(types)}）`).toEqual(['bar', 'line']);
  await expect(page.locator('.asm-merge-hint').filter({ hasText: '项状态参数不画曲线' })).toHaveCount(0);

  // 合并：仅数值 series 参与（1 条折线），ALARM 带不进合并
  await page.locator('.asm-result-head .el-radio-button', { hasText: '合并' }).click();
  await expect(page.locator('.asm-chart canvas').first()).toBeVisible();
  await expect(page.locator('.asm-merge-hint').filter({ hasText: '项报警/状态参数不画曲线' })).toHaveText('1 项报警/状态参数不画曲线（列表可查）');
  expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
});

test('g10-table-CSV 导出含 value_text 原文与计数列 @g10', async ({ page }) => {
  const errors: string[] = [];
  attachErrorCollectors(page, errors);
  await openHistory(page);
  await checkParams(page, ['红外报警', '设定温度']);

  const rows = page.locator('.el-table__body-wrapper .el-table__row');
  await expect(rows.first()).toBeVisible({ timeout: 20_000 });

  const downloadPromise = page.waitForEvent('download');
  await page.locator('.asm-result-head .el-button', { hasText: '导出' }).click();
  const download = await downloadPromise;
  const file = await download.path();
  expect(file, '导出应产出文件').toBeTruthy();
  const csv = fs.readFileSync(file!, 'utf8').replace(/^﻿/, '');
  const lines = csv.split(/\r?\n/).filter((l) => l.length > 0);
  const header = lines[0].split(',');
  // CSV 列头=表格 series 全名（设备·参数 (+单位)），按参数中文名子串锚定
  const irVal = header.findIndex((h) => h.includes('红外报警') && !h.includes('有效/总数'));
  const irCount = header.findIndex((h) => h.includes('红外报警') && h.includes('有效/总数'));
  const tempVal = header.findIndex((h) => h.includes('设定温度') && !h.includes('有效/总数'));
  const tempCount = header.findIndex((h) => h.includes('设定温度') && h.includes('有效/总数'));
  expect(irVal, `CSV 表头应有「红外报警」值列（实际：${header.join(' | ')}）`).toBeGreaterThanOrEqual(0);
  expect(irCount, '非数值参数不应带「有效/总数」计数列（2026-09-09 拍板移除）').toBe(-1);
  expect(tempVal, 'CSV 表头应有「设定温度」值列').toBeGreaterThanOrEqual(0);
  expect(tempCount, '数值参数应带计数列（数值向呈现保持现状）').toBeGreaterThanOrEqual(0);

  const body = lines.slice(1).map((l) => l.split(','));
  expect(body.length, '导出应含数据行').toBeGreaterThan(0);
  const irTexts = body.map((r) => r[irVal]);
  const tempCounts = body.map((r) => r[tempCount]);
  // 值列非空格必须是 value_text 原文：'--' 回潮 / 全列空串（W1 缺陷形态）都不许
  const irNonEmpty = irTexts.filter((v) => v !== '');
  expect(irNonEmpty.length, `红外报警值列应有非空 value_text（实际样例：${irTexts.slice(0, 5).join(',')}）`).toBeGreaterThan(0);
  expect(irTexts, `红外报警值列不应有 --（实际样例：${irTexts.slice(0, 5).join(',')}）`).not.toContain('--');
  expect(irNonEmpty, '红外报警值列应导 value_text 原文').toContain('alarm');
  // 数值参数计数列保留：数据格恒 N/M（非空样本数/总样本数），无数据网格行（值列空）计数列同空
  for (let i = 0; i < body.length; i++) {
    if (body[i][tempVal] === '') expect(tempCounts[i], `无数据网格行（第 ${i + 1} 行）计数列应为空`).toBe('');
    else expect(tempCounts[i], `计数列应为 N/M 形态（实际 ${tempCounts[i]}）`).toMatch(/^\d+\/\d+$/);
  }
  expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
});
