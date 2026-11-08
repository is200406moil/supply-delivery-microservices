package ru.mirea.p8.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import ru.mirea.p8.delivery.DeliveryServiceApplication.Delivery;
import ru.mirea.p8.delivery.DeliveryServiceApplication.DeliveryStatusChangedEvent;
import ru.mirea.p8.delivery.DeliveryServiceApplication.Status;

import java.time.Duration;

/** Реактивный REST API доставок (Mono/Flux) и SSE-поток статусов заказа. */
@RestController
@RequestMapping("/api/deliveries")
public class DeliveryController {

    private static final Logger log = LoggerFactory.getLogger(DeliveryController.class);

    private final DeliveryRepository deliveries;
    private final Sinks.Many<DeliveryStatusChangedEvent> sink;

    public DeliveryController(DeliveryRepository deliveries, Sinks.Many<DeliveryStatusChangedEvent> sink) {
        this.deliveries = deliveries;
        this.sink = sink;
    }

    @GetMapping
    public Flux<Delivery> findAll() {
        return deliveries.findAll();
    }

    @GetMapping("/{id}")
    public Mono<Delivery> findById(@PathVariable Long id) {
        return deliveries.findById(id)
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Доставка #" + id + " не найдена")));
    }

    @GetMapping("/by-order/{orderId}")
    public Mono<Delivery> byOrder(@PathVariable Long orderId) {
        return deliveries.findByOrderId(orderId)
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Доставки для заказа #" + orderId + " пока нет")));
    }

    /**
     * SSE: текущий статус доставки заказа (если она уже создана) + все следующие изменения по мере появления.
     * Через API Gateway доступен как GET /api/orders/{id}/events.
     */
    @GetMapping(value = "/orders/{orderId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<DeliveryStatusChangedEvent>> events(@PathVariable Long orderId,
                                                                    @RequestHeader(value = "X-Request-ID", required = false) String rid) {
        Flux<DeliveryStatusChangedEvent> current = deliveries.findByOrderId(orderId)
                .map(d -> new DeliveryStatusChangedEvent(null, d.orderId(), d.id(), d.status().name(), d.courier(), d.updatedAt()))
                .flux();
        Flux<DeliveryStatusChangedEvent> live = sink.asFlux().filter(e -> e.orderId().equals(orderId));
        return Flux.merge(live, current)
                .distinct(DeliveryStatusChangedEvent::status)
                .takeUntil(e -> Status.DELIVERED.name().equals(e.status()))
                .timeout(Duration.ofMinutes(2), Flux.empty())
                .doOnSubscribe(s -> log.info("[rid={}] SSE: клиент подписался на статусы заказа #{}", rid, orderId))
                .doOnNext(e -> log.info("[rid={}] SSE → заказ #{} статус {}", rid, orderId, e.status()))
                .doFinally(s -> log.info("[rid={}] SSE: поток заказа #{} закрыт ({})", rid, orderId, s))
                .map(e -> ServerSentEvent.builder(e).event("status").id(e.status()).build());
    }

    /** Лог входящих HTTP-запросов с X-Request-ID (в WebFlux MDC не привязан к потоку, поэтому rid пишем явно). */
    @Component
    static class RequestLogFilter implements WebFilter {
        @Override
        public Mono<Void> filter(ServerWebExchange ex, WebFilterChain chain) {
            String path = ex.getRequest().getURI().getRawPath();
            if (path.startsWith("/actuator")) {
                return chain.filter(ex);
            }
            String rid = ex.getRequest().getHeaders().getFirst("X-Request-ID");
            log.info("[rid={}] HTTP {} {}", rid, ex.getRequest().getMethod(), path);
            if (rid != null) {
                ex.getResponse().getHeaders().set("X-Request-ID", rid);
            }
            return chain.filter(ex);
        }
    }
}
