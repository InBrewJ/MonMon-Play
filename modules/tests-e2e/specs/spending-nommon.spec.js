// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Spending Pace & NomMon Meal Planner E2E', () => {

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
        await page.fill('#username', 'demo');
        await page.fill('#password', 'demo');
        await Promise.all([
          page.waitForNavigation(),
          page.click('#kc-login')
        ]);
      }
    }
  }

  test('Spending Pace view loads cycle breakdown and upfront pace metrics', async ({ page }) => {
    const consoleErrors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    await page.goto('/spendingPace');
    await loginIfRequired(page);

    await expect(page).toHaveTitle(/Spending Pace/);
    await expect(page.locator('body')).toBeVisible();

    // Verify upfront "At a Glance" stat cards
    await expect(page.locator('text=Constant Target Pace')).toBeVisible();
    await expect(page.locator('text=Average Cycle Pace')).toBeVisible();
    await expect(page.locator('text=Status vs Target')).toBeVisible();

    const relevantErrors = consoleErrors.filter(
      e => !e.includes('favicon') && !e.includes('fontawesome-free') && !e.includes('Mistral API key is not configured')
    );
    expect(relevantErrors).toEqual([]);
  });

  test('NomMon view loads weekly budget, basket estimate, and regenerate controls', async ({ page }) => {
    const consoleErrors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    await page.goto('/nommon');
    await loginIfRequired(page);

    await expect(page).toHaveTitle(/NomMon/);
    await expect(page.locator('body')).toBeVisible();

    // Verify headline header and metrics
    await expect(page.locator('text=NomMon').first()).toBeVisible();
    await expect(page.locator('text=Weekly Food Budget')).toBeVisible();
    await expect(page.locator('text=Estimated Basket Cost')).toBeVisible();
    await expect(page.locator('text=Safety Buffer')).toBeVisible();

    // Verify action button
    const regenBtn = page.locator('#btn-regenerate-nommon');
    await expect(regenBtn).toBeVisible();

    const relevantErrors = consoleErrors.filter(
      e => !e.includes('favicon') && !e.includes('fontawesome-free') && !e.includes('Mistral API key is not configured')
    );
    expect(relevantErrors).toEqual([]);
  });

});
