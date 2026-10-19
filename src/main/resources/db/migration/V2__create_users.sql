CREATE TABLE users (
                       id BIGINT NOT NULL AUTO_INCREMENT,
                       username VARCHAR(32) NOT NULL,
                       password_hash VARCHAR(255) NOT NULL,
                       nickname VARCHAR(50) NOT NULL,
                       created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                       PRIMARY KEY (id),
                       UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;