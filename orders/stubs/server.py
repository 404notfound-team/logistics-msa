#!/usr/bin/env python3
"""Local HTTP fixtures for Orders. Never a replacement for the real domain services."""
import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import signal
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import UUID, uuid4

RECEIVER = '147259cc-6ff6-42c7-a89a-8798c8187811'
SUPPLIER = '1ef5bfb1-6b53-40ef-b09a-54eb71846d73'
PRODUCT = '64f1ff8f-cd59-4504-b665-ef18727a6306'
ORIGIN = '00000000-0000-0000-0000-000000000002'
DESTINATION = '00000000-0000-0000-0000-000000000001'
STOCK = '00000000-0000-0000-0000-000000000003'


def token(username, role, secret, lifetime=900):
    if len(secret.encode()) < 32 or not username.strip() or len(username) > 100 or role not in ('USER', 'MASTER'):
        raise ValueError('Valid actor, role and JWT_SECRET of at least 32 UTF-8 bytes required')
    encode = lambda value: base64.urlsafe_b64encode(value).rstrip(b'=')
    now = int(time.time())
    head = encode(json.dumps({'alg': 'HS256', 'typ': 'JWT'}, separators=(',', ':')).encode())
    body = encode(json.dumps({'iss': 'logistics-users', 'sub': username, 'role': role,
                              'iat': now, 'exp': now + lifetime}, separators=(',', ':')).encode())
    message = head + b'.' + body
    return (message + b'.' + encode(hmac.new(secret.encode(), message, hashlib.sha256).digest())).decode()


