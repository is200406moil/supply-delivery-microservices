# Повторная доставка события OrderCreated (тот же eventId) — проверка идемпотентности Delivery Service.
param([int]$OrderId = 1)
if ([Console]::OutputEncoding.CodePage -ne 65001) { [Console]::OutputEncoding = [Text.Encoding]::UTF8 }
Set-Location (Split-Path $PSScriptRoot)
$rid = "resend-$OrderId-" + (Get-Date -Format "HHmmss")
Write-Host "> POST /api/orders/$OrderId/resend-event   (X-Request-ID: $rid)" -ForegroundColor Cyan
$r = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "http://localhost:8100/api/orders/$OrderId/resend-event" -Headers @{ 'X-Request-ID' = $rid }
$o = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json
Write-Host ("HTTP {0}: заказ #{1}, eventId={2}" -f $r.StatusCode, $o.id, $o.eventId)
Start-Sleep -Seconds 2
Write-Host ""
Write-Host "> журнал Delivery Service по eventId:" -ForegroundColor Cyan
$lines = docker compose logs --no-color --no-log-prefix delivery-service 2>$null | Where-Object { $_ -match $o.eventId }
# первая обработка события и строки текущей повторной отправки
@($lines | Select-Object -First 1) + @($lines | Where-Object { $_ -match [regex]::Escape("rid=$rid]") }) |
    ForEach-Object { $_ -replace '^(\S+\s+\S+)\s+\S+\s+:\s', '$1  ' }
Write-Host ""
Write-Host "> доставки заказа в deliveries_db:" -ForegroundColor Cyan
docker compose exec -T postgres psql -U postgres -d deliveries_db -c "SELECT d.id, d.order_id, d.status, d.courier FROM deliveries d WHERE d.order_id = $OrderId;" -c "SELECT event_id, processed_at FROM processed_events ORDER BY processed_at;"
