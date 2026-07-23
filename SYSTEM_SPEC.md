# FATCA 申報系統 — 系統規格書

版本：2026-07-23（依現有程式碼盤點，Flyway V13 / 12 張資料表）

---

## 1. 系統概述

本系統協助銀行（含分行）與信託部門完成 FATCA（美國海外帳戶稅收遵從法）年度申報作業，涵蓋：

- 從既有帳戶資料（XML_* legacy staging tables）產製符合 IRS 官方 `FatcaXML_v2.0.1.xsd` 規格的申報 XML
- 依 IRS IDES（International Data Exchange Service）傳輸格式對 XML 進行數位簽章與加密封裝
- 手動簽章加密（含 Nil Report 無帳戶可申報宣告）
- GIIN／金鑰／Sender Metadata XML 等申報前置設定管理
- IDES 回覆通知（notification）解密
- 帳戶查詢瀏覽

系統前身為 ASP.NET 應用（`FATCAB2`），核心資料表（`XML_*`）與預存程序（`STP_toXML`／`STP_CfmData_to_XmlData`）已於本系統中以 Java 服務重新實作（`SptXmlService`），資料庫維持與舊系統共用。

---

## 2. 系統架構

```
fatca-system/
├── fatca-backend/                 # Maven 多模組 Spring Boot 3.3.5
│   ├── fatca-core/                # JPA Entity / Repository / Enum，Flyway migration
│   ├── fatca-xml/                 # FATCA XML 建構與 XSD 驗證（xjc 產生的 JAXB 類別）
│   ├── fatca-crypto/               # IDES 簽章／加密／解密（BouncyCastle）
│   └── fatca-api/                 # Spring Boot 主模組（REST API、Controller、業務服務）
└── fatca-frontend/                # Angular 18 + PrimeNG
```

> **注意**：`STARTUP_GUIDE.md` 中列出的 `fatca-classify`、`fatca-submission` 兩個模組已於 2026-07-10 以死代碼名義移除，該文件尚未同步更新。

### 2.1 資料流總覽

```
CFM_*（核心金融系統確認資料，僅存在正式 SQL Server，本環境無法存取）
   │  STP_CfmData_to_XmlData（ASP.NET 端 SP，本系統未重作）
   ▼
XML_*（7 張 legacy staging 表，本系統的申報資料唯一來源，銀行部門資料為主）
   │  SptXmlService.generateXml / previewXml
   ▼
FatcaXmlBuilder（組裝 JAXB 物件圖）── FatcaXmlValidator（對官方 XSD 驗證，失敗回 422）
   ▼
純文字 FATCA XML
   │  IdesCryptoService.buildSubmissionPackage
   ▼
XMLDSig 簽章 → AES-256 加密 → RSA 包公鑰 → IDES 傳輸用 ZIP（bank/trust 各一包）
   │
   ▼
FatcaSubmission 記錄（狀態機：XML_GENERATED → SIGNED → ENCRYPTED → ZIP_READY → UPLOADED → ACK_RECEIVED / ERROR）
```

實際上傳到 IRS IDES 網站的動作**不在本系統範圍內**（`fatca-submission` 上傳模組已確認為死代碼移除），系統只做到產出可上傳的 ZIP 為止。

---

## 3. 技術棧

| 項目 | 版本／說明 |
|---|---|
| Java | 21 |
| Spring Boot | 3.3.5 |
| ORM | Spring Data JPA + Hibernate（`ddl-auto: validate`，schema 完全由 Flyway 管理） |
| DB migration | Flyway，`postgresql`/`sqlserver` 兩套 migration 目錄 |
| XML 處理 | JAXB（`org.jvnet.jaxb:jaxb-maven-plugin` 由官方 IRS XSD 產生） |
| 加密 | BouncyCastle（AES-256-CBC、RSA、XMLDSig、CMS） |
| 前端 | Angular 18 + PrimeNG |
| API 文件 | springdoc-openapi（`/swagger-ui.html`） |
| 認證 | HTTP Basic（`/api/v1/**`） |
| 監控 | Spring Boot Actuator（`health,info,metrics`） |

