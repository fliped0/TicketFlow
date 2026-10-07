-- Batch 9: asynchronous admission and transactional outbox.
ALTER TABLE tf_session ADD COLUMN purchase_mode VARCHAR(8) NOT NULL DEFAULT 'SYNC',
  ADD CONSTRAINT ck_session_purchase_mode CHECK (purchase_mode IN ('SYNC','ASYNC'));

CREATE TABLE tf_async_gate (
  session_id BIGINT PRIMARY KEY,
  epoch BIGINT NOT NULL DEFAULT 1,
  phase VARCHAR(16) NOT NULL DEFAULT 'PAUSED',
  maintenance_version BIGINT NOT NULL DEFAULT 0,
  maintenance_owner VARCHAR(64),
  updated_at DATETIME(6) NOT NULL,
  FOREIGN KEY (session_id) REFERENCES tf_session(id),
  CHECK (epoch>0 AND maintenance_version>=0),
  CHECK (phase IN ('PAUSED','REBUILDING','READY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO tf_async_gate(session_id,epoch,phase,updated_at)
  SELECT id,1,'PAUSED',UTC_TIMESTAMP(6) FROM tf_session;

CREATE TABLE tf_async_request (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  user_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  tier_id BIGINT NOT NULL,
  quantity INT NOT NULL DEFAULT 1,
  request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
  epoch BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  accepted_at DATETIME(6),
  create_deadline DATETIME(6),
  order_id BIGINT,
  result_code VARCHAR(64),
  http_status INT,
  result_json JSON,
  work_version BIGINT NOT NULL DEFAULT 0,
  lease_owner VARCHAR(64),
  lease_until DATETIME(6),
  attempt_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(6),
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  completed_at DATETIME(6),
  UNIQUE KEY uk_async_key(user_id,request_key),
  UNIQUE KEY uk_async_token(token),
  UNIQUE KEY uk_async_order(order_id),
  UNIQUE KEY uk_async_owner(id,user_id,session_id),
  KEY idx_async_retry(state,next_retry_at,id),
  KEY idx_async_deadline(state,create_deadline,id),
  FOREIGN KEY (user_id) REFERENCES tf_user(id),
  FOREIGN KEY (tier_id,session_id) REFERENCES tf_tier(id,session_id),
  FOREIGN KEY (order_id,user_id,session_id) REFERENCES tf_order(id,user_id,session_id),
  CHECK (quantity=1 AND epoch>0 AND work_version>=0 AND attempt_count>=0),
  CHECK (state IN ('ACCEPTED','PROCESSING','RETRY_WAIT','SUCCEEDED','REJECTED')),
  CHECK ((state IN ('ACCEPTED','PROCESSING','RETRY_WAIT') AND order_id IS NULL
      AND token IS NOT NULL AND accepted_at IS NOT NULL AND create_deadline IS NOT NULL
      AND accepted_at<create_deadline AND completed_at IS NULL)
    OR (state='SUCCEEDED' AND order_id IS NOT NULL AND completed_at IS NOT NULL
      AND token IS NOT NULL AND accepted_at IS NOT NULL AND create_deadline IS NOT NULL
      AND result_code IS NOT NULL AND http_status IS NOT NULL AND result_json IS NOT NULL)
    OR (state='REJECTED' AND order_id IS NULL AND completed_at IS NOT NULL
      AND result_code IS NOT NULL AND http_status IS NOT NULL AND result_json IS NOT NULL)),
  CHECK (state<>'PROCESSING' OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
  CHECK (state<>'RETRY_WAIT' OR next_retry_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tf_async_slot (
  user_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY(user_id,session_id),
  UNIQUE KEY uk_async_slot_request(request_id),
  FOREIGN KEY (request_id,user_id,session_id) REFERENCES tf_async_request(id,user_id,session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tf_async_tier_balance (
  tier_id BIGINT PRIMARY KEY,
  queued_count INT NOT NULL DEFAULT 0,
  projection_version BIGINT NOT NULL DEFAULT 0,
  FOREIGN KEY (tier_id) REFERENCES tf_stock(tier_id),
  CHECK (queued_count>=0 AND projection_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO tf_async_tier_balance(tier_id) SELECT tier_id FROM tf_stock;

CREATE TABLE tf_outbox (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  destination VARCHAR(8) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  aggregate_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  session_id BIGINT NOT NULL,
  tier_id BIGINT NOT NULL,
  epoch BIGINT NOT NULL,
  projection_seq BIGINT,
  payload JSON NOT NULL,
  state VARCHAR(8) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  claim_version BIGINT NOT NULL DEFAULT 0,
  lease_owner VARCHAR(64),
  lease_until DATETIME(6),
  created_at DATETIME(6) NOT NULL,
  sent_at DATETIME(6),
  UNIQUE KEY uk_outbox_projection(tier_id,projection_seq),
  KEY idx_outbox_dispatch(destination,state,next_attempt_at,id),
  FOREIGN KEY (tier_id,session_id) REFERENCES tf_tier(id,session_id),
  CHECK (destination IN ('BROKER','REDIS')),
  CHECK (state IN ('PENDING','SENDING','SENT')),
  CHECK (epoch>0 AND attempt_count>=0 AND claim_version>=0),
  CHECK ((destination='BROKER' AND projection_seq IS NULL AND event_type='CREATE_ORDER')
     OR (destination='REDIS' AND projection_seq IS NOT NULL AND projection_seq>0
       AND event_type IN ('ACTIVATE','MATERIALIZE','RELEASE'))),
  CHECK (state<>'SENDING' OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
  CHECK (state<>'SENT' OR sent_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Cross-table B<=available and combined slot uniqueness require the documented
-- gate/user/request/stock lock protocol and reconciliation, not just CHECK.
-- No DROP, no sample credentials, no automatic terminal-request cleanup.
