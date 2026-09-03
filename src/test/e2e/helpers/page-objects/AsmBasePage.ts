import { Page, Locator } from '@playwright/test';

/**
 * ASM 集成页基类：登录（8081 ruoyi 登录页）+ hash 路由导航 + ignoreCache reload。
 *
 * 六页 key：monitor / history_data / alarm_rule / alarm_list / control_list / config。
 * 登录凭据 Admin7s9k2G5/7sK2pG9dR3tQ（8081/8080 ruoyi 上下文；9999 是 admin/admin@123 别混）。
 */
export const ASM_ROUTE_BASE =
  '/#/ecat-integrations/integration-env-air-station-manager/air-station-manager/index';

export const ASM_PAGES = ['monitor', 'history_data', 'alarm_rule', 'alarm_list', 'control_list', 'config'] as const;

export class AsmBasePage {
  readonly page: Page;
  readonly route: string;
  readonly usernameInput: Locator;
  readonly passwordInput: Locator;
  readonly loginButton: Locator;
  /** 页面根容器（六页共用 .asm-page；防御性兜到 .app-main） */
  readonly root: Locator;

  constructor(page: Page, pageKey: string) {
    this.page = page;
    this.route = `${ASM_ROUTE_BASE}/${pageKey}`;
    this.usernameInput = page.locator('input[placeholder*="账号"]');
    this.passwordInput = page.locator('input[placeholder*="密码"]');
    this.loginButton = page.getByRole('button', { name: /登\s*录/ });
    this.root = page.locator('.app-main .asm-page').first();
  }

  async login(username = process.env.ASM_USER || 'Admin7s9k2G5',
              password = process.env.ASM_PASS || '7sK2pG9dR3tQ') {
    // 登录→等真正跳离 /login（带一次重试）：全量串行慢路径下首击偶发不跳转（按钮防抖/焦点竞争，
    // 2026-09-02 三次全量偶发挂 5a/5b 均此环节）——超时后重填重点一次，仍失败才如实抛。
    for (let attempt = 0; attempt < 2; attempt++) {
      await this.usernameInput.fill(username);
      await this.passwordInput.fill(password);
      await this.loginButton.click();
      try {
        await this.page.waitForURL((u) => !u.href.includes('/login'), { timeout: 20_000 });
        return;
      } catch (e) {
        if (attempt === 1) throw e;
      }
    }
  }

  /** 导航到目标页（未登录则先登录等跳离 /login 再重导）；ignoreCache reload 破 304 旧 bundle（jar mtime 重置陷阱）。
   *  等待用 load 非 networkidle：monitor 页 SSE 长连接（/asm-monitor/stream）永不关闭，networkidle 永不可达。 */
  /** 登录态确定性判定（替代瞬时 count 探测）：慢路径下 goto+load 后登录表单可能尚未渲染完，
   *  count()=0 误判「已登录」跳过登录 → 后续内容等待全空转（全量套件尾部 G12-5a 三次偶发根因）。
   *  等表单出现（5s）=需登录；超时未现且 URL 已离开 /login =已登录。 */
  private async needsLogin(): Promise<boolean> {
    try {
      await this.usernameInput.first().waitFor({ state: 'visible', timeout: 5_000 });
      return true;
    } catch {
      // 表单 5s 未现：URL 仍在 /login = 登录页加载慢（须登录，交给 login() 的 fill auto-wait）；
      // 已离开 /login = 真已登录跳走。
      return this.page.url().includes('/login');
    }
  }

  async goto(opts?: { username?: string; password?: string }) {
    await this.page.goto(this.route);
    await this.page.waitForLoadState('load');
    if (await this.needsLogin()) {
      await this.login(opts?.username, opts?.password);
      // 登录成功后 ruoyi router push /index；必须等真正跳离 /login 再设目标 hash，
      // 否则同 hash 导航被 router 覆写回 /login（登录态判定时序），reload 后停在登录页。
      await this.page.waitForURL((u) => !u.href.includes('/login'), { timeout: 20_000 });
      await this.page.goto(this.route);
      await this.page.waitForLoadState('load');
    }
    await this.page.reload({ ignoreCache: true });
    await this.page.waitForLoadState('load');
    // load 后页面数据异步拉取（snapshot/列表 API），等 .asm-page 渲染出实际内容再返回
    //（SSE 时代不能再用 networkidle 兜异步，改内容就绪确定性等待）。
    // 内容就绪等待（含 404 竞态自愈）：慢路径下 reload 后集成动态路由偶发未注册→解析 404，
    // .asm-page 不存在等 30s 必超时——失败时经 /#/index 中转强制重解析（navToOwn 同源机制）再等一轮。
    const contentReady = () => this.page.waitForFunction(
      () => { const el = document.querySelector('.asm-page'); return !!el && (el.innerText || '').trim().length > 50; },
      // 注意签名 (fn, arg, options)：第二参是传给页面函数的 arg——曾把 options 误放第二参
      // （被当 arg 吞掉、超时回落 config actionTimeout=15s），串行慢路径下 15s 边界 flaky
      undefined, { timeout: 30_000 });
    try { await contentReady(); } catch (e) { await this.navToOwn(); }
    if (await this.needsLogin()) {
      // reload 后回到登录页（G3 指定账号场景 / 会话未持久化的兜底）：再登一次并等跳转
      await this.login(opts?.username, opts?.password);
      await this.page.waitForURL((u) => !u.href.includes('/login'), { timeout: 20_000 });
      await this.page.goto(this.route);
      await this.page.waitForLoadState('load');
    }
    // ruoyi 动态路由注册竞态：登录/整页 reload 后集成路由尚未 addRoute 时先解析到 404 catchall，
    // 同 hash 再 goto 不触发重匹配 → 必须经 /#/index 中转跳一次强制重解析。
    await this.navToOwn();
  }

  /** /#/index 中转后跳本页路由并等 .asm-page 挂载（六页根容器）。 */
  async navToOwn() {
    await this.page.goto('/#/index');
    await this.page.waitForLoadState('load');
    await this.page.goto(this.route);
    await this.page.waitForLoadState('load');
    await this.page.locator('.asm-page').first().waitFor({ timeout: 30_000 });
  }
}

/** 收集本页 console error / pageerror（每测试独立 page，收集后断言 0 = 无「新增」error）。 */
export function attachErrorCollectors(page: Page, sink: string[]) {
  page.on('console', (msg) => {
    if (msg.type() === 'error') sink.push(`console.error: ${msg.text()}`);
  });
  page.on('pageerror', (err) => sink.push(`pageerror: ${err.message}`));
}

/** Date → datetime-local 本地串（秒级，与浏览器 input[type=datetime-local] 同口径）。 */
export function toLocalInput(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
