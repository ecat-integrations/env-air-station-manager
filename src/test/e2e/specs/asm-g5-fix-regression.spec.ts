/**
 * ASM 修复回归 G5（2026-08-19 用户实测 5 缺陷）：
 *  - G5-1 状态属性中文名：抽屉/瓦片参数列不泄漏裸 attrId（alarm_status 等下划线英文 key）
 *  - G5-2 瓦片与抽屉同集：摄像3 抽屉含「AI运行状态」（def 占位行，未绑定也显示）；动态校准仪抽屉 def 全集（≥30 行）
 *  - G5-3 数值出口修约：瓦片数值小数位 ≤2（HALF_EVEN 出口修约，如累积粉尘 160.67183…→160.67）
 */
import { test, expect } from '@playwright/test';
import { AsmBasePage } from '../helpers/page-objects/AsmBasePage';

/** 裸 attrId 形态：纯小写+下划线、无中文（如 alarm_status / running_status）。 */
const BARE_ATTR_ID = /^[a-z][a-z0-9_]*$/;

test('G5 监控页修复回归（中文名/同集/修约） @g5', async ({ page }) => {
  const base = new AsmBasePage(page, 'monitor');
  await base.goto();

  // G5-3 数值修约（累积粉尘瓦片）+ G5-1 瓦片参数名无裸 attrId
  const dust = await page.locator('.asm-tile').filter({ hasText: '累积粉尘' }).first().innerText();
  const num = dust.match(/累积粉尘\s*\n?\s*([\d.]+)/)?.[1];
  expect(num, `累积粉尘应显示数值（实际瓦片文本：${dust.replace(/\n/g, '|')}）`).toBeTruthy();
  const decimals = (num!.split('.')[1] || '').length;
  expect(decimals, `数值小数位应 ≤2（实际 ${num}）`).toBeLessThanOrEqual(2);

  const tileParamNames = await page.evaluate(() =>
    [...document.querySelectorAll('.asm-tile .asm-param-name')].map((e) => e.textContent?.trim() || ''));
  const bare = tileParamNames.filter((n) => BARE_ATTR_ID.test(n));
  expect(bare, `瓦片参数名不应泄漏裸 attrId（泄漏：${bare.join(',')}）`).toEqual([]);

  // G5-2 摄像3：抽屉含 AI运行状态（未绑定 def 占位行）；行名无裸 attrId
  await page.locator('.asm-tile').filter({ hasText: '3智能视频监控系统' }).first().click();
  await page.locator('.asm-table').waitFor({ timeout: 10000 });
  const camRows = await page.evaluate(() =>
    [...document.querySelectorAll('.asm-table tbody tr')].map((r) => r.cells[0].innerText.trim()));
  expect(camRows, '摄像3 抽屉应含「AI运行状态」def 行（瓦片与抽屉同集）').toContain('AI运行状态');
  const camBare = camRows.filter((n) => BARE_ATTR_ID.test(n));
  expect(camBare, `抽屉参数名不应泄漏裸 attrId（泄漏：${camBare.join(',')}）`).toEqual([]);

  // G5-2 动态校准仪：def 全集（20+ 定义属性 + 状态/浓度 live 行）
  await page.keyboard.press('Escape');
  await page.waitForTimeout(600);
  await page.locator('.asm-tile').filter({ hasText: '动态校准仪' }).first().click();
  await page.waitForTimeout(800);
  const calRows = await page.evaluate(() =>
    [...document.querySelectorAll('.asm-table tbody tr')].map((r) => r.cells[0].innerText.trim()));
  expect(calRows.length, `动态校准仪抽屉应显 def 全集（实际 ${calRows.length} 行）`).toBeGreaterThanOrEqual(30);
});
