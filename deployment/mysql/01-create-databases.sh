#!/bin/bash
# Sourced by the official entrypoint on an EMPTY data volume only.
set -e
for service in IDENTITY BOOKING AI NOTIFICATION; do
    user_key="${service}_DB_USER"
    password_key="${service}_DB_PASSWORD"
    db="${service,,}_db"
    user="${!user_key}"
    password="${!password_key}"
    # The generator uses alphanumeric usernames and 48-character hex passwords.
    [[ "$user" =~ ^[a-z][a-z0-9_]+$ ]] || exit 1
    [[ "$password" =~ ^[a-f0-9]{48}$ ]] || exit 1
    docker_process_sql <<SQL
CREATE DATABASE IF NOT EXISTS \`$db\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER '$user'@'%' IDENTIFIED BY '$password';
GRANT ALL PRIVILEGES ON \`$db\`.* TO '$user'@'%';
SQL
done

# Administrative root stays local to the container.
docker_process_sql <<'SQL'
DROP USER IF EXISTS 'root'@'%';
SQL
