# PokeSleepRater — Pokémon Sleep 個体評価アシスタント

Android向けの非公式・オープンソース試作アプリ。ユーザーの許可を得てゲーム画面を読み取り、日本語OCRから性格とサブスキルを抽出し、役割別の暫定評価を表示します。

- Android 11以上
- Jetpack Compose / Kotlin
- MediaProjectionで画面を取得し、ML Kitの日本語OCRを端末上で実行
- スクリーンショットと読み取り文字は外部に送信しない
- きのみ・食材・スキル得意別の暫定スコアとフローティング表示

**試作版であり、現時点のスコアは正確なゲーム内期待値や種族固有の評価を保証しません。** 取得できない情報を推測せず、将来の食材構成・ミント・サブスキルのたね等は未対応です。

## GitHub Actionsと署名付きAPK（v0.3.0以降）

同じアプリID `dev.automationse.pokesleeprater` と、同じ固定署名鍵でAPKを作れば、
旧APKをアンインストールせず上書き更新できます。GitHub Actionsはビルドごとに
`versionCode = 1000 + github.run_number` を設定して単調増加させます。

- Push（main）・手動実行：単体テスト → Debugコンパイル確認 → **固定署名Release APK** → 署名証明書検証 → Artifactsに保存（30日間）
- PR：単体テストとDebugコンパイル確認のみ。秘密鍵をPRで使用しません。
- **署名鍵を一度登録するまで、mainの署名付きビルドは意図的に失敗します。** 鍵を自動再生成して配ることはしません。

### 初回のみ：署名鍵の生成・登録（Windows PowerShell）

JDK 17（`keytool`）、GitHub CLI（`gh`）、GitHubへのログインが必要です。
ローカルリポジトリを最新化してから、次のスクリプトを1回だけ実行します。

```powershell
git pull origin main
gh auth login
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\setup-signing.ps1
```

このスクリプトは、ユーザーのローカルAppDataに固定の署名鍵とパスワードを保存し、
GitHub Secrets `PSR_KEYSTORE_BASE64` と `PSR_KEYSTORE_PASSWORD`、
GitHub Variable `PSR_SIGNING_CERT_SHA256` を登録し、Actionsを手動起動します。
ローカル鍵が既に存在すれば再利用し、リモートに登録済みなのにローカル鍵がない場合は
**新しい鍵への誤交換を防ぐため停止**します。ローカルの署名鍵ファイルとパスワードは
両方とも安全な場所にオフラインでバックアップしてください。第三者と共有せず、公開リポジトリへ絶対にPushしないでください。

### 更新・インストール

GitHub Actions → `Android CI and Signed APK` → 成功した最新の実行 →
Artifactsの **`PokeSleepRater-signed-update-v0.3.0-...`** を展開して
`app-release.apk` をインストールします。

**注意：従来のランダムなDebug署名APKから固定署名Release APKへの変更時だけ、
基本的に古いアプリのアンインストールが1回必要です。**
既存のDebug版の署名鍵は保持されておらず、同一署名で上書きすることはできません。
一度固定署名Release APKをインストールした後は、以後同じ署名鍵と
より大きいversionCodeが使われる限り、そのまま上書き更新できます。
そのときアプリの通常の保存データは維持されます。

ローカルビルドにはJDK 17・Android SDK 35とGradle 8.9が必要です。

> このリポジトリは任天堂、株式会社ポケモンおよびPokémon Sleep公式とは無関係です。


## 実機で画面解析を開始する

1. PokeSleep Raterで「他のアプリの上に表示」を許可する。
2. 得意タイプを選び「画面解析を開始」を押す。
3. Androidの画面共有許可で **画面全体** を選んで開始する（端末により選択表示は異なる）。
4. Pokémon Sleepに切り替え、ポケモンの性格／サブスキル画面を表示する。
5. フローティング表示を確認する。出ない場合はPokeSleep Raterへ戻り「処理済みOCRフレーム」と「エラー詳細」を確認する。

- フレーム数が増えない：画面共有が動作していない可能性がある。
- フレーム数が増えるが評価されない：OCRで性格やサブスキルを読み取れていない。診断欄のOCRテキストを確認する。
- 評価はあるがゲーム上に浮かばない：オーバーレイ権限を確認する。
- 個体を変える前には「別の個体を評価するためにクリア」を押す。
- 画面共有は毎回ユーザーの許可が必要。画像とOCR結果は外部に送信しない。

今後配布するAPKは上記の固定署名Release版を使用してください。単体テスト用Debug APKは配布しません。