---

## 4. 後端模組說明

### 4.1 `fatca-core`
JPA Entity、Repository、共用 Enum／VO，以及 Flyway migration script（`db/migration/{postgresql,sqlserver}`）。不含任何業務邏輯或 Controller。

### 4.2 `fatca-xml`
- `FatcaXmlBuilder`：Builder pattern，組裝官方 `FatcaXML_v2.0.1.xsd` 對應的 JAXB 物件圖（`oecd.ties.fatca.v2` 等 4 個 package，編譯期由 xjc 產生，未納入版控）。
- `FatcaXmlValidator`：載入 `schema/FatcaXML_v2.0.1.xsd`（含 3 個 `xsd:import`）對產出的 XML 做 schema 驗證；找不到 schema 時退化為僅檢查 well-formed 並記警告。

### 4.3 `fatca-crypto`
- `IdesCryptoService`：IDES 傳輸格式的簽章、加密、解密核心（詳見第 7 節）。
- `KeyFileService`：管理 P12／憑證檔案（`KeyStore` entity），上傳時計算 SHA-256 指紋與效期。
- `SenderMetadataXmlService`：管理各 FiType 對應的 Sender GIIN Metadata XML（`SenderMetadataXml` entity）。

### 4.4 `fatca-api`
Spring Boot 主模組，含所有 REST Controller、`SptXmlService`（XML 產製業務邏輯）、`LegacyAccountQueryService`（帳戶查詢）、`DatabaseProfileAutoSelector`（DB profile 自動偵測）、全域例外處理。

---

## 5. 資料庫設計

### 5.1 現況：12 張表（Flyway V13，postgresql/sqlserver 兩套 migration 已同步）

| 分類 | 表名 |
|---|---|
| XML_* legacy staging（7 張，銀行部門申報資料唯一來源） | `XML_DocSpec`、`XML_CorrectableOrganisationParty_Type`、`XML_CorrectableAccountReport_Type`、`XML_CorrectableAccountReport_Payment`、`XML_OrganisationParty_Type`、`XML_PersonParty_Type`、`XML_PoolReport` |
| 系統設定／狀態（5 張） | `fatca_giin_config`、`fatca_keystores`、`fatca_sender_metadata_xml`、`fatca_submissions`、`flyway_schema_history` |

### 5.2 Flyway migration 歷史

| 版本 | 內容摘要 |
|---|---|
| V1 | 初始 schema：`reporting_fi`、`account_holders`、`accounts`、`financial_accounts`、`fatca_reports`、`reportable_accounts`、`submission_records`、`fatca_bank_accounts`、`fatca_trust_accounts`、`fatca_submissions`、`fatca_giin_config`、`fatca_keystores` |
| V2 | 種子資料：1 筆 `fatca_giin_config` |
| V3 | 種子資料：5 筆銀行 + 3 筆信託測試帳戶（report_year=2024） |
| V4 | `fatca_reports` 加 3 個欄位（**已知問題**：同一 `ALTER TABLE` 內串接多個 `ADD COLUMN IF NOT EXISTS`，H2 測試環境會拋 `Syntax error 42000`，Postgres/SQL Server 正常，尚未修正） |
| V5 | `fatca_giin_config` 加 `address_en` |
| V6 | `fatca_giin_config` 加 `fi_type` |
| V7 | 建立 7 張 `XML_*` legacy staging 表 |
| V8 | 建立 `fatca_sender_metadata_xml` |
| V9 | 刪除 `fatca_bank_accounts`／`fatca_trust_accounts`（死代碼） |
| V10 | 刪除 `accounts`／`account_holders`／`financial_accounts`／`reportable_accounts` + 6 張 Spring Batch metadata 表（死代碼） |
| V11 | 7 張 `XML_*` 表全部加上 `giin` 欄位（支援依 FiType／GIIN 過濾） |
| V12 | 刪除 `fatca_reports`／`submission_records`（死代碼） |
| V13 | 刪除 `reporting_fi`（死代碼） |

