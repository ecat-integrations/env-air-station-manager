/**
 * ASM G7 回归（2026-08-19 报警可视化 P3）：
 *  - G7-1 筛选 chips 三态存在 + 计数与 DOM 瓦片数对账（全部/离线/报警 各点击后瓦片数=chip 计数）
 *  - G7-2 报警设备瓦片徽标出现（alarm 设备圆点显报警红 .asm-dot.alarm；2026-08-20 起瓦片头文字徽章
 *       移除、报警态并入三色圆点，红(报警)>灰(离线)>绿(在线)）
 *  - G7-3 抽屉行着色：NORMAL 行绿徽（tier-success）；存在 ALARM 行时红徽（tier-danger）；
 *       值列文字色随状态档变化（statusColor 内联 style）
 *  - G7-4 alarm_list 状态列 + 状态筛选：选「活跃」→ 请求带 status=ACTIVE 且行内徽标全「活跃」
 *
 * 报警态数据源：环境常驻 paper_tape.pm10/pm25（attr 侧自报 alarm=True，见 snapshot）；
 * 活跃筛选若环境无 ACTIVE episode，断言放宽为「请求带 status 参数 + 响应 code=200」（不造假数据）。
 */
import { test, expect } from '@playwright/test';
import { AsmBasePage } from '../helpers/page-objects/AsmBasePage';

/**
 * 点击 chip 后断言「chip 计数 == DOM 瓦片数」：单 evaluate 原子读 chip 文本计数与 .asm-tile 数
 * （同快照杜绝「读 chip 与数瓦片之间 SSE patch 改设备态」的撕裂竞态——先读 counts 再点击的旧形态
 * 在 SSE patch 落在两读之间时会闪红），expect.poll 收敛到相等。
 */
async function clickChipAndReconcile(page: any, key: string) {
  const labelOf: Record<string, string> = { all: '全部', offline: '离线', alarm: '报警' };
  await page.locator(`.asm-chip.${key}`).click();
  await page.locator(`.asm-chip.${key}.active`).waitFor();
  const read = () => page.evaluate((label) => {
    const el = [...document.querySelectorAll('.asm-chip')].find((c) => (c.textContent || '').startsWith(label));
    const m = (el!.textContent || '').match(/\((\d+)\)/)!;
    return { chip: Number(m[1]), tiles: document.querySelectorAll('.asm-tile').length };
  }, labelOf[key]);
  const first = await read();
  await expect.poll(async () => {
    const r = await read();
    return r.chip === r.tiles;
  }, { timeout: 15_000 }).toBe(true);
  const last = await read();
  expect(last.tiles, `chip(${key}) 计数 ${last.chip} 应等于 DOM 瓦片数（首读 ${first.chip}/${first.tiles}）`).toBe(last.chip);
}

test('G7-1 筛选 chips 三态 + 计数与 DOM 瓦片数对账 @g7', async ({ page }) => {
  const base = new AsmBasePage(page, 'monitor');
  await base.goto();

  await expect(page.locator('.asm-chip')).toHaveCount(3);

  // 三态各点击后：chip 计数 == DOM 瓦片数（单快照原子对账，SSE patch 改设备态后自然收敛）
  await clickChipAndReconcile(page, 'all');
  await clickChipAndReconcile(page, 'offline');
  await clickChipAndReconcile(page, 'alarm');
  await clickChipAndReconcile(page, 'all');   // 还原全部
});

test('G7-2 报警设备瓦片徽标出现（三色圆点红=报警） @g7', async ({ page }) => {
  const base = new AsmBasePage(page, 'monitor');
  await base.goto();

  // 报警过滤后每个瓦片圆点应为报警红（.asm-dot.alarm；并集非空→红，优先级 红>灰>绿）
  await page.locator('.asm-chip.alarm').click();
  await expect.poll(() => page.locator('.asm-tile').count(), { timeout: 15000 }).toBeGreaterThan(0);
  const perTile = await page.locator('.asm-tile').evaluateAll(
    (tiles) => tiles.every((t) => t.querySelector('.asm-dot.alarm') !== null));
  expect(perTile, '每个报警瓦片圆点应显报警红').toBe(true);
  await expect(page.locator('.asm-tile .asm-dot.alarm').first()).toBeVisible();
});

