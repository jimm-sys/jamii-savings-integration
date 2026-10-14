CREATE TABLE IF NOT EXISTS accounts (
    account_number VARCHAR(20)   PRIMARY KEY,
    customer_id    VARCHAR(20)   NOT NULL,
    status         VARCHAR(10)   NOT NULL,
    balance        NUMERIC(15,2) NOT NULL,
    currency       VARCHAR(3)    NOT NULL DEFAULT 'KES',
    CONSTRAINT chk_status CHECK (status IN ('ACTIVE','DORMANT','CLOSED'))
);
