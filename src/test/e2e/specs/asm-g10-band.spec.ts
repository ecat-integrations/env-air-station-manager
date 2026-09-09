/**
 * ASM 历史页 ALARM 三色状态带回归（g10-band-*，2026-09-09 W3）：
 *  - 分图：ALARM 参数（值域仅 normal/alarm）出三色带子图（bar 逐点红/绿 + showBackground 灰底），
 *    与数值折线子图共存（勾选顺序混排）；STATE 参数（状态串）仍不出子图
 *  - 三色像素采样：报警桶红 #f56c6c / 正常桶绿 #67c23a / 缺桶灰 #909399（粒度补空 null 槽位露灰底=无数据≠正常）
 *  - tooltip.formatter 三态中文：时刻｜参数名：报警/正常（有效 N/共 M）/无数据
 *  - 带格与数值格共用同一份补空时间轴（connect 联动按 category 值对齐的硬前提）
 *  - 合并模式仅数值（ALARM 带只在分图），提示回到 ALARM/STATE 全量口径
 *
 * 数据前提（环境常驻）：SecurityAlarm ir_alarm 处于告警态（红）、water_leak 全 normal（绿）。
 * 灰（缺桶）在分钟桶连续的常驻环境不出现 → 三色向用例经路由拦截构造 history 出参（真组件+真渲染+
 * 真悬停，只 mock 这一个只读查询端点，不碰产品码与共享部署），与真后端用例分层并列。
 *
 * ASM_BUNDLE_OVERRIDE（过渡验证缝）：部署 jar 落后于源码时把模块脚本路由到指定本地构建；正式套件
 * 与 W4 全量回归不设此 env（黑盒打部署产物）。
 */
import { test, expect, Page } from '@playwright/test';
import { AsmBasePage, attachErrorCollectors, toLocalInput } from '../helpers/page-objects/AsmBasePage';

const BUNDLE_OVERRIDE = process.env.ASM_BUNDLE_OVERRIDE || '';
const RED: [number, number, number] = [245, 108, 108];
const GREEN: [number, number, number] = [103, 194, 58];
const GRAY: [number, number, number] = [144, 147, 153];

/** 分钟粒度步长 / 窗口网格 tick 数（与前端 buildGridWindow / g13 同公式，轴下标换算用）。 */
const STEP_MS = 60_000;
function gridTicks(startMs: number, endMs: number): number {
  const firstMs = Math.ceil(startMs / STEP_MS) * STEP_MS;
  const lastMs = Math.floor(endMs / STEP_MS) * STEP_MS;
  return (lastMs - firstMs) / STEP_MS + 1;
}

/** 前置：分钟粒度 + 近 30min 窗（分钟桶延迟 +10s，必有数据），返回窗口 ms 供轴下标换算。 */
async function openHistory(page: Page) {
  if (BUNDLE_OVERRIDE) {
    await page.route('**/ecat-integrations/integration-env-air-station-manager/air-station-manager.js*', (route) =>
      route.fulfill({ path: BUNDLE_OVERRIDE, contentType: 'application/javascript' }));
    console.log(`[g10-band] ASM_BUNDLE_OVERRIDE 生效，模块脚本指向本地构建: ${BUNDLE_OVERRIDE}`);
  }
  const hp = new AsmBasePage(page, 'history_data');
  await hp.goto();
  // 正则全匹配：避免「分钟」子串命中「5分钟」
  await page.locator('.asm-filter .el-radio-button').filter({ hasText: /^分钟$/ }).click();
  const now = new Date();
  const start = new Date(now.getTime() - 30 * 60 * 1000);
  const end = new Date(now.getTime() + 60 * 1000);
  const times = page.locator('.asm-filter .el-range-input');
  await times.nth(0).fill(toLocalInput(start).replace('T', ' '));
  await times.nth(1).fill(toLocalInput(end).replace('T', ' '));
  return { startMs: start.getTime(), endMs: end.getTime() };
}

