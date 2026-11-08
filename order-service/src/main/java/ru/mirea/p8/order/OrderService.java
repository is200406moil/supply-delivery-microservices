package ru.mirea.p8.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mirea.p8.order.Contracts.ApiException;
import ru.mirea.p8.order.Contracts.CreateOrderRequest;
import ru.mirea.p8.order.Contracts.DeliveryStatusChangedEvent;
import ru.mirea.p8.order.Contracts.OrderCreatedEvent;
import ru.mirea.p8.order.Contracts.ProductDto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static ru.mirea.p8.order.OrderServiceApplication.DELIVERY_STATUS;
import static ru.mirea.p8.order.OrderServiceApplication.EXCHANGE;
import static ru.mirea.p8.order.OrderServiceApplication.ORDER_CREATED;
import static ru.mirea.p8.order.OrderServiceApplication.QUEUE_DELIVERY_STATUS;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final SupplierClient supplier;
    private final RabbitTemplate rabbit;

    public OrderService(OrderRepository orders, SupplierClient supplier, RabbitTemplate rabbit) {
        this.orders = orders;
        this.supplier = supplier;
        this.rabbit = rabbit;
    }

    /**
     * Сквозной сценарий: проверка товара в Supplier Service (синхронно) → резерв → сохранение заказа
     * → событие OrderCreated в RabbitMQ (асинхронно, Delivery Service создаст доставку).
     */
    public CustomerOrder create(CreateOrderRequest req) {
        log.info("Новый заказ: {} × товар #{} для «{}»", req.quantity(), req.productId(), req.customerName());
        ProductDto product = supplier.getProduct(req.productId());
        if (product.stock() < req.quantity()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Товар «" + product.name() + "» недоступен: остаток " + product.stock() + ", запрошено " + req.quantity());
        }
        supplier.reserve(product.id(), req.quantity());

        BigDecimal total = product.price().multiply(BigDecimal.valueOf(req.quantity()));
        CustomerOrder order = orders.save(new CustomerOrder(req.customerName(), req.address(), product.id(),
                product.name(), req.quantity(), total, UUID.randomUUID().toString(), MDC.get("requestId")));
        log.info("Заказ #{} сохранён в orders_db, статус {}, сумма {}", order.getId(), order.getStatus(), total);
        publish(order, false);
        return order;
    }

    public List<CustomerOrder> findAll() {
        return orders.findAll();
    }

    public CustomerOrder findById(Long id) {
        return orders.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Заказ #" + id + " не найден"));
    }

    /** Повторная отправка того же события (тот же eventId) — для проверки идемпотентности потребителя. */
    public CustomerOrder resendEvent(Long id) {
        CustomerOrder order = findById(id);
        publish(order, true);
        return order;
    }

    private void publish(CustomerOrder o, boolean resend) {
        OrderCreatedEvent event = new OrderCreatedEvent(o.getEventId(), o.getId(), o.getCustomerName(), o.getAddress(),
                o.getProductName(), o.getQuantity(), o.getCreatedAt());
        String rid = MDC.get("requestId");
        rabbit.convertAndSend(EXCHANGE, ORDER_CREATED, event, m -> {
            m.getMessageProperties().setHeader("X-Request-ID", rid);
            m.getMessageProperties().setMessageId(o.getEventId());
            return m;
        });
        log.info("→ RabbitMQ {} [{}]: OrderCreated заказ #{}, eventId={}{}", EXCHANGE, ORDER_CREATED, o.getId(),
                o.getEventId(), resend ? " (ПОВТОРНАЯ отправка)" : "");
    }

    /** Статусы доставки из Delivery Service → статус заказа. */
    @RabbitListener(queues = QUEUE_DELIVERY_STATUS)
    @Transactional
    public void onDeliveryStatus(DeliveryStatusChangedEvent event,
                                 @Header(name = "X-Request-ID", required = false) String rid) {
        MDC.put("requestId", rid == null ? "-----" : rid);
        try {
            CustomerOrder.Status next = CustomerOrder.Status.valueOf(event.status());
            orders.findById(event.orderId()).ifPresentOrElse(o -> {
                boolean changed = o.advanceTo(next);
                log.info("← RabbitMQ [{}]: заказ #{} доставка {} → статус заказа {}{}", DELIVERY_STATUS, o.getId(),
                        event.status(), o.getStatus(), changed ? "" : " (без изменений)");
            }, () -> log.warn("← RabbitMQ: событие для неизвестного заказа #{}", event.orderId()));
        } finally {
            MDC.remove("requestId");
        }
    }
}
