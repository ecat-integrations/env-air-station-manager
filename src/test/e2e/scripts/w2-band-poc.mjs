/**
 * W2 PoC：ASM 历史页 ALARM 三色状态带技术验证（2026-09-09，结论已录入设计文档 §5「W2 PoC 结论」）。
 *
 * 用途：只读注入验证——在真实 8081 历史页「活」的 echarts 实例（组件自建 markRaw 实例，经
 * echarts.getInstanceByDom 取 raw）上 setOption(opt, true) 注入试验 option，不 dispose 组件实例、
 * 不碰产品代码/dist；结束重查一次恢复页面原状。产出即 W4 g10 曲线向 e2e 的断言雏形
 * （canvas 像素采样断言带色、tooltip DOM 无 class 按 absolute+非空文本判定、真 mouse 悬停）。
 *
 * 验证项：gate-1 category 轴+字符串 data 默认 tooltip ／ gate-2 三色带三方案
 * （bar+showBackground、双 series 堆叠、markArea）+ 真实数据形态带 + resize/图例/connect/重查回归 ／
 * gate-3 tooltip.formatter 在 markRaw 时代 dist 域复活确认。
 *
 * 运行：cd src/test/e2e && node scripts/w2-band-poc.mjs   （前置：8081 在线 + .asm-auth.json 登录态）
 */
import { chromium } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
const __dirname = path.dirname(fileURLToPath(import.meta.url));

const BASE = 'http://localhost:8081';
const ROUTE = BASE + '/#/ecat-integrations/integration-env-air-station-manager/air-station-manager/index/history_data';
const AUTH = path.join(__dirname, '../test-results/.asm-auth.json');
const OUT = path.join(__dirname, '../test-results/w2-band-poc');
const GROUPS = ['安防报警监测装置', '站房温湿度监测仪'];  // ALARM 组 + 数值组（基线/connect 对照）
const VIEWPORT = { width: 1600, height: 900 };

const rows = [];
function report(gate, item, verdict, detail) {
  if (verdict === true) verdict = 'PASS';
  else if (verdict === false) verdict = 'FAIL';
  rows.push({ gate, item, verdict, detail });
  console.log(`  ${verdict === 'PASS' ? '✓' : verdict === 'FAIL' ? '✗' : '~'} [${gate}] ${item}${detail ? ' — ' + detail : ''}`);
}

/** 页内注入的 PoC 工具集：活实例访问 / tooltip 文本 / canvas 像素采样 */
const POC_HELPERS = () => {
  window.__poc = {
    cells: () => [...document.querySelectorAll('.asm-split-cell')],
    inst(i) {
      const dom = this.cells()[i];
      if (!dom) return null;
      const g = window.echarts.getInstanceByDom(dom);
      return g && !g.isDisposed() ? g : null;
    },
    name(i) {
      const o = this.opt(i);
      return o && o.series[0] ? o.series[0].name : null;
    },
    opt(i) {
      const g = this.inst(i);
      try { return g ? g.getOption() : null; } catch (e) { return null; }
    },
    setOpt(i, opt) { return this.inst(i).setOption(opt, true); },
    /** 图内 tooltip 文本（echarts5 tooltip div 无 class，按 absolute 定位 + 非空文本判定） */
    tooltipIn(dom) {
      for (const d of dom.querySelectorAll('div')) {
        const cs = getComputedStyle(d);
        if ((cs.position === 'absolute' || cs.position === 'fixed') && cs.display !== 'none' && cs.visibility !== 'hidden' && d.innerText.trim()) {
          const r = d.getBoundingClientRect();
          if (r.width > 4 && r.height > 4) return d.innerText.trim();
        }
      }
      return null;
    },
    tooltip(i) { return this.tooltipIn(this.cells()[i]); },
    /** 桶中心 x（相对格左缘的局部 px） */
    bucketLocalX(i, idx) {
      return this.inst(i).convertToPixel({ xAxisIndex: 0 }, idx);
    },
    midY(i) {
      const r = this.cells()[i].getBoundingClientRect();
      return r.top + r.height * 0.5;
    },
    /** canvas 像素采样（viewport CSS 坐标 → [r,g,b]） */
    probe(i, x, y) {
      const canvas = this.cells()[i].querySelector('canvas');
      const rect = canvas.getBoundingClientRect();
      const dpr = canvas.width / rect.width;
      const d = canvas.getContext('2d').getImageData(
        Math.round((x - rect.left) * dpr), Math.round((y - rect.top) * dpr), 1, 1).data;
      return [d[0], d[1], d[2]];
    },
    probeBucket(i, idx) {
      const r = this.cells()[i].getBoundingClientRect();
      return this.probe(i, r.left + this.bucketLocalX(i, idx), this.midY(i));
    },
  };
};

