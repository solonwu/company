# Gemma 4 開發提示詞：ATM無卡存款異常交易關懷報表系統

> 使用說明：Gemma 4 本身沒有像 Claude Code 那種「CLAUDE.md 專案記憶 + 多輪漸進式開發」的機制，
> 建議把下面這份「完整規格 Prompt」整份貼給 Gemma 4（不論你是透過 AI Studio、Ollama、
> LM Studio，或是某個 Agent CLI 如 Gemma CLI / Codex CLI --oss / Claude Code 接本機模型）。
> 如果你是用有工具呼叫（function calling / 檔案讀寫）能力的 Agent CLI 執行，Gemma 4 會直接建立檔案；
> 如果只是純聊天介面，Gemma 4 會把程式碼以區塊方式輸出，你再自行存檔。
>
> 由於 Gemma 4 單次輸出長度與規劃能力不如雲端大型模型，**建議分段貼**（見文末〈分段執行建議〉），
> 每段完成後再檢查一次程式碼再繼續下一段，避免一次要求太多导致遺漏或幻覺欄位。

---

## 完整規格 Prompt（可整份貼上，或依文末建議分段使用）

```
你是一個資深全端工程師，請依照以下規格，開發一個前後端分離系統。
請先列出你的實作計畫（檔案結構、主要類別/元件清單），再開始輸出程式碼。
若規格有不確定之處（例如資料庫實際欄位名稱），請用合理命名並在程式碼中加上
"// TODO: 待確認" 註解，不要自行省略需求。

【專案基本資訊】
- 專案名稱：atm-cardless-care-system
- 專案路徑：D:\ai-project\atm-cardless-care-system
- 架構：前後端分離
  - 後端：Spring Boot 3.x，Java 21，Maven 專案，放在 backend/ 目錄
  - 前端：Angular（最新穩定版，standalone components），放在 frontend/ 目錄
- 資料庫：FATCA
- 主要資料表：PB2503MD_ATMCardlessDepositTrackMain

【報表名稱】
ATM無卡存款異常交易關懷報表(總行)

【報表 / 資料表欄位】（請依序對應為合理的英文欄位命名）
1. 交易日期 transactionDate  (LocalDate)
2. 分行別 branchCode         (String)
3. 分行名稱 branchName       (String)
4. 客戶帳號 accountNo        (String)
5. 客戶姓名 customerName     (String)
6. 電話 phoneNo              (String)
7. 評估說明 assessmentDesc   (String，經辦登打欄位)
8. 簽核狀態 approveStatus    (Enum: PENDING_INPUT 待登打 / PENDING_APPROVE 待覆核 /
                              APPROVED 已核准 / REJECTED 已退回)
9. 登錄人員 inputUser        (String)
10. 登錄時間 inputTime       (LocalDateTime)
11. 簽核主管 approveUser     (String)
12. 簽核時間 approveTime     (LocalDateTime)

【業務流程】
1. 系統從 FATCA DB 的 PB2503MD_ATMCardlessDepositTrackMain 撈出異常交易資料，
   初始狀態為「待登打」。
2. 經辦（角色 CLERK）：
   - 查詢清單（可依交易日期區間、分行別、簽核狀態、客戶帳號/姓名篩選）
   - 點選案件後，登打「評估說明」欄位並送出
   - 送出後狀態變更為「待覆核」，並記錄 登錄人員 / 登錄時間
   - 只有狀態為「待登打」或「已退回」的案件可以編輯
3. 主管（角色 SUPERVISOR）：
   - 查詢「待覆核」案件，檢視經辦登打的評估說明
   - 可「核准」（狀態變為已核准，記錄 簽核主管 / 簽核時間）
   - 或「退回」（狀態變為已退回，需填退回原因，經辦可重新編輯再送出）
4. 兩種角色都可以查詢清單並匯出 Excel 報表，報表標題為
   「ATM無卡存款異常交易關懷報表(總行)」，欄位順序依上述 12 個欄位。

【後端需求 Spring Boot / Java 21】
- 分層：controller / service / repository / entity / dto / enums / exception / config
- Entity: AtmCardlessDepositTrack 對應資料表 PB2503MD_ATMCardlessDepositTrackMain
- Repository 支援動態條件查詢（交易日期區間、分行別、簽核狀態、客戶帳號/姓名模糊查詢）+ 分頁
- REST API（路徑前綴 /api/v1/atm-cardless-track）：
  - GET    /                    分頁查詢清單
  - GET    /{id}                查詢單筆明細
  - PUT    /{id}/assessment     經辦登打評估說明並送出覆核（僅 CLERK，狀態需為 待登打/已退回）
  - PUT    /{id}/approve        主管覆核，核准或退回（僅 SUPERVISOR，狀態需為 待覆核）
  - GET    /export              匯出 Excel 報表（用 Apache POI，套用查詢條件）
- 需要簡易角色權限控管（Spring Security，先用記憶體使用者或 JWT 骨架都可以，
  並在程式碼註解說明如何之後接公司 SSO/LDAP）
- 需要統一例外處理與狀態轉換的業務規則檢查（例如已核准案件不可再編輯，
  要回傳明確的錯誤訊息與 HTTP 狀態碼）
- application.yml 需區分 dev/prod profile，資料庫連線資訊使用環境變數或預留位置，不要寫死帳密

【前端需求 Angular】
- Standalone components + Angular Material
- 路由：
  - /login
  - /cases        案件清單頁（依角色顯示不同操作按鈕）
  - /cases/:id    案件明細頁（經辦登打 或 主管覆核，依角色顯示不同表單）
- 案件清單頁：
  - 查詢條件列（交易日期區間、分行別、簽核狀態、客戶帳號/姓名）
  - 表格顯示 12 個報表欄位，含分頁、排序
  - 「匯出Excel報表」按鈕
  - CLERK 看到「登打」按鈕（僅待登打/已退回可點）
  - SUPERVISOR 看到「覆核」按鈕（僅待覆核可點）
- 案件明細頁：
  - CLERK 模式：評估說明可編輯 + 送出按鈕（Reactive Forms + 驗證：必填、長度限制）
  - SUPERVISOR 模式：評估說明唯讀 + 核准/退回按鈕（退回需填原因）
  - 需要 loading 狀態與錯誤訊息提示（Snackbar）
- proxy.conf.json 設定，避免開發時 CORS 問題（後端 8080、前端 4200）

【輸出要求】
1. 先輸出完整檔案結構樹狀圖
2. 依 backend → frontend 順序，逐檔輸出完整程式碼（檔名 + 完整內容，不要省略）
3. 最後列出：
   - 啟動方式（後端 mvn 指令、前端 ng serve 指令）
   - 尚待我確認的事項（例如：FATCA 真實 DDL、SSO 串接方式、異常資料如何寫入主表）
```

