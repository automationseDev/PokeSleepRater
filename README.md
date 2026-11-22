# PokeSleepRater — Pokémon Sleep 個体評価アシスタント

Android向けの非公式・オープンソース試作アプリ。ユーザーの許可を得てゲーム画面を読み取り、日本語OCRから性格とサブスキルを抽出し、役割別の暫定評価を表示します。

- Android 11以上
- Jetpack Compose / Kotlin
- MediaProjectionで画面を取得し、ML Kitの日本語OCRを端末上で実行
- スクリーンショットと読み取り文字は外部に送信しない
- きのみ・食材・スキル得意別の暫定スコアとフローティング表示

**試作版であり、現時点のスコアは正確なゲーム内期待値や種族固有の評価を保証しません。** 取得できない情報を推測せず、将来の食材構成・ミント・サブスキルのたね等は未対応です。

## GitHub Actions

`main` へのPush、PR、手動実行でユニットテストとDebug APKビルドを行います。
成功すると **Actions → Android Build (Debug APK) → Artifacts** にAPKが保存されます。

ローカルビルドにはJDK 17・Android SDK 35とGradle 8.9が必要です。

> このリポジトリは任天堂、株式会社ポケモンおよびPokémon Sleep公式とは無関係です。
