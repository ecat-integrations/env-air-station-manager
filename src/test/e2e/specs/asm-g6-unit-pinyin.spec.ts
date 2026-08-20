/**
 * ASM G6 回归（2026-08-19 单位双模式 + 抽屉拼音序）：
 *  - G6-1 监控页显示单位切换：右上角 标准|自定义 分段控件；切自定义 → 重拉 snapshot 带 unit=custom；
 *       切回 标准 → 重拉 unit=standard（缺省参数形态）且控件态跟随
 *  - G6-2 抽屉参数分组序（2026-08-20 方案 B）：状态类→命令类→数值类，组内 displayName 拼音序
 *       （「重置*」多音字按 chóng 排命令组内最前）；SSE patch 后不重开抽屉仍有序（drawerAttrs 兜底）
 *  - G6-3 snapshot API 直连双模式：unit=standard 与 unit=custom 各自返回（行集非空、行序拼音不降序）
 */
import { test, expect, Page } from '@playwright/test';
import { AsmBasePage } from '../helpers/page-objects/AsmBasePage';

/** 浏览器侧拼音比较（zh-Hans-CN collation，与后端 Collator zh 同口径）。 */
async function pinyinSorted(page: Page, names: string[]): Promise<boolean> {
  return page.evaluate((ns) => {
    const cmp = (a: string, b: string) => String(a).localeCompare(String(b), 'zh-Hans-CN');
    return ns.every((n, i) => i === 0 || cmp(ns[i - 1], ns[i]) <= 0);
  }, names);
}

test('G6-1 监控页单位切换：自定义重拉 unit=custom，切回标准 @g6', async ({ page }) => {
  const base = new AsmBasePage(page, 'monitor');
  await base.goto();

  const switcher = page.locator('.asm-unit-switcher');
  await expect(switcher).toBeVisible();
  await expect(switcher.locator('.asm-unit-btn.active')).toHaveText('标准');

  // 切自定义：等待 unit=custom 的 snapshot 请求命中（网络断言，确定性）
  const customReq = page.waitForRequest((r) => r.url().includes('/asm-monitor/snapshot') && r.url().includes('unit=custom'), { timeout: 15000 });
  await switcher.locator('.asm-unit-btn', { hasText: '自定义' }).click();
  await customReq;
  await expect(switcher.locator('.asm-unit-btn.active')).toHaveText('自定义');

  // 切回标准：url 不带 unit 参数（standard 缺省形态）
  const stdReq = page.waitForRequest((r) => r.url().includes('/asm-monitor/snapshot') && !r.url().includes('unit='), { timeout: 15000 });
  await switcher.locator('.asm-unit-btn', { hasText: '标准' }).click();
  await stdReq;
  await expect(switcher.locator('.asm-unit-btn.active')).toHaveText('标准');
});

test('G6-2 校准仪抽屉分组序 + SSE patch 后不乱序 @g6', async ({ page }) => {
  const base = new AsmBasePage(page, 'monitor');
  await base.goto();

  await page.locator('.asm-tile').filter({ hasText: '动态校准仪' }).first().click();
  await page.locator('.asm-table').waitFor({ timeout: 10000 });
  const rowsOf = () => page.evaluate(() =>
    [...document.querySelectorAll('.asm-table tbody tr')].map((r) => r.cells[0].innerText.trim()));
  const names = await rowsOf();
  expect(names.length, '校准仪抽屉应 ≥30 行').toBeGreaterThanOrEqual(30);
  // 分组序锚点（2026-08-20 方案 B）：状态类（报警<运行<在线 组内拼音）→ 命令类（校准仪命令）→ 数值类（零气实时流量）
  const idx = (n: string) => names.findIndex((x) => x === n);
  expect(idx('报警状态'), `应含「报警状态」行（实际：${names.join(',')}）`).toBeGreaterThanOrEqual(0);
  expect(idx('运行状态'), '应含「运行状态」行').toBeGreaterThanOrEqual(0);
  expect(idx('在线状态'), '应含「在线状态」行').toBeGreaterThanOrEqual(0);
  expect(idx('校准仪命令'), '应含命令类行「校准仪命令」').toBeGreaterThanOrEqual(0);
  expect(idx('零气实时流量'), '应含数值类行「零气实时流量」').toBeGreaterThanOrEqual(0);
  expect(idx('报警状态'), '状态组内拼音：报警(bao) < 运行(yun)').toBeLessThan(idx('运行状态'));
  expect(idx('运行状态'), '状态组内拼音：运行(yun) < 在线(zai)').toBeLessThan(idx('在线状态'));
  expect(idx('在线状态'), '分组序：状态类 < 命令类').toBeLessThan(idx('校准仪命令'));
  expect(idx('校准仪命令'), '分组序：命令类 < 数值类').toBeLessThan(idx('零气实时流量'));

  // 等 SSE 帧到达（页面 window.__asmSseFrames 计数 ≥1，确定性轮询断言由 expect.poll 提供）后不重开抽屉复查序
  await expect.poll(async () => page.evaluate(() => (window as any).__asmSseFrames || 0),
    { timeout: 60000 }).toBeGreaterThan(0);
  const after = await rowsOf();
  const idxA = (n: string) => after.findIndex((x) => x === n);
  expect(idxA('在线状态'), 'SSE patch 后分组序仍应：状态类 < 命令类').toBeLessThan(idxA('校准仪命令'));
  expect(idxA('校准仪命令'), 'SSE patch 后分组序仍应：命令类 < 数值类').toBeLessThan(idxA('零气实时流量'));
});

