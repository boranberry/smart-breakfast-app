-- 1. Delete associated orders first from live_orders
DELETE FROM live_orders
WHERE room_id IN (
    SELECT id FROM rooms
    WHERE (status = 'APPROVED_AND_CLOSED' AND approved_at IS NOT NULL AND approved_at <= NOW() - INTERVAL 24 HOUR)
       OR (status IN ('CLOSED', 'PENDING_APPROVAL') AND approved_at IS NULL AND created_at <= NOW() - INTERVAL 4 HOUR)
);

-- 2. Delete approved rooms after 24 hours of approval
DELETE FROM rooms
WHERE status = 'APPROVED_AND_CLOSED'
  AND approved_at IS NOT NULL
  AND approved_at <= NOW() - INTERVAL 24 HOUR;

-- 3. Delete unapproved closed or pending rooms created more than 4 hours ago
DELETE FROM rooms
WHERE status IN ('CLOSED', 'PENDING_APPROVAL')
  AND approved_at IS NULL
  AND created_at <= NOW() - INTERVAL 4 HOUR;