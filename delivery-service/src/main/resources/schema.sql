CREATE TABLE IF NOT EXISTS deliveries (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT       NOT NULL UNIQUE,
    customer_name VARCHAR(200),
    address       VARCHAR(300),
    product_name  VARCHAR(200),
    quantity      INT,
    courier       VARCHAR(100),
    status        VARCHAR(30)  NOT NULL,
    request_id    VARCHAR(64),
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ
);

-- идемпотентность потребителя: обработанные eventId
CREATE TABLE IF NOT EXISTS processed_events (
    event_id     VARCHAR(64) PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
