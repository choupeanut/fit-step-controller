# 活力步步 Logo 設計規格

## 目標

為 Fit Step Controller 建立一個活潑、親和且在 Android launcher 小尺寸下仍清楚的 Logo，並將它套用到 app 的 adaptive launcher icon 與 round icon。

## 設計方向

- 概念名稱：活力步步
- 主圖形：兩個向右上前進的貓咪肉球；每個肉球由一個圓潤掌墊與四個 toe beans 組成，左下肉球較大，右上肉球較小，形成連續步伐與進度感。
- 動態元素：一個天空藍圓角亮點位於腳印前方，表達下一步與持續前進。
- 背景：暖米白，讓明亮色彩在 launcher 圖示上保持辨識度。
- 色彩：珊瑚橘 `#FF6B6B`、薄荷綠 `#4FD1B5`、天空藍 `#5BA7FF`、暖米白 `#FFF7F2`。
- 形式：不放文字、不使用細線；所有形狀使用粗圓角幾何，以適應 48dp 左右的 launcher 尺寸。
- 品牌語意：貓咪肉球代表步數與親和陪伴，右上方向代表進展，明亮配色代表活力；不暗示醫療或感測器資料。

## Android 資源策略

- 使用 `mipmap-anydpi-v26` adaptive icon，foreground 使用 vector drawable，background 使用純色 drawable。
- `ic_launcher` 與 `ic_launcher_round` 共用同一套 mark，避免圓形 launcher 裁切時產生不同品牌圖形。
- 保留現有 application label、package name、功能與 Health Connect 資料流程不變。
- 由 Android adaptive icon 處理 launcher mask；圖形內容保留足夠內距，避免圓形、圓角方形與 squircle mask 裁切腳印或亮點。

## 驗收條件

1. Debug APK 可成功建置。
2. AndroidManifest 的 launcher icon 仍解析到 `ic_launcher`／`ic_launcher_round`，且資源可在 API 28+ 使用。
3. foreground XML 僅包含相容的 vector path／shape，沒有外部字型或 runtime 網路依賴。
4. Logo 更新不改變 Mode 1／Mode 2、Health Connect 權限或步數寫入行為。
5. 使用 `aapt2`／Gradle 資源處理確認 adaptive icon 與 vector 資源可打包；若有可用的圖片檢視工具，檢查輸出圖示仍保持雙腳印與亮點構圖。