> V9～V13 皆為「宣告了 Entity/Repository 但從未被任何 Controller/Service 使用」的死代碼清理，清理前均以全文搜尋確認零引用後才執行。

### 5.3 XML_* 表詳細欄位（V11 之後，每張表皆多一個 `giin` 欄位）

| 表名 | 主要欄位 |
|---|---|
| `XML_DocSpec` | `NodeIdent`(5)、`DocTypeIndic`(7)、`DocRefID`(36)、`CorrMessageRefID`(36)、`CorrDocRefID`(36) |
| `XML_CorrectableOrganisationParty_Type` | `NodeIdent`(4)、`ResCountryCode`(2)、`TIN`(20)、`TINissuedBy`(2)、`NAME`(100)、`CountryCode`(2)、`AddressFree`(150)、`FilerCategory`(8) |
| `XML_CorrectableAccountReport_Type` | `DocRefID`(36)、`AccountNumber`(20)、`isOrg`(bool)、`isSubOwner`(bool)、`isSubOrg`(int)、`AccountBalance`(18,2)、`BalanceCurrCode`(3) |
| `XML_CorrectableAccountReport_Payment` | `DocRefID`(36)、`PaymentType`(8)、`PaymentAmnt`(18,2)、`PaymentCurrCode`(3) |
| `XML_OrganisationParty_Type` | `ResCountryCode`(2)、`TIN`(20)、`NAME`(100)、`CountryCode`(2)、`AddressFree`(150)、`AcctHolderType`(8)、`TINissuedBy`(2)、`AccountNumber`(20) |
| `XML_PersonParty_Type` | `ResCountryCode`(2)、`TIN`(11)、`TINissuedBy`(2)、`FirstName`/`LastName`(100)、`CountryCode`(2)、`AddressFree`(150)、`AccountNumber`(20) |
| `XML_PoolReport` | `DocRefID`(36)、`AccountCount`(int)、`AccountPoolReportType`(8)、`PoolBalance`(18,2)、`BalanceCurrCode`(3) |

### 5.4 現行 JPA Entity（4 個，對應 5 張非 legacy 表）

| Entity | Table | 關鍵欄位 |
|---|---|---|
| `GiinConfig` | `fatca_giin_config` | `giin`(unique)、`fiName`、`fiNameEn`、`fiType`、`addressEn`、`transmittingCountry`(預設TW)、`receivingCountry`(預設US)、`active`、`createdBy`/`updatedBy` |
| `FatcaSubmission` | `fatca_submissions` | `messageRefId`(unique)、`reportYear`、`status`、`bankCount`/`trustCount`、`bankZipFileName`/`Path`、`trustZipFileName`/`Path`、`errorMessage` |
| `KeyStore` | `fatca_keystores` | `keyType`、`fileName`、`storagePath`、`fingerprint`、`validFrom`/`To`、`active` |
| `SenderMetadataXml` | `fatca_sender_metadata_xml` | `fiType`、`fileName`、`xmlContent`、`active` |

四張表之間**沒有任何 JPA 關聯**（無 `@ManyToOne`/`@OneToMany`），彼此僅透過 GIIN 字串或 FiType 在查詢層對應。

---

## 6. API 規格

所有 API 皆掛在 `/api/v1/**`，需 HTTP Basic 認證。統一以 `ApiResponse<T>{success, data, error, code}` 包裝回傳，例外由 `FatcaGlobalExceptionHandler` 統一轉換（`FatcaValidationException`→422、`FatcaEncryptionException`→500、找不到檔案→404、非法參數→400）。

### 6.1 `FatcaAccountQueryController` — `/api/v1/accounts`

