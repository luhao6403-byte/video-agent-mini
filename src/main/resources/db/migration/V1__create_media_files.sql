CREATE TABLE media_files (
                             id BIGINT NOT NULL AUTO_INCREMENT,
                             filename VARCHAR(255) NOT NULL,
                             status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED',
                             file_path VARCHAR(1024) NOT NULL,
                             upload_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                             PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;