/** 参数弹窗勾选（中文名锚定）并确定（确定即触发重查）。 */
async function checkParams(page: Page, names: string[]) {
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

/** 分图格序列（按勾选顺序）：series 形态与名字（echarts 实例经宿主全局 echarts 取 raw）。 */
async function cellShapes(page: Page) {
  return page.evaluate(() => {
    const cells = [...document.querySelectorAll('.asm-split-cell')];
    return cells.map((dom) => {
      const inst = (window as any).echarts.getInstanceByDom(dom);
      const s = inst && !inst.isDisposed() ? inst.getOption().series[0] : null;
      return { type: s ? s.type : null, name: s ? s.name : null, background: !!(s && s.showBackground) };
    });
  });
}

/** 各分图格 x 轴 category 数组（逐字节一致性=connect 联动对齐前提）。 */
async function cellAxes(page: Page) {
  return page.evaluate(() => {
    const cells = [...document.querySelectorAll('.asm-split-cell')];
    return cells.map((dom) => {
      const inst = (window as any).echarts.getInstanceByDom(dom);
      return inst && !inst.isDisposed() ? inst.getOption().xAxis[0].data as string[] : null;
    });
  });
}

/**
 * 页内注入采样/悬浮工具（echarts5 tooltip div 无 class，按 absolute 定位 + 非空文本判定）。
 * probeBucket 取桶中心 x + 60% 高（带格绘图区纵中段，避开标题与 x 轴标签）。
 */
async function injectBandHelpers(page: Page) {
  await page.evaluate(() => {
    const cells = () => [...document.querySelectorAll('.asm-split-cell')];
    const inst = (i: number) => {
      const g = (window as any).echarts.getInstanceByDom(cells()[i]);
      return g && !g.isDisposed() ? g : null;
    };
    const tooltipIn = (dom: HTMLElement) => {
      for (const d of dom.querySelectorAll('div')) {
        const cs = getComputedStyle(d);
        if ((cs.position === 'absolute' || cs.position === 'fixed') && cs.display !== 'none' && d.innerText.trim()) {
          const r = d.getBoundingClientRect();
          if (r.width > 4 && r.height > 4) return d.innerText.trim();
        }
      }
      return null;
    };
    (window as any).__band = {
      bucketX: (i: number, idx: number) => inst(i).convertToPixel({ xAxisIndex: 0 }, idx),
      probe(i: number, x: number, y: number) {
        const canvas = cells()[i].querySelector('canvas')!;
        const rect = canvas.getBoundingClientRect();
        const dpr = canvas.width / rect.width;
        const d = canvas.getContext('2d')!.getImageData(
          Math.round((x - rect.left) * dpr), Math.round((y - rect.top) * dpr), 1, 1).data;
        return [d[0], d[1], d[2]];
      },
      probeBucket(i: number, idx: number) {
        const r = cells()[i].getBoundingClientRect();
        return this.probe(i, r.left + this.bucketX(i, idx), r.top + r.height * 0.6);
      },
      tooltip: (i: number) => tooltipIn(cells()[i]),
    };
  });
}

/** 真鼠标悬停某分图格某桶中心（x=桶中心，y=格 60% 高处必落在绘图区）。 */
async function hoverBucket(page: Page, cellIdx: number, bucketIdx: number) {
  const pt = await page.evaluate(([i, k]) => {
    const dom = document.querySelectorAll('.asm-split-cell')[i];
    const r = dom.getBoundingClientRect();
    return { x: r.left + (window as any).__band.bucketX(i, k), y: r.top + r.height * 0.6 };
  }, [cellIdx, bucketIdx]);
  await page.mouse.move(pt.x, pt.y, { steps: 6 });
}

/**
 * 构造 history 出参（10 个分钟桶，桶尾=请求窗末 tick=页网格最新时刻）：
 *  - ir_alarm（红外报警）：normal×3 → alarm×3 → 缺 2 桶（灰）→ normal×2，计数恒 3/5
 *  - water_leak（漏水报警）：全 normal（绿），计数 5/5
 * 行键取自真实请求的 params=（uid:attrId），不硬编码设备 uid。桶位由请求窗决定（而非 wall clock），
 * 与「轴=页网格全 tick、升序」的轴下标换算确定性挂钩（窗口填充与请求跨分钟也不抖）。
 */
async function mockBandHistory(page: Page) {
  const pad = (n: number) => String(n).padStart(2, '0');
  const iso = (d: Date) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}Z`;
  await page.route('**/asm-monitor/history*', async (route) => {
    const url = new URL(route.request().url());
    const keys = (url.searchParams.get('params') || '').split(',').filter(Boolean);
    // 页请求窗=[段首, 段末 tick+1 步长)（前端 gridSegment 上界多让一个步长），末 tick=上界-步长
    const endMs = new Date(url.searchParams.get('end') as string).getTime() - STEP_MS;
    const rows: Record<string, unknown>[] = [];
    for (const key of keys) {
      const sep = key.lastIndexOf(':');
      const uid = key.slice(0, sep);
      const attrId = key.slice(sep + 1);
      for (let i = 0; i < 10; i++) {
        // ir_alarm 桶序 0-2 正常 / 3-5 报警 / 6-7 缺桶（不产行=灰）/ 8-9 正常
        if (attrId === 'ir_alarm' && (i === 6 || i === 7)) continue;
        const text = attrId === 'ir_alarm' ? (i >= 3 && i <= 5 ? 'alarm' : 'normal') : 'normal';
        rows.push({
          dataTime: iso(new Date(endMs - (9 - i) * STEP_MS)),
          logicDeviceUniqueId: uid,
          attrId,
          value: null,
          value_text: text,
          unit: '',
          display_unit: '',
          validCount: attrId === 'ir_alarm' ? 3 : 5,
          totalCount: 5,
        });
      }
    }
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, msg: '操作成功', data: { granularity: 'MINUTE', mode: 'BACK', unit: 'custom', pageNum: 1, pageSize: 200, rows } }),
    });
  });
  console.log('[g10-band] history 出参已构造（页网格最新 10 tick：绿绿绿红红红缺缺绿绿 / 全绿）');
}

test('g10-band-ALARM 参数出三色带子图与数值子图共存、STATE 不出曲线 @g10', async ({ page }) => {
  const errors: string[] = [];
  attachErrorCollectors(page, errors);
  await openHistory(page);
  await checkParams(page, ['红外报警', '漏水报警', '转速档位', '设定温度']);
  await page.locator('.asm-result-head .el-radio-button', { hasText: '曲线' }).click();

  // 分图 3 格=红外报警/漏水报警（带）+ 设定温度（折线）；转速档位（STATE 状态串）不出格不占位
  await expect(page.locator('.asm-split-cell')).toHaveCount(3);
  await expect(page.locator('.asm-split-cell canvas').first()).toBeVisible({ timeout: 20_000 });
  const shapes = await cellShapes(page);
  expect(shapes[0].type, '红外报警应出 bar 三色带').toBe('bar');
  expect(shapes[1].type, '漏水报警应出 bar 三色带').toBe('bar');
  expect(shapes[2].type, '设定温度应保持数值折线').toBe('line');
  expect(shapes[0].background && shapes[1].background, '带格应挂 showBackground 灰底（缺桶=灰）').toBe(true);
  // 带格与数值格共用同一份时间轴（connect 联动按 category 值对齐的硬前提）
  const axes = await cellAxes(page);
  expect(new Set(axes!.map((a) => a.join('|'))).size, '各分图格 x 轴应逐字节一致').toBe(1);
  // 带格三色图例语义=子图注小字（title.subtext）
  const legend = await page.evaluate(() => {
    const dom = document.querySelectorAll('.asm-split-cell')[0];
    return (window as any).echarts.getInstanceByDom(dom).getOption().title[0].subtext;
  });
  expect(legend).toContain('报警');
  expect(legend).toContain('正常');
  expect(legend).toContain('无数据');
  // 提示按布局区分：分图只剩 STATE 数量
  await expect(page.locator('.asm-merge-hint').filter({ hasText: '项状态参数不画曲线' }))
    .toHaveText('1 项状态参数不画曲线（列表可查）');

  // 合并：仅数值 series 参与（ALARM 带只在分图），提示回到 ALARM/STATE 全量口径
  await page.locator('.asm-result-head .el-radio-button', { hasText: '合并' }).click();
  await expect(page.locator('.asm-chart canvas').first()).toBeVisible({ timeout: 20_000 });
  const mergeSeries = await page.evaluate(() => {
    const dom = document.querySelector('.asm-chart');
    return (window as any).echarts.getInstanceByDom(dom).getOption().series.length;
  });
  expect(mergeSeries, '合并图应只有数值 1 条折线').toBe(1);
  await expect(page.locator('.asm-merge-hint').filter({ hasText: '项报警/状态参数不画曲线' }))
    .toHaveText('3 项报警/状态参数不画曲线（列表可查）');
  expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
});

test('g10-band-三色带像素采样（红/绿/灰）与 formatter 三态文案 @g10', async ({ page }) => {
  const errors: string[] = [];
  attachErrorCollectors(page, errors);
  await mockBandHistory(page);
  const win = await openHistory(page);
  await checkParams(page, ['红外报警', '漏水报警']);
  await page.locator('.asm-result-head .el-radio-button', { hasText: '曲线' }).click();
  await expect(page.locator('.asm-split-cell canvas').first()).toBeVisible({ timeout: 20_000 });
  await injectBandHelpers(page);
  // 构造出参=页网格最新 10 桶（缺 2 桶），轴=页网格全 tick（升序），分图格 0=红外报警
  const total = gridTicks(win.startMs, win.endMs);
  await page.waitForFunction((n) => {
    const dom = document.querySelectorAll('.asm-split-cell')[0];
    const inst = dom && (window as any).echarts.getInstanceByDom(dom);
    return !!(inst && !inst.isDisposed() && inst.getOption().series[0].data.length === n);
  }, total, { timeout: 20_000 });
  // 桶位→轴下标：构造桶尾=网格最新 tick，10 桶占轴末 10 槽（i=0 最旧 … i=9 最新）
  const axisIdx = (i: number) => total - 10 + i;

  const near = (a: number[], b: number[], tol = 24) => a.length === b.length && a.every((v, i) => Math.abs(v - b[i]) <= tol);
  const px = await page.evaluate(([normalIdx, alarmIdx, gapIdx]) => ({
    normal: (window as any).__band.probeBucket(0, normalIdx),
    alarm: (window as any).__band.probeBucket(0, alarmIdx),
    gap: (window as any).__band.probeBucket(0, gapIdx),
  }), [axisIdx(1), axisIdx(4), axisIdx(6)] as unknown as [number, number, number]);
  expect(near(px.normal, GREEN), `正常桶应绿 #67c23a（实际 rgb(${px.normal})）`).toBe(true);
  expect(near(px.alarm, RED), `报警桶应红 #f56c6c（实际 rgb(${px.alarm})）`).toBe(true);
  expect(near(px.gap, GRAY), `缺桶槽位应露灰底 #909399（实际 rgb(${px.gap})）`).toBe(true);

  // formatter 三态：报警/正常带桶计数（有效 3/共 5），无数据不添计数；时刻｜参数名：态（计数）
  await hoverBucket(page, 0, axisIdx(4));
  await page.waitForFunction(() => (window as any).__band.tooltip(0) !== null, { timeout: 10_000 });
  const alarmTt = await page.evaluate(() => (window as any).__band.tooltip(0));
  expect(alarmTt, `报警桶文案应含「报警（有效 3/共 5）」（实际：${alarmTt}）`).toMatch(/红外报警：报警（有效 3\/共 5）/);
  expect(alarmTt, '报警桶文案应带时刻前缀（formatLocalDateTime 分精度仍含年月日）').toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}｜/);

  await hoverBucket(page, 0, axisIdx(1));
  await page.waitForFunction(() => ((window as any).__band.tooltip(0) || '').includes('正常'), { timeout: 10_000 });
  const normalTt = await page.evaluate(() => (window as any).__band.tooltip(0));
  expect(normalTt, `正常桶文案应含「正常（有效 3/共 5）」（实际：${normalTt}）`).toMatch(/红外报警：正常（有效 3\/共 5）/);

  await hoverBucket(page, 0, axisIdx(6));
  await page.waitForFunction(() => ((window as any).__band.tooltip(0) || '').includes('无数据'), { timeout: 10_000 });
  const gapTt = await page.evaluate(() => (window as any).__band.tooltip(0));
  expect(gapTt, `缺桶文案应显「无数据」且不带计数（实际：${gapTt}）`).toMatch(/红外报警：无数据$/);
  expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
});