| Method | Path | 參數 | 回傳 | 說明 |
|---|---|---|---|---|
| GET | `/bank` | `page`(預設0)、`sortField`、`sortOrder` | `Page<LegacyAccountRow>` | 依目前啟用中 BANK GIIN 分頁查詢銀行帳戶 |
| GET | `/trust` | 同上 | `Page<LegacyAccountRow>` | 依 TRUST GIIN 查詢信託帳戶 |
| GET | `/branch` | 同上 | `Page<LegacyAccountRow>` | 依 BRANCH GIIN 查詢分行帳戶 |

固定每頁 20 筆；若該 FiType 沒有啟用中的 GIIN 設定，回傳空頁而非錯誤。排序欄位透過白名單（`SORTABLE_COLUMNS`）過濾，避免 SQL injection。

### 6.2 `FatcaV1Controller` — `/api/v1/fatca`

| Method | Path | 請求 | 回傳 | 說明 |
|---|---|---|---|---|
| POST | `/report/from-sp` | `{fiType, reportYear, p12Password}` | `ReportTriggerResponse` | 查 XML_* → 產 XML（`SptXmlService.generateXml`）→ 簽章加密封裝 ZIP → 存 `FatcaSubmission` |
| POST | `/report/preview` | `{fiType, reportYear}` | `ReportPreviewDto`（可能 422） | 同上查詢+建 XML，不做加密，回傳解析後帳戶清單供畫面預覽 |
| GET | `/submissions` | `reportYear`(選填) | `List<FatcaSubmission>` | 申報歷程，新到舊排序 |
| GET | `/status/{reportYear}` | path | `ReportStatusResponse` | 該年度最新申報狀態＋ZIP 檔案清單 |
| POST | `/decrypt`（multipart） | `file`、`p12Password` | `DecryptResponse` | 解密上傳的 IDES 回覆通知（`.p7m`/`.zip`） |
| GET | `/download/bank-zip/{reportYear}` | path | 檔案 | 下載該年度最新銀行 ZIP（無則 404） |
| GET | `/download/trust-zip/{reportYear}` | path | 檔案 | 下載該年度最新信託 ZIP |
| POST | `/manual-sign-encrypt`（multipart） | `file` 或 `xmlContent` 擇一、`p12Password`、`messageRefId`(選)、`fiType`、`reportYear`(選) | `ManualEncryptResponse` | 對任意 XML 手動簽章加密，並記錄申報紀錄 |
| POST | `/nil-report`（multipart） | `reportYear`、`p12Password`、`fiType` | `ManualEncryptResponse` | 依啟用中 GIIN 設定建立 Nil Report XML 並簽章加密 |
| GET | `/download/zip/{fileName}` | path | 檔案 | 依檔名下載（已做路徑穿越防護：`normalize()`+`startsWith` 檢查） |

### 6.3 `GiinConfigController` — `/api/v1/giin`

`GET /`（全部）、`GET /active`、`POST /`（新增）、`PUT /{id}`（部分更新）、`DELETE /{id}`（軟刪除即停用），回傳 `GiinConfigDto`。

### 6.4 `KeyStoreController` — `/api/v1/keystore`

`GET /`（清單）、`POST /sender-p12`、`POST /irs-cert`、`POST /branch-cert`（皆為 multipart 上傳）、`DELETE /{id}`（停用），回傳 `KeyStoreDto`。

### 6.5 `SenderMetadataXmlController` — `/api/v1/metadata-xml`

`GET /`（清單）、`POST /{fiType}`（multipart 上傳，取代原啟用中檔案）、`DELETE /{id}`（停用），回傳 `SenderMetadataXmlDto`。

---

## 7. 核心業務流程

### 7.1 XML 產製（`SptXmlService`）

取代舊系統的 `STP_toXML` 預存程序呼叫，`generateXml(giin, compName, compAddr, transmittingCountry, reportYear, fiType)`：

