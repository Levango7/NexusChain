# HMAC 最终探针（fresh 商户 key + 服务端共享验签密钥）
$secret = 'drill-request-signing-secret-2026'
$key = 'cpk_97cc6dcc05304e56a6ede63161dc19c4'
$ts = [string][DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$nonce = 'drill-final-' + (Get-Random)
$method = 'POST'
$path = '/api/v1/payments'
$body = '{"merchantId":1,"amount":"1000000000000000000","currency":"NEXUS","chain":"nexus"}'
$payload = $ts + $nonce + $method + $path + $body
$hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($secret))
try { $sig = -join ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($payload)) | ForEach-Object { $_.ToString('x2') }) } finally { $hmac.Dispose() }
$headers = @(
  'X-NexusChain-ApiKey: ' + $key,
  'X-NexusChain-Timestamp: ' + $ts,
  'X-NexusChain-Nonce: ' + $nonce,
  'X-NexusChain-Signature: ' + $sig,
  'Content-Type: application/json'
)
& curl.exe -s -w "`nHTTP %{http_code}`n" -X POST 'http://127.0.0.1:18080/api/v1/payments' -H $headers -d $body