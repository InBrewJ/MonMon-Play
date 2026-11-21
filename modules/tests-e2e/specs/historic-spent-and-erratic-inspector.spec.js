// @ts-check
const { test, expect } = require('@playwright/test');

function buildAuthCookies(baseURL) {
  const origin = new URL(baseURL || 'http://localhost:9000').origin;
  return [
    { name: 'user_id', value: process.env.DEMO_USER || 'demo', url: origin },
    { name: 'monmon_session', value: process.env.DEMO_USER || 'demo', url: origin },
    { name: 'authenticated', value: 'true', url: origin },
    { name: 'PLAY_SESSION', value: 'authenticated', url: origin },
  ];
}

async function loginIfRequired(page) {
  if (page.url().includes('auth') || page.url().includes('login')) {
    const isMonmonLogin = await page.locator('form[action="/login"]').isVisible({ timeout: 1500 }).catch(() => false);
    if (isMonmonLogin) {
      await page.fill('input[name="username"]', process.env.DEMO_USER || 'demo');
      await page.fill('input[name="password"]', process.env.DEMO_PASSWORD || 'demo');
      await Promise.all([
        page.waitForNavigation(),
        page.click('button[type="submit"]')
      ]);
      return;
    }
    const isRauthy = await page.locator('input[type="email"]').isVisible({ timeout: 1500 }).catch(() => false);
    if (isRauthy) {
      await page.fill('input[type="email"]', process.env.DEMO_EMAIL || 'demo@urawizard.com');
      await page.click('button:has-text("Login")');
      const pwd = page.locator('input[type="password"]');
      await pwd.waitFor({ state: 'visible', timeout: 5000 });
      await pwd.fill(process.env.DEMO_PASSWORD || 'demo');
      await Promise.all([
        page.waitForURL(url => !url.toString().includes('auth/v1/oidc'), { timeout: 15000 }),
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

test.describe('Historic Spent Two-View Deck & Erratic Balance Inspector E2E', () => {
  test('/historicSpent separates Monthly Pots (matching SPOG) and All Accounts with refresh-safe #hash tabs', async ({ page, context, baseURL, isMobile }) => {
    await context.addCookies(buildAuthCookies(baseURL));
    const consoleErrors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    // 1. Capture live SPOG Spent This Month value for exact parity check
    await page.goto('/spog', { waitUntil: 'networkidle' });
    await loginIfRequired(page);
    await expect(page).toHaveTitle(/MonMon SPOG/);

    if (isMobile) {
      const spentPill = page.locator('.spog-nav-pill[data-target="spog-card-spent"]');
      if (await spentPill.isVisible()) {
        await spentPill.click();
      }
    }
    const spogSpentCard = page.locator('#spog-card-spent');
    await expect(spogSpentCard).toBeAttached();
    const spogCardText = await spogSpentCard.innerText();
    const spogSpentMatch = spogCardText.match(/£\d+\.\d{2}\s*\/\s*£\d+\.\d{2}\s*monthly pot/);
    expect(spogSpentMatch, `Expected SPOG spent/pot figure in: ${spogCardText}`).not.toBeNull();
    const spogSpentLine = spogSpentMatch[0].replace(/\s+/g, ' ').trim();

    // 2. Visit /historicSpent -> URL normalizes to #pots
    await page.goto('/historicSpent', { waitUntil: 'networkidle' });
    await expect(page).toHaveTitle(/MonMon - Spending History/);
    await expect(page).toHaveURL(/#pots$/);

    const potsPill = page.locator('#historic-spent-nav .deck-nav-pill[data-hash="pots"]');
    const allPill = page.locator('#historic-spent-nav .deck-nav-pill[data-hash="all-accounts"]');
    const potsCard = page.locator('#historic-card-pots');
    const allCard = page.locator('#historic-card-all-accounts');

    await expect(potsPill).toBeVisible();
    await expect(allPill).toBeVisible();
    await expect(potsPill).toHaveClass(/active/);
    await expect(potsCard).toBeVisible();

    // Verify Monthly Pots KPI strip & exact SPOG parity on current cycle card
    await expect(potsCard).toContainText('Avg Pot Spend');
    await expect(potsCard).toContainText('Avg Daily Pace');
    await expect(potsCard).toContainText('Within MAX Pace');

    const firstPotCycle = potsCard.locator('details.historic-cycle-card').first();
    await expect(firstPotCycle).toBeVisible();
    const firstCycleText = (await firstPotCycle.innerText()).replace(/\s+/g, ' ');
    expect(firstCycleText).toContain(spogSpentLine);

    // 3. Switch to All Accounts -> URL updates to #all-accounts
    await allPill.click();
    await expect(page).toHaveURL(/#all-accounts$/);
    await expect(allPill).toHaveClass(/active/);
    await expect(allCard).toBeVisible();
    await expect(allCard).toContainText('Avg All-Accounts Spend');
    await expect(allCard).toContainText('Highest Spend Cycle');
    await expect(allCard).toContainText('Lowest Spend Cycle');

    if (!isMobile) {
      await expect(potsCard).not.toBeVisible();
    }

    // 4. Refresh page -> #all-accounts persists (refresh-safe)
    await page.reload({ waitUntil: 'networkidle' });
    await expect(page).toHaveURL(/#all-accounts$/);
    await expect(allPill).toHaveClass(/active/);
    await expect(allCard).toBeVisible();
    if (!isMobile) {
      await expect(potsCard).not.toBeVisible();
    }

    // 5. Expand first cycle card in All Accounts -> verify responsive breakdown (desktop table vs mobile Stacked Action Cards)
    const firstAllCycle = allCard.locator('details.historic-cycle-card').first();
    await firstAllCycle.locator('summary').click();
    await expect(firstAllCycle).toHaveAttribute('open', '');
    if (isMobile) {
      await expect(firstAllCycle.locator('.historic-mobile-cards')).toBeVisible();
      await expect(firstAllCycle.locator('.historic-desktop-table')).not.toBeVisible();
      await expect(firstAllCycle.locator('.historic-mobile-cards')).toContainText('Total Across Accounts');
    } else {
      await expect(firstAllCycle.locator('.historic-desktop-table')).toBeVisible();
      await expect(firstAllCycle.locator('.historic-mobile-cards')).not.toBeVisible();
      await expect(firstAllCycle.locator('.historic-desktop-table')).toContainText('Total Across Accounts');
    }

    // 6. Switch back to Monthly Pots -> URL updates back to #pots
    await potsPill.click();
    await expect(page).toHaveURL(/#pots$/);
    await expect(potsPill).toHaveClass(/active/);
    await expect(potsCard).toBeVisible();

    const fatalErrors = consoleErrors.filter(
      e => !e.includes('favicon') && !e.includes('fontawesome') && !e.includes('401') && !e.includes('404')
    );
    expect(fatalErrors).toEqual([]);
  });

  test('Erratic Balance Inspector shows transaction dates and filters live by account name', async ({ page, context, baseURL }) => {
    await context.addCookies(buildAuthCookies(baseURL));
    const consoleErrors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    await page.goto('/myAccounts', { waitUntil: 'networkidle' });
    await loginIfRequired(page);
    await expect(page).toHaveTitle(/MonMon Accounts/);

    // Create a temporary account + two balances with a £650 jump so the test is 100% deterministic
    const uniqueAcc = 'ErraticTestVault-' + Date.now();
    await page.locator('button[onclick="openAccountModal()"]').click();
    await expect(page.locator('#account-modal')).toBeVisible();
    await page.fill('#acc-name', uniqueAcc);
    await page.selectOption('#type', 'CURRENT');
    await page.fill('#availableLimit', '0');
    await Promise.all([
      page.waitForNavigation(),
      page.locator('#account-modal-form button[type="submit"]').click()
    ]);

    try {
      // Record two balances (£100.00 then £750.00 -> Δ £650.00) on /myBalances
      await page.goto('/myBalances', { waitUntil: 'networkidle' });
      await expect(page.locator('#record-balance-form')).toBeVisible();

      const accOption = page.locator('#account option', { hasText: uniqueAcc });
      const accValue = await accOption.getAttribute('value');
      expect(accValue).toBeTruthy();

      await page.selectOption('#account', accValue);
      await page.fill('#balance-value', '100.00');
      await Promise.all([
        page.waitForNavigation(),
        page.locator('#record-balance-form button[type="submit"]').click()
      ]);

      await page.selectOption('#account', accValue);
      await page.fill('#balance-value', '750.00');
      await Promise.all([
        page.waitForNavigation(),
        page.locator('#record-balance-form button[type="submit"]').click()
      ]);

      // Open Erratic Balance Inspector modal
      await page.locator('#btn-erratic-balances').click();
      const modal = page.locator('#erratic-balances-modal');
      await expect(modal).toBeVisible();

      const container = page.locator('#erratic-balances-container');
      await expect(container).toContainText(uniqueAcc, { timeout: 10000 });
      // Verify 2dp jump values and formatted transaction date (e.g. "21 Nov 2026, 18:45")
      const card = container.locator('.item-card', { hasText: uniqueAcc }).first();
      await expect(card).toContainText('£100.00');
      await expect(card).toContainText('£750.00');
      await expect(card).toContainText('£650.00');
      await expect(card).toContainText(/\d{2}\s+[A-Z][a-z]{2}\s+\d{4},\s+\d{2}:\d{2}/);

      // Search by account name (case-insensitive)
      const searchInput = page.locator('#erratic-account-search');
      const clearBtn = page.locator('#erratic-search-clear');
      const countLbl = page.locator('#erratic-result-count');

      await searchInput.fill(uniqueAcc.toLowerCase());
      await expect(clearBtn).toBeVisible();
      await expect(countLbl).toContainText(/^Showing 1 of \d+ jump\(s\)$/);
      await expect(container.locator('.item-card')).toHaveCount(1);

      // Search with no matches
      await searchInput.fill('NoSuchAccountNameXYZ999');
      await expect(countLbl).toContainText(/^Showing 0 of \d+ jump\(s\)$/);
      await expect(container).toContainText('No erratic jumps matching');

      // Clear search button restores full list
      await clearBtn.click();
      await expect(searchInput).toHaveValue('');
      await expect(clearBtn).not.toBeVisible();
      await expect(countLbl).toContainText(/^Found \d+ jump\(s\)$/);
    } finally {
      // Clean up temporary test account via /myAccounts
      await page.goto('/myAccounts', { waitUntil: 'networkidle' });
      const delBtn = page.locator(`tr:has-text("${uniqueAcc}") button.btn-delete, .item-card:has-text("${uniqueAcc}") button.btn-delete`).first();
      if (await delBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
        page.once('dialog', d => d.accept());
        await delBtn.click();
        await page.waitForTimeout(500);
      }
    }

    const fatalErrors = consoleErrors.filter(
      e => !e.includes('favicon') && !e.includes('fontawesome') && !e.includes('401') && !e.includes('404')
    );
    expect(fatalErrors).toEqual([]);
  });
});
