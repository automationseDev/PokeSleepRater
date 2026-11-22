# One-time setup for stable Android APK signing.
# Run locally in PowerShell. Never commit your keystore or password.
[CmdletBinding()]
param(
    [string]$Repository = 'automationseDev/PokeSleepRater',
    [switch]$SkipWorkflowDispatch
)

$ErrorActionPreference = 'Stop'

$gh = Get-Command 'gh' -ErrorAction SilentlyContinue
if (-not $gh) {
    throw 'GitHub CLI (gh) が見つかりません。https://cli.github.com/ からインストールしてください。'
}
$keytool = Get-Command 'keytool' -ErrorAction SilentlyContinue
if (-not $keytool -and $env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    if (Test-Path $candidate) { $keytool = Get-Item $candidate }
}
if (-not $keytool) {
    throw 'JDK (keytool) が必要です。JDK 17をインストールし、JAVA_HOMEを設定してください。'
}
& $gh.Source auth status 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'GitHubへの認証が必要です。gh auth login を実行してください。'
}

$localData = [Environment]::GetFolderPath('LocalApplicationData')
if ([string]::IsNullOrWhiteSpace($localData)) {
    throw 'Windowsのローカルアプリデータフォルダーが見つかりません。'
}
$folder = Join-Path $localData 'PokeSleepRater\Signing'
New-Item -Path $folder -ItemType Directory -Force | Out-Null
$keystore = Join-Path $folder 'pokesleeprater-release.jks'
$passwordFile = Join-Path $folder 'pokesleeprater-release.password.txt'

if ((Test-Path $keystore) -xor (Test-Path $passwordFile)) {
    throw '署名鍵とパスワードファイルの一方が見つかりません。誤って別の署名鍵を作らないよう停止しました。元のバックアップを復元してください。'
}

if (-not (Test-Path $keystore)) {
    $random = [byte[]]::new(40)
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($random) } finally { $rng.Dispose() }
    $password = [Convert]::ToBase64String($random).TrimEnd('=').Replace('+', '-').Replace('/', '_')

    Write-Host '新しい固定署名鍵をローカルに作成します...'
    & $keytool.Source -genkeypair -noprompt -storetype PKCS12 `
        -keystore $keystore -alias pokesleeprater -keyalg RSA -keysize 3072 `
        -validity 11000 -dname 'CN=PokeSleepRater, OU=Android, O=automationseDev, C=JP' `
        -storepass $password -keypass $password
    if ($LASTEXITCODE -ne 0) {
        if (Test-Path $keystore) { Remove-Item $keystore -Force }
        throw '署名鍵の生成に失敗しました。'
    }
    [System.IO.File]::WriteAllText(
        $passwordFile, $password, [System.Text.UTF8Encoding]::new($false)
    )
} else {
    Write-Host '既存の固定署名鍵を再利用します（新しい鍵は作りません）。'
    $password = [System.IO.File]::ReadAllText($passwordFile).Trim()
}

& $keytool.Source -list -storetype PKCS12 -keystore $keystore `
    -storepass $password -alias pokesleeprater | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw '署名鍵の確認に失敗しました。バックアップの対応関係を確認してください。'
}

Write-Host 'GitHub ActionsのSecretsを登録します...'
$encoded = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($keystore))
$encoded | & $gh.Source secret set PSR_KEYSTORE_BASE64 --repo $Repository
if ($LASTEXITCODE -ne 0) { throw 'PSR_KEYSTORE_BASE64 の登録に失敗しました。' }
$password | & $gh.Source secret set PSR_KEYSTORE_PASSWORD --repo $Repository
if ($LASTEXITCODE -ne 0) { throw 'PSR_KEYSTORE_PASSWORD の登録に失敗しました。' }

Write-Host ''
Write-Host '完了：同じ署名鍵で今後のAPKをビルドできます。' -ForegroundColor Green
Write-Host "署名鍵（必ずバックアップ）: $keystore"
Write-Host "パスワード（必ずバックアップ）: $passwordFile"
Write-Warning '鍵とパスワードを紛失すると、同じ署名でアップデートできなくなる可能性があります。GitHubの公開リポジトリには絶対にコミットしないでください。'
Write-Warning '以前のランダムなDebug署名版から固定署名版への切り替えは、基本的に一度だけアンインストールが必要です。'

if (-not $SkipWorkflowDispatch) {
    & $gh.Source workflow run android-build.yml --repo $Repository --ref main
    if ($LASTEXITCODE -ne 0) {
        throw 'Secretsは登録されましたが、ワークフローの手動起動に失敗しました。GitHub Actionsから手動実行できます。'
    }
    Write-Host "Actions: https://github.com/$Repository/actions"
}
