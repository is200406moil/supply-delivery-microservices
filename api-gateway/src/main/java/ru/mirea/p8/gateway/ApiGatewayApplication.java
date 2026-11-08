package ru.mirea.p8.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API Gateway (Spring Cloud Gateway, реактивный). Единственная точка входа для клиента:
 * маршрутизация /api/orders, /api/suppliers, /api/deliveries; X-Request-ID; circuit breaker с fallback;
 * сводное состояние системы /api/system/health.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    private static final Logger log = LoggerFactory.getLogger("gateway");

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

    /** Генерирует X-Request-ID (если клиент не прислал), передаёт его во внутренние сервисы и возвращает клиенту. */
    @Component
    static class RequestIdFilter implements GlobalFilter, Ordered {
        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            String incoming = exchange.getRequest().getHeaders().getFirst("X-Request-ID");
            String rid = incoming != null && !incoming.isBlank() ? incoming : "req-" + UUID.randomUUID().toString().substring(0, 8);
            ServerWebExchange ex = exchange.mutate()
                    .request(r -> r.headers(h -> h.set("X-Request-ID", rid)))
                    .build();
            // set() перед отправкой ответа — заголовок один, даже если внутренний сервис тоже вернул X-Request-ID
            ex.getResponse().beforeCommit(() -> {
                ex.getResponse().getHeaders().set("X-Request-ID", rid);
                return Mono.empty();
            });
            Route route = ex.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String target = route == null ? "?" : route.getUri().getHost();
            long start = System.currentTimeMillis();
            String req = ex.getRequest().getMethod() + " " + ex.getRequest().getURI().getRawPath();
            log.info("[rid={}] → {} → {}", rid, req, target);
            return chain.filter(ex).doFinally(s -> log.info("[rid={}] ← {} {} ({} мс)", rid, req,
                    ex.getResponse().getStatusCode(), System.currentTimeMillis() - start));
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    /** Ответ circuit breaker шлюза, когда внутренний сервис не отвечает (остановлен, таймаут). */
    @RestController
    @RequestMapping("/fallback")
    static class FallbackController {
        @GetMapping("/{service}")
        public ResponseEntity<Map<String, Object>> fallbackGet(@PathVariable String service, ServerWebExchange ex) {
            return fallback(service, ex);
        }

        @org.springframework.web.bind.annotation.PostMapping("/{service}")
        public ResponseEntity<Map<String, Object>> fallbackPost(@PathVariable String service, ServerWebExchange ex) {
            return fallback(service, ex);
        }

        private ResponseEntity<Map<String, Object>> fallback(String service, ServerWebExchange ex) {
            String rid = ex.getRequest().getHeaders().getFirst("X-Request-ID");
            Throwable cause = ex.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
            log.warn("[rid={}] fallback: {} недоступен ({})", rid, service, cause == null ? "circuit breaker" : cause.getClass().getSimpleName());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", 503);
            body.put("error", "Service Unavailable");
            body.put("message", service + " временно недоступен, запрос не выполнен. Повторите позже.");
            body.put("cause", cause == null ? "circuit breaker open" : cause.getClass().getSimpleName());
            body.put("requestId", rid);
            body.put("timestamp", Instant.now().toString());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }

    /** Какие компоненты системы доступны прямо сейчас: опрос /actuator/health каждого сервиса с таймаутом 1 с. */
    @RestController
    static class SystemHealthController {
        private static final List<String> SERVICES = List.of("supplier-service", "order-service", "delivery-service");
        private final WebClient web = WebClient.builder().build();

        @GetMapping("/api/system/health")
        public Mono<Map<String, Object>> health() {
            return Flux.fromIterable(SERVICES)
                    .flatMapSequential(svc -> web.get().uri("http://" + svc + ":8080/actuator/health")
                            .retrieve().bodyToMono(Map.class)
                            .timeout(Duration.ofSeconds(1))
                            .map(h -> Map.entry(svc, (Object) summary(h)))
                            .onErrorResume(e -> Mono.just(Map.entry(svc, (Object) Map.of("status", "DOWN",
                                    "error", e.getClass().getSimpleName())))))
                    .collectList()
                    .map(list -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("api-gateway", Map.of("status", "UP"));
                        list.forEach(e -> result.put(e.getKey(), e.getValue()));
                        result.put("checkedAt", Instant.now().toString());
                        return result;
                    });
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> summary(Map<String, Object> health) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("status", health.get("status"));
            Object comps = health.get("components");
            if (comps instanceof Map<?, ?> c) {
                c.forEach((k, v) -> {
                    if (List.of("db", "r2dbc", "rabbit", "circuitBreakers").contains(k) && v instanceof Map<?, ?> m) {
                        Object st = m.get("status");
                        if ("circuitBreakers".equals(k) && m.get("details") instanceof Map<?, ?> d
                                && d.get("supplier") instanceof Map<?, ?> cb && cb.get("details") instanceof Map<?, ?> cbd) {
                            st = "supplier: " + cbd.get("state");
                        }
                        s.put((String) k, st);
                    }
                });
            }
            return s;
        }
    }
}
