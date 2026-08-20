# 空檔補步最近 12 小時範圍設計

## 目標

讓 Mode 2「空檔補步」提供一個預設勾選的選項，將掃描與補步範圍限制為當地今天內、最多從目前時間往前 12 小時，避免範圍跨到前一天而造成 Google Fit 無法讀取 Health Connect 步數。取消勾選時維持原本的當地午夜到目前時間行為。

## 設計

- `StepAvailabilityPlanner.todayUpToLast12HoursRange(now: Instant, zoneId: ZoneId)` 提供純函式計算 `[max(now - 12h, local midnight), now]`，回傳型別為 `Pair<Instant, Instant>?`；若沒有正長度範圍則回傳 `null`。既有當地午夜範圍邏輯保留。
- `HealthConnectStepWriter` 已持有預設為 `ZoneId.systemDefault()` 的 `zoneId`，由 `readBackfillAvailability(limitToLast12Hours = true)` 將同一時區傳給範圍函式，統一處理「今天內最多最近 12 小時」與「當地午夜至現在」兩種策略。
- `MainActivity` 以 `rememberSaveable` 保存 `limitToLast12Hours`，預設為 `true`。一般掃描與補步前的 fresh re-scan 都傳入相同選項；實際寫入沿用 fresh scan 回傳的固定 `rangeStart`／`rangeEnd`。
- 切換選項時清除舊 availability，觸發新的掃描，避免顯示與目前選項不一致的容量或空檔。
- `ModeBackfillCard` 顯示 Checkbox、動態範圍說明與掃描按鈕文字，明確說明勾選模式只會使用今天內的最近 12 小時。

## 資料流與並行

- 一般掃描呼叫 `readBackfillAvailability` 取得固定範圍；補步按下後再呼叫同一入口 fresh re-scan，並把 fresh 結果的 `rangeStart`／`rangeEnd` 傳入 `backfillAvailableSteps`。
- `backfillAvailableSteps` 內的每次重新讀取、配置及寫入都只使用傳入的固定範圍。因此任何 Mode 2 配置與寫入區間都不得早於該次 fresh scan 所使用的當地午夜。
- `availabilityScanning` 在掃描或整個補步批次期間維持為 `true`，Checkbox、重新整理與寫入按鈕在此期間停用。操作開始時會先同步設為 `true` 再啟動 coroutine，避免切換範圍與舊操作競爭。
- Checkbox 只可在沒有掃描或寫入時切換；切換後清除舊結果，由 `LaunchedEffect` 依新選項重新掃描，所以舊範圍的容量不會覆蓋新狀態。

## 邊界與錯誤處理

- 勾選模式的範圍固定為 `[max(now - 12h, local day start), now]`：從當日起點經過 12 個實際小時以前皆由當日起點開始，之後維持完整 12 小時。一般 24 小時日的切換點是本地中午；DST 切換日可能不是 12:00。
- 正好在當地 00:00 時，勾選與取消勾選都沒有正長度掃描範圍，回傳 `null`。
- `now - 12h` 使用固定 `Duration`，本地午夜使用 `zoneId` 的日曆與 `atStartOfDay` 計算，以正確處理 DST。掃描開始後固定該次範圍；裝置時區變更會在下一次建立 writer／掃描時生效。
- Health Connect 讀取或補步失敗時，沿用現有狀態訊息與部分完成處理。

## 驗證

- planner 單元測試確認一般日期在當日起點後 12 小時內截斷、之後維持完整 12 小時、正好午夜時沒有有效範圍，以及非 UTC 時區與 DST gap／overlap 日期的語意；既有午夜範圍行為維持不變。
- writer 測試確認一般掃描使用截斷後的 `rangeStart`／`rangeEnd`；程式路徑檢查確認 fresh re-scan 的結果原樣傳入 `backfillAvailableSteps`，且其內部所有讀取與配置均重用固定範圍。
- 執行 `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 與 `:app:assembleRelease`。
- README 更新 Mode 2 預設範圍、取消選項後的行為與手動驗證步驟。
- 自動驗收以 Health Connect 的查詢、配置與寫入都不早於當地午夜為準。Google Fit 是非同步、可能套用來源優先順序的選用 consumer；其顯示結果只列為非阻擋手動觀察，不作為本次修正成功的保證。
