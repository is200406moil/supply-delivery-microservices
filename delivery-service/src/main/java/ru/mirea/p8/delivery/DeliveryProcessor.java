package ru.mirea.p8.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import ru.mirea.p8.delivery.DeliveryServiceApplication.Delivery;
import ru.mirea.p8.delivery.DeliveryServiceApplication.DeliveryStatusChangedEvent;
import ru.mirea.p8.delivery.DeliveryServiceApplication.OrderCreatedEvent;
import ru.mirea.p8.delivery.DeliveryServiceApplication.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static ru.mirea.p8.delivery.DeliveryServiceApplication.DELIVERY_STATUS;
import static ru.mirea.p8.delivery.DeliveryServiceApplication.EXCHANGE;
import static ru.mirea.p8.delivery.DeliveryServiceApplication.QUEUE_ORDER_CREATED;

/**
 * Обработка OrderCreated и жизненный цикл доставки.
 * Идемпотентность: eventId записывается в processed_events (PRIMARY KEY, INSERT ... ON CONFLICT DO NOTHING);
 * если строка не вставилась — событие уже обработано и повторно доставку не создаём.
 * Дополнительно order_id в deliveries уникален.
 */
@Component
public class DeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);
    private static final List<String> COURIERS = List.of("Курьер Алексей", "Курьер Мария", "Курьер Олег");

    private final DeliveryRepository deliveries;
    private final DatabaseClient db;
    private final RabbitTemplate rabbit;
    private final Sinks.Many<DeliveryStatusChangedEvent> sink;
    private final Duration step;

    public DeliveryProcessor(DeliveryRepository deliveries, DatabaseClient db, RabbitTemplate rabbit,
                             Sinks.Many<DeliveryStatusChangedEvent> sink,
                             @Value("${delivery.step:3s}") Duration step) {
        this.deliveries = deliveries;
        this.db = db;
        this.rabbit = rabbit;
        this.sink = sink;
        this.step = step;
    }

    @RabbitListener(queues = QUEUE_ORDER_CREATED)
    public void onOrderCreated(OrderCreatedEvent event, @Header(name = "X-Request-ID", required = false) String rid) {
        try {
            log.info("[rid={}] ← RabbitMQ [order.created]: OrderCreated заказ #{}, eventId={}", rid, event.orderId(), event.eventId());
            Long inserted = db.sql("INSERT INTO processed_events(event_id, processed_at) VALUES (:id, now()) ON CONFLICT DO NOTHING")
                    .bind("id", event.eventId())
                    .fetch().rowsUpdated()
                    .block(Duration.ofSeconds(5)); // поток слушателя AMQP, а не event loop HTTP — блокировка допустима
            if (inserted == null || inserted == 0) {
                log.warn("[rid={}] Повторное событие eventId={} для заказа #{} — уже обработано, доставка НЕ создаётся (идемпотентность)",
                        rid, event.eventId(), event.orderId());
                return;
            }
            Delivery created = deliveries.save(new Delivery(null, event.orderId(), event.customerName(), event.address(),
                            event.productName(), event.quantity(), null, Status.DELIVERY_CREATED, rid,
                            Instant.now(), Instant.now()))
                    .block(Duration.ofSeconds(5));
            log.info("[rid={}] Создана доставка #{} для заказа #{} в deliveries_db, статус {}", rid, created.id(), created.orderId(), created.status());
            publish(created, rid);
            runLifecycle(created, rid);
        } catch (DuplicateKeyException e) {
            log.warn("[rid={}] Доставка для заказа #{} уже существует — событие пропущено", rid, event.orderId());
        }
    }

    /** Имитация работы курьера: каждые {@code step} следующий статус, каждый шаг сохраняется и публикуется. */
    private void runLifecycle(Delivery d, String rid) {
        String courier = COURIERS.get((int) (d.orderId() % COURIERS.size()));
        Flux.just(Status.COURIER_ASSIGNED, Status.IN_TRANSIT, Status.DELIVERED)
                .delayElements(step)
                .concatMap(s -> deliveries.findById(d.id()).map(cur -> cur.next(s, courier)).flatMap(deliveries::save))
                .doOnNext(saved -> publish(saved, rid))
                .onErrorResume(e -> {
                    log.error("[rid={}] Ошибка жизненного цикла доставки #{}: {}", rid, d.id(), e.toString());
                    return Mono.empty();
                })
                .subscribe();
    }

    private void publish(Delivery d, String rid) {
        DeliveryStatusChangedEvent event = new DeliveryStatusChangedEvent(UUID.randomUUID().toString(), d.orderId(), d.id(),
                d.status().name(), d.courier(), d.updatedAt());
        sink.tryEmitNext(event);
        rabbit.convertAndSend(EXCHANGE, DELIVERY_STATUS, event, m -> {
            m.getMessageProperties().setHeader("X-Request-ID", rid);
            return m;
        });
        // жизненный цикл идёт в потоках Reactor, где MDC нет, поэтому rid пишем явно
        log.info("[rid={}] → RabbitMQ [{}]: доставка #{} заказ #{} статус {}{}", rid, DELIVERY_STATUS, d.id(), d.orderId(),
                d.status(), d.courier() == null ? "" : " (" + d.courier() + ")");
    }
}
