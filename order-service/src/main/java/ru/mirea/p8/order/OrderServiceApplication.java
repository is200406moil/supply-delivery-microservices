package ru.mirea.p8.order;

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

/**
 * Order Service — создание и изменение заказов. Собственная БД orders_db.
 * Синхронно (RestClient + Resilience4j) проверяет и резервирует товар в Supplier Service,
 * асинхронно публикует OrderCreated в RabbitMQ и принимает DeliveryStatusChanged от Delivery Service.
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static final String EXCHANGE = "food.events";
    public static final String ORDER_CREATED = "order.created";
    public static final String DELIVERY_STATUS = "delivery.status.changed";
    public static final String QUEUE_DELIVERY_STATUS = "order-service.delivery-status";

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }

    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue deliveryStatusQueue() {
        return QueueBuilder.durable(QUEUE_DELIVERY_STATUS).build();
    }

    @Bean
    Binding deliveryStatusBinding(Queue deliveryStatusQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(deliveryStatusQueue).to(eventsExchange).with(DELIVERY_STATUS);
    }

    @Bean
    MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
