package ru.mirea.p8.delivery;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import reactor.core.publisher.Sinks;

import java.time.Instant;

/**
 * Delivery Service — доставка и её состояние. Реактивный сервис: Spring WebFlux + R2DBC (deliveries_db).
 * Получает OrderCreated из RabbitMQ (идемпотентно), ведёт жизненный цикл доставки, публикует
 * DeliveryStatusChanged и отдаёт клиенту поток статусов через SSE (Flux).
 */
@SpringBootApplication
public class DeliveryServiceApplication {

    public static final String EXCHANGE = "food.events";
    public static final String ORDER_CREATED = "order.created";
    public static final String DELIVERY_STATUS = "delivery.status.changed";
    public static final String QUEUE_ORDER_CREATED = "delivery-service.order-created";

    public static void main(String[] args) {
        SpringApplication.run(DeliveryServiceApplication.class, args);
    }

    // ------------------------------------------------------------------ модель deliveries_db

    public enum Status { DELIVERY_CREATED, COURIER_ASSIGNED, IN_TRANSIT, DELIVERED }

    @Table("deliveries")
    public record Delivery(@Id Long id, Long orderId, String customerName, String address, String productName,
                           Integer quantity, String courier, Status status, String requestId,
                           Instant createdAt, Instant updatedAt) {

        public Delivery next(Status s, String newCourier) {
            return new Delivery(id, orderId, customerName, address, productName, quantity,
                    newCourier != null ? newCourier : courier, s, requestId, createdAt, Instant.now());
        }
    }

    /** Событие из Order Service. */
    public record OrderCreatedEvent(String eventId, Long orderId, String customerName, String address,
                                    String productName, int quantity, Instant createdAt) {
    }

    /** Событие для Order Service и для SSE-клиентов. */
    public record DeliveryStatusChangedEvent(String eventId, Long orderId, Long deliveryId, String status,
                                             String courier, Instant at) {
    }

    // ------------------------------------------------------------------ RabbitMQ

    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue orderCreatedQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_CREATED).build();
    }

    @Bean
    Binding orderCreatedBinding(Queue orderCreatedQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(orderCreatedQueue).to(eventsExchange).with(ORDER_CREATED);
    }

    @Bean
    MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /** Горячий поток изменений статусов внутри сервиса — источник для SSE. */
    @Bean
    Sinks.Many<DeliveryStatusChangedEvent> statusSink() {
        return Sinks.many().multicast().directBestEffort();
    }
}
