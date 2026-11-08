# Прохождение одного запроса через сервисы: все строки логов с данным X-Request-ID, отсортированные по времени.
# Использование: powershell -File scripts\trace-request.ps1 <requestId>
param([Parameter(Mandatory = $true)][string]$RequestId)
if ([Console]::OutputEncoding.CodePage -ne 65001) { [Console]::OutputEncoding = [Text.Encoding]::UTF8 }
Set-Location (Split-Path $PSScriptRoot)
$colors = @{ 'api-gateway' = 'Cyan'; 'order-service' = 'Yellow'; 'supplier-service' = 'Green'; 'delivery-service' = 'Magenta' }
$rows = foreach ($svc in 'api-gateway', 'order-service', 'supplier-service', 'delivery-service') {
    docker compose logs --no-color --no-log-prefix $svc 2>$null | Where-Object { $_ -match [regex]::Escape("rid=$RequestId]") } |
        ForEach-Object { [pscustomobject]@{ Time = $_.Substring(0, 12); Service = $svc; Line = $_ } }
}
Write-Host ("Запрос X-Request-ID = {0}: {1} строк в логах {2} сервисов" -f $RequestId, @($rows).Count, (@($rows) | Select-Object -Unique Service).Count) -ForegroundColor White
foreach ($r in $rows | Sort-Object Time) {
    $msg = ($r.Line -replace '^\S+\s+\S+\s+', '') -replace '\[rid=[^\]]*\]\s*', '' -replace '^\S+\s+:\s', ''
    Write-Host ("{0}  " -f $r.Time) -NoNewline -ForegroundColor DarkGray
    Write-Host ("{0,-17}" -f $r.Service) -NoNewline -ForegroundColor $colors[$r.Service]
    Write-Host $msg
}
