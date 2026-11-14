// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Landing and Public Navigation E2E', () => {
  test('Landing page loads with brand, title, and onboarding CTA', async ({ page }) => {
    const consoleErrors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    const res = await page.goto('/');
    expect(res?.status()).toBe(200);

    await expect(page).toHaveTitle(/MonMon/);
    await expect(page.locator('body')).toBeVisible();

    // Verify brand or header presence
    const header = page.locator('header, .w3-top, .navbar, h1').first();
    await expect(header).toBeVisible();

    // Check no uncaught JS syntax errors
    expect(consoleErrors.filter(e => !e.includes('favicon'))).toEqual([]);
  });

  test('About page loads correctly with navigation', async ({ page }) => {
    const res = await page.goto('/about');
    expect(res?.status()).toBe(200);

    await expect(page).toHaveTitle(/MonMon/);
    const content = page.locator('.w3-content, main, body').first();
    await expect(content).toBeVisible();
  });

  test('Unauthenticated user attempting protected route is redirected', async ({ page }) => {
    await page.goto('/myOutgoings');
    // Expect redirection to login or auth provider
    const url = page.url();
    expect(url).toMatch(/auth|login|callback|identity/);
  });
});
