-- Durable local alert inbox and idempotent, operator-triggered DLQ replay receipts.
CREATE TABLE tf_async_alert (
  alert_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  category VARCHAR(32) NOT NULL,
  resource_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  detail VARCHAR(128) NOT NULL,
  occurrences BIGINT NOT NULL DEFAULT 1,
  first_seen DATETIME(6) NOT NULL,
  last_seen DATETIME(6) NOT NULL,
  resolved_at DATETIME(6),
  KEY idx_async_alert_open(resolved_at,last_seen),
  CHECK (occurrences>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tf_async_dead_replay (
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  replayed_at DATETIME(6) NOT NULL,
  FOREIGN KEY (event_id) REFERENCES tf_outbox(id),
  FOREIGN KEY (request_id) REFERENCES tf_async_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
