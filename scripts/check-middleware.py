"""Isolated Redis/AMQP smoke check through an existing SSH tunnel.

Requires pika==1.3.2. Credentials are read over SSH into memory, never saved.
--restart deliberately restarts both development containers; no production use.
"""
import argparse
import base64
import datetime
import json
import pathlib
import socket
import subprocess
import time
import urllib.parse
import urllib.request
import uuid

import pika


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True)
    parser.add_argument('--key', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--restart', action='store_true')
    args = parser.parse_args()
    ssh = ['ssh', '-i', str(args.key), '-o', 'BatchMode=yes',
           '-o', 'StrictHostKeyChecking=yes', '-o', 'HostKeyAlias=118.178.253.75',
           '-o', 'ConnectTimeout=8', 'root@' + args.host]

    def remote(command):
        result = subprocess.run(ssh + [command], capture_output=True, text=True,
                                timeout=90)
        if result.returncode:
            raise RuntimeError('SSH operation failed; output suppressed')
        return result.stdout

    secrets = dict(line.split('=', 1) for line in
                   remote('cat /opt/ticketflow/.env').splitlines() if '=' in line)
    auth = base64.b64encode(('tf_admin:' + secrets['RABBITMQ_PASSWORD']).encode()).decode()

    def api(method, path, data=None):
        body = None if data is None else json.dumps(data).encode()
        request = urllib.request.Request('http://127.0.0.1:15672/api/' + path,
                                         data=body, method=method,
                                         headers={'Authorization': 'Basic ' + auth,
                                                  'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=10) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    def redis(*commands):
        with socket.create_connection(('127.0.0.1', 16379), timeout=10) as connection:
            reader = connection.makefile('rb')

            def send(parts):
                encoded = [str(part).encode() for part in parts]
                packet = b'*%d\r\n' % len(encoded)
                packet += b''.join(b'$%d\r\n' % len(part) + part + b'\r\n' for part in encoded)
                connection.sendall(packet)
                line = reader.readline()
                if line.startswith(b'$'):
                    length = int(line[1:])
                    if length == -1:
                        return None
                    result = reader.read(length)
                    assert reader.read(2) == b'\r\n'
                    return result.decode()
                if line.startswith(b'-'):
                    raise RuntimeError('Redis command failed; output suppressed')
                if not line:
                    raise RuntimeError('Redis connection closed')
                return line[1:-2].decode()

            assert send(['AUTH', secrets['REDIS_PASSWORD']]) == 'OK'
            return [send(command) for command in commands]

    suffix = uuid.uuid4().hex
    vhost = '/ticketflow-probe-' + suffix
    vpath = urllib.parse.quote(vhost, safe='')
    redis_key = 'tf:probe:' + suffix
    queue = 'probe.durable'
    payload = ('probe-' + suffix).encode()
    result = {'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'scope': 'middleware smoke; not Java/business integration or capacity test',
              'restartRequested': args.restart, 'checks': {}}
    created = False
    connections = []

    def connect():
        connection = pika.BlockingConnection(pika.ConnectionParameters(
            host='127.0.0.1', port=15673, virtual_host=vhost,
            credentials=pika.PlainCredentials('tf_admin', secrets['RABBITMQ_PASSWORD']),
            heartbeat=30, socket_timeout=10, stack_timeout=15,
            blocked_connection_timeout=10, connection_attempts=1))
        connections.append(connection)
        return connection, connection.channel()

    try:
        result['rabbitmqVersion'] = api('GET', 'overview')['rabbitmq_version']
        assert redis(['PING'], ['SET', redis_key, suffix, 'EX', 600]) == ['PONG', 'OK']
        result['checks']['redis_authenticated_write'] = True
        api('PUT', 'vhosts/' + vpath, {})
        created = True
        api('PUT', 'permissions/' + vpath + '/tf_admin',
            {'configure': '.*', 'write': '.*', 'read': '.*'})
        connection, channel = connect()
        channel.queue_declare(queue=queue, durable=True,
                              arguments={'x-queue-type': 'quorum'})
        channel.confirm_delivery()
        channel.basic_publish(exchange='', routing_key=queue, body=payload,
                              mandatory=True, properties=pika.BasicProperties(
                                  delivery_mode=2, message_id=suffix))
        result['checks']['amqp_authenticated_confirmed_publish'] = True
        try:
            channel.basic_publish(exchange='', routing_key='probe.missing',
                                  body=payload, mandatory=True)
            raise AssertionError('Expected mandatory unroutable rejection')
        except pika.exceptions.UnroutableError:
            result['checks']['mandatory_unroutable_detected'] = True
        method, properties, body = channel.basic_get(queue, auto_ack=False)
        assert method and body == payload and properties.message_id == suffix
        connection.close()  # Unacknowledged delivery must return to the queue.
        connection, channel = connect()
        deadline = time.monotonic() + 15
        while True:
            method, properties, body = channel.basic_get(queue, auto_ack=False)
            if method:
                break
            assert time.monotonic() < deadline, 'Redelivery timed out'
            time.sleep(0.2)
        assert method.redelivered and body == payload
        result['checks']['unacked_redelivery'] = True
        channel.basic_ack(method.delivery_tag)
        channel.queue_declare(queue=queue, passive=True)  # Barrier after ack.
        assert channel.basic_get(queue, auto_ack=False)[0] is None
        result['checks']['manual_ack_removes_message'] = True
        channel.confirm_delivery()
        channel.basic_publish(exchange='', routing_key=queue, body=payload,
                              mandatory=True, properties=pika.BasicProperties(delivery_mode=2))
        connection.close()
        if args.restart:
            time.sleep(2)  # Redis AOF everysec; this is a graceful restart check.
            remote('cd /opt/ticketflow && docker compose restart redis rabbitmq')
            deadline = time.monotonic() + 90
            while True:
                try:
                    api('GET', 'overview')
                    assert redis(['GET', redis_key]) == [suffix]
                    break
                except (OSError, AssertionError):
                    if time.monotonic() >= deadline:
                        raise RuntimeError('Services did not recover in 90 seconds')
                    time.sleep(2)
            connection, channel = connect()
            method, _, body = channel.basic_get(queue, auto_ack=False)
            assert method and body == payload
            channel.basic_ack(method.delivery_tag)
            channel.queue_declare(queue=queue, passive=True)
            result['checks']['redis_graceful_restart_persistence'] = True
            result['checks']['rabbitmq_graceful_restart_persistence'] = True
        result['passed'] = True
    finally:
        cleanup_errors = []
        for connection in connections:
            if connection.is_open:
                try:
                    connection.close()
                except Exception:
                    cleanup_errors.append('AMQP close')
        try:
            redis(['DEL', redis_key])
        except Exception:
            cleanup_errors.append('Redis probe key')
        if created:
            try:
                api('DELETE', 'vhosts/' + vpath)
            except Exception:
                cleanup_errors.append('Probe vhost')
        result['cleanupErrors'] = cleanup_errors
        result['finishedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        result['passed'] = result.get('passed', False) and not cleanup_errors
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(result, indent=2))
    assert result['passed'], 'Middleware verification failed'


if __name__ == '__main__':
    main()
