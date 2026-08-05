# Gemma 4 開發提示詞：ATM無卡存款異常交易關懷報表系統（atm-care-system）

> 使用說明同前：Gemma 4 沒有 CLAUDE.md 那種專案記憶機制，請整份貼給 Gemma 4，
> 或依文末「分段執行建議」拆成多次貼上。這次因為已提供真實 DDL，
> 欄位命名不需要再用推測，品質會比上次更準確。

---

## 欄位對應表（先自己確認一次，避免 Gemma 4 對應錯誤）

| 報表欄位 | 資料表欄位 | 型別 | 備註 |
|---|---|---|---|
| 交易日期 | TranDate | date | |
| 分行別 | Brno | varchar(10) | |
| 分行名稱 | BrnoName | varchar(10) | 欄位長度僅10，可能存簡稱 |
| 客戶帳號 | CustAcc | varchar(15) | |
| 客戶姓名 | CustName | nvarchar(50) | |
| 電話 | CustPhone | varchar(15) | |
| 評估說明 | Caption | nvarchar(100) | 經辦登打欄位 |
| 簽核狀態 | FlowFlag | char(1) | ⚠️需你確認實際代碼定義，下方為假設值 |
| 登錄人員 | ApplicateUser | nvarchar(20) | |
| 登錄時間 | ApplicateTime | datetime | |
| 簽核主管 | ApproveUser | nvarchar(20) | |
| 簽核時間 | ApproveTime | datetime | |

**表格中還有但不在報表欄位內的欄位**（Prompt 裡會保留，供業務邏輯使用）：
- `MainListId`：PK，int identity
- `DataDate`：資料日期（可能是批次撈取日）
- `ExpDate`：期限日期（可能是關懷處理期限，逾期需提醒）
- `ContactStatus`：char(1)，⚠️用途待確認，暫定為「是否已聯絡客戶」的狀態
- `ApproveRole`：nvarchar(6)，⚠️用途待確認，暫定為「此案件應送哪個角色簽核」

**⚠️ FlowFlag 假設值**（因為原始 DDL 沒有 CHECK CONSTRAINT 或註解說明代碼意義，
下面是我依照你先前的業務流程「待登打→待覆核→已核准/已退回」推測的假設，
**請務必在開發前跟你確認或跟既有系統/DBA 核對**，如果不對，之後改一個地方（Enum 對照表）即可）：
- `'1'` = 待登打 PENDING_INPUT
- `'2'` = 待覆核 PENDING_APPROVE
- `'3'` = 已核准 APPROVED
- `'4'` = 已退回 REJECTED

---

## 完整規格 Prompt（可整份貼上，或依文末建議分段使用）

