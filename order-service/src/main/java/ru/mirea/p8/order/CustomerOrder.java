package ru.mirea.p8.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** Заказ (таблица orders в orders_db). Хранит только копию нужных данных о товаре, а не ссылку на чужую БД. */
@Entity
@Table(name = "orders")
public class CustomerOrder {

    public enum Status { CREATED, CONFIRMED, DELIVERY_CREATED, COURIER_ASSIGNED, IN_TRANSIT, DELIVERED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String customerName;
    private String address;
    private Long productId;
    private String productName;
    private int quantity;
    private BigDecimal total;
    @Enumerated(EnumType.STRING)
    private Status status;
    /** Идентификатор события OrderCreated — повторная отправка использует тот же eventId. */
    @Column(unique = true)
    private String eventId;
    private String requestId;
    private Instant createdAt;
    private Instant updatedAt;

    protected CustomerOrder() {
    }

    public CustomerOrder(String customerName, String address, Long productId, String productName, int quantity,
                         BigDecimal total, String eventId, String requestId) {
        this.customerName = customerName;
        this.address = address;
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.total = total;
        this.status = Status.CONFIRMED;
        this.eventId = eventId;
        this.requestId = requestId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    /** Статус двигается только вперёд: повторные и запоздавшие события не откатывают заказ. */
    public boolean advanceTo(Status next) {
        if (next.ordinal() <= status.ordinal()) {
            return false;
        }
        status = next;
        updatedAt = Instant.now();
        return true;
    }

    public Long getId() { return id; }
    public String getCustomerName() { return customerName; }
    public String getAddress() { return address; }
    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public int getQuantity() { return quantity; }
    public BigDecimal getTotal() { return total; }
    public Status getStatus() { return status; }
    public String getEventId() { return eventId; }
    public String getRequestId() { return requestId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
