// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('SPOG Swipe Deck & Financial Primitives E2E', () => {

  async function loginIfRequired(page) {
    if (page.url().includes('auth')) {
      const isRauthy = await page.locator('input[type="email"]').isVisible({ timeout: 1000 }).catch(() => false);
      if (isRauthy) {
        await page.fill('input[type="email"]', 'demo@demo.com');
        await page.click('button:has-text("Login")');
        const pwd = page.locator('input[type="password"]');
        await pwd.waitFor({ state: 'visible' });
        await pwd.fill('demo');
        await Promise.all([
          page.waitForNavigation(),
          page.click('button:has-text("Login")')
        ]);
      } else {
        // Keycloak / monmon-auth fallback
        await page.fill('#username', 'demo');
        await page.fill('#password', 'demo');
        await Promise.all([
          page.waitForNavigation(),
          page.click('#kc-login')
        ]);
      }
    }
  }

  test('SPOG desktop layout: all 4 cards present with key headline metrics', async ({ page, isMobile }) => {
    test.skip(isMobile, 'Desktop-only layout test');
    const consoleErrors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    await page.goto('/spog');
    await loginIfRequired(page);

    await expect(page).toHaveTitle(/MonMon SPOG/);
    await expect(page.locator('body')).toBeVisible();

    // Verify all 4 SPOG cards exist in DOM
    const potCard = page.locator('#spog-card-pot');
    const spentCard = page.locator('#spog-card-spent');
    const savingsCard = page.locator('#spog-card-savings');
    const accountsCard = page.locator('#spog-card-accounts');

    await expect(potCard).toBeVisible();
    await expect(spentCard).toBeVisible();
    await expect(savingsCard).toBeVisible();
    await expect(accountsCard).toBeVisible();

    // Verify headline metrics on Pot card
    await expect(potCard).toContainText('Left per day in your');
    await expect(potCard).toContainText('Daily & Monthly Allowances');

    // Verify headline metrics on Accounts card
    await expect(accountsCard).toContainText('Debit Available');
    await expect(accountsCard).toContainText('Credit Available');

    // Ensure no fatal JS crashes
    const fatalErrors = consoleErrors.filter(
      e => !e.includes('favicon') && !e.includes('fontawesome-free')
    );
    expect(fatalErrors).toEqual([]);
  });

  test('SPOG mobile layout: swipe deck primitive works smoothly in mobile viewport', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });

    await page.goto('/spog');
    await loginIfRequired(page);

    await expect(page).toHaveTitle(/MonMon SPOG/);

    const nav = page.locator('#spog-nav');
    await expect(nav).toBeVisible();

    // Verify initial active tab is pot
    const potPill = page.locator('.spog-nav-pill[data-target="spog-card-pot"]');
    await expect(potPill).toHaveClass(/active/);

    // Tap Accounts pill on mobile
    const accountsPill = page.locator('.spog-nav-pill[data-target="spog-card-accounts"]');
    await accountsPill.click();
    await expect(accountsPill).toHaveClass(/active/);

    // Verify accounts card is scrolled/visible
    const accountsCard = page.locator('#spog-card-accounts');
    await expect(accountsCard).toBeAttached();

    // Tap Spent pill on mobile
    const spentPill = page.locator('.spog-nav-pill[data-target="spog-card-spent"]');
    await spentPill.click();
    await expect(spentPill).toHaveClass(/active/);
  });
});
