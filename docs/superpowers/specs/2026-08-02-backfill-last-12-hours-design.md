# 空檔補步最近 12 小時範圍設計

## 目標

讓 Mode 2「空檔補步」提供一個預設勾選的選項，將掃描與補步範圍限制為目前時間往前 12 小時到目前時間，避免預設從當地時間凌晨 00:00 開始補送。取消勾選時維持原本的當地午夜到目前時間行為。

## 設計

- `StepAvailabilityPlanner` 提供純函式計算最近 12 小時的時間範圍；既有當地午夜範圍邏輯保留。
- `HealthConnectStepWriter` 提供依範圍模式讀取 Mode 2 availability 的方法，統一處理最近 12 小時與當地午夜兩種策略。
- `MainActivity` 以 `rememberSaveable` 保存選項狀態，預設為 `true`。一般掃描與補步前的 fresh re-scan 都傳入相同選項；實際寫入沿用 fresh scan 回傳的固定 `rangeStart`／`rangeEnd`。
- 切換選項時清除舊 availability，觸發新的掃描，避免顯示與目前選項不一致的容量或空檔。
- `ModeBackfillCard` 顯示 Checkbox、動態範圍說明與掃描按鈕文字。

## 邊界與錯誤處理

- 最近 12 小時範圍固定為 `[now - 12h, now]`，不因跨日而縮短。
- 取消勾選時沿用既有午夜邊界行為：若剛好是當地 00:00，尚無正長度掃描範圍。
- Health Connect 讀取或補步失敗時，沿用現有狀態訊息與部分完成處理。

## 驗證

- 單元測試確認最近 12 小時範圍起訖時間，以及既有午夜範圍行為不變。
- 執行 `:app:testDebugUnitTest`、`:app:lintDebug` 與 `:app:assembleDebug`。
- README 更新 Mode 2 預設範圍、取消選項後的行為與手動驗證步驟。
