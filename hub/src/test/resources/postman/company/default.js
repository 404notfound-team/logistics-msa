// Contract source: Company PR #65, InternalProductController and InternalProductResponse.
// Local verification only. Credentials below are test fixtures, never runtime secrets.
const http = require('http');
const failureId = n => `00000000-0000-0000-0001-${String(n).padStart(12, '0')}`;
http.createServer(async (req, res) => {
  const json = (status, body) => {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(typeof body === 'string' ? body : JSON.stringify(body));
  };
  const match = /^\/internal\/products\/([0-9a-f-]{36})$/.exec(req.url);
  if (req.method !== 'GET' || !match) return json(501, { code: 'NOT_IMPLEMENTED' });
  if (req.headers['x-service-token'] !== 'hub-service-test-token' || !req.headers['x-actor-username']) {
    return json(401, { code: 'UNAUTHORIZED' });
  }
  const id = match[1];
  if (id === failureId(2)) return json(404, { code: 'PRODUCT_NOT_FOUND', message: '상품을 찾을 수 없습니다.', trace_id: null, field_errors: [] });
  if (id === failureId(3)) return json(500, { code: 'INTERNAL_ERROR' });
  if (id === failureId(4)) return json(500, '<html>upstream secret</html>');
  if (id === failureId(5)) await new Promise(resolve => setTimeout(resolve, 1200));
  if (id === failureId(6)) return json(401, { code: 'UNAUTHORIZED' });
  if (id === failureId(7)) return json(403, { code: 'FORBIDDEN' });
  if (id === failureId(8)) return json(200, 'invalid-json');
  if (id === failureId(9)) return json(200, { data: null });
  if (id === failureId(10)) return json(200, { data: { id: '11111111-1111-1111-1111-111111111111' } });
  if (id === failureId(11)) {
    res.writeHead(302, { Location: '/internal/products/11111111-1111-1111-1111-111111111111' });
    return res.end();
  }
  if (id === failureId(12)) return json(404, '<html>proxy not found</html>');
  if (id === failureId(13)) return json(200, '');
  if (id === failureId(14)) return json(200, { data: { id: null } });
  if (id === failureId(15)) return req.socket.destroy();
  return json(200, { data: { id, company_id: '11111111-1111-1111-1111-111111111111', name: '상품', price: 100 } });
}).listen(process.env.PORT || 49173, '127.0.0.1');
