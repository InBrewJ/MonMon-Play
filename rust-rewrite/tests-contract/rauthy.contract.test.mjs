// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

const RAUTHY_URL = process.env.RAUTHY_URL || 'http://localhost:8085';
const CLIENT_ID = 'monmon-web';
const CLIENT_SECRET = '01c283f569ff42e4a080971629656fbb01c283f569ff42e4a080971629656fbb';
const REDIRECT_URI = 'http://localhost:9000/callback';

describe('Rauthy OIDC Contract Tests', () => {
  it('GET /auth/v1/ping returns 200 OK', async () => {
    const res = await fetch(`${RAUTHY_URL}/auth/v1/ping`);
    assert.equal(res.status, 200);
  });

  it('GET /auth/v1/.well-known/openid-configuration returns discovery metadata', async () => {
    const res = await fetch(`${RAUTHY_URL}/auth/v1/.well-known/openid-configuration`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(body.issuer);
    assert.ok(body.authorization_endpoint.includes('/auth/v1/oidc/authorize'));
    assert.ok(body.token_endpoint.includes('/auth/v1/oidc/token'));
    assert.ok(body.jwks_uri.includes('/auth/v1/oidc/certs'));
    assert.ok(body.id_token_signing_alg_values_supported.includes('RS256'));
    assert.ok(body.response_types_supported.includes('code'));
    assert.ok(body.scopes_supported.includes('openid'));
  });

  it('GET /auth/v1/oidc/certs exposes RS256 JWKS public key with n and e', async () => {
    const res = await fetch(`${RAUTHY_URL}/auth/v1/oidc/certs`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(Array.isArray(body.keys));
    assert.ok(body.keys.length > 0);
    const rsaKey = body.keys.find(k => k.kty === 'RSA');
    assert.ok(rsaKey, 'Should have an RSA key in JWKS');
    assert.equal(rsaKey.alg, 'RS256');
    assert.ok(rsaKey.n && rsaKey.n.length > 100);
    assert.equal(rsaKey.e, 'AQAB');
  });

  it('GET /auth/v1/oidc/authorize renders HTML for monmon-web client', async () => {
    const url = `${RAUTHY_URL}/auth/v1/oidc/authorize?client_id=${CLIENT_ID}&response_type=code&redirect_uri=${encodeURIComponent(REDIRECT_URI)}&scope=openid+profile+email`;
    const res = await fetch(url);
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type') || '', /text\/html/);
    const html = await res.text();
    assert.ok(html.includes('MonMon Web') || html.includes('monmon-web'));
  });

  it('POST /auth/v1/oidc/token fails without authorization code or secret', async () => {
    const params = new URLSearchParams();
    params.append('grant_type', 'authorization_code');
    params.append('code', 'invalid-code');
    params.append('redirect_uri', REDIRECT_URI);
    params.append('client_id', CLIENT_ID);
    params.append('client_secret', 'wrong-secret');

    const res = await fetch(`${RAUTHY_URL}/auth/v1/oidc/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params.toString()
    });
    assert.ok(res.status === 400 || res.status === 401);
  });
});