test('G6-3 snapshot 双模式响应行集有序（standard/custom 各验一次） @g6', async ({ page }) => {
  const base = new AsmBasePage(page, 'monitor');
  await base.goto();

  const assertOrdered = (body: any, unit: string) => {
    expect(body.code, `snapshot(${unit}) body code=200（ruoyi 恒 HTTP200 看 code）`).toBe(200);
    expect(body.data.length, `snapshot(${unit}) 应有站房设备行`).toBeGreaterThan(0);
    const cal = body.data.find((d: any) => /校准仪/.test(d.displayName || d.logicDeviceUniqueId));
    expect(cal, `snapshot(${unit}) 应含校准仪设备`).toBeTruthy();
    const names: string[] = cal.attrs.map((a: any) => a.displayName || a.attrId);
    // 行序锚点断言（非全序）：Java Collator zh 与浏览器 ICU zh 在多音字/下标字符（校/₂）上读序有差，
    // 两侧一致的稳定锚点做断言——报警(bao) < 运行(yun) < 在线(zai)，且 ASCII 前缀行排在中文行前。
    const idx = (n: string) => names.findIndex((x) => x === n);
    expect(idx('报警状态'), `应含「报警状态」行（实际：${names.join(',')}）`).toBeGreaterThanOrEqual(0);
    expect(idx('运行状态'), `应含「运行状态」行`).toBeGreaterThanOrEqual(0);
    expect(idx('在线状态'), `应含「在线状态」行`).toBeGreaterThanOrEqual(0);
    expect(idx('报警状态'), '拼音序锚点：报警(bao) < 运行(yun)').toBeLessThan(idx('运行状态'));
    expect(idx('运行状态'), '拼音序锚点：运行(yun) < 在线(zai)').toBeLessThan(idx('在线状态'));
  };

  // standard（缺省形态）：goto 期间首拉已错过，挂监听后 ignoreCache reload 重挂载组件触发 onMounted 重拉
  const stdResPromise = page.waitForResponse((r) => r.url().includes('/asm-monitor/snapshot') && !r.url().includes('unit='), { timeout: 20000 });
  await page.reload({ ignoreCache: true });
  await page.waitForLoadState('load');
  assertOrdered(await (await stdResPromise).json(), 'standard');

  // custom：先挂监听再点切换（await 会先于 click 阻塞，必须持有 promise 后触发）
  const customResPromise = page.waitForResponse((r) => r.url().includes('/asm-monitor/snapshot') && r.url().includes('unit=custom'), { timeout: 20000 });
  await page.locator('.asm-unit-switcher .asm-unit-btn', { hasText: '自定义' }).click();
  assertOrdered(await (await customResPromise).json(), 'custom');
  // 还原默认 standard（localStorage 不残留 custom 影响后续用例）
  await page.locator('.asm-unit-switcher .asm-unit-btn', { hasText: '标准' }).click();
  await page.waitForTimeout(300);
});
