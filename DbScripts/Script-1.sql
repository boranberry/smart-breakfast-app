-- 1. Enable MySQL Event Scheduler
SET GLOBAL event_scheduler = ON;

-- 2. Create an event that runs automatically on the 1st of every month
DELIMITER //

CREATE EVENT IF NOT EXISTS monthly_rooms_reset
ON SCHEDULE EVERY 1 MONTH
STARTS '2026-10-01 00:00:00'
DO
BEGIN
    -- Temporarily disable foreign key checks for truncation
    SET FOREIGN_KEY_CHECKS = 0;
    
    -- Truncate tables
    TRUNCATE TABLE orders;
    TRUNCATE TABLE rooms;
    
    -- Re-enable foreign key checks
    SET FOREIGN_KEY_CHECKS = 1;
END //

DELIMITER ;