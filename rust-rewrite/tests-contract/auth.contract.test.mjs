// @ts-check
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

const AUTH_URL = process.env.AUTH_URL || 'http://127.0.0.1:8080';

describe('monmon-auth OIDC Contract Tests', () => {
  it('GET /health returns 200 OK', async () => {
    const res = await fetch(`${AUTH_URL}/health`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.status, 'UP');
  });

  it('GET /.well-known/openid-configuration returns discovery metadata', async () => {
    const res = await fetch(`${AUTH_URL}/.well-known/openid-configuration`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(body.issuer);
    assert.ok(body.authorization_endpoint.includes('/protocol/openid-connect/auth'));
    assert.ok(body.token_endpoint.includes('/protocol/openid-connect/token'));
    assert.ok(body.jwks_uri.includes('/protocol/openid-connect/certs'));
    assert.ok(body.id_token_signing_alg_values_supported.includes('RS256'));
    assert.ok(body.response_types_supported.includes('code'));
  });

  it('GET /auth/realms/monmon/.well-known/openid-configuration returns Keycloak compatible discovery', async () => {
    const res = await fetch(`${AUTH_URL}/auth/realms/monmon/.well-known/openid-configuration`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(body.issuer);
    assert.ok(body.token_endpoint);
  });

  it('GET /certs exposes RS256 JWKS public key with n and e', async () => {
    const res = await fetch(`${AUTH_URL}/certs`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(Array.isArray(body.keys));
    assert.ok(body.keys.length > 0);
    const key = body.keys[0];
    assert.equal(key.kty, 'RSA');
    assert.equal(key.alg, 'RS256');
    assert.equal(key.use, 'sig');
    assert.ok(key.n && key.n.length > 100);
    assert.equal(key.e, 'AQAB');
  });

  it('GET /auth renders clean HTML login form with required selectors', async () => {
    const res = await fetch(`${AUTH_URL}/auth?client_id=monmon-web&redirect_uri=http://localhost:9000/callback&state=test-state&nonce=test-nonce`);
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type') || '', /text\/html/);
    const html = await res.text();
    assert.ok(html.includes('id="username"'));
    assert.ok(html.includes('id="password"'));
    assert.ok(html.includes('id="kc-login"'));
    assert.ok(html.includes('name="client_id" value="monmon-web"'));
  });

  it('POST /auth/login fails on invalid credentials with 401', async () => {
    const form = new URLSearchParams({
      client_id: 'monmon-web',
      redirect_uri: 'http://localhost:9000/callback',
      username: 'wrong-user',
      password: 'wrong-password',
      state: 'st',
      nonce: 'nc'
    });
    const res = await fetch(`${AUTH_URL}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form.toString(),
      redirect: 'manual'
    });
    assert.equal(res.status, 401);
    const html = await res.text();
    assert.ok(html.includes('Invalid username or password'));
  });

  it('Complete OIDC Authorization Code -> Token exchange flow for demo user', async () => {
    // 1. Submit login form
    const form = new URLSearchParams({
      client_id: 'monmon-web',
      redirect_uri: 'http://localhost:9000/callback',
      username: 'demo',
      password: 'demo',
      state: 'test-state-123',
      nonce: 'test-nonce-456'
    });

    const loginRes = await fetch(`${AUTH_URL}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form.toString(),
      redirect: 'manual'
    });

    assert.ok([302, 303].includes(loginRes.status));
    const location = loginRes.headers.get('location');
    assert.ok(location);
    const redirectUrl = new URL(location, AUTH_URL);
    assert.equal(redirectUrl.pathname, '/callback');
    assert.equal(redirectUrl.searchParams.get('state'), 'test-state-123');
    const code = redirectUrl.searchParams.get('code');
    assert.ok(code);

    // 2. Exchange code for tokens
    const tokenForm = new URLSearchParams({
      grant_type: 'authorization_code',
      code: code,
      client_id: 'monmon-web',
      client_secret: '01c283f5-69ff-42e4-a080-971629656fbb',
      redirect_uri: 'http://localhost:9000/callback'
    });

    const tokenRes = await fetch(`${AUTH_URL}/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: tokenForm.toString()
    });

    assert.equal(tokenRes.status, 200);
    const tokenData = await tokenRes.json();
    assert.equal(tokenData.token_type, 'Bearer');
    assert.ok(tokenData.access_token);
    assert.ok(tokenData.id_token);

    // 3. Inspect ID Token claims
    const [headerB64, payloadB64] = tokenData.id_token.split('.');
    const header = JSON.parse(Buffer.from(headerB64, 'base64url').toString('utf8'));
    const claims = JSON.parse(Buffer.from(payloadB64, 'base64url').toString('utf8'));

    assert.equal(header.alg, 'RS256');
    assert.equal(claims.aud, 'monmon-web');
    assert.equal(claims.sub, '9aee56db-733a-457c-aa74-74fe74e2ac91');
    assert.equal(claims.preferred_username, 'demo');
    assert.equal(claims.email, 'demo@demo.com');
    assert.equal(claims.nonce, 'test-nonce-456');
    assert.ok(claims.roles.includes('ROLE_ADMIN'));

    // 4. Test UserInfo endpoint
    const userinfoRes = await fetch(`${AUTH_URL}/userinfo`, {
      headers: { Authorization: `Bearer ${tokenData.access_token}` }
    });
    assert.equal(userinfoRes.status, 200);
    const userInfo = await userinfoRes.json();
    assert.equal(userInfo.sub, '9aee56db-733a-457c-aa74-74fe74e2ac91');
    assert.equal(userInfo.preferred_username, 'demo');
  });
});
