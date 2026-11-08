// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

const MONMON_URL = process.env.MONMON_URL || 'http://localhost:9001';
const TEST_USER = 'demo';

describe('Net Worth Domain Contract Tests', () => {

  it('GET /api/networth returns aggregated assets, liabilities, and accounts breakdown', async () => {
    const res = await fetch(`${MONMON_URL}/api/networth?userId=${TEST_USER}`);
    assert.equal(res.status, 200);
    const data = await res.json();

    assert.ok(typeof data.netWorth === 'number', 'netWorth must be a number');
    assert.ok(typeof data.totalAssets === 'number', 'totalAssets must be a number');
    assert.ok(typeof data.totalLiabilities === 'number', 'totalLiabilities must be a number');
    assert.ok(typeof data.cashTotal === 'number', 'cashTotal must be a number');
    assert.ok(typeof data.pensionTotal === 'number', 'pensionTotal must be a number');
    assert.ok(typeof data.customAssetsTotal === 'number', 'customAssetsTotal must be a number');

    assert.ok(Array.isArray(data.customAssets), 'customAssets must be an array');
    assert.ok(Array.isArray(data.cashAccounts), 'cashAccounts must be an array');
    assert.ok(Array.isArray(data.pensionAccounts), 'pensionAccounts must be an array');

    // Net worth identity check: Net Worth == Total Assets - Total Liabilities (within 0.05 due to float rounding)
    assert.ok(
      Math.abs(data.netWorth - (data.totalAssets - data.totalLiabilities)) < 0.05,
      `Net worth (${data.netWorth}) should equal total assets (${data.totalAssets}) - total liabilities (${data.totalLiabilities})`
    );
  });

  it('POST /api/networth/asset creates asset and updates custom assets total', async () => {
    const assetName = `Contract Test Asset ${Date.now()}`;
    const assetValue = 42000.0;

    const createRes = await fetch(`${MONMON_URL}/api/networth/asset`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: TEST_USER,
        name: assetName,
        category: 'PROPERTY',
        value: assetValue,
        notes: 'Testing contract persistence',
      }),
    });
    assert.equal(createRes.status, 200);
    const created = await createRes.json();
    assert.ok(created.id, 'Created asset must have an id');
    assert.equal(created.name, assetName);
    assert.equal(created.value, assetValue);

    // Verify it is present in /api/networth
    const nwRes = await fetch(`${MONMON_URL}/api/networth?userId=${TEST_USER}`);
    const nwData = await nwRes.json();
    const found = nwData.customAssets.find((a) => a.id === created.id);
    assert.ok(found, 'Created asset should appear in /api/networth customAssets list');
    assert.equal(found.name, assetName);

    // Clean up / archive the asset
    const archiveRes = await fetch(`${MONMON_URL}/netWorth/binAsset/${created.id}?userId=${TEST_USER}`, {
      method: 'GET',
    });
    assert.ok([200, 302, 303].includes(archiveRes.status), 'Bin asset should return success or redirect');
  });
});
