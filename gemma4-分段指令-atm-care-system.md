# Gemma 4 分段指令：ATM無卡存款異常交易關懷報表系統（atm-care-system）

> 使用方式：依序貼「第1段」→ 確認輸出合理 → 貼「第2段」→ 依此類推。
> 每段都已重複必要的欄位對照/DDL資訊，避免 Gemma 4 在多輪對話中忘記規格。
> 每段結束後，建議先看過 Gemma 4 產出的內容再貼下一段，發現不對就當場糾正，
> 不要累積到最後才一次抓錯。

---

## 【第1段】專案規劃（先不要寫程式碼）

```
你是一個資深全端工程師，我要分階段跟你討論並開發一個系統，這是第1段：專案規劃。
請只列出實作計畫（檔案結構樹狀圖、主要類別/元件清單），這一段不要輸出程式碼。

【專案基本資訊】
- 專案名稱：atm-care-system
- 專案路徑：D:\ai-project\atm-care-system
- 架構：前後端分離
  - 後端：Spring Boot 3.x，Java 21，Maven，放在 backend/
  - 前端：Angular（最新穩定版，standalone components）+ Angular Material，放在 frontend/
- 資料庫：SQL Server，資料庫名稱 FATCA
- 主要資料表：PB2503MD_ATMCardlessDepositTrackMain，DDL如下：

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

【報表名稱】ATM無卡存款異常交易關懷報表(總行)

【報表欄位對應】（Entity/DTO 請用這些英文命名，不要自己另外亂取名）
1. 交易日期  → tranDate      ← TranDate
2. 分行別    → brno          ← Brno
3. 分行名稱  → brnoName      ← BrnoName
4. 客戶帳號  → custAcc       ← CustAcc
5. 客戶姓名  → custName      ← CustName
6. 電話      → custPhone     ← CustPhone
7. 評估說明  → caption       ← Caption（經辦登打欄位）
8. 簽核狀態  → flowFlag      ← FlowFlag（Enum，見下）
9. 登錄人員  → applicateUser ← ApplicateUser
10. 登錄時間 → applicateTime ← ApplicateTime
11. 簽核主管 → approveUser   ← ApproveUser
12. 簽核時間 → approveTime   ← ApproveTime

非報表欄位但保留在 Entity：mainListId(PK)、dataDate、expDate(關懷處理期限)、
contactStatus(是否已聯絡客戶，代碼待確認)、approveRole(應送哪個角色簽核，待確認)

【FlowFlag 簽核狀態 Enum 對照】（假設值，之後需與DBA核對）
'1'=PENDING_INPUT 待登打, '2'=PENDING_APPROVE 待覆核, '3'=APPROVED 已核准, '4'=REJECTED 已退回

【業務流程】
1. 系統從FATCA撈異常資料，初始 flowFlag='1'(待登打)
2. 經辦(CLERK)：查詢清單→登打caption→送出後flowFlag='2'(待覆核)，記錄applicateUser/applicateTime；
   只有flowFlag為'1'或'4'的案件可編輯
3. 主管(SUPERVISOR)：查詢flowFlag='2'的案件→核准(flowFlag='3'，記錄approveUser/approveTime)
   或退回(flowFlag='4'，退回原因不寫入DB，因表格沒有對應欄位)
4. 兩角色皆可依交易日期區間/分行別/簽核狀態/客戶帳號查詢，並匯出Excel報表

請輸出檔案結構樹狀圖與主要類別/元件清單即可，不用寫程式碼。
```

---

## 【第2段】後端：Entity / Enum / Converter / DTO / Repository

```
延續前面規格，這是第2段：請完整輸出以下後端程式碼（不要重複第1段已完成的規劃說明）。

先初始化 Spring Boot 3.x + Java 21 + Maven 專案於 backend/，
groupId=com.bank.atm, artifactId=atm-care-system，
依賴：Spring Web, Spring Data JPA, Validation, mssql-jdbc, Lombok, Spring Security,
Apache POI(匯出Excel), springdoc-openapi。

application.yml 資料庫連線用環境變數 SPRING_DATASOURCE_URL/USERNAME/PASSWORD 預留位置，
不要寫死帳密，URL格式：
jdbc:sqlserver://<host>:1433;databaseName=FATCA;encrypt=true;trustServerCertificate=true

請建立：
1. Entity: AtmCardlessDepositTrack 對應 PB2503MD_ATMCardlessDepositTrackMain，
   主鍵 mainListId 對應 MainListId(identity)，欄位對應規則同第1段的報表欄位對應表，
   完整包含 dataDate/expDate/contactStatus/approveRole 這些非報表欄位。
2. Enum: FlowFlag（PENDING_INPUT, PENDING_APPROVE, APPROVED, REJECTED），
   並用 JPA AttributeConverter 做 char(1) <-> Enum 轉換（'1'~'4'對照第1段），
   在Converter類別加註解 "// TODO: 請與資料庫實際代碼定義核對"
3. Repository: AtmCardlessDepositTrackRepository（JpaRepository + JpaSpecificationExecutor，
   支援動態查詢 tranDate區間、brno、flowFlag、custAcc/custName模糊查詢）
4. DTO：
   - AtmCareListDTO（列表用，含12個報表欄位）
   - AtmCareDetailDTO（明細用，含全部欄位）
   - AssessmentInputRequest（caption）
   - ApproveRequest（approve boolean, rejectReason選填）
   - SearchCriteria（dateFrom, dateTo, brno, flowFlag, custAcc, custName, 分頁參數）
```