class StubError(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code


class State:
    def __init__(self, filename):
        self.lock = threading.RLock()
        self.db = sqlite3.connect(filename, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.rules, self.counts, self.actors, self.inflight = {}, {}, {}, 0
        self.db.executescript('''
          CREATE TABLE IF NOT EXISTS companies(id TEXT PRIMARY KEY, hub_id TEXT, active INTEGER);
          CREATE TABLE IF NOT EXISTS products(id TEXT PRIMARY KEY, company_id TEXT, name TEXT, price TEXT, active INTEGER);
          CREATE TABLE IF NOT EXISTS stocks(id TEXT PRIMARY KEY, hub_id TEXT, product_id TEXT, quantity INTEGER, UNIQUE(hub_id,product_id));
          CREATE TABLE IF NOT EXISTS deliveries(delivery_id TEXT PRIMARY KEY, order_id TEXT UNIQUE, departure_hub_id TEXT,
            destination_hub_id TEXT, address TEXT, recipient_name TEXT, status TEXT, deleted INTEGER DEFAULT 0);
          CREATE TABLE IF NOT EXISTS notifications(id INTEGER PRIMARY KEY, payload TEXT);
        ''')
        self.seed()

    def seed(self):
        with self.db:
            self.db.executemany('INSERT OR IGNORE INTO companies VALUES(?,?,1)', [(RECEIVER, DESTINATION), (SUPPLIER, ORIGIN)])
            self.db.execute('INSERT OR IGNORE INTO products VALUES(?,?,?,?,1)', (PRODUCT, SUPPLIER, '테스트 상품', '10000.00'))
            self.db.execute('INSERT OR IGNORE INTO stocks VALUES(?,?,?,1000)', (STOCK, ORIGIN, PRODUCT))

    def row(self, sql, values=()):
        value = self.db.execute(sql, values).fetchone()
        return dict(value) if value else None

    def delivery(self, row):
        return {key: row[key] for key in ('delivery_id', 'order_id', 'status')}

    def control(self, body):
        action = body.get('action')
        if action == 'reset':
            with self.db:
                for table in ('deliveries', 'notifications', 'stocks', 'companies', 'products'):
                    self.db.execute('DELETE FROM ' + table)
            self.rules.clear(); self.counts.clear(); self.actors.clear(); self.seed()
        elif action == 'fault':
            for key in ('delay_before_ms', 'delay_after_ms'):
                if not 0 <= body.get(key, 0) <= 10000:
                    raise StubError(400, 'INVALID_REQUEST')
            self.rules[body['operation']] = dict(body)
        elif action == 'delivery_status':
            if body['status'] not in ('WAITING', 'HUB_TRANSIT', 'DESTINATION_HUB_ARRIVED', 'COMPANY_TRANSIT', 'COMPLETED', 'CANCELLED'):
                raise StubError(400, 'INVALID_REQUEST')
            with self.db:
                self.db.execute('UPDATE deliveries SET status=? WHERE order_id=?', (body['status'], body['order_id']))
        elif action == 'stock':
            if type(body['quantity']) is not int or body['quantity'] < 0:
                raise StubError(400, 'INVALID_REQUEST')
            with self.db:
                self.db.execute('UPDATE stocks SET quantity=? WHERE id=?', (body['quantity'], STOCK))
        elif action == 'product':
            with self.db:
                self.db.execute('UPDATE products SET name=?,price=?,active=? WHERE id=?',
                                (body.get('name', '테스트 상품'), str(body.get('price', '10000.00')), int(body.get('active', True)), PRODUCT))
        elif action == 'company_hub':
            with self.db:
                self.db.execute('UPDATE companies SET hub_id=? WHERE id=?', (str(UUID(body['hub_id'])), body['company_id']))
        else:
            raise StubError(400, 'INVALID_REQUEST')
        return {'ok': True}

    def call(self, operation, body, identifier):
        if operation == 'company.get':
            row = self.row('SELECT * FROM companies WHERE id=? AND active=1', (identifier,))
            if not row: raise StubError(404, 'COMPANY_NOT_FOUND')
            return 200, {'id': row['id'], 'hub_id': row['hub_id']}
        if operation == 'product.get':
            row = self.row('SELECT * FROM products WHERE id=? AND active=1', (identifier,))
            if not row: raise StubError(404, 'PRODUCT_NOT_FOUND')
            return 200, {**{key: row[key] for key in ('id', 'company_id', 'name')}, 'price': float(row['price'])}
        if operation == 'stock.deduct':
            UUID(body['order_id'])  # Correlation only; no per-order deduction history.
            hub_id, product_id = (str(UUID(body[key])) for key in ('hub_id', 'product_id'))
            quantity = body['quantity']
            if type(quantity) is not int or not 0 < quantity <= 2**63 - 1: raise StubError(400, 'INVALID_REQUEST')
            stock = self.row('SELECT * FROM stocks WHERE hub_id=? AND product_id=?', (hub_id, product_id))
            if not stock: raise StubError(404, 'STOCK_NOT_FOUND')
            with self.db:
                changed = self.db.execute('UPDATE stocks SET quantity=quantity-? WHERE id=? AND quantity>=?',
                                          (quantity, stock['id'], quantity)).rowcount
                if not changed: raise StubError(409, 'INSUFFICIENT_STOCK')
            return 200, self.row('SELECT * FROM stocks WHERE id=?', (stock['id'],))
        if operation == 'delivery.get':
            row = self.row('SELECT * FROM deliveries WHERE order_id=? AND deleted=0', (identifier,))
            if not row: raise StubError(404, 'DELIVERY_NOT_FOUND')
            return 200, self.delivery(row)
        if operation == 'delivery.create':
            order_id = str(UUID(body['order_id']))
            fields = ('departure_hub_id', 'destination_hub_id', 'address', 'recipient_name')
            for key in ('departure_hub_id', 'destination_hub_id'): UUID(body[key])
            if not body['address'].strip() or len(body['address']) > 255 or not body['recipient_name'].strip() or len(body['recipient_name']) > 100:
                raise StubError(400, 'INVALID_REQUEST')
            row = self.row('SELECT * FROM deliveries WHERE order_id=?', (order_id,))
            if row:
                if any(row[key] != body[key] for key in fields): raise StubError(409, 'REQUEST_CONFLICT')
                if row['deleted']: raise StubError(409, 'INVALID_STATE_TRANSITION')
                return 200, self.delivery(row)
            with self.db:
                self.db.execute('INSERT INTO deliveries VALUES(?,?,?,?,?,?,?,0)',
                                (str(uuid4()), order_id, *(body[key] for key in fields), 'WAITING'))
            return 201, self.delivery(self.row('SELECT * FROM deliveries WHERE order_id=?', (order_id,)))
        if operation in ('delivery.cancel', 'delivery.delete', 'delivery.depart'):
            row = self.row('SELECT * FROM deliveries WHERE delivery_id=?', (identifier,))
            if operation == 'delivery.depart':
                if not row or row['deleted']: raise StubError(404, 'DELIVERY_NOT_FOUND')
                if body.get('status') != 'HUB_TRANSIT' or row['status'] != 'WAITING':
                    raise StubError(409, 'INVALID_STATE_TRANSITION')
                with self.db: self.db.execute("UPDATE deliveries SET status='HUB_TRANSIT' WHERE delivery_id=? AND status='WAITING'", (identifier,))
                return 200, self.delivery(self.row('SELECT * FROM deliveries WHERE delivery_id=?', (identifier,)))
            if operation == 'delivery.delete':
                if not row or row['deleted']: return 204, None
                if row['status'] not in ('CANCELLED', 'COMPLETED'): raise StubError(409, 'INVALID_STATE_TRANSITION')
                with self.db: self.db.execute('UPDATE deliveries SET deleted=1 WHERE delivery_id=?', (identifier,))
                return 204, None
            if not row or row['deleted']: raise StubError(404, 'DELIVERY_NOT_FOUND')
            if row['status'] not in ('WAITING', 'CANCELLED'): raise StubError(409, 'DELIVERY_ALREADY_STARTED')
            with self.db: self.db.execute("UPDATE deliveries SET status='CANCELLED' WHERE delivery_id=?", (identifier,))
            return 200, self.delivery(self.row('SELECT * FROM deliveries WHERE delivery_id=?', (identifier,)))
        if operation == 'slack':
            with self.db: self.db.execute('INSERT INTO notifications(payload) VALUES(?)', (json.dumps(body),))
            return 200, {'ok': True}
        raise StubError(404, 'NOT_FOUND')


def serve(port, filename, service_token, jwt_secret):
    if len(service_token) < 16: raise ValueError('ORDERS_SERVICE_TOKEN requires at least 16 characters')
    state = State(filename)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def respond(self, status, body):
            raw = json.dumps(body, ensure_ascii=False).encode() if body is not None else b''
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.send_header('Connection', 'close')
            self.end_headers()
            try: self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError): pass
        def do_GET(self): self.handle_request()
        def do_POST(self): self.handle_request()
        def do_PATCH(self): self.handle_request()
        def do_DELETE(self): self.handle_request()
        def handle_request(self):
            try:
                length = int(self.headers.get('Content-Length', 0))
                if not 0 <= length <= 1048576: raise StubError(400, 'INVALID_REQUEST')
                body = json.loads(self.rfile.read(length)) if length else {}
                path = self.path.split('?')[0]
                if path == '/actuator/health': self.respond(200, {'status': 'UP'}); return
                if path != '/__stub/slack' and self.headers.get('X-Service-Token') != service_token:
                    raise StubError(401, 'UNAUTHORIZED')
                if path == '/__stub/token':
                    self.respond(200, {'data': {'access_token': token(body['username'], body.get('role', 'USER'), jwt_secret, body.get('lifetime', 900))}}); return
                with state.lock:
                    if path == '/__stub/control': self.respond(200, {'data': state.control(body)}); return
                    if path == '/__stub/state':
                        self.respond(200, {'data': {**{table: [dict(row) for row in state.db.execute('SELECT * FROM ' + table)]
                            for table in ('stocks', 'deliveries', 'notifications')}, 'counts': state.counts, 'actors': state.actors, 'inflight': state.inflight}}); return
                if path.startswith('/internal/') and not self.headers.get('X-Actor-Username', '').strip():
                    raise StubError(401, 'UNAUTHORIZED')
                operation, identifier = None, None
                if self.command == 'GET' and path.startswith('/internal/companies/'): operation, identifier = 'company.get', path.rsplit('/', 1)[1]
                elif self.command == 'GET' and path.startswith('/internal/products/'): operation, identifier = 'product.get', path.rsplit('/', 1)[1]
                elif self.command == 'POST' and path == '/internal/stocks/deductions': operation = 'stock.deduct'
                elif self.command == 'GET' and path.startswith('/internal/deliveries/by-order/'): operation, identifier = 'delivery.get', path.rsplit('/', 1)[1]
                elif self.command == 'POST' and path == '/internal/deliveries': operation = 'delivery.create'
                elif self.command == 'POST' and path.startswith('/internal/deliveries/') and path.endswith('/cancel'): operation, identifier = 'delivery.cancel', path.split('/')[-2]
                elif self.command == 'DELETE' and path.startswith('/internal/deliveries/'): operation, identifier = 'delivery.delete', path.rsplit('/', 1)[1]
                elif self.command == 'PATCH' and path.startswith('/api/deliveries/'): operation, identifier = 'delivery.depart', path.rsplit('/', 1)[1]
                elif self.command == 'POST' and path == '/__stub/slack': operation = 'slack'
                if operation is None: raise StubError(404, 'NOT_FOUND')
                with state.lock:
                    state.counts[operation] = state.counts.get(operation, 0) + 1
                    state.actors[operation] = self.headers.get('X-Actor-Username')
                    rule = dict(state.rules.get(operation, {}))
                    if rule.get('remaining', 1) == 0: rule = {}
                    elif operation in state.rules: state.rules[operation]['remaining'] = rule.get('remaining', 1) - 1
                with state.lock: state.inflight += 1
                try:
                    if rule.get('delay_before_ms'): time.sleep(rule['delay_before_ms'] / 1000)
                    if rule.get('status'): raise StubError(rule['status'], rule.get('code', 'UPSTREAM_UNAVAILABLE'))
                    with state.lock: status, result = state.call(operation, body, identifier)
                    if rule.get('delay_after_ms'): time.sleep(rule['delay_after_ms'] / 1000)
                    payload = rule['response'] if 'response' in rule else ({'data': result} if result is not None else None)
                    self.respond(status, payload)
                finally:
                    with state.lock: state.inflight -= 1
            except StubError as error:
                self.respond(error.status, {'code': error.code, 'message': 'Stub request failed', 'trace_id': None, 'field_errors': []})
            except (ValueError, KeyError, TypeError, AttributeError):
                self.respond(400, {'code': 'INVALID_REQUEST', 'message': 'Invalid stub request', 'trace_id': None, 'field_errors': []})

    class StubServer(ThreadingHTTPServer):
        request_queue_size = 128

    server = StubServer(('127.0.0.1', port), Handler)
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())
    print(json.dumps({'port': server.server_address[1], 'pid': os.getpid()}), flush=True)
    try: server.serve_forever(poll_interval=0.1)
    except KeyboardInterrupt: pass
    finally: server.server_close(); state.db.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18090)
    parser.add_argument('--state', default=str(Path(__file__).with_name('state.sqlite')))
    parser.add_argument('--token', metavar='USERNAME')
    parser.add_argument('--role', choices=('USER', 'MASTER'), default='USER')
    args = parser.parse_args()
    secret = os.environ.get('JWT_SECRET', '')
    if args.token: print(token(args.token, args.role, secret))
    else: serve(args.port, args.state, os.environ.get('ORDERS_SERVICE_TOKEN', ''), secret)
