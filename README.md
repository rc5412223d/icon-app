# PocketCam Android

Android 攝影機端，目標設備 Samsung Galaxy Note10 Lite / Android 13。

## 開啟與編譯
使用 Android Studio 開啟此 repository，等待 Gradle Sync，選擇 app 設定並執行，或使用 Build > Build APK(s)。

## 使用
1. 先部署 `icon-web` 的 Cloudflare Worker。
2. 在 App 輸入 Worker 的 HTTPS 網址（不要加尾端斜線）。
3. 授予相機、麥克風與通知權限。
4. 點擊「開始攝影機」，把畫面顯示的房間 ID 與六位數配對碼輸入網頁端。
5. 點擊網頁「連線觀看」。
6. 結束時回到 App 點擊「停止直播」。

## 目前版本
- Camera2 相機擷取（預設後鏡頭）
- WebRTC 音訊／影像軌道
- WebSocket 訊號交換
- 六位數配對碼
- Android 前景服務通知

## 限制
- 這是第一版基礎串流實作，尚未加入 TURN 設定介面、動態偵測、多攝影機與背景服務重連。
- STUN 不保證所有跨網路環境都能直連。若連線狀態卡在 checking/failed，需部署 TURN（例如 coturn），並將短效憑證加入 App 與網頁設定。
- 請先在同一個 Wi-Fi 測試，再測試跨網路。
- Android 會顯示持續通知；直播需由使用者主動啟動，也可隨時停止。