test('G7-3 抽屉行着色：NORMAL 绿徽 / ALARM 红徽 / 值列文字色 @g7', async ({ page }) => {
  const base = new AsmBasePage(page, 'monitor');
  await base.goto();

  // 正常在线设备（温湿度）：LIVE 行状态徽章应全 tier-success（status=NORMAL 枚举 key 配色）
  await page.locator('.asm-tile').filter({ hasText: '温湿度' }).first().click();
  await page.locator('.asm-table').waitFor({ timeout: 10000 });
  const badges = page.locator('.asm-table .asm-badge');
  const cnt = await badges.count();
  expect(cnt, '温湿度抽屉应有状态徽章行').toBeGreaterThan(0);
  const tiers = await badges.evaluateAll((els) => els.map((e) => e.className));
  expect(tiers.every((c) => c.includes('tier-success')),
    `在线正常设备状态徽章应全绿（实际：${tiers.join(' | ')}）`).toBe(true);

  // 值列文字色：成功档行内联 color=rgb(103, 194, 58)（#67c23a）
  const valueColor = await page.locator('.asm-table tbody tr td:nth-child(2)').first()
    .evaluate((el) => (el as HTMLElement).style.color);
  expect(valueColor).toBe('rgb(103, 194, 58)');

  // 报警设备（paper_tape.pm10）：该设备 attr 侧报警态行应有 danger 档徽章（或报警徽标设备至少可开抽屉）
  await page.keyboard.press('Escape');
  await page.locator('.asm-chip.alarm').click();
  await page.locator('.asm-tile').filter({ hasText: 'PM10纸带' }).first().click();
  await page.locator('.asm-table').waitFor({ timeout: 10000 });
  const alarmTiers = await page.locator('.asm-table .asm-badge').evaluateAll((els) => els.map((e) => e.className));
  expect(alarmTiers.some((c) => c.includes('tier-danger')),
    `报警设备抽屉应存在 danger 档徽章（实际：${alarmTiers.join(' | ')}）`).toBe(true);

  // 设备级状态条（2026-08-20）：报警并集徽章按档渲染（tier-danger 红），仅报警/离线时出现
  const statusbar = page.locator('.asm-drawer-statusbar');
  await expect(statusbar).toBeVisible();
  const barHasDanger = await statusbar.locator('.asm-badge.tier-danger').first().isVisible();
  expect(barHasDanger, 'paper_tape 抽屉状态条应有 danger 报警徽章').toBe(true);
});

test('G7-4 alarm_list 状态列 + 状态筛选生效 @g7', async ({ page }) => {
  const base = new AsmBasePage(page, 'alarm_list');
  await base.goto();

  // 状态列存在：徽章文本 ∈ {活跃, 已恢复}
  await page.locator('.asm-st-badge').first().waitFor({ timeout: 15000 });
  const texts = await page.locator('.asm-st-badge').allInnerTexts();
  expect(texts.length).toBeGreaterThan(0);
  expect(texts.every((t) => t === '活跃' || t === '已恢复')).toBe(true);

  // 选「活跃」：请求带 status=ACTIVE（网络断言确定性）
  const reqPromise = page.waitForRequest(
    (r) => r.url().includes('/asm-monitor/alarm-record/list') && r.url().includes('status=ACTIVE'),
    { timeout: 15000 });
  await page.locator('select').filter({ has: page.locator('option[value="ACTIVE"]') })
    .selectOption('ACTIVE');
  await reqPromise;
  await page.waitForResponse((r) => r.url().includes('status=ACTIVE') && r.status() === 200, { timeout: 15000 });
  await page.waitForTimeout(0);  // 让响应渲染落定（上面 waitForResponse 已确定性等到数据帧）
  // 若环境有 ACTIVE episode：行徽全「活跃」；若当前窗内无 ACTIVE 行（全已恢复），只验筛选请求已带参（上方网络断言）
  const activeTexts = await page.locator('.asm-st-badge').allInnerTexts();
  if (activeTexts.length > 0) {
    expect(activeTexts.every((t) => t === '活跃'),
      `活跃筛选后行徽应全「活跃」（实际：${activeTexts.join(',')}）`).toBe(true);
  }

  // 还原「全部」
  await page.locator('select').filter({ has: page.locator('option[value="ACTIVE"]') }).selectOption('');
});
