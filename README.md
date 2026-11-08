# Практическая работа №8. Приложение с микросервисной архитектурой

Фрагмент распределённой системы поставщиков и доставки (продолжение АС Поставщиков и АС сервиса доставки
из BootcampLabs и практических работ 4–7). Все компоненты запускаются одной командой `docker compose up`.

## Архитектура

```
                               Client (браузер / curl)
                                        │  http://localhost:8100
                                        ▼
                              ┌───────────────────┐
                              │  Traefik v3.6     │  внешний reverse proxy (инфраструктурная маршрутизация)
                              └─────────┬─────────┘
                                        ▼   сеть edge
                              ┌───────────────────┐  маршрутизация внутри приложения, X-Request-ID,
                              │   API Gateway     │  CircuitBreaker + fallback, /api/system/health
                              │ Spring Cloud GW   │
                              └──┬──────┬──────┬──┘
                /api/suppliers/**│      │/api/orders/**    │/api/deliveries/**, /api/orders/{id}/events (SSE)
                                 ▼      ▼                  ▼                     сеть internal
                 ┌─────────────────┐  ┌──────────────────────┐  ┌─────────────────────────┐
                 │ Supplier Service│◄─┤   Order Service      │  │   Delivery Service      │
                 │ Spring MVC, JPA │  │ Spring MVC, JPA      │  │ WebFlux, R2DBC (Mono,   │
                 │ товары, резерв  │  │ RestClient +         │  │ Flux, SSE)              │
                 └────────┬────────┘  │ Resilience4j         │  └───┬─────────────────▲───┘
                          │   sync HTTP (timeout, retry, CB)   │      │                 │
                          ▼           └──────┬───────▲───────┘      ▼                 │
                    suppliers_db             ▼       │         deliveries_db          │
                                         orders_db   │                                │
                                             │ OrderCreated          DeliveryStatusChanged
                                             ▼       │                                │
                              ┌──────────────────────┴────────────────────────────────┴──┐
                              │ RabbitMQ: topic exchange food.events                      │
                              │  order.created           → delivery-service.order-created │
                              │  delivery.status.changed → order-service.delivery-status  │
                              └───────────────────────────────────────────────────────────┘
          PostgreSQL 16: три базы (suppliers_db, orders_db, deliveries_db), у каждой свой пользователь-владелец,
          CONNECT для остальных отозван — сервис физически не может подключиться к чужой БД.
```

| Компонент | Технологии | Ответственность | Данные |
|---|---|---|---|
| `api-gateway` | Spring Cloud Gateway (реактивный), Resilience4j | единая точка входа, маршруты, `X-Request-ID`, fallback 503, сводный health | — |
| `supplier-service` | Spring MVC, Spring Data JPA | поставщики и товары, проверка остатка, резервирование | `suppliers_db` |
| `order-service` | Spring MVC, JPA, RestClient, Resilience4j, Spring AMQP | создание и изменение заказов, публикация `OrderCreated`, приём статусов доставки | `orders_db` |
| `delivery-service` | Spring WebFlux, R2DBC, Spring AMQP | доставка и её состояние, идемпотентный приём `OrderCreated`, SSE-поток статусов | `deliveries_db` |
| `rabbitmq` | RabbitMQ 3.13 (management UI) | асинхронный обмен событиями | — |
| `postgres` | PostgreSQL 16 | три изолированные базы | — |
| `traefik` | Traefik v3.6 | внешний reverse proxy → API Gateway | — |

## Сквозной сценарий

```
POST /api/orders ─► Traefik ─► API Gateway (X-Request-ID) ─► Order Service
   ─► Supplier Service: GET товара (timeout 2 с, retry ×3, circuit breaker) ─► проверка остатка
   ─► Supplier Service: POST резерв (без retry — неидемпотентно)
   ─► заказ CONFIRMED в orders_db ─► OrderCreated{eventId, orderId, адрес, товар, количество} ─► RabbitMQ
   ─► Delivery Service: processed_events (идемпотентность) ─► доставка DELIVERY_CREATED в deliveries_db
   ─► COURIER_ASSIGNED ─► IN_TRANSIT ─► DELIVERED (каждые 3 с, Flux.delayElements + concatMap(save))
   ─► DeliveryStatusChanged ─► RabbitMQ ─► Order Service обновляет статус заказа
   ─► клиент получает статусы через GET /api/orders/{id}/events (SSE, Flux<ServerSentEvent>)
```

## Требования задания → реализация

