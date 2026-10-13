-- 教师默认具有普通使用者身份；管理权限需额外授予 RESOURCE_ADMIN 并配置管理范围。
-- 使用增量迁移，保留已部署 V1 的校验和及现有账户。
INSERT INTO `role`(code,name) VALUES ('TEACHER','教师');
