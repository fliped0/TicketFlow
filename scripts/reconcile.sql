SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
SELECT DATABASE() AS database_name, UTC_TIMESTAMP(6) AS observed_at, @tf_reconcile_tier AS tier_scope;
WITH scoped_orders AS (
  SELECT * FROM tf_order WHERE @tf_reconcile_tier IS NULL OR tier_id=@tf_reconcile_tier
), scoped_users AS (
  SELECT user_id FROM scoped_orders
  UNION
  SELECT id FROM tf_user WHERE @tf_reconcile_tier IS NULL
), ledger AS (
  SELECT o.id, o.status,
    SUM(l.movement='RESERVE') reserves, SUM(l.movement='PAY') pays,
    SUM(l.movement='RELEASE') releases, SUM(l.movement='REFUND') refunds
  FROM scoped_orders o LEFT JOIN tf_stock_log l ON l.order_id=o.id GROUP BY o.id,o.status
)
SELECT 'STOCK_BALANCE' category, CAST(s.tier_id AS CHAR) resource_id, 'capacity/reserved/sold differs from orders' detail
FROM tf_stock s WHERE (@tf_reconcile_tier IS NULL OR s.tier_id=@tf_reconcile_tier)
 AND (s.capacity<>s.available+s.reserved+s.sold OR s.available<0 OR s.reserved<0 OR s.sold<0
   OR s.reserved<>(SELECT COUNT(*) FROM scoped_orders o WHERE o.tier_id=s.tier_id AND o.status='PENDING')
   OR s.sold<>(SELECT COUNT(*) FROM scoped_orders o WHERE o.tier_id=s.tier_id AND o.status='PAID'))
UNION ALL
SELECT 'MISSING_STOCK',CAST(t.id AS CHAR),'tier has no stock row' FROM tf_tier t LEFT JOIN tf_stock s ON s.tier_id=t.id
 WHERE (@tf_reconcile_tier IS NULL OR t.id=@tf_reconcile_tier) AND s.tier_id IS NULL
UNION ALL
SELECT 'PURCHASE_SLOT',CAST(o.id AS CHAR),'qualification presence or ownership differs from status'
FROM scoped_orders o LEFT JOIN tf_purchase_slot s ON s.order_id=o.id
WHERE (o.status IN ('PENDING','PAID') AND (s.order_id IS NULL OR s.user_id<>o.user_id OR s.session_id<>o.session_id))
 OR (o.status NOT IN ('PENDING','PAID') AND s.order_id IS NOT NULL)
UNION ALL
SELECT 'PAYMENT_REFUND',CAST(o.id AS CHAR),'successful records or amounts differ from status'
FROM scoped_orders o LEFT JOIN tf_payment p ON p.order_id=o.id LEFT JOIN tf_refund r ON r.order_id=o.id
WHERE (o.status IN ('PAID','REFUNDED') AND (p.id IS NULL OR p.amount_fen<>o.amount_fen))
 OR (o.status NOT IN ('PAID','REFUNDED') AND p.id IS NOT NULL)
 OR (o.status='REFUNDED' AND (r.id IS NULL OR r.amount_fen<>o.amount_fen))
 OR (o.status<>'REFUNDED' AND r.id IS NOT NULL)
UNION ALL
SELECT 'STOCK_LOG',CAST(id AS CHAR),'reserve/pay/release/refund movements differ from status' FROM ledger
WHERE COALESCE(reserves,0)<>1 OR COALESCE(pays,0)<>IF(status IN ('PAID','REFUNDED'),1,0)
 OR COALESCE(releases,0)<>IF(status IN ('CANCELLED','CLOSED'),1,0) OR COALESCE(refunds,0)<>IF(status='REFUNDED',1,0)
UNION ALL
SELECT 'ORDER_SNAPSHOT',CAST(id AS CHAR),'snapshot amount, quantity, ownership or deadline mismatch' FROM scoped_orders
WHERE COALESCE(JSON_UNQUOTE(JSON_EXTRACT(snapshot,'$.tierId')),'')<>CAST(tier_id AS CHAR)
 OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(snapshot,'$.sessionId')),'')<>CAST(session_id AS CHAR)
 OR COALESCE(JSON_EXTRACT(snapshot,'$.quantity'),0)<>quantity
 OR COALESCE(JSON_EXTRACT(snapshot,'$.unitPriceFen'),0)<>unit_price_fen
 OR COALESCE(JSON_EXTRACT(snapshot,'$.amountFen'),0)<>amount_fen
 OR amount_fen<>unit_price_fen OR quantity<>1 OR created_at>=expires_at OR expires_at>starts_at
UNION ALL
SELECT 'REQUEST_TERMINAL',CAST(q.id AS CHAR),'request is not a valid committed terminal result' FROM scoped_users u
JOIN tf_request q ON q.user_id=u.user_id
LEFT JOIN tf_order o ON o.id=q.order_id
WHERE (q.state='PROCESSING' OR (q.state='REJECTED' AND q.order_id IS NOT NULL)
  OR (q.state='SUCCEEDED' AND (o.id IS NULL OR o.user_id<>q.user_id OR q.result_code<>'OK'
    OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(q.result_json,'$.data.orderId')),'')<>CAST(q.order_id AS CHAR))))
ORDER BY category,resource_id;
COMMIT;