1. 依 `giin` 過濾查詢 `XML_CorrectableAccountReport_Type`／`XML_OrganisationParty_Type`／`XML_PersonParty_Type`／`XML_CorrectableAccountReport_Payment`／`XML_PoolReport`。
2. 依 `isOrg` 旗標路由組織戶／個人戶（若旗標與實際哪張表有資料衝突，有 fallback 邏輯處理）。
3. 組織戶會掛載其對應的 `SubstantialOwner`（重大實質受益人）——**目前資料來源直接重用 `XML_PersonParty_Type` 的個人資料，沒有專屬的實質受益人資料表**，因此無法表達「法人」作為實質受益人，即使 schema 本身支援。
4. 呼叫 `FatcaXmlBuilder` 組裝 JAXB 物件、`FatcaXmlValidator` 對官方 XSD 驗證，失敗拋 `FatcaValidationException`（HTTP 422）。

`previewXml(...)` 做相同查詢與建置，但不進行加密，將結果 DOM 解析為 `ReportPreviewDto` 供畫面預覽比對。

**重要更正**：舊文件曾記載「`fiType=TRUST` 會拋 `IllegalStateException`（信託部門資料尚未支援）」，此限制已隨 V11 遷移（`XML_*` 表新增 `giin` 欄位）移除——目前 TRUST 與 BANK／BRANCH 處理方式完全一致，皆以 GIIN 過濾對應資料列。

### 7.2 IDES 傳輸封裝（`IdesCryptoService.buildSubmissionPackage` → `buildTypeZip`）

1. **XMLDSig 簽章**：對申報 XML 做 W3C XMLDSig **enveloping** 簽章（RSA-SHA256、**Inclusive C14N**——`Reference` 轉換與 `SignedInfo` 的 `CanonicalizationMethod` 皆為 Inclusive，比照 IRS 官方 IDES 工具，程式內有註解「DO NOT CHANGE CanonicalizationMethod.INCLUSIVE」），`KeyInfo` 內嵌送件方 X.509 憑證。
2. 簽章後的 XML 包成內層 ZIP，單一項目 `{senderGiin}_Payload.xml`。
3. 產生隨機 AES-256 金鑰（32 bytes）+ IV（16 bytes），以 AES-256-CBC 加密內層 ZIP。
4. 將 `key‖IV`（共 48 bytes）以收件方公鑰（一般為 IRS 憑證，分行案件則用 BRANCH_CERT）RSA/ECB/PKCS1Padding 加密。
5. 組成外層 ZIP：`{IRS_IDES_GIIN}_Key`（`IRS_IDES_GIIN = "000000.00000.TA.840"`）、`{senderGiin}_Metadata.xml`（明文，取自 `SenderMetadataXmlService` 啟用中的檔案）、`{senderGiin}_Payload`（AES 密文）。
6. 輸出至設定的 ZIP 目錄，檔名 `{UTC時間戳}_{giin}.zip`。

### 7.3 解密（IDES 回覆通知）

`decryptNotificationFile` → `processZipNotification` → 依序嘗試：
1. CMS 格式偵測（`.p7m`/`.p7`/`.p7s`/`.p7c`，供第三方或非本系統簽發的 CMS 格式通知使用）
2. IRS 原生格式（`_Key`＋`_Payload`），RSA 多策略嘗試（OAEP-SHA1 → OAEP-SHA256 → PKCS1v15）、AES 金鑰排列多策略嘗試（16/24/32 bytes 標準排列，或 48 bytes `key‖IV` 三種排列 fallback）
3. 純 XML／gzip／巢狀 ZIP 偵測

舊版 CMS SignedData→EnvelopedData（`sign`/`encrypt`/`signAndEncrypt`）仍保留，但**不是**送件封裝的實際路徑，只用於 `decryptNotification` 對稱加解密與第三方 CMS 格式通知的 fallback。

### 7.4 XSD 驗證