```
你是一個資深全端工程師，請依照以下規格，開發一個前後端分離系統。
請先列出你的實作計畫（檔案結構、主要類別/元件清單），再開始輸出程式碼。

【專案基本資訊】
- 專案名稱：atm-care-system
- 專案路徑：D:\ai-project\atm-care-system
- 架構：前後端分離
  - 後端：Spring Boot 3.x，Java 21，Maven 專案，放在 backend/ 目錄
  - 前端：Angular（最新穩定版，standalone components），放在 frontend/ 目錄
  - 前端使用 Angular Material，請務必使用官方正確的 API（例如 mat-datepicker-toggle
    的綁定屬性是 [for]，不是 [forDatepicker]；如果不確定某個 Material 元件的正確屬性名稱，
    請採用該元件最基本、最常見的用法，不要自創屬性名稱）
- 資料庫：SQL Server，資料庫名稱 FATCA
- 主要資料表：PB2503MD_ATMCardlessDepositTrackMain，DDL 如下：

CREATE TABLE [dbo].[PB2503MD_ATMCardlessDepositTrackMain](
	[MainListId] [int] IDENTITY(1,1) NOT NULL,
	[DataDate] [datetime] NULL,
	[TranDate] [date] NULL,
	[ExpDate] [date] NULL,
	[Brno] [varchar](10) NULL,
	[BrnoName] [varchar](10) NULL,
	[CustAcc] [varchar](15) NULL,
	[CustName] [nvarchar](50) NULL,
	[CustPhone] [varchar](15) NULL,
	[ContactStatus] [char](1) NULL,
	[Caption] [nvarchar](100) NULL,
	[FlowFlag] [char](1) NULL,
	[ApproveRole] [nvarchar](6) NULL,
	[ApplicateUser] [nvarchar](20) NULL,
	[ApplicateTime] [datetime] NULL,
	[ApproveUser] [nvarchar](20) NULL,
	[ApproveTime] [datetime] NULL
) ON [PRIMARY]

【報表名稱】
ATM無卡存款異常交易關懷報表(總行)

【報表欄位與資料表欄位對應】（Entity/DTO 請使用以下英文命名，維持與 DB 一致，
避免自己另外亂取名稱）
1. 交易日期        → tranDate       (LocalDate)       ← TranDate
2. 分行別          → brno           (String)          ← Brno
3. 分行名稱        → brnoName       (String)          ← BrnoName
4. 客戶帳號        → custAcc        (String)          ← CustAcc
5. 客戶姓名        → custName       (String)          ← CustName
6. 電話            → custPhone      (String)          ← CustPhone
7. 評估說明        → caption        (String，經辦登打欄位) ← Caption
8. 簽核狀態        → flowFlag       (Enum，見下)       ← FlowFlag
9. 登錄人員        → applicateUser  (String)          ← ApplicateUser
10. 登錄時間       → applicateTime  (LocalDateTime)   ← ApplicateTime
11. 簽核主管       → approveUser    (String)          ← ApproveUser
12. 簽核時間       → approveTime    (LocalDateTime)   ← ApproveTime

其餘欄位（保留在 Entity，非報表顯示欄位，但業務邏輯會用到）：
- mainListId (PK, Integer, identity)
- dataDate (LocalDateTime)
- expDate (LocalDate)：關懷處理期限，若超過此日期仍為「待登打」狀態，前端清單請用顏色標示逾期
- contactStatus (char(1))：是否已聯絡客戶的狀態欄位，先做成 String 保留，Enum 對照表先用
  '0'=未聯絡, '1'=已聯絡，並加註解 "// TODO: 待確認實際代碼定義"
- approveRole (String, 最長6字)：此案件應送哪個角色/單位簽核，先原樣保留字串即可，
  不用特別做關聯查詢

【FlowFlag 簽核狀態 Enum 對照】（char(1) DB值 → Enum）
- '1' → PENDING_INPUT   待登打
- '2' → PENDING_APPROVE 待覆核
- '3' → APPROVED        已核准
- '4' → REJECTED        已退回
請用 JPA AttributeConverter 或 @Convert 做這個 char(1) <-> Enum 的轉換，
並在轉換類別加註解 "// TODO: 請與資料庫實際代碼定義核對"

【業務流程】
1. 系統從 FATCA 資料庫的 PB2503MD_ATMCardlessDepositTrackMain 撈出異常交易資料，
   初始狀態 flowFlag 為 PENDING_INPUT（'1'）。
2. 經辦（角色 CLERK）：
   - 查詢清單（可依 tranDate 區間、brno、flowFlag、custAcc/custName 篩選）
   - 點選案件後，登打 caption（評估說明）欄位並送出
   - 送出後 flowFlag 變更為 PENDING_APPROVE（'2'），並記錄 applicateUser / applicateTime
   - 只有 flowFlag 為 PENDING_INPUT 或 REJECTED 的案件可以編輯
3. 主管（角色 SUPERVISOR）：
   - 查詢 flowFlag 為 PENDING_APPROVE 的案件，檢視經辦登打的 caption
   - 可「核准」（flowFlag 變為 APPROVED，記錄 approveUser / approveTime）
   - 或「退回」（flowFlag 變為 REJECTED，退回原因先另外用 API 參數傳入，
     不寫回資料庫欄位，因為目前資料表沒有退回原因欄位，
     若之後需要保存退回原因，建議另建一張 History/Log 表，先在程式碼加註解說明此擴充點）
4. 兩種角色都可以查詢清單並匯出 Excel 報表，報表標題為
   「ATM無卡存款異常交易關懷報表(總行)」，欄位順序依上述 12 個報表欄位。

【後端需求 Spring Boot / Java 21】
- 分層：controller / service / repository / entity / dto / enums / converter / exception / config
- 資料庫連線：SQL Server，請加入 mssql-jdbc 依賴，application.yml 使用環境變數
  SPRING_DATASOURCE_URL / USERNAME / PASSWORD 預留位置，不要寫死帳密，
  URL 格式範例：jdbc:sqlserver://<host>:1433;databaseName=FATCA;encrypt=true;trustServerCertificate=true
- Entity: AtmCardlessDepositTrack 對應資料表 PB2503MD_ATMCardlessDepositTrackMain，
  主鍵 mainListId 對應 MainListId (identity，不可由程式指定)
- Repository 支援動態條件查詢（tranDate 區間、brno、flowFlag、custAcc/custName 模糊查詢）+ 分頁
- REST API（路徑前綴 /api/v1/atm-care）：
  - GET    /                    分頁查詢清單
  - GET    /{mainListId}        查詢單筆明細
  - PUT    /{mainListId}/assessment  經辦登打 caption 並送出覆核（僅 CLERK，
                                       狀態需為 PENDING_INPUT/REJECTED）
  - PUT    /{mainListId}/approve     主管覆核，核准或退回（僅 SUPERVISOR，
                                       狀態需為 PENDING_APPROVE）
  - GET    /export              匯出 Excel 報表（用 Apache POI，套用查詢條件）
- 簡易角色權限控管（Spring Security，先用記憶體使用者或 JWT 骨架都可以，
  並在程式碼註解說明如何之後接公司 SSO/LDAP）
- 統一例外處理與狀態轉換的業務規則檢查（例如已核准案件不可再編輯，
  要回傳明確的錯誤訊息與 HTTP 狀態碼）

【前端需求 Angular】
- Standalone components + Angular Material（務必用官方正確 API，
  例如 mat-datepicker-toggle 的綁定屬性是 [for]）
- 路由：
  - /login
  - /cases        案件清單頁（依角色顯示不同操作按鈕）
  - /cases/:id    案件明細頁（經辦登打 或 主管覆核，依角色顯示不同表單）
- 案件清單頁：
  - 查詢條件列（交易日期區間、分行別、簽核狀態、客戶帳號/姓名）
  - 表格顯示 12 個報表欄位，含分頁、排序
  - expDate 已逾期但仍是「待登打」的案件，用紅字或標籤提示
  - 「匯出Excel報表」按鈕
  - CLERK 看到「登打」按鈕（僅待登打/已退回可點）
  - SUPERVISOR 看到「覆核」按鈕（僅待覆核可點）
- 案件明細頁：
  - CLERK 模式：評估說明可編輯（Reactive Forms，必填，maxlength=100，
    因為 DB 欄位 Caption 是 nvarchar(100)）+ 送出按鈕
  - SUPERVISOR 模式：評估說明唯讀 + 核准/退回按鈕（退回需填原因，
    此原因只送到後端 API 參數，不會存回資料庫，若要保存需另建歷程表，先加 TODO 註解）
  - loading 狀態與錯誤訊息提示（Snackbar）
- proxy.conf.json 設定，避免開發時 CORS 問題（後端 8080、前端 4200）

【輸出要求】
1. 先輸出完整檔案結構樹狀圖
2. 依 backend → frontend 順序，逐檔輸出完整程式碼（檔名 + 完整內容，不要省略）
3. 最後列出：
   - 啟動方式（後端 mvn 指令、前端 ng serve 指令）
   - 尚待我確認的事項（特別是：FlowFlag/ContactStatus/ApproveRole 的真實代碼定義、
     退回原因是否需要新建歷程表、SSO 串接方式）
```

