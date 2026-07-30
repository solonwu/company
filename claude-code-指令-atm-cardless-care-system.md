# Claude Code 建置指令：ATM無卡存款異常交易關懷報表系統

以下是分階段的 Claude Code 指令。建議**依序**在專案目錄下用 `claude` 啟動後，一段一段貼上執行，
每階段完成後先檢查再進行下一階段，避免一次要求太多導致品質下降。

---

## 前置準備（在終端機執行，不是貼給 Claude）

```bash
mkdir -p D:\ai-project\atm-cardless-care-system
cd D:\ai-project\atm-cardless-care-system
claude
```

---

## 階段 0：專案基本資訊（先貼這段讓 Claude 建立 CLAUDE.md 記住規格）

```
請在目前目錄 D:\ai-project\atm-cardless-care-system 建立一份 CLAUDE.md，
記錄以下專案規格，之後所有開發都要遵循：

【專案名稱】atm-cardless-care-system
【專案說明】ATM無卡存款異常交易關懷報表(總行) 管理系統
【架構】前後端分離
  - 前端：Angular（最新穩定版），資料夾 frontend/
  - 後端：Spring Boot 3.x，Java 21，資料夾 backend/
【資料庫】
  - 名稱：FATCA
  - 主表：PB2503MD_ATMCardlessDepositTrackMain
【報表欄位】（對應資料表/DTO 欄位，請你先幫我推論合理的英文欄位命名）
  1. 交易日期 transactionDate
  2. 分行別 branchCode
  3. 分行名稱 branchName
  4. 客戶帳號 accountNo
  5. 客戶姓名 customerName
  6. 電話 phoneNo
  7. 評估說明 assessmentDesc（經辦登打欄位）
  8. 簽核狀態 approveStatus（列舉：待登打PENDING_INPUT / 待覆核PENDING_APPROVE / 已核准APPROVED / 已退回REJECTED）
  9. 登錄人員 inputUser
  10. 登錄時間 inputTime
  11. 簽核主管 approveUser
  12. 簽核時間 approveTime

【核心業務流程】
  1. 系統定期/依查詢條件從 FATCA DB 撈出異常交易資料（來源可能是每日批次寫入主表的原始異常清單，
     初期 approveStatus 預設為「待登打」）。
  2. 經辦(角色 CLERK)：可查詢清單、點選單筆案件，登打「評估說明」欄位，
     送出後狀態變更為「待覆核」，並記錄登錄人員/登錄時間。
  3. 主管(角色 SUPERVISOR)：可查詢「待覆核」案件，檢視經辦登打內容，
     可「核准」（狀態變更為已核准，記錄簽核主管/簽核時間）或「退回」（狀態變更為已退回，
     退回後經辦可重新編輯評估說明再送出）。
  4. 兩種角色皆可依 交易日期區間、分行別、簽核狀態、客戶帳號 查詢清單，並匯出成 Excel 報表
     （報表標題：ATM無卡存款異常交易關懷報表(總行)）。

請先只建立 CLAUDE.md 檔案記錄以上內容，尚不要寫程式碼。
```

---

## 階段 1：初始化後端專案（Spring Boot + Java 21）

```
請依 CLAUDE.md 的規格，在 backend/ 目錄下初始化 Spring Boot 3.x 專案（Java 21, Maven），
套件座標 groupId=com.bank.atm，artifactId=atm-cardless-care-system，
需要的依賴：
  - Spring Web
  - Spring Data JPA
  - Spring Boot Validation
  - Oracle JDBC driver（FATCA 資料庫，若不確定廠牌先用 Oracle，之後我會告知確切資訊）
  - Lombok
  - Spring Security（先做最簡單的角色權限控管，之後再細化）
  - Apache POI（用於匯出 Excel 報表）
  - springdoc-openapi（產生 Swagger API 文件）

請規劃分層架構：controller / service / repository / entity / dto / enums / exception / config，
並設定 application.yml（含 dev/prod profile 骨架，資料庫連線先用預留位置，不要寫死帳密）。

完成後幫我確認可以透過 mvn clean package 成功編譯。
```

---

## 階段 2：後端 Entity / Repository / DTO

```
請依 CLAUDE.md 的欄位規格，建立以下後端程式碼：

1. Entity: AtmCardlessDepositTrack，對應資料表 PB2503MD_ATMCardlessDepositTrackMain，
   欄位對應規則請用底線轉駝峰的合理猜測（若欄位名稱與資料庫實際命名不同，
   我事後會提供 DDL 讓你修正，先以邏輯欄位名稱實作，並在程式加上 TODO 註解標示待確認欄位）。
   包含主鍵設計（建議用交易日期+分行別+客戶帳號+序號 或 UUID，先用 Long id 自動遞增並保留註解說明）。

2. Enum: ApproveStatus（PENDING_INPUT 待登打, PENDING_APPROVE 待覆核, APPROVED 已核准, REJECTED 已退回）

3. Repository: AtmCardlessDepositTrackRepository（JpaRepository + JpaSpecificationExecutor，
   以支援動態查詢：交易日期區間、分行別、簽核狀態、客戶帳號、客戶姓名 模糊查詢）

4. DTO：
   - AtmCardlessDepositTrackListDTO（列表用，含全部12個報表欄位）
   - AtmCardlessDepositTrackDetailDTO（明細用）
   - AssessmentInputRequest（經辦登打用：assessmentDesc）
   - ApproveRequest（主管覆核用：approve boolean, rejectReason 選填）
   - SearchCriteria（查詢條件：dateFrom, dateTo, branchCode, approveStatus, accountNo, customerName, 分頁參數）

請使用 MapStruct 或手動轉換皆可，說明你的選擇。
```

