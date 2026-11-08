#!/bin/sh
# Три логически изолированные БД: у каждого сервиса свой пользователь и доступ только к своей базе.
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<SQL
CREATE USER suppliers WITH PASSWORD '$SUPPLIERS_DB_PASSWORD';
CREATE USER orders WITH PASSWORD '$ORDERS_DB_PASSWORD';
CREATE USER deliveries WITH PASSWORD '$DELIVERIES_DB_PASSWORD';
CREATE DATABASE suppliers_db OWNER suppliers;
CREATE DATABASE orders_db OWNER orders;
CREATE DATABASE deliveries_db OWNER deliveries;
REVOKE CONNECT ON DATABASE suppliers_db FROM PUBLIC;
REVOKE CONNECT ON DATABASE orders_db FROM PUBLIC;
REVOKE CONNECT ON DATABASE deliveries_db FROM PUBLIC;
SQL