---

## 分段執行建議（若一次貼太長 Gemma 4 生成品質下降，建議拆成以下 6 次貼）

1. **第1段**：只貼「專案基本資訊」「報表欄位」「業務流程」三段，請它先輸出檔案結構規劃，暫不寫程式碼。
2. **第2段**：貼「後端需求」，請它先做 Entity / Enum / DTO / Repository。
3. **第3段**：接續請它做 Service / Controller / Security / 例外處理。
4. **第4段**：貼「前端需求」，請它先做 Angular 專案骨架與路由。
5. **第5段**：請它實作案件清單頁與明細頁的元件、Service、表單驗證。
6. **第6段**：請它輸出啟動方式、README、以及尚待確認事項清單。

每段開頭都可以加一句：「延續前面規格，這是第 N 段，請完整輸出程式碼，不要重複前面已完成的部分。」
因為 Gemma 4 的長上下文記憶效果不一定穩定，若發現它忘記前面規格，建議把「業務流程」與「12個欄位」
這兩段規格重複貼一次再繼續。

## 額外提醒

- 若你是用 Ollama/LM Studio 純聊天模式，Gemma 4 不會自動建立實體檔案，程式碼只會顯示在對話中，
  需要你自己複製貼到 D:\ai-project\atm-cardless-care-system 對應路徑。
- 若你是接到有檔案讀寫工具的 Agent CLI（例如把 Claude Code 或 Codex CLI 指向本機 Gemma 4），
  它才能直接在 D:\ai-project\atm-cardless-care-system 建立實體檔案結構。
- 建議在開始前先提供 FATCA 資料庫真實的 DDL 或欄位命名規則，避免 Gemma 4 用推測的英文欄位名稱，
  和正式環境對不起來。
