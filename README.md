# URLFilter

## 概要

こアプリケーションは、Android のアクセシビリティサービスを利用して、ユーザーが特定のウェブサイトにアクセスしようとした際に検知し、設定に基づいて別のページにリダイレクトする機能を提供します。

設定は Firebase Remote Config を通じて動的に更新することが可能です。

## 主な機能

*   **URLフィルタリング**: 指定されたブラウザアプリケーション（Chromeなど）で開かれるURLを監視します。
*   **リダイレクト**: 設定された制限リストに含まれるURLへのアクセスがあった場合、指定された別のURLへリダイレクトします。
*   **動的な設定更新**: Firebase Remote Config を利用して、制限するURLのリストやリダイレクト先URLをアプリのアップデートなしに更新できます。
*   **アクセシビリティ設定の保護**: 意図しないアクセシビリティサービス設定の変更を防ぐため、特定条件下で設定画面へのアクセスを制限する機能があります (オプトイン)。

## 使用技術

*   Kotlin
*   Android Accessibility Service
*   Firebase
    *   Firebase Analytics
    *   Firebase Remote Config
    *   Firebase Installations
*   Jetpack Compose (UI構築)

## セットアップ

このプロジェクトは `Firebase` を使用します。

Andoroid での Firebase の詳しいセットアップは Firebase のドキュメントを参照して下さい。

その他は他の Android プロジェクト同様、gradle 経由でビルド、パッケージングを行います。

## 注意事項

このアプリケーションはアクセシビリティサービスを使用します。

インストール後、ユーザーは明示的にこのサービスを有効にする必要があります。