`fatca-xml/src/main/resources/schema/` 下為官方 IRS 4 檔案 schema 組：`FatcaXML_v2.0.1.xsd`（根，import 其餘 3 個）、`isofatcatypes_v1.2.xsd`、`oecdtypes_v4.2.xsd`、`stffatcatypes_v2.0.xsd`。JAXB 類別由 `org.jvnet.jaxb:jaxb-maven-plugin` 在編譯期依同一份 schema 產生（`oecd.ties.fatca.v2` 等 4 個 package，不納入版控，每次建置重新產生）。

---

## 8. 前端功能

Angular 路由（`/dashboard`、`/giin`、`/keystore`、`/metadata-xml`、`/report`、`/accounts`、`/decrypt`、`/sign-encrypt`，其餘導向 `/dashboard`）：

| 功能模組 | 畫面說明 |
|---|---|
| `dashboard` | 總覽（啟用中 GIIN、近期申報紀錄、金鑰/帳戶統計） |
| `giin-management` | GIIN 設定 CRUD |
| `keystore-management` | 金鑰／憑證管理（SENDER_P12／IRS_CERT／BRANCH_CERT 上傳與停用） |
| `metadata-xml-management` | Sender GIIN Metadata XML 管理（每 FiType 一份，可上傳/停用） |
| `report-management`（申報管理） | SP 模式申報流程（`/report/from-sp`、`/report/preview`）、申報歷程查詢，年度欄位預設**今年** |
| `account-list`（帳戶查詢） | 銀行／信託／分行帳戶分頁瀏覽 |
| `decrypt-notification`（解密回覆） | 上傳並解密 IDES 回覆通知檔 |
| `xml-sign-encrypt`（手動簽章加密） | 手動 XML 簽章加密（檔案／文字兩個分頁）＋ Nil Report 產生分頁，年度欄位預設**今年**（2026-07-23 修正，原為去年） |

前端服務層（`core/services/`）皆為對應 Controller 的薄 `HttpClient` 包裝：`account.service.ts`、`auth.service.ts`（HTTP Basic 憑證管理）、`decrypt.service.ts`、`error-notifier.service.ts`、`giin.service.ts`、`keystore.service.ts`、`report.service.ts`、`sender-metadata-xml.service.ts`。

---

## 9. 安全性設計

- **傳輸認證**：`/api/v1/**` 全面 HTTP Basic（帳密透過環境變數 `API_USER`/`API_PASSWORD` 設定，預設僅供開發）；CSRF 停用（純 API 服務）；CORS 限制來源（`CORS_ORIGINS`，預設 `http://localhost:4200`）。
- **金鑰保護**：P12 密碼**不落地**，僅在解鎖當下於記憶體使用（`KeyFileService.loadP12`）；憑證上傳時計算 SHA-256 指紋供比對。
- **申報資料加密**：見第 7.2 節，AES-256-CBC + RSA 信封加密，符合 IRS IDES 傳輸規範；簽章採 Inclusive C14N canonicalization，避免與 IRS 官方工具的簽章格式不相容。
- **路徑穿越防護**：`/download/zip/{fileName}` 下載端點對檔名做 `normalize()` + `startsWith` 檢查。
- **SQL Injection 防護**：`LegacyAccountQueryService` 動態排序欄位透過白名單 (`SORTABLE_COLUMNS`) 過濾，不直接拼接使用者輸入。
- **輸入驗證**：`FatcaXmlBuilder` 對必填欄位（國別碼、GIIN 等）做 blank-safe 檢查，缺漏時丟出明確例外而非產生不合規 XML；`FatcaXmlValidator` 對照官方 XSD 做結構驗證。

---

## 10. 環境設定與部署

### 10.1 資料庫 Profile 自動偵測

`DatabaseProfileAutoSelector`（`EnvironmentPostProcessor`，`HIGHEST_PRECEDENCE`）在 Spring context 啟動前探測 SQL Server（預設 `172.16.85.5:1433`，3 秒逾時）：
- 可連線 → 啟用 `sqlserver` profile
- 無法連線 → 退回 `postgresql` profile（`localhost:5432`）
- 可用 `DB_PROFILE` 環境變數或 `spring.profiles.active` 強制覆蓋

