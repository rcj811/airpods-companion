# Pods 管家 — AirPods for Android

在 Android 上管理 AirPods：電量、連線彈窗、降噪模式、EQ 音效。

## 功能
- **連線即顯示電量**：AirPods 連上手機（或打開充電盒）時，從底部滑出 iPad 風格的電量卡片（左耳 / 右耳 / 充電盒）
- **常駐通知**：即時電量，含「顯示電量」「切換降噪」按鈕；低於 20% 提醒
- **聲音模式**：降噪 / 通透 / 自適應 / 關閉、對話感知；快速設定磚一鍵切換
- **EQ 音效**
  - 原音校正：依 AutoEq 實測資料，把你的耳機調成中性曲線
  - 模擬其他耳機：讓 AirPods 聽起來像 Sony / Bose / Galaxy Buds / HD 600…
  - 風格音效：重低音、人聲、跑步、Podcast…
  - 10 段手動均衡器

## 相容性
| 功能 | 需求 |
|---|---|
| 電量、彈窗、EQ | Android 10 以上，免 root |
| 降噪 / 對話感知 | Pixel Android 16 QPR3+、ColorOS/OxygenOS 16、Android 17，或已 root |

Apple 官方 EQ 預設與韌體更新只能在 Apple 裝置上使用。

## 下載與更新
每次推送到 `main`，GitHub Actions 會自動編譯，APK 在 Releases 的 **latest**（`pods-companion.apk`）。
App 會在開啟時與每 3 小時自動檢查 `version.json`，有新版就下載並安裝（第一次需允許「安裝未知應用程式」）。
所有版本都用 `ci/release.jks` 簽章，因此新版可直接覆蓋安裝、保留設定。

## 致謝
- [LibrePods](https://github.com/librepods-org/librepods)、[CAPod](https://github.com/d4rken-org/capod)：AirPods 協定的公開逆向研究（本專案為獨立實作）
- [AutoEq](https://github.com/jaakkopasanen/AutoEq)（MIT）：耳機頻響校正資料（Rtings／B&K 5128 量測）
