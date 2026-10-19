const http = require('http');
http.createServer(async (req, res) => {
  const reply = (status, body) => {res.writeHead(status, {'Content-Type':'application/json'}); res.end(JSON.stringify(body));};
  let state = await pm.state.get('delivery.orders') || {orders:{}, reject:false};
  const body = req.body || {};
  if (req.method === 'POST' && req.url === '/control') {state = {orders:{}, ...body}; await pm.state.set('delivery.orders', state); return reply(200, state);}
  if (req.method === 'GET' && req.url.startsWith('/internal/deliveries/by-order/')) {
    const entry = state.orders[req.url.split('/').pop()];
    return reply(entry ? 200 : 404, entry ? {data:entry.result} : {code:'DELIVERY_NOT_FOUND'});
  }
  if (req.method === 'POST' && req.url === '/internal/deliveries') {
    if (state.reject) return reply(404, {code:'HUB_ROUTE_NOT_FOUND'});
    const signature = JSON.stringify(body);
    let entry = state.orders[body.order_id];
    if (entry && entry.signature !== signature) return reply(409, {code:'DELIVERY_ALREADY_EXISTS'});
    const created = !entry;
    if (!entry) {entry = {signature, result:{delivery_id: require('crypto').randomUUID(), order_id:body.order_id, status:'HUB_WAITING'}}; state.orders[body.order_id]=entry;}
    await pm.state.set('delivery.orders', state);
    return reply(created ? 201 : 200, {data:entry.result});
  }
  if (req.method === 'POST' && /^\/internal\/deliveries\/[^/]+\/cancel$/.test(req.url)) {
    const id = req.url.split('/')[3];
    const entry = Object.values(state.orders).find(value => value.result.delivery_id === id);
    if (!entry) return reply(404, {code:'DELIVERY_NOT_FOUND'});
    if (!['HUB_WAITING','CANCELLED'].includes(entry.result.status)) return reply(409, {code:'DELIVERY_ALREADY_STARTED'});
    entry.result.status = 'CANCELLED';
    await pm.state.set('delivery.orders', state);
    return reply(200, {data:entry.result});
  }
  return reply(501, {code:'UNMODELED_TEST_ENDPOINT'});
}).listen(process.env.PORT);
