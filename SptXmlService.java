package com.fatca.api.service;

import com.fatca.api.dto.AccountSummaryRow;
import com.fatca.api.dto.ReportPreviewDto;
import com.fatca.core.enums.FiType;
import com.fatca.core.vo.GIIN;
import com.fatca.core.vo.Money;
import com.fatca.xml.enums.PaymentCode;
import com.fatca.xml.exception.FatcaValidationException;
import com.fatca.xml.service.FatcaXmlBuilder;
import com.fatca.xml.service.FatcaXmlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 從 XML_* 舊申報暫存表直接查表組出 FATCA XML —— 取代原本呼叫 SQL Server 預存程序
 * {@code [dbo].[STP_toXML]} 的作法，改用 Java 程式碼查詢 + {@link FatcaXmlBuilder} 組裝。
 *
 * <p>銀行部／信託部／分行（{@link FiType#BANK} / {@link FiType#TRUST} / {@link FiType#BRANCH}）
 * 帳戶都存在同一組 XML_* 表中，以各表的 giin 欄位區分部門歸屬（與帳戶查詢畫面
 * {@code LegacyAccountQueryService} 採同一種過濾方式）：呼叫端須傳入該部門目前生效的 GIIN
 * （{@code giinId}），{@link #queryAccountReports} 只會查出 {@code giin} 相符的帳戶，避免把
 * 其他部門的帳戶誤植入這次申報（申報正確性風險）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SptXmlService {

    private final JdbcTemplate jdbc;
    private final FatcaXmlValidator xmlValidator;

    /**
     * 查表組出 FATCA XML 字串。
     *
     * @param giinId              發送機構 GIIN
     * @param compName            機構名稱（英文）
     * @param compAddr            機構地址（英文）
     * @param transmittingCountry 傳送國別（如 TW）
     * @param reportYear          申報年度，決定 XML 的 ReportingPeriod
     * @param fiType              機構類型（僅用於記錄；實際過濾依 giinId）
     * @return FATCA XML 字串
     * @throws FatcaValidationException 產出的 XML 未通過 FATCA XSD 驗證
     */
    public String generateXml(String giinId, String compName, String compAddr,
                               String transmittingCountry, int reportYear, FiType fiType)
            throws FatcaValidationException {
        List<FatcaXmlBuilder.AccountReportSpec> reports = queryAccountReports(giinId);
        log.info("由 XML_* 舊表組出 {} 筆帳戶申報明細（fiType={}）", reports.size(), fiType);

        FatcaXmlBuilder builder = FatcaXmlBuilder.newBuilder()
                .giin(GIIN.of(giinId))
                .reportYear(reportYear)
                .transmittingCountry(transmittingCountry)
                .reportingFI(FatcaXmlBuilder.FISpec.builder()
                        .tin(giinId)
                        .tinCountry(transmittingCountry)
                        .name(compName)
                        .addressFree(compAddr)
                        .addressCountry(transmittingCountry)
                        .build());

        if (reports.isEmpty()) {
            builder.nilReport();
        } else {
            reports.forEach(builder::addAccountReport);
        }

        String xml = builder.build();
        xmlValidator.validate(xml);
        log.info("XML 組裝完成並通過 XSD 驗證：{} 字元，giinId={}", xml.length(), giinId);
        return xml;
    }

    /**
     * 查表組出 XML 後解析，回傳前端可顯示的帳戶申報明細清單。
     * p12Password 不需要；僅做資料查詢，不執行簽章加密。
     */
    public ReportPreviewDto previewXml(String giinId, String compName, String compAddr,
                                        String transmittingCountry, int reportYear, FiType fiType)
            throws FatcaValidationException {
        String xml = generateXml(giinId, compName, compAddr, transmittingCountry, reportYear, fiType);
        List<AccountSummaryRow> accounts = parseAccountReports(xml);
        String messageRefId = extractMessageRefId(xml);
        return new ReportPreviewDto(messageRefId, fiType.name(), accounts.size(), accounts);
    }

    // ── XML_* 舊表查詢 ──────────────────────────────────────────

    private record BaseRow(String docRefId, String accountNumber, Boolean isOrg,
                            BigDecimal balance, String balanceCurrency) {}
    private record OrgHolder(String name, String resCountryCode, String tin, String tinIssuedBy,
                              String acctHolderType, String addressCountry, String addressFree) {}
    private record PersonHolder(String firstName, String lastName, String resCountryCode,
                                 String tin, String tinIssuedBy, String addressCountry, String addressFree) {}
    private record PaymentRow(String type, BigDecimal amount, String currency) {}

    private List<FatcaXmlBuilder.AccountReportSpec> queryAccountReports(String giin) {
        List<BaseRow> baseRows = jdbc.query(
                "SELECT DocRefID, AccountNumber, isOrg, AccountBalance, BalanceCurrCode " +
                "FROM XML_CorrectableAccountReport_Type WHERE giin = ? ORDER BY AccountNumber",
                (rs, rowNum) -> new BaseRow(
                        rs.getString("DocRefID"),
                        rs.getString("AccountNumber"),
                        rs.getObject("isOrg", Boolean.class),
                        rs.getBigDecimal("AccountBalance"),
                        rs.getString("BalanceCurrCode")
                ),
                giin
        );
        if (baseRows.isEmpty()) return List.of();

        List<String> accountNumbers = baseRows.stream().map(BaseRow::accountNumber).toList();
        List<String> docRefIds = baseRows.stream().map(BaseRow::docRefId).toList();

        Map<String, OrgHolder> orgByAccount = findOrgHolders(accountNumbers);
        Map<String, PersonHolder> personByAccount = findPersonHolders(accountNumbers);
        Map<String, List<PaymentRow>> paymentsByDocRef = findPayments(docRefIds);

        List<FatcaXmlBuilder.AccountReportSpec> result = new ArrayList<>();
        for (BaseRow base : baseRows) {
            boolean isOrg = Boolean.TRUE.equals(base.isOrg());
            OrgHolder org = isOrg ? orgByAccount.get(base.accountNumber()) : null;
            PersonHolder person = !isOrg ? personByAccount.get(base.accountNumber()) : null;

            if ((isOrg && org == null) || (!isOrg && person == null)) {
                // 帳戶查詢畫面對這種缺持有人資料的帳戶仍會顯示（欄位空白），申報明細不能悄悄漏掉
                // 這筆帳戶——否則等同送出一份少報帳戶的 FATCA 申報，屬於合規風險，必須明確擋下讓
                // 使用者知道要修資料，而不是靜默略過。
                throw new IllegalStateException(
                        "帳號 " + base.accountNumber() + " 完全查無對應持有人資料（isOrg=" + isOrg +
                        "），無法產生此帳戶的申報內容，請確認資料來源是否缺漏");
            }

            FatcaXmlBuilder.AccountReportSpec.AccountReportSpecBuilder specBuilder =
                    FatcaXmlBuilder.AccountReportSpec.builder()
                            .accountNumber(base.accountNumber())
                            .docRefId(base.docRefId())
                            .balance(Money.of(base.balance(), base.balanceCurrency()));

            if (isOrg) {
                // XML_OrganisationParty_Type.AcctHolderType 是 CHAR(8)，Postgres 對定長不足的值
                // 會補右邊空白，查出來要 trim 掉才能跟 FatcaAcctHolderType_EnumType 的值比對。
                String acctHolderType = org.acctHolderType() != null ? org.acctHolderType().trim() : null;
                specBuilder.organisation(FatcaXmlBuilder.OrganisationSpec.builder()
                        .resCountryCode(org.resCountryCode())
                        .tin(org.tin())
                        .tinCountry(org.tinIssuedBy())
                        .name(org.name())
                        .acctHolderType(acctHolderType)
                        .addressCountry(org.addressCountry())
                        .addressFree(org.addressFree())
                        .build());
            } else {
                specBuilder.individual(FatcaXmlBuilder.IndividualSpec.builder()
                        .resCountryCode(person.resCountryCode())
                        .tin(person.tin())
                        .tinCountry(person.tinIssuedBy())
                        .firstName(person.firstName())
                        .lastName(person.lastName())
                        .addressCountry(person.addressCountry())
                        .addressFree(person.addressFree())
                        .build());
            }

            for (PaymentRow p : paymentsByDocRef.getOrDefault(base.docRefId(), List.of())) {
                PaymentCode code = PaymentCode.fromXmlCode(p.type()).orElse(null);
                if (code == null) {
                    log.warn("未知的 PaymentType={}（帳號={}，非 IRS 官方 FATCA501-504 代碼），略過此筆所得",
                            p.type(), base.accountNumber());
                    continue;
                }
                specBuilder.addPayment(code, Money.of(p.amount(), p.currency()));
            }

            result.add(specBuilder.build());
        }
        return result;
    }

    /**
     * 優先取 AcctHolderType 有值的列（帳戶持有人本人），但若同一帳號下所有列的 AcctHolderType
     * 都是 NULL（例如只留有實質受益人列，帳戶持有人本人列從未正確補上），仍取任一列 fallback，
     * 而不是整個帳戶從申報中消失——帳戶查詢畫面對這種不完整資料仍會顯示該帳戶（欄位顯示空白），
     * 申報明細不應該因為同一份資料就直接把整個帳戶排除在外。若真的完全缺 AcctHolderType，
     * 下游 {@link FatcaXmlBuilder} 既有的必填檢查會明確拋出例外，而不是在這裡悄悄漏掉。
     */
    private Map<String, OrgHolder> findOrgHolders(List<String> accountNumbers) {
        if (accountNumbers.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(accountNumbers.size(), "?"));
        List<Object[]> rows = jdbc.query(
                "SELECT AccountNumber, NAME, ResCountryCode, TIN, TINissuedBy, AcctHolderType, " +
                "CountryCode, AddressFree " +
                "FROM XML_OrganisationParty_Type " +
                "WHERE AccountNumber IN (" + placeholders + ") " +
                "ORDER BY CASE WHEN AcctHolderType IS NOT NULL THEN 0 ELSE 1 END",
                (rs, rowNum) -> new Object[]{
                        rs.getString("AccountNumber"), rs.getString("NAME"),
                        rs.getString("ResCountryCode"), rs.getString("TIN"), rs.getString("TINissuedBy"),
                        rs.getString("AcctHolderType"), rs.getString("CountryCode"), rs.getString("AddressFree")
                },
                accountNumbers.toArray()
        );
        Map<String, OrgHolder> result = new HashMap<>();
        for (Object[] r : rows) {
            result.putIfAbsent((String) r[0],
                    new OrgHolder((String) r[1], (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                            (String) r[6], (String) r[7]));
        }
        return result;
    }

    private Map<String, PersonHolder> findPersonHolders(List<String> accountNumbers) {
        if (accountNumbers.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(accountNumbers.size(), "?"));
        List<Object[]> rows = jdbc.query(
                "SELECT AccountNumber, FirstName, LastName, ResCountryCode, TIN, TINissuedBy, " +
                "CountryCode, AddressFree " +
                "FROM XML_PersonParty_Type WHERE AccountNumber IN (" + placeholders + ")",
                (rs, rowNum) -> new Object[]{
                        rs.getString("AccountNumber"), rs.getString("FirstName"), rs.getString("LastName"),
                        rs.getString("ResCountryCode"), rs.getString("TIN"), rs.getString("TINissuedBy"),
                        rs.getString("CountryCode"), rs.getString("AddressFree")
                },
                accountNumbers.toArray()
        );
        Map<String, PersonHolder> result = new HashMap<>();
        for (Object[] r : rows) {
            result.putIfAbsent((String) r[0],
                    new PersonHolder((String) r[1], (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                            (String) r[6], (String) r[7]));
        }
        return result;
    }

    private Map<String, List<PaymentRow>> findPayments(List<String> docRefIds) {
        if (docRefIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(docRefIds.size(), "?"));
        List<Object[]> rows = jdbc.query(
                "SELECT DocRefID, PaymentType, PaymentAmnt, PaymentCurrCode " +
                "FROM XML_CorrectableAccountReport_Payment WHERE DocRefID IN (" + placeholders + ")",
                (rs, rowNum) -> new Object[]{
                        rs.getString("DocRefID"), rs.getString("PaymentType"),
                        rs.getBigDecimal("PaymentAmnt"), rs.getString("PaymentCurrCode")
                },
                docRefIds.toArray()
        );
        Map<String, List<PaymentRow>> result = new HashMap<>();
        for (Object[] r : rows) {
            result.computeIfAbsent((String) r[0], k -> new ArrayList<>())
                    .add(new PaymentRow((String) r[1], (BigDecimal) r[2], (String) r[3]));
        }
        return result;
    }

    // ── XML 解析（供 previewXml 使用）───────────────────────────

    private List<AccountSummaryRow> parseAccountReports(String xml) {
        List<AccountSummaryRow> result = new ArrayList<>();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));

            NodeList reports = doc.getElementsByTagNameNS("*", "AccountReport");
            for (int i = 0; i < reports.getLength(); i++) {
                result.add(parseOneReport((Element) reports.item(i)));
            }
        } catch (Exception e) {
            log.warn("AccountReport 解析失敗，回傳空清單：{}", e.getMessage());
        }
        return result;
    }

    private AccountSummaryRow parseOneReport(Element report) {
        String accountNumber = text(report, "AccountNumber");

        // AccountHolder — Individual 或 Organisation
        NodeList indList = report.getElementsByTagNameNS("*", "Individual");
        NodeList orgList = report.getElementsByTagNameNS("*", "Organisation");

        String holderName, holderType, resCountry, tin;
        if (indList.getLength() > 0) {
            Element ind = (Element) indList.item(0);
            String lastName  = text(ind, "LastName");
            String firstName = text(ind, "FirstName");
            holderName  = (lastName + " " + firstName).trim();
            holderType  = "INDIVIDUAL";
            resCountry  = text(ind, "ResCountryCode");
            tin         = tinValue(ind);
        } else if (orgList.getLength() > 0) {
            Element org = (Element) orgList.item(0);
            holderName  = text(org, "Name");
            holderType  = "ORGANISATION";
            resCountry  = text(org, "ResCountryCode");
            tin         = tinValue(org);
        } else {
            holderName  = "";
            holderType  = "UNKNOWN";
            resCountry  = "";
            tin         = "";
        }

        // AccountBalance
        NodeList balanceNodes = report.getElementsByTagNameNS("*", "AccountBalance");
        BigDecimal balance = BigDecimal.ZERO;
        String balanceCurrency = "";
        if (balanceNodes.getLength() > 0) {
            Element balElem = (Element) balanceNodes.item(0);
            String val = balElem.getTextContent();
            if (val != null && !val.isBlank()) {
                try { balance = new BigDecimal(val.trim()); } catch (NumberFormatException ignored) {}
            }
            balanceCurrency = balElem.getAttribute("currCode");
        }

        // Payments
        List<AccountSummaryRow.PaymentSummary> payments = new ArrayList<>();
        NodeList payNodes = report.getElementsByTagNameNS("*", "Payment");
        for (int j = 0; j < payNodes.getLength(); j++) {
            Element pay = (Element) payNodes.item(j);
            String type = text(pay, "Type");
            NodeList amntNodes = pay.getElementsByTagNameNS("*", "PaymentAmnt");
            if (amntNodes.getLength() > 0) {
                Element amntElem = (Element) amntNodes.item(0);
                String amntText = amntElem.getTextContent();
                BigDecimal amnt = BigDecimal.ZERO;
                try { amnt = new BigDecimal(amntText.trim()); } catch (NumberFormatException ignored) {}
                payments.add(new AccountSummaryRow.PaymentSummary(type, amnt, amntElem.getAttribute("currCode")));
            }
        }

        return new AccountSummaryRow(accountNumber, holderName, holderType,
                resCountry, tin, balance, balanceCurrency, payments);
    }

    /** 取元素第一個同名子元素的文字內容（namespace-agnostic）。 */
    private static String text(Element parent, String localName) {
        NodeList nl = parent.getElementsByTagNameNS("*", localName);
        if (nl.getLength() == 0) return "";
        String t = nl.item(0).getTextContent();
        return t == null ? "" : t.trim();
    }

    /** 取第一個 TIN 元素的文字內容。 */
    private static String tinValue(Element holder) {
        NodeList tins = holder.getElementsByTagNameNS("*", "TIN");
        if (tins.getLength() == 0) return "";
        String t = tins.item(0).getTextContent();
        return t == null ? "" : t.trim();
    }

    /** 從 XML 字串抓 MessageRefId（簡單字串解析，避免多次 DOM parse）。 */
    private static String extractMessageRefId(String xml) {
        int start = xml.indexOf("<MessageRefId>");
        if (start < 0) start = xml.indexOf(":MessageRefId>");
        if (start < 0) return null;
        int tagEnd = xml.indexOf('>', start) + 1;
        int end    = xml.indexOf('<', tagEnd);
        if (end < 0) return null;
        return xml.substring(tagEnd, end).trim();
    }
}
