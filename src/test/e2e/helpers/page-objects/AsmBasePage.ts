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
    await this.usernameInput.fill(username);
    await this.passwordInput.fill(password);
    await this.loginButton.click();
    await this.page.waitForLoadState('load');
  }

  /** 导航到目标页（未登录则先登录等跳离 /login 再重导）；ignoreCache reload 破 304 旧 bundle（jar mtime 重置陷阱）。
   *  等待用 load 非 networkidle：monitor 页 SSE 长连接（/asm-monitor/stream）永不关闭，networkidle 永不可达。 */
  async goto(opts?: { username?: string; password?: string }) {
    await this.page.goto(this.route);
    await this.page.waitForLoadState('load');
    if (await this.usernameInput.count() > 0) {
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
    await this.page.waitForFunction(
      () => { const el = document.querySelector('.asm-page'); return !!el && (el.innerText || '').trim().length > 50; },
      { timeout: 20_000 });
    if (await this.usernameInput.count() > 0) {
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
