// CompanyClient·OrderClientTypes 계약에 맞춘 로컬 테스트 데이터다.
const http = require('http');
const supplier = '11111111-1111-4111-8111-111111111111';
const receiver = '22222222-2222-4222-8222-222222222222';
const product = '33333333-3333-4333-8333-333333333333';
http.createServer((req, res) => {
  let data;
  if (req.method === 'GET' && req.url === '/internal/companies/' + supplier) data = {id: supplier, hub_id: '00000000-0000-0000-0000-000000000001'};
  if (req.method === 'GET' && req.url === '/internal/companies/' + receiver) data = {id: receiver, hub_id: '00000000-0000-0000-0000-000000000002'};
  if (req.method === 'GET' && req.url === '/internal/products/' + product) data = {id: product, company_id: supplier, name: 'HTTP contract product', price: 100};
  res.writeHead(data ? 200 : 501, {'Content-Type': 'application/json'});
  res.end(JSON.stringify(data ? {data} : {code: 'UNMODELED_TEST_ENDPOINT'}));
}).listen(process.env.PORT);
