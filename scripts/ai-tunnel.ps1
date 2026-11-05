<#
LLM PC의 vlog serve(127.0.0.1:8000)를 Backend VM에 역방향 SSH 터널로 연다.
Backend 컨테이너는 http://host.docker.internal:18000/api/v1 로 이 PC를 호출한다.
끊기면 10초 뒤 다시 연결한다. 터널 전용 키(비밀번호 없음)가 필요하다. 준비는 README의 "Backend와 연결" 참고.

예: pwsh -File scripts\ai-tunnel.ps1 -Server 192.168.0.17 -User ubuntu
#>
param(
    [Parameter(Mandatory = $true)] [string] $Server,
    [Parameter(Mandatory = $true)] [string] $User,
    [int] $Port = 2222,
    [string] $Key = (Join-Path $HOME ".ssh\vlog_tunnel"),
    [int] $LocalPort = 8000,
    [string] $Listen = "172.17.0.1:18000"
)

if (-not (Test-Path $Key)) {
    Write-Error "터널 키가 없습니다: $Key  (ssh-keygen -t ed25519 -f `"$Key`" -N `"`" -C vlog-tunnel)"
    exit 1
}

while ($true) {
    Write-Host "$(Get-Date -Format s) 터널 연결: ${Listen} (VM) -> 127.0.0.1:${LocalPort} (이 PC)"
    & ssh -N -i $Key -p $Port `
        -o BatchMode=yes `
        -o ExitOnForwardFailure=yes `
        -o ServerAliveInterval=30 `
        -o ServerAliveCountMax=3 `
        -R "${Listen}:127.0.0.1:${LocalPort}" `
        "$User@$Server"
    Write-Host "$(Get-Date -Format s) 터널이 끊겼습니다(코드 $LASTEXITCODE). 10초 뒤 다시 연결합니다."
    Start-Sleep -Seconds 10
}
