// 실행: Playwright가 설치된 환경에서 node doc/tests/architecture-check.cjs
// 브라우저: ARCHITECTURE_BROWSER 환경 변수 또는 설치된 Microsoft Edge를 사용한다.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const { expect } = require('playwright/test');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

// 임의 포트의 문서를 열면 실제 DOM 이벤트로 패널을 읽고 기존 재생 기능을 사용할 수 있다.
test('아키텍처 설명과 요청 재생', { timeout: 90000 }, async t => {
  const html = fs.readFileSync(path.join(__dirname, '../00-overview/tomcat-architecture.html'));
  const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(html);
  });
  let browser;
  let context;
  try {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    browser = await chromium.launch({
      headless: true,
      ...(process.env.ARCHITECTURE_BROWSER
        ? { executablePath: process.env.ARCHITECTURE_BROWSER }
        : { channel: 'msedge' })
    });
    context = await browser.newContext({ viewport: { width: 1440, height: 1100 } });
    const page = await context.newPage();
    page.setDefaultTimeout(5000);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const url = `http://127.0.0.1:${server.address().port}`;
    await page.goto(url);
    const panel = page.locator('#component-panel');
    const term = name => page.locator(`.flow-map [data-term="${name}"]`).first();
    const screenshotDir = process.env.ARCHITECTURE_SCREENSHOT_DIR;
    if (screenshotDir) fs.mkdirSync(screenshotDir, { recursive: true });

    // 마우스를 올리면 짧은 요약, 클릭하면 패키지·관계·소스가 있는 별도 상세 패널이 열린다.
    await t.test('hover 요약과 클릭 상세', async () => {
      await expect(panel).toHaveCount(1);
      await expect(panel).toBeHidden();
      await term('Connector').hover();
      await expect(page.locator('#term-tip')).toBeVisible();
      await expect(page.locator('#term-tip')).toContainText('org.apache.catalina.connector.Connector');
      await expect(panel).toBeHidden();
      await term('Connector').click();
      await expect(panel).toBeVisible();
      await expect(panel).toContainText('Connector.initInternal()');
      await expect(panel).toContainText('설정·주입');
      await expect(term('Connector')).toHaveAttribute('aria-expanded', 'true');
      if (screenshotDir) await page.screenshot({ path: path.join(screenshotDir, 'desktop.png') });
    });

    // 모든 용어를 선택하면 내용이 교체되고, Tomcat 근거 링크는 10.1.34 태그를 가리킨다.
    await t.test('전체 용어와 고정 소스 링크', async () => {
      const names = await page.locator('.flow-map [data-term]').evaluateAll(nodes =>
        [...new Set(nodes.map(node => node.dataset.term))]);
      assert.ok(names.length >= 35);
      t.diagnostic(`상세 패널을 확인한 노출 용어: ${names.length}개`);
      for (const name of names) {
        await term(name).click();
        await expect(page.locator('#component-title')).toHaveText(name);
        assert.ok((await panel.innerText()).length > 100, name);
        const links = await panel.locator('a[href*="github.com/apache/tomcat"]').evaluateAll(nodes => nodes.map(n => n.href));
        for (const link of links) assert.ok(link.includes('/blob/10.1.34/'), link);
      }
      await term('Mapper').click();
      await expect(panel).toContainText('MappingData');
      await expect(panel).toContainText('직접');
      await term('Endpoint').click();
      await expect(panel).toContainText('Poller');
      await expect(panel).toContainText('Executor');
      await term('Wrapper').click();
      await expect(panel).toContainText('deallocate');
      await term('DispatcherServlet').click();
      await expect(panel).toContainText('Tomcat 소속 아님');
    });

    // 키보드로 상세를 열고 닫으면 선택 용어로 포커스가 돌아온다.
    await t.test('키보드와 닫기 포커스', async () => {
      await term('Mapper').focus();
      await expect(page.locator('#term-tip')).toBeVisible();
      await page.keyboard.press('Enter');
      await expect(panel).toBeVisible();
      await expect(page.locator('#component-title')).toBeFocused();
      await page.keyboard.press('Escape');
      await expect(panel).toBeHidden();
      await expect(term('Mapper')).toBeFocused();
      await page.keyboard.press('Space');
      await expect(panel).toBeVisible();
      await page.locator('#component-close').click();
      await expect(term('Mapper')).toBeFocused();
      await expect(term('Mapper')).toHaveAttribute('aria-expanded', 'false');
    });

    // 일반 요청과 JSP 요청에서 단계 이동·초기화·속도 변경·전체 재생이 기존대로 동작한다.
    await t.test('일반·JSP 요청 재생 보존', async () => {
      await page.clock.install();
      for (const mode of ['mvc', 'jsp']) {
        await page.locator('#request-mode').selectOption(mode);
        await page.locator('#next').click();
        await expect(page.locator('#position')).toHaveText('2 / 8');
        await page.locator('#prev').click();
        await expect(page.locator('#position')).toHaveText('1 / 8');
        await page.locator('#reset').click();
        await page.locator('#speed').selectOption('2');
        await page.locator('#play').click();
        await page.clock.runFor(1000);
        await page.locator('#play').click();
        await expect(page.locator('#play')).toHaveText('▶ 이어서 재생');
        await page.locator('#play').click();
        await page.clock.runFor(17000);
        await expect(page.locator('#motion-status')).toContainText('완료');
        await expect(page.locator('#motion-status')).toContainText(mode === 'jsp' ? 'Hello JSP' : '본문 1');
        await page.locator('#reset').click();
        await expect(page.locator('#position')).toHaveText('1 / 8');
      }
    });

    // 작은 터치 화면에서도 상세 패널과 긴 클래스명이 뷰포트 안에 들어온다.
    await t.test('모바일 터치와 긴 패키지명', async () => {
      const mobile = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
      try {
        const tab = await mobile.newPage();
        tab.on('pageerror', error => errors.push(error.message));
        await tab.goto(url);
        await tab.locator('.flow-map [data-term="ProtocolHandler"]').first().tap();
        await expect(tab.locator('#component-panel')).toBeVisible();
        await expect(tab.locator('#component-panel')).toContainText('org.apache.coyote.http11.Http11NioProtocol');
        assert.ok(await tab.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
        if (screenshotDir) await tab.screenshot({ path: path.join(screenshotDir, 'mobile.png') });
        await tab.locator('#component-close').tap();
        await expect(tab.locator('#component-panel')).toBeHidden();
      } finally { await mobile.close(); }
    });
    // 로컬 HTML을 직접 열어도 추가 리소스 다운로드 없이 상세 설명을 읽을 수 있다.
    await t.test('단일 HTML 직접 열기', async () => {
      const local = await context.newPage();
      const remote = [];
      local.on('request', request => {
        if (/^https?:/.test(request.url())) remote.push(request.url());
      });
      local.on('pageerror', error => errors.push(error.message));
      try {
        await local.goto(pathToFileURL(path.join(__dirname, '../00-overview/tomcat-architecture.html')).href);
        await local.locator('.flow-map [data-term="Mapper"]').first().click();
        await expect(local.locator('#component-panel')).toContainText('MappingData');
        assert.deepEqual(remote, []);
      } finally { await local.close(); }
    });
    assert.deepEqual(errors, []);
  } finally {
    await context?.close();
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
  }
});
