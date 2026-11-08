# Состояние контейнеров системы и сводный health через API Gateway.
if ([Console]::OutputEncoding.CodePage -ne 65001) { [Console]::OutputEncoding = [Text.Encoding]::UTF8 }
Set-Location (Split-Path $PSScriptRoot)
Write-Host "> docker compose ps" -ForegroundColor Cyan
docker compose ps --format "table {{.Name}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}"
Write-Host ""
Write-Host "> GET http://localhost:8100/api/system/health" -ForegroundColor Cyan
$r = Invoke-WebRequest -UseBasicParsing http://localhost:8100/api/system/health
([Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json).PSObject.Properties |
    Where-Object { $_.Name -ne 'checkedAt' } |
    ForEach-Object { "{0,-18} {1}" -f $_.Name, (($_.Value.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join '  ') }
