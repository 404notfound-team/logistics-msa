// Hub HTTP 계약의 테스트용 대체 구현이며, 실제 DB 동작은 Hub 통합 테스트로 검증한다.
const http = require('http');
const empty = () => ({quantity: 100, requests: {}, receipts: {}, restorations: {}, calls: 0, restores: 0, mode: 'normal'});
http.createServer(async (req, res) => {
  const reply = (status, body) => {res.writeHead(status, {'Content-Type': 'application/json'}); res.end(JSON.stringify(body));};
  let state = await pm.state.get('hub.stock') || empty();
  const body = req.body || {};
  if (req.url === '/control' && req.method === 'POST') {
    state = body.action === 'stock' ? {...state, quantity: body.quantity} : {...empty(), ...body};
    await pm.state.set('hub.stock', state);
    return reply(200, state);
  }
  if (req.url === '/control' && req.method === 'GET') return reply(200, state);
  if (req.method !== 'POST' || !['/internal/stocks/deductions','/internal/stocks/restorations'].includes(req.url)) return reply(501, {code:'UNMODELED_TEST_ENDPOINT'});
  if (!req.headers['x-service-token'] || !req.headers['x-actor-username']) return reply(401, {code:'UNAUTHORIZED'});
  const key = body.order_id;
  const signature = JSON.stringify([body.hub_id, body.product_id, body.quantity]);
  const result = () => ({id: '44444444-4444-4444-8444-444444444444', hub_id: body.hub_id, product_id: body.product_id, quantity: state.quantity});
  if (req.url.endsWith('/restorations')) {
    state.restores++;
    if (state.requests[key] !== signature || !state.receipts[key] || state.receipts[key].status !== 200) {await pm.state.set('hub.stock', state); return reply(409, {code:'STOCK_RESTORATION_CONFLICT'});}
    if (!state.restorations[key]) {state.quantity += body.quantity; state.restorations[key] = result();}
    await pm.state.set('hub.stock', state);
    return reply(200, {data: state.restorations[key]});
  }
  state.calls++;
  if (state.requests[key] && state.requests[key] !== signature) {await pm.state.set('hub.stock', state); return reply(409, {code:'STOCK_DEDUCTION_CONFLICT'});}
  if (state.restorations[key]) {await pm.state.set('hub.stock', state); return reply(409, {code:'STOCK_ALREADY_RESTORED'});}
  const mode = state.mode;
  state.mode = 'normal';
  if (mode === 'before-commit-error') {await pm.state.set('hub.stock', state); return reply(503, {code:'SERVICE_UNAVAILABLE'});}
  if (mode === 'permanent-conflict') {await pm.state.set('hub.stock', state); return reply(409, {code:'STOCK_DEDUCTION_CONFLICT'});}
  if (mode === 'html-error') {await pm.state.set('hub.stock', state); res.writeHead(502, {'Content-Type':'text/html'}); return res.end('<html>upstream unavailable</html>');}
  if (!state.receipts[key]) {
    state.requests[key] = signature;
    if (state.quantity < body.quantity) state.receipts[key] = {status: 409, body: {code:'INSUFFICIENT_STOCK'}};
    else {state.quantity -= body.quantity; state.receipts[key] = {status:200, body:{data:result()}};}
  }
  const receipt = state.receipts[key];
  await pm.state.set('hub.stock', state);
  if (mode === 'lost-response') await new Promise(resolve => setTimeout(resolve, 800));
  if (mode === 'malformed-response') return reply(200, {data:null});
  return reply(receipt.status, receipt.body);
}).listen(process.env.PORT);