| № | Требование | Где реализовано |
|---|---|---|
| 1 | ≥ 3 бизнес-сервиса | `supplier-service`, `order-service`, `delivery-service` — отдельные Spring Boot-приложения и образы |
| 2 | Собственные данные | три БД, init-скрипт `infra/postgres/init-databases.sh`, у каждой свой владелец, `REVOKE CONNECT ... FROM PUBLIC` |
| 3 | API Gateway | `api-gateway`: маршруты в `application.yml`, внутренние сервисы наружу не публикуются (только сеть `internal`) |
| 4 | Синхронное взаимодействие | `order-service` → `SupplierClient` (RestClient): проверка товара и резерв |
| 5 | Асинхронное взаимодействие | RabbitMQ `food.events`: `OrderCreated` → Delivery, `DeliveryStatusChanged` → Order |
| 6 | Повторные события | `delivery-service`: `INSERT INTO processed_events ... ON CONFLICT DO NOTHING` по `eventId` + `UNIQUE(order_id)`; в Order Service статус только растёт (`advanceTo`). Проверка: `POST /api/orders/{id}/resend-event` |
| 7 | Обработка отказов | timeout (connect 1 с, read 2 с), `@Retry` (3 попытки, только GET), `@CircuitBreaker` (окно 6, порог 50 %, OPEN 15 с) с fallback 503; в шлюзе — connect/response timeout и CircuitBreaker-фильтр с `forward:/fallback/{service}` |
| 8 | Контейнеризация | `compose.yaml`: gateway, 3 сервиса, RabbitMQ, PostgreSQL, Traefik; многоступенчатые Dockerfile (Maven → JRE) |
| 9 | Маршрутизация | Traefik (Docker labels) → API Gateway → сервисы |
| 10 | Health checks | Actuator в каждом сервисе (db / r2dbc / rabbit / circuitBreakers), healthcheck в compose, `GET /api/system/health` в шлюзе |
| 11 | Корреляция | `X-Request-ID` генерируется шлюзом, передаётся в HTTP-заголовках и в заголовке AMQP-сообщений, пишется в каждую строку логов (`rid=...`); `scripts/trace-request.ps1 <id>` собирает путь запроса по логам всех сервисов |
| 12 | Реактивный сценарий | Delivery Service: WebFlux + R2DBC, `GET /api/orders/{id}/events` (через шлюз) → `Flux<ServerSentEvent>` |
| 13 | Полный бизнес-сценарий | см. выше; демо-клиент `http://localhost:8100/demo/index.html?s=flow` |
| 14 | Отказоустойчивость | `docker compose stop supplier-service` → 503 за ≤ 4 с, затем за миллисекунды (CB OPEN); остальные функции работают; после `start` CB → HALF_OPEN → CLOSED |

## API (через шлюз, http://localhost:8100)

| Метод и путь | Сервис |
|---|---|
| `GET /api/suppliers`, `GET /api/suppliers/products`, `GET /api/suppliers/products/{id}` | Supplier |
| `POST /api/suppliers/products/{id}/reserve`, `POST /api/suppliers/admin/latency?ms=` (задержка для демонстрации таймаута) | Supplier |
| `POST /api/orders`, `GET /api/orders`, `GET /api/orders/{id}`, `POST /api/orders/{id}/resend-event` | Order |
| `GET /api/deliveries`, `GET /api/deliveries/{id}`, `GET /api/deliveries/by-order/{orderId}` | Delivery |
| `GET /api/orders/{id}/events` (SSE) | Delivery (переписывается шлюзом в `/api/deliveries/orders/{id}/events`) |
| `GET /api/system/health` | API Gateway |

## Мониторинг: Prometheus и Grafana

Каждый сервис отдаёт метрики Micrometer на `/actuator/prometheus` (зависимость `micrometer-registry-prometheus`,
тег `application` = имя сервиса, гистограмма задержек `http.server.requests`).
Prometheus и Grafana поднимаются вместе с остальными контейнерами и сидят во внутренней сети `internal`;
наружу их порты открыты только на `127.0.0.1`.

| Компонент | Адрес | Что там |
| --- | --- | --- |
| Prometheus | http://localhost:9090 | `Status → Targets`: 4 сервиса, опрос раз в 5 с; `Alerts`: правила ниже |
| Grafana | http://localhost:3000 (admin / `GRAFANA_ADMIN_PASSWORD` из `.env`) | дашборд «food-micro: обзор сервисов» подключается автоматически |

Дашборд (`infra/grafana/dashboards/overview.json`): доступность сервисов (`up`), запросы в секунду,
доля ответов 5xx, задержка p95, состояние circuit breaker Resilience4j, JVM heap.

Правила алертов (`infra/prometheus/alerts.yml`, Alertmanager не подключён, алерты видны в UI Prometheus):

| Алерт | Условие |
| --- | --- |
| `ServiceDown` | сервис не отдаёт метрики 30 с |
| `HighErrorRate` | больше 5% ответов 5xx за минуту |
| `CircuitBreakerOpen` | circuit breaker открыт дольше 10 с |

Проверка отказа: `docker compose stop supplier-service` → через ~30 с в Prometheus срабатывает `ServiceDown`,
на дашборде supplier-service становится DOWN, а при запросах заказов растёт доля 5xx и открывается circuit breaker
в order-service. `docker compose start supplier-service` возвращает всё обратно.

## Запуск

```
docker compose up -d --build
```

* демо-клиент: http://localhost:8100/demo/index.html (`?s=flow`, `?s=errors`, `?s=outage`, `?s=recovery`)
* состояние: `powershell -File scripts\status.ps1`
* трассировка запроса: `powershell -File scripts\trace-request.ps1 <X-Request-ID>`
* идемпотентность: `powershell -File scripts\resend-event.ps1 1`
* отказ: `docker compose stop supplier-service` / `docker compose start supplier-service`
* RabbitMQ UI: http://localhost:15673, Traefik dashboard: http://localhost:8101 (оба только на 127.0.0.1)

Пароли и параметры подключения задаются в `.env` (учебные значения для локального стенда) и передаются
в сервисы через переменные окружения.

Перед первым запуском скопируйте шаблон: `cp .env.example .env` (PowerShell: `Copy-Item .env.example .env`).