---

## 階段 3：後端 Service / Controller（含權限控管）

```
請建立後端 Service 與 Controller 層，API 設計如下（RESTful，路徑前綴 /api/v1/atm-cardless-track）：

1. GET  /api/v1/atm-cardless-track           - 分頁查詢清單（支援 SearchCriteria 條件）
2. GET  /api/v1/atm-cardless-track/{id}      - 查詢單筆明細
3. PUT  /api/v1/atm-cardless-track/{id}/assessment  - 經辦登打評估說明並送出覆核
                                                （僅 CLERK 角色可呼叫；只有狀態為 待登打/已退回 時可編輯）
4. PUT  /api/v1/atm-cardless-track/{id}/approve     - 主管覆核（核准/退回）
                                                （僅 SUPERVISOR 角色可呼叫；只有狀態為 待覆核 時可操作）
5. GET  /api/v1/atm-cardless-track/export           - 匯出 Excel 報表
                                                （報表標題「ATM無卡存款異常交易關懷報表(總行)」，
                                                  欄位順序依 CLAUDE.md 12 個欄位，套用查詢條件篩選）

請加入：
  - 統一例外處理 @ControllerAdvice
  - 狀態轉換的業務規則檢查（例如已核准的案件不可再編輯，需回傳明確錯誤訊息）
  - 使用 Spring Security 建立簡易角色驗證（先用記憶體使用者或 JWT 骨架皆可，說明你的選擇並註記待接公司 SSO/LDAP）
  - Swagger 註解，讓 API 文件清楚

完成後列出所有 API 的路徑、方法、角色權限、request/response 範例給我看。
```

---

## 階段 4：初始化前端專案（Angular）

```
請在 frontend/ 目錄下初始化 Angular 最新穩定版專案，使用：
  - Angular CLI standalone components 架構
  - Angular Material 作為 UI 元件庫
  - 路由規劃：
      /login
      /cases            案件清單頁（經辦/主管共用，依角色顯示不同操作按鈕）
      /cases/:id         案件明細頁（經辦登打 或 主管覆核 依角色顯示不同表單）
  - 建立 core/（http interceptor、auth guard、角色權限判斷）
  - 建立 shared/（共用元件，如查詢條件列、狀態標籤）
  - 建立 features/cases/（清單、明細、服務 service）

請先產出專案骨架與路由設定，暫不串接後端 API。
```

---

## 階段 5：前端頁面實作

```
請實作以下前端頁面，串接階段3定義的後端 API：

1. 案件清單頁 (/cases)
   - 查詢條件：交易日期區間、分行別、簽核狀態、客戶帳號/姓名
   - 表格顯示 12 個報表欄位（交易日期、分行別、分行名稱、客戶帳號、客戶姓名、電話、
     評估說明、簽核狀態、登錄人員、登錄時間、簽核主管、簽核時間）
   - 分頁、排序
   - 「匯出Excel報表」按鈕（呼叫 export API 下載檔案，檔名含日期）
   - 依角色顯示不同操作：CLERK 看到「登打」按鈕（僅待登打/已退回可點），
     SUPERVISOR 看到「覆核」按鈕（僅待覆核可點）

2. 案件明細頁 (/cases/:id)
   - 顯示完整交易資訊（唯讀欄位）
   - CLERK 模式：評估說明可編輯的文字區塊 + 送出覆核按鈕
   - SUPERVISOR 模式：評估說明唯讀顯示 + 核准/退回按鈕（退回需填退回原因）
   - 表單驗證（評估說明必填、長度限制等，請提出合理建議）

請使用 Angular Reactive Forms，並加上 loading 狀態與錯誤訊息提示（Snackbar）。
```

---

## 階段 6：整合測試與收尾

```
請幫我：
1. 確認前後端可以本機串接（後端 8080，前端 4200，設定 proxy.conf.json 避免 CORS 問題）
2. 撰寫 README.md，說明如何啟動前後端、環境需求（JDK 21、Node 版本）、資料庫連線設定方式
3. 列出目前尚待我確認/提供的事項清單，例如：
   - FATCA 資料庫實際連線資訊與 PB2503MD_ATMCardlessDepositTrackMain 的真實 DDL
   - 使用者/角色資料來源（是否串公司 SSO）
   - 異常資料如何寫入主表（是否已有批次程式，或需要另外開發匯入功能）
```

---

## 使用建議

- 每個階段結束後，建議先用 `git init` + `git commit` 保留版本，方便後續調整或回退。
- 若你已經有實際的資料表 DDL 或 API 命名規範，建議在**階段 2** 之前先提供給 Claude，
  避免欄位命名跟正式環境對不起來。
- 若公司有既有的授權/角色管理機制（如 SSO、LDAP），請在**階段 3** 明確告知，
  Claude 才不會做出之後要整套重寫的權限設計。
