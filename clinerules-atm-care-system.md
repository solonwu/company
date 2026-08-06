# .clinerules — atm-care-system 專案規則

> 使用方式：把這份檔案存成專案根目錄下的 `.clinerules`（或放進 `.clinerules/` 資料夾內，
> 檔名例如 `.clinerules/01-project-rules.md`），Cline 每次執行任務都會自動讀取。
> 這份規則是特別針對「Cline + Gemma 4 本機模型」的組合設計的：
> 小模型在多步驟自主任務中容易失去連貫性，所以規則會刻意收緊範圍、限制自由發揮。

---

## 一、專案基本資訊（每次任務都要遵守，不可自行變更）

- 專案名稱：atm-care-system
- 專案路徑：D:\ai-project\atm-care-system
- 架構：前後端分離
  - 後端：backend/（Spring Boot 3.x, Java 21, Maven）
  - 前端：frontend/（Angular standalone components + Angular Material）
- 資料庫：SQL Server，資料庫名稱 FATCA
- 主要資料表：PB2503MD_ATMCardlessDepositTrackMain

## 二、欄位對照表（禁止自己另外發明欄位名稱）

| 報表欄位 | Entity/DTO 欄位 | DB 欄位 | 型別 |
|---|---|---|---|
| 交易日期 | tranDate | TranDate | date |
| 分行別 | brno | Brno | varchar(10) |
| 分行名稱 | brnoName | BrnoName | varchar(10) |
| 客戶帳號 | custAcc | CustAcc | varchar(15) |
| 客戶姓名 | custName | CustName | nvarchar(50) |
| 電話 | custPhone | CustPhone | varchar(15) |
| 評估說明 | caption | Caption | nvarchar(100) |
| 簽核狀態 | flowFlag | FlowFlag | char(1)，見下方 Enum 對照 |
| 登錄人員 | applicateUser | ApplicateUser | nvarchar(20) |
| 登錄時間 | applicateTime | ApplicateTime | datetime |
| 簽核主管 | approveUser | ApproveUser | nvarchar(20) |
| 簽核時間 | approveTime | ApproveTime | datetime |

非報表欄位但保留於 Entity：`mainListId`(PK)、`dataDate`、`expDate`(關懷處理期限)、
`contactStatus`(是否已聯絡客戶，代碼待確認)、`approveRole`(應送哪個角色簽核，代碼待確認)。

**FlowFlag Enum 對照**（假設值，尚未經 DBA 核對，修改時只能改 Converter 這一個檔案）：
`'1'`=PENDING_INPUT 待登打／`'2'`=PENDING_APPROVE 待覆核／`'3'`=APPROVED 已核准／`'4'`=REJECTED 已退回

## 三、業務流程（修改業務邏輯前必須重讀這段）

1. 系統從 FATCA 撈異常資料，初始 `flowFlag='1'`。
2. 經辦（CLERK）：只能編輯 `flowFlag` 為 `'1'` 或 `'4'` 的案件，登打 `caption` 後送出，
   送出後 `flowFlag='2'`，記錄 `applicateUser`/`applicateTime`。
3. 主管（SUPERVISOR）：只能操作 `flowFlag='2'` 的案件，核准後 `flowFlag='3'`（記錄
   `approveUser`/`approveTime`），退回後 `flowFlag='4'`（退回原因不寫入 DB，因表格無對應欄位）。
4. 任何違反上述狀態機的操作，後端必須回傳明確錯誤訊息，不可以默默放行。

---

## 四、行為規範（給 Cline + Gemma 4 的執行規則，優先度最高）

1. **一次只做一件事**。收到任務後，先用一句話說明你打算怎麼做、會動到哪些檔案，
   再開始執行；不要一次規劃五步以上，做完一步再看結果決定下一步。
2. **每次工具呼叫後，先確認結果再繼續**，不要連續呼叫多個工具卻不檢查中間輸出。
3. **不確定的 API/屬性名稱，一律不要憑印象生成**。特別是 Angular Material：
   只能使用官方文件中真實存在的屬性/事件名稱（例如 `mat-datepicker-toggle` 正確
   綁定屬性是 `[for]`，沒有 `[forDatepicker]`）。不確定時，寧可用最基本、最保守的寫法，
   或先在回覆中標註「⚠️此用法未確認，請查證」。
4. **修改檔案前先讀取現有內容**，不要憑空覆寫已存在的檔案；若檔案不存在才新建。
5. **禁止一次修改超過 3 個檔案**（除非任務本身明確要求跨檔案重構）。
   遇到需要動到多個檔案的任務，先拆解成子步驟，一步一步確認再繼續。
6. **狀態機/角色權限相關邏輯，一律寫成獨立、可單元測試的方法或類別**
   （例如 FlowFlag 狀態轉換規則寫成獨立 Service 方法），不要在多個地方各自重複判斷。
7. **遇到規格不明確或與本文件衝突的情況，先停下來詢問，不要自行假設後直接動工。**
8. **不要引入本文件未提及的新框架/新依賴**，除非任務明確要求。
9. **禁止刪除或大幅改寫既有測試**，除非任務本身就是要修測試。

## 五、程式碼風格

- 後端：Java 21，套用 Lombok 減少樣板碼；分層維持 controller / service / repository /
  entity / dto / enums / converter / exception / config。
- 前端：Angular standalone components，Reactive Forms 做表單驗證；
  不使用 NgModule 寫法。
- 命名一律遵循第二節「欄位對照表」，禁止中途改名。

## 六、Cline 操作建議（人為設定，非模型規則，但務必配合）

- 使用 **Plan 模式**確認計畫後才切到 **Act 模式**執行，先不要開 YOLO 模式。
- 若透過 Ollama 執行 Gemma 4，請確認 `num_ctx` 已調高（建議至少 16K，
  複雜任務建議 32K 以上），避免上下文被截斷導致規則被遺忘。
- 若任務執行到一半發現 Gemma 4 明顯偏離本文件規則（例如自創欄位名稱、
  跳過狀態機檢查），請立即中止並重新以更小範圍的任務重新下指令，
  不要讓它繼續往下累積錯誤。