const NEAR = (a, b, tol = 30) => a.length === b.length && a.every((v, k) => Math.abs(v - b[k]) <= tol);
const RED = [245, 108, 108], GREEN = [103, 194, 58], GRAY = [144, 147, 153], DEFAULT_BLUE = [84, 112, 198], BG = [255, 255, 255];
function colorName(rgb) {
  if (NEAR(rgb, RED)) return '红';
  if (NEAR(rgb, GREEN)) return '绿';
  if (NEAR(rgb, GRAY)) return '灰';
  if (NEAR(rgb, DEFAULT_BLUE)) return 'echarts默认蓝#5470c6';
  if (NEAR(rgb, BG)) return '白底';
  return `rgb(${rgb.join(',')})`;
}

/** 悬停某分图格内局部 x（相对格左缘）处，y 取格子纵向中点（必落在 grid 绘图区内） */
async function hover(page, cellIdx, localX) {
  await page.evaluate((i) => document.querySelectorAll('.asm-split-cell')[i].scrollIntoViewIfNeeded(), cellIdx);
  await page.waitForTimeout(120);
  const p = await page.evaluate(([i, dx]) => {
    const r = document.querySelectorAll('.asm-split-cell')[i].getBoundingClientRect();
    return { x: r.left + dx, y: r.top + r.height * 0.5 };
  }, [cellIdx, localX]);
  await page.mouse.move(p.x, p.y, { steps: 6 });
  await page.waitForTimeout(320);
}
/** 悬停某格某桶中心 */
async function hoverBucket(page, cellIdx, bucketIdx) {
  const lx = await page.evaluate(([i, k]) => window.__poc.bucketLocalX(i, k), [cellIdx, bucketIdx]);
  await hover(page, cellIdx, lx);
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  if (!fs.existsSync(AUTH)) throw new Error('登录态缺失: ' + AUTH);
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: VIEWPORT, storageState: AUTH });
  const page = await ctx.newPage();

  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });

  // —— 部署产物指纹：确认 8081 在跑的是 markRaw 时代 dist（无 pageRealmReplay 绕行）——
  let bundleFingerprint = null;
  page.on('response', async (r) => {
    if (r.url().includes('air-station-manager.js') && (r.headers()['content-type'] || '').includes('javascript')) {
      try {
        const body = await r.text();
        bundleFingerprint = { url: r.url(), bytes: body.length, markRaw: body.includes('markRaw'), replay: /pageRealm|__asmReplay/i.test(body), splitCell: body.includes('splitCell') };
      } catch (e) { /* body 可能已被消费 */ }
    }
  });

  console.log('== 环境与部署产物 ==');
  await page.goto(ROUTE);
  await page.waitForLoadState('load');
  await page.waitForTimeout(1500);
  if (!(await page.locator('.asm-page').count())) {
    await page.goto(BASE + '/#/index');
    await page.waitForTimeout(1200);
    await page.goto(ROUTE);
    await page.waitForTimeout(1500);
  }
  await page.evaluate(() => localStorage.removeItem('asm-history-checked'));
  await page.reload();
  await page.waitForLoadState('load');
  await page.locator('.asm-page').first().waitFor({ timeout: 30000 });
  await page.waitForTimeout(800);
  await page.evaluate(POC_HELPERS);

  report('ENV', '页面挂载 .asm-page', 'PASS', ROUTE);
  console.log('  部署产物指纹:', JSON.stringify(bundleFingerprint));
  report('ENV', '8081 在跑 markRaw 时代 dist（无 pageRealmReplay 注入绕行）',
    !!(bundleFingerprint && bundleFingerprint.markRaw && !bundleFingerprint.replay) ? 'PASS' : 'WARN',
    bundleFingerprint ? `${bundleFingerprint.bytes}B markRaw=${bundleFingerprint.markRaw} replay=${bundleFingerprint.replay}` : '未捕获模块响应');

  // —— 选参：ALARM 组 + 数值组 → 搜索 → 曲线分图 ——
  const paramInput = page.locator('.asm-filter input[placeholder="选择参数..."]');
  await paramInput.waitFor({ timeout: 15000 });
  await paramInput.click();
  const dialog = page.locator('.el-dialog .asm-params');
  await dialog.waitFor({ timeout: 10000 });
  for (const g of GROUPS) {
    await dialog.locator('.asm-param-head .el-checkbox', { hasText: g }).first().click();
  }
  await page.locator('.el-dialog__footer button', { hasText: '确 定' }).click();
  await page.waitForTimeout(400);
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await page.locator('.el-table__header-wrapper thead tr').first().waitFor({ timeout: 20000 });
  await page.locator('.asm-view-col .el-radio-button__inner', { hasText: '曲线' }).click();
  await page.locator('.asm-split-cell canvas').first().waitFor({ timeout: 20000 });
  await page.waitForTimeout(600);

  const cellCount = await page.evaluate(() => window.__poc.cells().length);
  const names = [];
  for (let i = 0; i < cellCount; i++) names.push(await page.evaluate((i) => window.__poc.name(i), i));
  console.log('  分图各格:', names.map((n, i) => `${i}:${n}`).join('  '));
  report('ENV', '分图挂载（ALARM 组+数值组）', cellCount >= GROUPS.length ? 'PASS' : 'FAIL', `${cellCount} 格`);

  const alarmIdx = names.findIndex((n) => n && n.includes('漏水报警'));
  const numericIdx = names.findIndex((n) => n && n.endsWith('·温度'));  // 真数值参数（非阈值）
  const alarmSlot = alarmIdx >= 0 ? alarmIdx : 0;

  // ============================================================
  console.log('\n== 基线：现役（markRaw 时代）组件原生 tooltip ==');
  if (numericIdx >= 0) {
    const n = await page.evaluate((i) => {
      const s = window.__poc.inst(i).getOption().series[0];
      return { points: (s.data || []).filter((v) => v != null).length, name: s.name, firstIdx: (s.data || []).findIndex((v) => v != null) };
    }, numericIdx);
    await hoverBucket(page, numericIdx, Math.max(n.firstIdx, 0));
    const tt = await page.evaluate((i) => window.__poc.tooltip(i), numericIdx);
    report('BASELINE', `数值参数「${n.name}」默认 tooltip 原生出值（${n.points} 点）`, !!(tt && n.points > 0), tt ? JSON.stringify(tt) : '无 tooltip');
    await page.screenshot({ path: path.join(OUT, '0-baseline-tooltip.png') });
  } else {
    report('BASELINE', '未定位到数值参数格，跳过基线', 'WARN', names.join('、'));
  }
  const preErrors = errors.length;

  // —— 试验数据：40 桶、绿(0-14) / 灰缺桶(15-19) / 红(20-29) / 绿(30-39) ——
  const bandData = await page.evaluate(() => {
    const times = [], vals = [];
    const base = new Date(2026, 8, 9, 9, 0, 0);
    for (let i = 0; i < 40; i++) {
      const t = new Date(base.getTime() + i * 60000);
      times.push(`09-09 ${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}`);
      vals.push(i >= 15 && i <= 19 ? null : i >= 20 && i <= 29 ? 'alarm' : 'normal');
    }
    return { times, vals };
  });

  // ============================================================
  console.log('\n== gate-1: category 轴 + 字符串 data → 默认 tooltip ==');
  // 1a line + category y 轴（2 档）+ 字符串 data
  await page.evaluate(([i, d]) => {
    window.__poc.setOpt(i, {
      animation: false,
      title: { text: 'PoC 1a line+categoryY+字符串data', left: 6, top: 2, textStyle: { fontSize: 12, fontWeight: 500 } },
      tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'line' } },
      grid: { left: 56, right: 14, top: 30, bottom: 26 },
      xAxis: { type: 'category', data: d.times, axisLabel: { hideOverlap: true } },
      yAxis: { type: 'category', data: ['normal', 'alarm'] },
      series: [{ name: '漏水报警', type: 'line', showSymbol: true, symbolSize: 7, data: d.vals }],
    });
  }, [alarmSlot, bandData]);
  {
    await hoverBucket(page, alarmSlot, 22);
    const tt = await page.evaluate((i) => window.__poc.tooltip(i), alarmSlot);
    report('GATE-1', '1a line + category y 轴 + 字符串 data → 默认 tooltip 显字符串值+系列名',
      !!(tt && tt.includes('alarm') && tt.includes('漏水报警')), tt ? JSON.stringify(tt) : '无 tooltip');
    await page.screenshot({ path: path.join(OUT, '1a-line-caty-string.png') });
  }

  // 1b bar + category y 轴 + 字符串 data（字符串值能否驱动 bar 形态）
  await page.evaluate(([i, d]) => {
    window.__poc.setOpt(i, {
      animation: false,
      title: { text: 'PoC 1b bar+categoryY+字符串data', left: 6, top: 2, textStyle: { fontSize: 12, fontWeight: 500 } },
      tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'line' } },
      grid: { left: 56, right: 14, top: 30, bottom: 26 },
      xAxis: { type: 'category', data: d.times, axisLabel: { hideOverlap: true } },
      yAxis: { type: 'category', data: ['normal', 'alarm'] },
      series: [{ name: '漏水报警', type: 'bar', data: d.vals, barWidth: '100%' }],
    });
  }, [alarmSlot, bandData]);
  {
    await page.waitForTimeout(250);
    const rgb = await page.evaluate((i) => window.__poc.probeBucket(i, 22), alarmSlot);
    report('GATE-1', '1b bar + category y 轴 + 字符串 data → bar 出形（默认调色板色，无按值着色）',
      !NEAR(rgb, BG), `采样=${colorName(rgb)}`);
    await hoverBucket(page, alarmSlot, 22);
    const tt = await page.evaluate((i) => window.__poc.tooltip(i), alarmSlot);
    report('GATE-1', '1b 同上 → 默认 tooltip 显字符串值', !!(tt && tt.includes('alarm')), tt ? JSON.stringify(tt) : '无 tooltip');
    await page.screenshot({ path: path.join(OUT, '1b-bar-caty-string.png') });
  }

  // 1c bar + value y（0..1，隐藏）+ 数值 data + 逐点 itemStyle（状态带形态）
  await page.evaluate(([i, d]) => {
    window.__poc.setOpt(i, {
      animation: false,
      title: { text: 'PoC 1c bar+valueY+数值data（带形态）', left: 6, top: 2, textStyle: { fontSize: 12, fontWeight: 500 } },
      tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'line' } },
      grid: { left: 56, right: 14, top: 30, bottom: 26 },
      xAxis: { type: 'category', data: d.times, axisLabel: { hideOverlap: true } },
      yAxis: { type: 'value', min: 0, max: 1, show: false },
      series: [{
        name: '漏水报警', type: 'bar', barWidth: '100%',
        data: d.vals.map((v) => v == null ? null : { value: 1, itemStyle: { color: v === 'alarm' ? '#f56c6c' : '#67c23a' } }),
      }],
    });
  }, [alarmSlot, bandData]);
  {
    await page.waitForTimeout(250);
    const rgb = await page.evaluate((i) => window.__poc.probeBucket(i, 22), alarmSlot);
    report('GATE-1', '1c bar + 隐藏 value y + 数值 data + 逐点色 → 带形出形（红桶采样红）', NEAR(rgb, RED), `采样=${colorName(rgb)}`);
    await hoverBucket(page, alarmSlot, 22);
    const tt = await page.evaluate((i) => window.__poc.tooltip(i), alarmSlot);
    report('GATE-1', '1c 同上 → 默认 tooltip 内容（预期退化为数值 1，丢语义）', !!tt, tt ? JSON.stringify(tt) : '无 tooltip');
    await page.screenshot({ path: path.join(OUT, '1c-bar-valuey-numeric.png') });
  }

  // ============================================================
  console.log('\n== gate-2: 三色状态带渲染与交互回归 ==');

  // 2A 方案一：单 series 逐点 itemStyle + showBackground 灰底（缺桶=null 槽位）
  const bandSingle = (d) => ({
    animation: false,
    title: { text: 'PoC 2A 三色带 bar+showBackground', left: 6, top: 2, textStyle: { fontSize: 12, fontWeight: 500 } },
    tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'line' } },
    grid: { left: 48, right: 14, top: 26, bottom: 26 },
    xAxis: { type: 'category', data: d.times, axisLabel: { hideOverlap: true } },
    yAxis: { type: 'value', min: 0, max: 1, show: false },
    series: [{
      name: '漏水报警', type: 'bar', barWidth: '100%', showBackground: true,
      backgroundStyle: { color: '#909399' },
      data: d.vals.map((v) => v == null ? null : { value: 1, itemStyle: { color: v === 'alarm' ? '#f56c6c' : '#67c23a' } }),
    }],
  });
  await page.evaluate(([i, opt]) => window.__poc.setOpt(i, opt), [alarmSlot, bandSingle(bandData)]);
  {
    await page.waitForTimeout(250);
    const samples = await page.evaluate((i) => ({
      green: window.__poc.probeBucket(i, 5),
      gap: window.__poc.probeBucket(i, 17),
      red: window.__poc.probeBucket(i, 22),
      green2: window.__poc.probeBucket(i, 35),
    }), alarmSlot);
    report('GATE-2', '2A 单series+showBackground：正常桶=绿', NEAR(samples.green, GREEN), colorName(samples.green));
    report('GATE-2', '2A 报警桶=红', NEAR(samples.red, RED), colorName(samples.red));
    report('GATE-2', '2A 缺桶槽位（data=null）露出灰底 → 「无数据≠正常」', NEAR(samples.gap, GRAY), colorName(samples.gap));
    report('GATE-2', '2A 尾段正常桶=绿', NEAR(samples.green2, GREEN), colorName(samples.green2));
    await page.screenshot({ path: path.join(OUT, '2a-band-single.png') });

    await hoverBucket(page, alarmSlot, 22);
    const ttRed = await page.evaluate((i) => window.__poc.tooltip(i), alarmSlot);
    report('GATE-2', '2A 带上悬浮默认 tooltip 可用（内容=数值 1）', !!ttRed, ttRed ? JSON.stringify(ttRed) : '无 tooltip');
    await hoverBucket(page, alarmSlot, 17);
    const ttGap = await page.evaluate((i) => window.__poc.tooltip(i), alarmSlot);
    report('GATE-2', '2A 缺桶槽位悬浮：默认 tooltip 行为（记录）', 'INFO', ttGap ? JSON.stringify(ttGap) : '无 tooltip（默认渲染对 null 桶无内容）');

    await page.setViewportSize({ width: 1280, height: 800 });
    await page.waitForTimeout(700);
    const after = await page.evaluate((i) => window.__poc.probeBucket(i, 22), alarmSlot);
    report('GATE-2', '2A 视口 resize 后带仍完整（红桶仍红）', NEAR(after, RED), colorName(after));
    await page.screenshot({ path: path.join(OUT, '2a-band-after-resize.png') });
    await page.setViewportSize(VIEWPORT);
    await page.waitForTimeout(500);
  }

  // 2B 方案二：双 series 堆叠（正常绿/报警红）→ 原生图例 + 每桶单色带
  const bandStack = (d) => ({
    animation: false,
    title: { text: 'PoC 2B 三色带 双series堆叠+图例', left: 6, top: 2, textStyle: { fontSize: 12, fontWeight: 500 } },
    tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'line' } },
    legend: { show: true, top: 2, right: 8, itemWidth: 14, itemHeight: 8, data: ['正常', '报警'] },
    grid: { left: 48, right: 14, top: 30, bottom: 26 },
    xAxis: { type: 'category', data: d.times, axisLabel: { hideOverlap: true } },
    yAxis: { type: 'value', min: 0, max: 1, show: false },
    series: [
      { name: '正常', type: 'bar', stack: 'band', barWidth: '100%', itemStyle: { color: '#67c23a' },
        showBackground: true, backgroundStyle: { color: '#909399' },
        data: d.vals.map((v) => v === 'normal' ? 1 : 0) },
      { name: '报警', type: 'bar', stack: 'band', barWidth: '100%', itemStyle: { color: '#f56c6c' },
        data: d.vals.map((v) => v === 'alarm' ? 1 : 0) },
    ],
  });
  await page.evaluate(([i, opt]) => window.__poc.setOpt(i, opt), [alarmSlot, bandStack(bandData)]);
  {
    await page.waitForTimeout(250);
    const samples = await page.evaluate((i) => ({
      green: window.__poc.probeBucket(i, 5),
      gap: window.__poc.probeBucket(i, 17),
      red: window.__poc.probeBucket(i, 22),
    }), alarmSlot);
    report('GATE-2', '2B 双series堆叠：正常桶=绿', NEAR(samples.green, GREEN), colorName(samples.green));
    report('GATE-2', '2B 报警桶=红', NEAR(samples.red, RED), colorName(samples.red));
    report('GATE-2', '2B 缺桶槽位=灰（showBackground 在堆叠底 series 上）', NEAR(samples.gap, GRAY), colorName(samples.gap));
    await page.screenshot({ path: path.join(OUT, '2b-band-stack.png') });

    // 图例关闭「报警」→ 红段消失露灰底（echarts legend 为 canvas 绘制，dispatchAction 等价触发）
    await page.evaluate((i) => window.__poc.inst(i).dispatchAction({ type: 'legendToggleSelect', name: '报警' }), alarmSlot);
    await page.waitForTimeout(300);
    const afterToggle = await page.evaluate((i) => window.__poc.probeBucket(i, 22), alarmSlot);
    report('GATE-2', '2B 图例关闭「报警」→ 红段消失露灰底（无渲染错乱）', NEAR(afterToggle, GRAY), colorName(afterToggle));
    await page.evaluate((i) => window.__poc.inst(i).dispatchAction({ type: 'legendToggleSelect', name: '报警' }), alarmSlot);
    await page.waitForTimeout(300);

    // connect 组队联动：带格与兄弟格「同轴」（组件真实 times，即 W3 实现场景）时悬浮同步出值。
    // 联动按 category 值对齐：若给带格喂与兄弟格不同的时间轴，兄弟格不同步（对照实测）——W3 须复用同一份 times。
    if (numericIdx >= 0) {
      await page.evaluate(([i, j]) => {
        const times = window.__poc.inst(j).getOption().xAxis[0].data;
        const vals = times.map((t, k) => (k === 0 ? 'alarm' : k === 1 ? null : 'normal'));
        window.__poc.setOpt(i, {
          animation: false,
          title: { text: "PoC 2B' 同轴带+connect 联动", left: 6, top: 2, textStyle: { fontSize: 12, fontWeight: 500 } },
          tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'line' } },
          grid: { left: 48, right: 14, top: 26, bottom: 26 },
          xAxis: { type: 'category', data: times, axisLabel: { hideOverlap: true } },
          yAxis: { type: 'value', min: 0, max: 1, show: false },
          series: [{
            name: '漏水报警', type: 'bar', barWidth: '100%', showBackground: true,
            backgroundStyle: { color: '#909399' },
            data: vals.map((v) => v == null ? null : { value: 1, itemStyle: { color: v === 'alarm' ? '#f56c6c' : '#67c23a' } }),
          }],
        });
      }, [alarmSlot, numericIdx]);
      await page.waitForTimeout(300);
      await hoverBucket(page, alarmSlot, 0);
      const bandTt = await page.evaluate((i) => window.__poc.tooltip(i), alarmSlot);
      const otherTt = await page.evaluate((i) => window.__poc.tooltip(i), numericIdx);
      report('GATE-2', "2B' 同轴带 connect 联动：带格悬浮 → 数值格 tooltip 同步出值",
        !!(otherTt && bandTt), `带格=${bandTt ? JSON.stringify(bandTt) : '无'} / 数值格=${otherTt ? JSON.stringify(otherTt).slice(0, 60) : '无'}`);
    }
  }

  // 2C 方案三：markArea 灰底带（对照）
  const bandMarkArea = (d) => ({
    animation: false,
    title: { text: 'PoC 2C markArea 灰底对照', left: 6, top: 2, textStyle: { fontSize: 12, fontWeight: 500 } },
    tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'line' } },
    grid: { left: 48, right: 14, top: 26, bottom: 26 },
    xAxis: { type: 'category', data: d.times, axisLabel: { hideOverlap: true } },
    yAxis: { type: 'value', min: 0, max: 1, show: false },
    series: [{
      name: '漏水报警', type: 'bar', barWidth: '100%',
      data: d.vals.map((v) => v == null ? null : { value: 1, itemStyle: { color: v === 'alarm' ? '#f56c6c' : '#67c23a' } }),
      markArea: { silent: true, itemStyle: { color: '#909399' }, data: [[{ xAxis: d.times[0] }, { xAxis: d.times[d.times.length - 1] }]] },
    }],
  });
  await page.evaluate(([i, opt]) => window.__poc.setOpt(i, opt), [alarmSlot, bandMarkArea(bandData)]);
  {
    await page.waitForTimeout(250);
    const samples = await page.evaluate((i) => ({
      gap: window.__poc.probeBucket(i, 17),
      red: window.__poc.probeBucket(i, 22),
    }), alarmSlot);
    report('GATE-2', '2C markArea 灰底：缺桶槽位=灰', NEAR(samples.gap, GRAY), colorName(samples.gap));
    report('GATE-2', '2C markArea 不盖柱（报警桶仍红）', NEAR(samples.red, RED), colorName(samples.red));
    await page.screenshot({ path: path.join(OUT, '2c-band-markarea.png') });
  }

  // 2D 真实数据形态带：页内 fetch 近 1h water_leak（value=null + value_text），按粒度补空后画带
  {
    const real = await page.evaluate(async () => {
      const token = (document.cookie.split('; ').find((c) => c.startsWith('Admin-Token=')) || '').split('=').slice(1).join('=');
      const p = (n) => String(n).padStart(2, '0');
      const fmt = (d) => `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
      const endD = new Date(); const startD = new Date(endD.getTime() - 55 * 60000);
      const url = `/dev-api/asm-monitor/history?granularity=MINUTE&start=${fmt(startD)}&end=${fmt(endD)}`
        + '&params=logicdevice_station.security_alarm:water_leak&pageNum=1&pageSize=200';
      const res = await fetch(url, { credentials: 'include', headers: { Authorization: 'Bearer ' + decodeURIComponent(token) } });
      const j = await res.json();
      const rs = (j.data && j.data.rows) || [];
      const keyOf = (d) => `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
      const end = new Date(endD.getTime()); end.setSeconds(0, 0);
      const times = [], vals = [];
      const byKey = new Map(rs.map((r) => [keyOf(new Date(r.dataTime)), r.value_text]));
      for (let t = new Date(end.getTime() - 60 * 60000); t <= end; t = new Date(t.getTime() + 60000)) {
        times.push(keyOf(t));
        vals.push(byKey.get(keyOf(t)) || null);
      }
      return { times, vals, rowCount: rs.length, shape: rs[0] ? { value: rs[0].value, value_text: rs[0].value_text } : null };
    });
    console.log(`  [2D] 真实窗口 rows=${real.rowCount} 桶=${real.times.length} 缺桶=${real.vals.filter((v) => v == null).length} 行形态=${JSON.stringify(real.shape)}`);
    await page.evaluate(([i, d]) => {
      window.__poc.setOpt(i, {
        animation: false,
        title: { text: 'PoC 2D 真实数据带 water_leak（近1h 补空）', left: 6, top: 2, textStyle: { fontSize: 12, fontWeight: 500 } },
        tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'line' } },
        grid: { left: 48, right: 14, top: 26, bottom: 26 },
        xAxis: { type: 'category', data: d.times, axisLabel: { hideOverlap: true } },
        yAxis: { type: 'value', min: 0, max: 1, show: false },
        series: [{
          name: '漏水报警', type: 'bar', barWidth: '100%', showBackground: true,
          backgroundStyle: { color: '#909399' },
          data: d.vals.map((v) => v == null ? null : { value: 1, itemStyle: { color: v === 'alarm' ? '#f56c6c' : '#67c23a' } }),
        }],
      });
    }, [alarmSlot, real]);
    await page.waitForTimeout(250);
    const firstValIdx = real.vals.findIndex((v) => v != null);
    const firstGapIdx = real.vals.findIndex((v) => v == null);
    if (firstValIdx >= 0) {
      const rgbVal = await page.evaluate(([i, k]) => window.__poc.probeBucket(i, k), [alarmSlot, firstValIdx]);
      const expect = real.vals[firstValIdx] === 'alarm' ? RED : GREEN;
      report('GATE-2', `2D 真实数据带：有行桶着色正确（${real.times[firstValIdx]}=${real.vals[firstValIdx]}）`, NEAR(rgbVal, expect), colorName(rgbVal));
    }
    if (firstGapIdx >= 0) {
      const rgbGap = await page.evaluate(([i, k]) => window.__poc.probeBucket(i, k), [alarmSlot, firstGapIdx]);
      report('GATE-2', `2D 真实数据带：真实缺桶（${real.times[firstGapIdx]}）露灰`, NEAR(rgbGap, GRAY), colorName(rgbGap));
    } else {
      report('GATE-2', '2D 真实窗口无缺桶', 'INFO', '灰段不可见（符合口径：无样本才有灰）');
    }
    await page.screenshot({ path: path.join(OUT, '2d-band-realdata.png') });
  }

  // ============================================================
  console.log('\n== gate-3: tooltip.formatter 函数在当前 dist 域是否复活 ==');

  // 3a 组件原生数值图：setOption 注入 formatter
  if (numericIdx >= 0) {
    await page.evaluate((i) => {
      const o = window.__poc.opt(i);
      o.tooltip = { trigger: 'axis', confine: true, axisPointer: { type: 'line' }, formatter: (params) => 'PoC-FMT|' + (Array.isArray(params) ? params.map((p) => p.seriesName + '=' + p.value).join(',') : params.value) };
      window.__poc.setOpt(i, o);
    }, numericIdx);
    const nFirst = await page.evaluate((i) => {
      const d = window.__poc.inst(i).getOption().series[0].data;
      return Math.max(d.findIndex((v) => v != null), 0);
    }, numericIdx);
    await hoverBucket(page, numericIdx, nFirst);
    const tt = await page.evaluate((i) => window.__poc.tooltip(i), numericIdx);
    report('GATE-3', '3a 分图数值图 tooltip.formatter（活实例 setOption 注入）', !!(tt && tt.includes('PoC-FMT')), tt ? JSON.stringify(tt) : '无 tooltip');
    await page.screenshot({ path: path.join(OUT, '3a-formatter-split.png') });
  }

  // 3b 三色带 + formatter（中文 + 计数，W3 终态形态）
  await page.evaluate(([i, d]) => {
    const counts = d.vals.reduce((a, v) => { if (v) a[v]++; return a; }, { alarm: 0, normal: 0 });
    window.__poc.setOpt(i, {
      animation: false,
      title: { text: 'PoC 3b 三色带+formatter 中文计数', left: 6, top: 2, textStyle: { fontSize: 12, fontWeight: 500 } },
      tooltip: {
        trigger: 'axis', confine: true, axisPointer: { type: 'line' },
        formatter: (params) => {
          const p = Array.isArray(params) ? params[0] : params;
          const v = d.vals[p.dataIndex];
          if (v == null) return 'PoC-FMT-BAND|' + p.name + '|无数据';
          return 'PoC-FMT-BAND|' + p.name + '|' + (v === 'alarm' ? '报警' : '正常') + `（alarm ${counts.alarm} 桶 / normal ${counts.normal} 桶）`;
        },
      },
      grid: { left: 48, right: 14, top: 26, bottom: 26 },
      xAxis: { type: 'category', data: d.times, axisLabel: { hideOverlap: true } },
      yAxis: { type: 'value', min: 0, max: 1, show: false },
      series: [{
        name: '漏水报警', type: 'bar', barWidth: '100%', showBackground: true,
        backgroundStyle: { color: '#909399' },
        data: d.vals.map((v) => v == null ? null : { value: 1, itemStyle: { color: v === 'alarm' ? '#f56c6c' : '#67c23a' } }),
      }],
    });
  }, [alarmSlot, bandData]);
  {
    await page.waitForTimeout(250);
    await hoverBucket(page, alarmSlot, 17);
    const ttGap = await page.evaluate((i) => window.__poc.tooltip(i), alarmSlot);
    report('GATE-3', '3b 三色带+formatter：缺桶悬浮显「无数据」中文', !!(ttGap && ttGap.includes('PoC-FMT-BAND') && ttGap.includes('无数据')), ttGap ? JSON.stringify(ttGap) : '无 tooltip');
    await hoverBucket(page, alarmSlot, 22);
    const ttRed = await page.evaluate((i) => window.__poc.tooltip(i), alarmSlot);
    report('GATE-3', '3b 三色带+formatter：报警桶悬浮显「报警」+计数中文', !!(ttRed && ttRed.includes('报警')), ttRed ? JSON.stringify(ttRed) : '无 tooltip');
    await page.screenshot({ path: path.join(OUT, '3b-formatter-band.png') });
  }

  // 3c 合并图：默认 tooltip 基线 + formatter 注入
  await page.locator('.asm-view-col .el-radio-button__inner', { hasText: '合并' }).first().click();
  await page.locator('.asm-chart canvas').first().waitFor({ timeout: 20000 });
  await page.waitForTimeout(500);
  {
    const hasMerge = await page.evaluate(() => {
      const dom = document.querySelector('.asm-chart');
      const g = dom && window.echarts.getInstanceByDom(dom);
      return !!g && !g.isDisposed();
    });
    if (hasMerge) {
      const m = await page.evaluate(() => {
        const g = window.echarts.getInstanceByDom(document.querySelector('.asm-chart'));
        const s = g.getOption().series.find((x) => (x.data || []).some((v) => v != null));
        const idx = s ? s.data.findIndex((v) => v != null) : 0;
        return { x: g.convertToPixel({ xAxisIndex: 0 }, Math.max(idx, 0)), seriesCount: g.getOption().series.length };
      });
      const pt = await page.evaluate((x) => {
        const r = document.querySelector('.asm-chart').getBoundingClientRect();
        return { x: r.left + x, y: r.top + r.height * 0.5 };
      }, m.x);
      await page.mouse.move(pt.x, pt.y, { steps: 6 });
      await page.waitForTimeout(400);
      const defTt = await page.evaluate(() => window.__poc.tooltipIn(document.querySelector('.asm-chart')));
      report('GATE-3', `3c 合并图默认 tooltip 基线（${m.seriesCount} series）`, !!defTt, defTt ? JSON.stringify(defTt).slice(0, 110) : '无 tooltip');
      await page.evaluate(() => {
        const g = window.echarts.getInstanceByDom(document.querySelector('.asm-chart'));
        const o = g.getOption();
        o.tooltip = { trigger: 'axis', confine: true, axisPointer: { type: 'cross' }, formatter: (params) => 'PoC-FMT-MERGE|' + (Array.isArray(params) ? params.length + ' series' : params.value) };
        g.setOption(o, true);
      });
      await page.mouse.move(pt.x + 10, pt.y + 6, { steps: 4 });
      await page.waitForTimeout(400);
      const fmtTt = await page.evaluate(() => window.__poc.tooltipIn(document.querySelector('.asm-chart')));
      report('GATE-3', '3c 合并图 tooltip.formatter 注入', !!(fmtTt && fmtTt.includes('PoC-FMT-MERGE')), fmtTt ? JSON.stringify(fmtTt).slice(0, 110) : '无 tooltip');
      await page.screenshot({ path: path.join(OUT, '3c-formatter-merge.png') });
    } else {
      report('GATE-3', '3c 合并图实例未挂载，跳过', 'WARN', '');
    }
  }

  // ============================================================
  console.log('\n== 回归：注入不污染组件生命周期 ==');
  await page.locator('.asm-view-col .el-radio-button__inner', { hasText: '分图' }).first().click();
  await page.waitForTimeout(600);
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await page.waitForTimeout(2000);
  {
    const alive = await page.evaluate((i) => {
      const dom = document.querySelectorAll('.asm-split-cell')[i];
      const g = dom && window.echarts.getInstanceByDom(dom);
      return g ? { disposed: g.isDisposed(), seriesType: g.getOption().series[0].type } : null;
    }, alarmSlot);
    report('REGRESS', '重查后组件自重建（注入 option 被正常冲掉，无残留错乱）',
      !!(alive && !alive.disposed && alive.seriesType === 'line'), JSON.stringify(alive));
    const newErrors = errors.slice(preErrors);
    report('REGRESS', 'PoC 注入期间无新增 console error / pageerror', newErrors.length === 0, newErrors.slice(0, 3).join(' | ') || 'clean');
  }
  await page.screenshot({ path: path.join(OUT, '4-restored.png') });

  await browser.close();

  console.log('\n===== PoC 结论汇总 =====');
  const pass = rows.filter((r) => r.verdict === 'PASS').length;
  const fail = rows.filter((r) => r.verdict === 'FAIL').length;
  console.log(`PASS ${pass} / FAIL ${fail} / 其他 ${rows.length - pass - fail}`);
  fs.writeFileSync(path.join(OUT, 'result.json'), JSON.stringify(rows, null, 2));
  console.log('明细: ' + path.join(OUT, 'result.json'));
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error('SCRIPT ERROR:', e); process.exit(2); });