### 10.2 主要環境變數

| 變數 | 預設值 | 說明 |
|---|---|---|
| `DB_HOST`/`DB_PORT`/`DB_NAME` | 依 profile 而異 | 資料庫連線 |
| `API_USER`/`API_PASSWORD` | `admin`/`admin123` | HTTP Basic 帳密 |
| `SERVER_PORT` | `8080` | 後端埠號 |
| `CORS_ORIGINS` | `http://localhost:4200` | 允許的前端來源 |
| `FATCA_XML_DIR`/`FATCA_ZIP_DIR`/`FATCA_DECRYPT_DIR`/`FATCA_KEYSTORE_DIR` | — | 各類輸出檔案落地目錄 |

日誌輸出至 `./logs/fatca-api.log`（10MB 滾動、保留 30 天）。API 文件見 `/swagger-ui.html`；健康檢查見 Actuator `/actuator/health`。

---

## 11. 已知限制與待辦事項

### 11.1 業務邏輯上的既有限制（設計如此，非缺陷）

- 帳戶對應不到任何組織戶／個人戶資料列時，`SptXmlService` 直接拋例外中止申報（寧可失敗也不送出不完整資料）。
- 個人戶帳號在 `XML_PersonParty_Type` 有多筆資料（如聯名帳戶）時，僅取第一筆作為戶名，其餘僅記警告——**目前資料模型不支援聯名帳戶**。
- 任一 FiType（BANK/TRUST/BRANCH）沒有啟用中的 GIIN 設定時，相關查詢／申報一律報錯或回空頁，需先在「GIIN 管理」設定啟用中的一筆。
- 尚未上傳對應 FiType 的 Sender Metadata XML 前，無法完成 ZIP 封裝。
- `SubstantialOwner`（實質受益人）目前僅能表達自然人，因為資料來源重用個人戶查詢，尚無法表達法人實質受益人（schema 本身支援，但無對應資料表/欄位可填）。

### 11.2 已知技術債

- `V4__add_fatca_reports_columns.sql`：在同一 `ALTER TABLE` 內串接多個 `ADD COLUMN IF NOT EXISTS`，Postgres/SQL Server 正常，但 H2（測試用記憶體資料庫）會拋 `Syntax error 42000`，導致 `fatca-api` 模組在此沙盒環境下的整批測試於 Spring context 啟動階段失敗。**尚未修正**（用戶先前選擇暫不處理）；該欄位其實屬於已刪除的 `fatca_reports` 表，此 migration 目前已無意義，未來清理時應一併檢討是否能整支刪除（需評估歷史 migration 是否可安全改寫）。
- 以下 Enum／VO/Record 類別經本次盤點確認為零引用死代碼，尚未清理：`AccountStatus`、`AccountType`、`ReportabilityStatus`、`WFormStatus`、`AccountHolderType`（enum）、`AccountBalance`（VO）、`ReportingPeriod`（record）、`TaxIdentification`（record）——皆為先前「proper 帳戶資料模型」（`accounts`/`account_holders` 等，已於 V10 刪除表本身）留下的殘餘類別。
- `STARTUP_GUIDE.md` 仍列出已刪除的 `fatca-classify`／`fatca-submission` 模組，內容過時待更新。

### 11.3 範圍外（Out of Scope）

- 實際上傳 ZIP 至 IRS IDES 網站（`fatca-submission` 上傳模組已確認為死代碼並移除，本系統只產出待上傳的 ZIP）。
- `CFM_*` 核心金融系統確認資料 → `XML_*` staging 的 ETL（對應舊系統 `STP_CfmData_to_XmlData`），本系統無 Java 實作，`XML_*` 資料新鮮度完全依賴 ASP.NET 端該 SP 或人工手動維護。
