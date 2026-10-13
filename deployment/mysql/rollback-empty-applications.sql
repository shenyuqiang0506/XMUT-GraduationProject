-- 仅供人工审查后在已停机、已备份的空 V3 环境回退；本次不执行。
-- 有任何申请、占用或事件时拒绝回退，保留历史应使用前向修复/独立恢复库。
DELIMITER //
CREATE PROCEDURE rollback_empty_applications()
BEGIN
 IF (SELECT COUNT(*) FROM activity_application)>0 OR (SELECT COUNT(*) FROM resource_occupancy)>0
    OR (SELECT COUNT(*) FROM outbox_event)>0 OR (SELECT COUNT(*) FROM applicant_guard)>0 THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Application schema is not empty; rollback refused';
 END IF;
 DROP TABLE approval_record;
 DROP TABLE resource_occupancy;
 DROP TABLE application_resource;
 DROP TABLE activity_application;
 DROP TABLE outbox_event;
 DROP TABLE applicant_guard;
 DROP TABLE booking_write_guard;
 DELETE FROM flyway_schema_history WHERE version='3' AND script='V3__applications_and_platform_occupancy.sql';
END//
DELIMITER ;
CALL rollback_empty_applications();
DROP PROCEDURE rollback_empty_applications;