---

## 分段執行建議（若一次貼太長 Gemma 4 生成品質下降，建議拆成以下 6 次貼）

1. **第1段**：貼「專案基本資訊」「欄位對應表」「FlowFlag Enum對照」「業務流程」，
   請它先輸出檔案結構規劃，暫不寫程式碼。
2. **第2段**：貼「後端需求」，請它做 Entity / Enum / Converter / DTO / Repository。
3. **第3段**：接續請它做 Service / Controller / Security / 例外處理。
4. **第4段**：貼「前端需求」，請它先做 Angular 專案骨架與路由。
5. **第5段**：請它實作案件清單頁與明細頁的元件、Service、表單驗證
   （**特別提醒**：這段開始前先貼一句「請務必只用 Angular Material 官方存在的屬性名稱，
   例如 mat-datepicker-toggle 只有 [for]，沒有 [forDatepicker]」，避免重蹈上次的編譯錯誤）。
6. **第6段**：請它輸出啟動方式、README、以及尚待確認事項清單。

每段開頭都加一句：「延續前面規格，這是第 N 段，請完整輸出程式碼，不要重複前面已完成的部分。」

## 額外提醒

- **FlowFlag / ContactStatus / ApproveRole 的真實代碼定義務必跟 DBA 或既有系統核對**，
  這份 Prompt 裡的對照表是我依你的業務流程描述推測的，如果代碼錯了，
  只要改 Enum Converter 那一個檔案即可，不影響其他程式碼結構。
- 這次因為有真實 DDL，欄位命名已經很明確，Gemma 4 出錯的機率會比上次低很多，
  但 Angular Material 的元件屬性名稱它還是容易記錯（如上次的 forDatepicker 事件），
  建議程式碼生成後，`ng serve` 編譯若報錯，直接把錯誤訊息貼回去讓它修正即可。