test('g10-band-真实数据带色正确且带格↔数值格 connect 联动 @g10', async ({ page }) => {
  const errors: string[] = [];
  attachErrorCollectors(page, errors);
  await openHistory(page);
  await checkParams(page, ['红外报警', '漏水报警', '设定温度']);
  await page.locator('.asm-result-head .el-radio-button', { hasText: '曲线' }).click();
  await expect(page.locator('.asm-split-cell canvas').first()).toBeVisible({ timeout: 20_000 });
  await injectBandHelpers(page);
  await page.waitForFunction(() => {
    const cells = document.querySelectorAll('.asm-split-cell');
    return cells.length === 3 && [...cells].every((dom) => {
      const inst = (window as any).echarts.getInstanceByDom(dom);
      return inst && !inst.isDisposed() && inst.getOption().series[0].data.some((v: unknown) => v != null);
    });
  }, { timeout: 20_000 });

  const near = (a: number[], b: number[], tol = 24) => a.length === b.length && a.every((v, i) => Math.abs(v - b[i]) <= tol);
  // 各带格首个非空桶采样：环境常驻 ir_alarm=告警态（红）、water_leak=全 normal（绿）
  const bandPx = await page.evaluate(() => {
    const cells = [...document.querySelectorAll('.asm-split-cell')];
    return cells.map((dom, i) => {
      const inst = (window as any).echarts.getInstanceByDom(dom);
      const data = inst.getOption().series[0].data;
      const idx = data.findIndex((v: unknown) => v != null);
      return idx >= 0 ? (window as any).__band.probeBucket(i, idx) : null;
    });
  });
  expect(bandPx[0], '红外报警带应出红段（环境常驻告警态）').not.toBeNull();
  expect(near(bandPx[0]!, RED), `红外报警带应为红（实际 rgb(${bandPx[0]})）`).toBe(true);
  expect(bandPx[1], '漏水报警带应出绿段').not.toBeNull();
  expect(near(bandPx[1]!, GREEN), `漏水报警带应为绿（实际 rgb(${bandPx[1]})）`).toBe(true);

  // connect 联动：带格悬浮 → 数值格 tooltip 同步出值且时刻一致（同轴按 category 值对齐）
  const idx = await page.evaluate(() => {
    const inst = (window as any).echarts.getInstanceByDom(document.querySelectorAll('.asm-split-cell')[0]);
    return inst.getOption().series[0].data.findIndex((v: unknown) => v != null);
  });
  await hoverBucket(page, 0, idx);
  await page.waitForFunction(() => (window as any).__band.tooltip(0) !== null, { timeout: 10_000 });
  const bandTt = await page.evaluate(() => (window as any).__band.tooltip(0));
  const tempTt = await page.evaluate(() => (window as any).__band.tooltip(2));
  expect(bandTt, `带格 tooltip 应出中文态+计数（实际：${bandTt}）`).toMatch(/红外报警：(报警|正常)（有效 \d+\/共 \d+）/);
  expect(tempTt, `带格悬浮时数值格 tooltip 应同步出值（实际：${tempTt}）`).toBeTruthy();
  // 数值格默认 tooltip 无「｜」分隔（首行=轴时刻），与带格 formatter 首段=同一 category 值
  expect(bandTt.split('｜')[0], '带格与数值格悬浮时刻应一致').toBe(tempTt!.split('\n')[0]);
  expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
});