---

## 【第3段】後端：Service / Controller / Security / 例外處理

```
延續前面規格，這是第3段：請完整輸出後端 Service 與 Controller 層。

API設計（路徑前綴 /api/v1/atm-care）：
1. GET  /                          分頁查詢清單
2. GET  /{mainListId}               查詢單筆明細
3. PUT  /{mainListId}/assessment    經辦登打caption並送出覆核
                                     （僅CLERK；flowFlag需為PENDING_INPUT或REJECTED）
4. PUT  /{mainListId}/approve       主管覆核核准/退回
                                     （僅SUPERVISOR；flowFlag需為PENDING_APPROVE）
5. GET  /export                     匯出Excel報表（Apache POI，標題「ATM無卡存款異常交易關懷報表(總行)」，
                                     欄位順序依12個報表欄位，套用查詢條件）

請加入：
- 統一例外處理 @ControllerAdvice
- 狀態轉換業務規則檢查（例如已核准案件不可再編輯，回傳明確錯誤訊息與HTTP狀態碼）
- Spring Security簡易角色驗證（先用記憶體使用者或JWT骨架，註記之後接公司SSO/LDAP）
- Swagger註解

完成後列出所有API的路徑、方法、角色權限、request/response範例。
```

---

## 【第4段】前端：Angular專案骨架與路由

```
延續前面規格，這是第4段：請在 frontend/ 初始化 Angular 最新穩定版專案。

- Standalone components架構 + Angular Material
- ⚠️請務必只用Angular Material官方存在的屬性名稱，例如 mat-datepicker-toggle 的
  綁定屬性是 [for]，沒有 [forDatepicker] 這種屬性，不確定的元件請用最基本常見的用法
- 路由：
  /login
  /cases        案件清單頁(依角色顯示不同操作按鈕)
  /cases/:id    案件明細頁(經辦登打 或 主管覆核，依角色顯示不同表單)
- 建立 core/(http interceptor、auth guard、角色權限判斷)
- 建立 shared/(共用元件，如查詢條件列、狀態標籤)
- 建立 features/cases/(清單、明細、service)
- 設定 proxy.conf.json(後端8080、前端4200，避免CORS問題)

先產出專案骨架與路由設定，暫不串接後端API。
```

---

## 【第5段】前端：案件清單頁與明細頁實作

```
延續前面規格，這是第5段：請實作以下頁面，串接第3段定義的後端API。

⚠️再次提醒：Angular Material元件只能用官方真實存在的屬性/事件名稱，
若你不確定某屬性是否存在，請採用最簡單保守的寫法，不要自創屬性名稱
（例如 mat-datepicker-toggle 正確用法是 [for]="picker"）。

1. 案件清單頁 (/cases)
   - 查詢條件：交易日期區間、分行別、簽核狀態、客戶帳號/姓名
   - 表格顯示12個報表欄位（交易日期、分行別、分行名稱、客戶帳號、客戶姓名、電話、
     評估說明、簽核狀態、登錄人員、登錄時間、簽核主管、簽核時間）
   - 分頁、排序
   - expDate已逾期但仍是「待登打」的案件，用紅字或標籤提示
   - 「匯出Excel報表」按鈕
   - CLERK看到「登打」按鈕(僅待登打/已退回可點)，SUPERVISOR看到「覆核」按鈕(僅待覆核可點)

2. 案件明細頁 (/cases/:id)
   - 顯示完整交易資訊(唯讀欄位)
   - CLERK模式：評估說明可編輯文字區塊(Reactive Forms，必填，maxlength=100，
     因DB欄位Caption是nvarchar(100)) + 送出覆核按鈕
   - SUPERVISOR模式：評估說明唯讀顯示 + 核准/退回按鈕(退回需填原因，
     此原因只送到後端API參數，不寫回資料庫，因表格沒有對應欄位)

請加上loading狀態與錯誤訊息提示(Snackbar)。
```

---

## 【第6段】整合、README、待確認事項

```
延續前面規格，這是第6段，也是最後一段：

1. 確認前後端可本機串接(後端8080、前端4200)
2. 撰寫README.md，說明如何啟動前後端、環境需求(JDK21、Node版本)、資料庫連線設定方式
3. 列出目前尚待我確認/提供的事項清單，特別是：
   - FlowFlag/ContactStatus/ApproveRole 的真實代碼定義是否正確
   - 退回原因是否需要另建歷程表保存
   - 使用者/角色資料來源(是否串公司SSO/LDAP)
   - 異常資料如何寫入主表(是否已有批次程式，或需另外開發匯入功能)
```

---

## 補充提醒

- 每段開始前，若你發現 Gemma 4 前一段已經忘記某些規格（例如欄位命名跑掉、
  FlowFlag對照錯誤），先在該段開頭補一句提醒或貼回對照表，再往下走。
- 若你是用 Cline 或其他 Agent CLI 執行，建議依照我先前給你的建議：
  拉高 Ollama 的 context window(num_ctx 至少16K以上)，並縮小每一段任務範圍，
  不要一次要求它跨檔案自主完成太多東西。
- 每段完成後建議 `git commit` 一次，方便追蹤是哪一段開始出問題。
