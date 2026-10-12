"""Local API-only catalogue preparation; credentials arrive on stdin, never arguments."""
import argparse
import json
import os
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler
from contextlib import contextmanager

ROOT = Path(__file__).resolve().parents[1]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Demo:
    def __init__(self, base, directory, credential):
        parsed = urlsplit(base)
        if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or not parsed.port or parsed.path or parsed.query or parsed.fragment or parsed.username:
            raise RuntimeError("Only an explicit http://127.0.0.1:PORT origin is allowed")
        self.base = base
        self.path = (ROOT / directory).resolve()
        if not self.path.is_relative_to((ROOT / '.tools').resolve()):
            raise RuntimeError("Manifest must stay within the ignored .tools directory")
        self.path.mkdir(parents=True, exist_ok=True)
        self.file = self.path / 'manifest.json'
        self.http = build_opener(ProxyHandler({}), NoRedirect())
        self.token = self.call('POST', '/auth/login', credential)['accessToken']
        if self.call('GET', '/users/me')['role'] != 'ADMIN':
            raise RuntimeError('Existing administrator credentials are required')

    def call(self, method, path, body=None):
        headers = {'Accept': 'application/json'}
        if getattr(self, 'token', None):
            headers['Authorization'] = 'Bearer ' + self.token
        data = None
        if body is not None:
            headers['Content-Type'] = 'application/json'
            data = json.dumps(body, ensure_ascii=False).encode('utf-8')
        try:
            with self.http.open(Request(self.base + '/api/v1' + path, data=data, headers=headers, method=method), timeout=12) as response:
                payload = json.loads(response.read())
                if payload.get('code') != 'OK':
                    raise RuntimeError('API did not report success')
                return payload['data']
        except HTTPError as error:
            raise RuntimeError(f'API {method} {path.split("?")[0]} returned HTTP {error.code}') from None
        except (URLError, TimeoutError):
            raise RuntimeError('API result unknown; manifest preserved. Run again to reconcile before creating more data.') from None

    def save(self):
        temporary = self.file.with_suffix('.tmp')
        with temporary.open('w', encoding='utf-8') as handle:
            handle.write(json.dumps(self.state, ensure_ascii=False, indent=2))
            handle.flush()
            os.fsync(handle.fileno())
        temporary.replace(self.file)

    @contextmanager
    def lock(self):
        # OS-owned lock releases on process exit; no stale lock deletion is needed.
        with (self.path / 'manifest.lock').open('a+b') as handle:
            if handle.tell() == 0:
                handle.write(b'0')
                handle.flush()
            handle.seek(0)
            try:
                if os.name == 'nt':
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                raise RuntimeError('Another demo preparation owns this manifest; retry after it exits') from None
            try:
                yield
            finally:
                handle.seek(0)
                if os.name == 'nt':
                    msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    fcntl.flock(handle.fileno(), fcntl.LOCK_UN)

    def listing(self, path):
        result = []
        for page in range(1, 101):
            data = self.call('GET', path + ('&' if '?' in path else '?') + urlencode({'page': page, 'size': 100}))
            result.extend(data['items'])
            if page * 100 >= data['total']:
                return result
        raise RuntimeError('Catalogue reconciliation exceeded its bounded page limit')

    def ensure(self, item, field, kind, path, body, lookup, match):
        if field in item:
            return item[field]
        pending = self.state.get('pending')
        identity = f'{item["name"]}:{field}'
        if pending:
            if pending != identity:
                raise RuntimeError('Unresolved creation stage; refusing a different creation')
            matches = [row for row in self.listing(lookup) if match(row)]
            if len(matches) != 1:
                raise RuntimeError(f'Unknown {kind} creation cannot be reconciled uniquely; no POST retried')
            result = matches[0]
        else:
            self.state['pending'] = identity
            self.save()  # Durable intent before non-idempotent admin POST.
            result = self.call('POST', path, body)
        item[field] = result['id']
        self.state.pop('pending', None)
        self.save()
        return result['id']

    def prepare(self, new_batch=False):
        if self.file.exists():
            self.state = json.loads(self.file.read_text(encoding='utf-8'))
            if self.state.get('schemaVersion') != 1 or self.state.get('origin') != self.base:
                raise RuntimeError('Manifest version or API origin mismatch; use a separate manifest directory')
            if self.state.get('ready') and not new_batch:
                reusable = True
                for item in self.state['events']:
                    event = self.call('GET', '/admin/events/' + item['eventId'])
                    tiers = self.listing('/admin/sessions/' + item['sessionId'] + '/tiers')
                    reusable &= event['status'] == 'ON_SALE' and any(t['saleStatus'] == 'ON_SALE' and t['available'] > 0 for t in tiers)
                if reusable:
                    return self.state
                new_batch = True
            if new_batch:
                if self.state.get('pending') or not self.state.get('ready'):
                    raise RuntimeError('Unfinished batch must be reconciled before starting a new batch')
                history = self.path / 'history'
                history.mkdir(exist_ok=True)
                (history / (self.state['marker'] + '.json')).write_text(json.dumps(self.state, ensure_ascii=False, indent=2), encoding='utf-8')
        if not self.file.exists() or new_batch:
            marker = uuid.uuid4().hex[:12]
            now = datetime.now(timezone.utc)
            self.state = {'schemaVersion': 1, 'origin': self.base, 'marker': marker, 'ready': False, 'events': []}
            for title, category, city, venue in [('城市回声音乐会', '音乐', '上海', '城市音乐厅'), ('周末小剧场', '戏剧', '杭州', '河畔剧场'), ('光与时间展', '展览', '北京', '艺术空间')]:
                self.state['events'].append({'name': f'[演示] {title} · {marker}', 'category': category, 'city': city, 'venue': venue,
                    'description': '本机演示活动。支付和退款均为模拟，不发生真实资金交易。',
                    'times': {'startsAt': (now + timedelta(days=7)).isoformat(), 'saleStartAt': (now + timedelta(hours=1)).isoformat(), 'saleEndAt': (now + timedelta(days=7, hours=-1)).isoformat()}})
            self.save()
        for item in self.state['events']:
            event_id = self.ensure(item, 'eventId', 'event', '/admin/events', {k: item[k] for k in ('name', 'category', 'city', 'venue', 'description')}, '/admin/events?' + urlencode({'keyword': item['name']}), lambda row: row['name'] == item['name'])
            session_id = self.ensure(item, 'sessionId', 'session', f'/admin/events/{event_id}/sessions', item['times'], f'/admin/events/{event_id}/sessions', lambda row: datetime.fromisoformat(row['startsAt'].replace('Z', '+00:00')) == datetime.fromisoformat(item['times']['startsAt']))
            for key, title, price in [('standardTierId', '标准票', 9900), ('premiumTierId', '优选票', 19900)]:
                self.ensure(item, key, 'tier', f'/admin/sessions/{session_id}/tiers', {'name': title, 'priceFen': price, 'capacity': 20}, f'/admin/sessions/{session_id}/tiers', lambda row, title=title: row['name'] == title)
            # Configuration PUT and status PUT are reconciled from actual state before resending.
            session = next(row for row in self.listing(f'/admin/events/{event_id}/sessions') if row['id'] == session_id)
            if 'finalSaleStartAt' not in item:
                item['finalSaleStartAt'] = (datetime.now(timezone.utc) + timedelta(seconds=3)).isoformat()
                self.save()
            desired = datetime.fromisoformat(item['finalSaleStartAt'])
            actual = datetime.fromisoformat(session['saleStartAt'].replace('Z', '+00:00'))
            if actual != desired:
                if desired <= datetime.now(timezone.utc):
                    item['finalSaleStartAt'] = (datetime.now(timezone.utc) + timedelta(seconds=3)).isoformat()
                    self.save()
                self.call('PUT', f'/admin/sessions/{session_id}', {**item['times'], 'saleStartAt': item['finalSaleStartAt'], 'expectedVersion': session['version']})
            event = self.call('GET', f'/admin/events/{event_id}')
            if event['status'] != 'ON_SALE':
                self.call('PUT', f'/admin/events/{event_id}/status', {'status': 'ON_SALE', 'expectedVersion': event['version']})
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if all(any(t['saleStatus'] == 'ON_SALE' for t in self.listing('/sessions/' + item['sessionId'] + '/tiers')) for item in self.state['events']):
                self.state['ready'] = True
                self.save()
                return self.state
            time.sleep(.5)
        raise RuntimeError('Sale readiness timeout; keep the manifest and reconcile on the next run')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default='http://127.0.0.1:8080')
    parser.add_argument('--directory', default='.tools/frontend-demo')
    parser.add_argument('--new-batch', action='store_true')
    args = parser.parse_args()
    try:
        demo = Demo(args.base, args.directory, json.load(sys.stdin))
        with demo.lock():
            state = demo.prepare(args.new_batch)
        print(json.dumps({'ready': state['ready'], 'marker': state['marker'], 'events': [{'id': i['eventId'], 'name': i['name']} for i in state['events']]}, ensure_ascii=False))
    except Exception as error:
        print('Demo preparation failed: ' + (str(error) if isinstance(error, RuntimeError) else type(error).__name__), file=sys.stderr)
        sys.exit(1)
