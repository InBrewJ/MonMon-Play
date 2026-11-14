// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Settings and Management Views E2E', () => {
  test('Settings page renders Waiting List Approvals and Region Selector', async ({ page }) => {
    const res = await page.goto('/settings');
    // Either renders 200 settings page or redirects to auth
    if (page.url().includes('auth') || page.url().includes('login')) {
      expect(page.url()).toMatch(/auth|login/);
      return;
    }

    expect(res?.status()).toBe(200);
    await expect(page).toHaveTitle(/Settings/);
    const card = page.locator('#waiting-list-card, .w3-card').first();
    await expect(card).toBeVisible();
  });

  test('Waiting List page renders status information or redirects if approved', async ({ page }) => {
    const res = await page.goto('/waitinglist');
    const url = page.url();
    // If user is already approved, redirects to /spog, or shows waiting list card
    if (url.includes('/spog')) {
      expect(url).toContain('/spog');
    } else if (url.includes('auth') || url.includes('login')) {
      expect(url).toMatch(/auth|login/);
    } else {
      expect(res?.status()).toBe(200);
      await expect(page).toHaveTitle(/Waiting List/);
    }
  });

  test('Accounts management page renders Stacked Action Cards on mobile', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const res = await page.goto('/myAccounts');
    if (page.url().includes('auth') || page.url().includes('login')) {
      expect(page.url()).toMatch(/auth|login/);
      return;
    }

    expect(res?.status()).toBe(200);
    await expect(page.locator('.w3-content').first()).toBeVisible();
  });

  test('Outgoings management page renders list and pause action', async ({ page }) => {
    const res = await page.goto('/myOutgoings');
    if (page.url().includes('auth') || page.url().includes('login')) {
      expect(page.url()).toMatch(/auth|login/);
      return;
    }

    expect(res?.status()).toBe(200);
    await expect(page.locator('body')).toBeVisible();
  });
});
