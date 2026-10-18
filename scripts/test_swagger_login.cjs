const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');

function client() {
  let now = 1000;
  const authorizations = [];
  const sandbox = {
    URL, Date: { now: () => now },
    setTimeout: () => 1, clearTimeout() {},
    atob: value => Buffer.from(value, 'base64').toString('binary'),
    document: { getElementById: () => null },
    window: {
      location: { origin: 'http://localhost:8080', href: 'http://localhost:8080/swagger-ui/index.html' },
      ui: {
        specSelectors: { securityDefinitions: () => new Map([['bearerAuth', {}]]) },
        preauthorizeApiKey: (name, token) => authorizations.push({ name, token }),
        authActions: { logout() {} },
      },
    },
  };
  const source = readFileSync(resolve(__dirname, '../gateway/src/main/resources/swagger/login.js'), 'utf8');
  const login = vm.runInNewContext(source + '\nlogisticsSwaggerLogin;', sandbox);
  const jwt = 'header.' + Buffer.from(JSON.stringify({ sub: 'testuser' })).toString('base64url') + '.signature';
  const signin = () => login.response({ url: 'http://localhost:8080/api/users/signin', status: 200,
    obj: { data: { access_token: jwt, expires_in: 60 } } });
  return { login, signin, jwt, authorizations, expire: () => { now += 60001; } };
}

test('a successful signin response authorizes requests across all business services', () => {
  const c = client();
  c.signin();
  assert.equal(c.authorizations[0].name, 'bearerAuth');
  for (const path of ['/api/companies', '/api/hubs', '/api/orders', '/api/deliveries']) {
    const request = c.login.request({ url: path, headers: { authorization: 'stale-token' } });
    assert.equal(request.headers.Authorization, 'Bearer ' + c.jwt);
    assert.equal(request.headers.authorization, undefined);
  }
});

test('credentials never accompany external, documentation, signup or signin requests', () => {
  const c = client();
  c.signin();
  for (const url of ['https://example.com/api/orders', '/v3/api-docs/orders', '/api/users/signup', '/api/users/signin']) {
    const request = c.login.request({ url, headers: { authorization: 'old-token', Accept: 'application/json' } });
    assert.equal(request.headers.authorization, undefined);
    assert.equal(request.headers.Authorization, undefined);
    assert.equal(request.headers.Accept, 'application/json');
  }
});

test('expired tokens and an already prepared Authorization header are removed', () => {
  const c = client();
  c.signin();
  c.expire();
  const request = c.login.request({ url: '/api/orders', headers: { Authorization: 'Bearer ' + c.jwt } });
  assert.equal(request.headers.Authorization, undefined);
  assert.equal(c.login.request({ url: '/api/orders', headers: {} }).headers.Authorization, undefined);
});

test('the standard Swagger logout action also clears shared authentication', () => {
  const c = client();
  c.signin();
  let called = false;
  c.login.plugin().statePlugins.auth.wrapActions.logout(() => { called = true; })(['bearerAuth']);
  assert.equal(called, true);
  assert.equal(c.login.request({ url: '/api/orders', headers: {} }).headers.Authorization, undefined);
});
