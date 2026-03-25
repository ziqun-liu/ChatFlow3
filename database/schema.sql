-- ChatFlow3 message persistence schema
-- Supports 4 core queries + analytics queries required by Assignment 3.

CREATE DATABASE IF NOT EXISTS chatflow
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE chatflow;

CREATE TABLE IF NOT EXISTS messages (
  id               BIGINT        AUTO_INCREMENT PRIMARY KEY,
  message_id       VARCHAR(36)   NOT NULL,
  room_id          VARCHAR(10)   NOT NULL,
  user_id          VARCHAR(10)   NOT NULL,
  username         VARCHAR(20)   NOT NULL,
  message          TEXT          NOT NULL,
  message_type     VARCHAR(10)   NOT NULL,
  client_timestamp DATETIME(3)   NOT NULL,
  server_timestamp DATETIME(3)   NOT NULL,
  server_id        VARCHAR(20)   NOT NULL,

  -- Idempotent writes: duplicate message_id is silently ignored via INSERT IGNORE
  UNIQUE KEY uq_message_id (message_id),

  -- Query 1: room messages in time range (covers WHERE + ORDER BY, no filesort)
  INDEX idx_room_time (room_id, client_timestamp),

  -- Query 2: user history  |  Query 4: rooms by user
  INDEX idx_user_time (user_id, client_timestamp)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
