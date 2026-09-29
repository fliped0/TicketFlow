CREATE TABLE tf_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'USER',
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_user_name (username),
  CHECK (role IN ('USER','ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tf_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  description TEXT NOT NULL,
  category VARCHAR(32) NOT NULL,
  city VARCHAR(64) NOT NULL,
  venue VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  KEY idx_event_list (status, city, category, id),
  CHECK (status IN ('DRAFT','ON_SALE','OFF_SALE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tf_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  starts_at DATETIME(6) NOT NULL,
  sale_start_at DATETIME(6) NOT NULL,
  sale_end_at DATETIME(6) NOT NULL,
  freeze_at DATETIME(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  KEY idx_session_event (event_id, id),
  FOREIGN KEY (event_id) REFERENCES tf_event(id),
  CHECK (sale_start_at < sale_end_at AND sale_end_at <= starts_at),
  CHECK (freeze_at <= sale_start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tf_tier (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  price_fen BIGINT NOT NULL,
  refund_policy VARCHAR(32) NOT NULL DEFAULT 'FULL_BEFORE_START_V1',
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_tier_name (session_id, name),
  UNIQUE KEY uk_tier_session (id, session_id),
  FOREIGN KEY (session_id) REFERENCES tf_session(id),
  CHECK (price_fen BETWEEN 1 AND 100000000),
  CHECK (refund_policy = 'FULL_BEFORE_START_V1')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tf_stock (
  tier_id BIGINT PRIMARY KEY,
  capacity INT NOT NULL,
  available INT NOT NULL,
  reserved INT NOT NULL DEFAULT 0,
  sold INT NOT NULL DEFAULT 0,
  updated_at DATETIME(6) NOT NULL,
  FOREIGN KEY (tier_id) REFERENCES tf_tier(id),
  CHECK (capacity BETWEEN 0 AND 1000000),
  CHECK (available >= 0 AND reserved >= 0 AND sold >= 0),
  CHECK (capacity = available + reserved + sold)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tf_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  tier_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  quantity INT NOT NULL DEFAULT 1,
  unit_price_fen BIGINT NOT NULL,
  amount_fen BIGINT NOT NULL,
  snapshot JSON NOT NULL,
  starts_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_order_owner_session (id, user_id, session_id),
  KEY idx_order_user (user_id, id),
  KEY idx_order_expiry (status, expires_at, id),
  KEY idx_order_session (session_id, status, id),
  KEY idx_order_created (created_at, id),
  FOREIGN KEY (user_id) REFERENCES tf_user(id),
  FOREIGN KEY (tier_id, session_id) REFERENCES tf_tier(id, session_id),
  CHECK (status IN ('PENDING','PAID','CANCELLED','CLOSED','REFUNDED')),
  CHECK (quantity = 1 AND unit_price_fen BETWEEN 1 AND 100000000),
  CHECK (amount_fen = unit_price_fen),
  CHECK (created_at < expires_at AND expires_at <= starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tf_purchase_slot (
  user_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, session_id),
  UNIQUE KEY uk_slot_order (order_id),
  FOREIGN KEY (order_id, user_id, session_id)
    REFERENCES tf_order(id, user_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tf_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  operation VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(16) NOT NULL,
  http_status SMALLINT NULL,
  result_code VARCHAR(64) NULL,
  result_json JSON NULL,
  order_id BIGINT NULL,
  created_at DATETIME(6) NOT NULL,
  completed_at DATETIME(6) NULL,
  UNIQUE KEY uk_request (user_id, operation, request_key),
  FOREIGN KEY (user_id) REFERENCES tf_user(id),
  FOREIGN KEY (order_id) REFERENCES tf_order(id),
  CHECK (operation IN ('CREATE','PAY','CANCEL','REFUND')),
  CHECK (state IN ('PROCESSING','SUCCEEDED','REJECTED')),
  CHECK ((state = 'PROCESSING' AND completed_at IS NULL)
    OR (state IN ('SUCCEEDED','REJECTED') AND completed_at IS NOT NULL
      AND http_status IS NOT NULL AND result_code IS NOT NULL
      AND result_json IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tf_payment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  amount_fen BIGINT NOT NULL,
  paid_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_payment_order (order_id),
  KEY idx_payment_time (paid_at, id),
  FOREIGN KEY (order_id) REFERENCES tf_order(id),
  CHECK (amount_fen BETWEEN 1 AND 100000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tf_refund (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  amount_fen BIGINT NOT NULL,
  refunded_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_refund_order (order_id),
  KEY idx_refund_time (refunded_at, id),
  FOREIGN KEY (order_id) REFERENCES tf_payment(order_id),
  CHECK (amount_fen BETWEEN 1 AND 100000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tf_stock_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  movement VARCHAR(16) NOT NULL,
  delta_available INT NOT NULL,
  delta_reserved INT NOT NULL,
  delta_sold INT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_stock_movement (order_id, movement),
  FOREIGN KEY (order_id) REFERENCES tf_order(id),
  CHECK ((movement = 'RESERVE' AND delta_available = -1
      AND delta_reserved = 1 AND delta_sold = 0)
    OR (movement = 'PAY' AND delta_available = 0
      AND delta_reserved = -1 AND delta_sold = 1)
    OR (movement = 'RELEASE' AND delta_available = 1
      AND delta_reserved = -1 AND delta_sold = 0)
    OR (movement = 'REFUND' AND delta_available = 1
      AND delta_reserved = 0 AND delta_sold = -1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tf_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  actor_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  object_type VARCHAR(16) NOT NULL,
  object_id BIGINT NOT NULL,
  before_json JSON NULL,
  after_json JSON NOT NULL,
  trace_id VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  KEY idx_audit_object (object_type, object_id, id),
  FOREIGN KEY (actor_id) REFERENCES tf_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

