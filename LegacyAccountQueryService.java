package com.fatca.api.service;

import com.fatca.api.dto.LegacyAccountRow;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 帳戶查詢畫面資料來源。
 *
 * 銀行部（{@link #findBankPage}）直接讀取 STP_toXML 產檔所用的舊 XML_* 申報暫存表，
 * 與實際會產出的 ZIP 內容一致。持有人姓名／類型／居住國／TIN 透過子查詢在同一條 SQL
 * 內帶出（而非分頁後另外查表拼接），確保依任一欄排序時分頁結果仍正確。
 *
 * 信託部、分行帳戶不在這幾張 XML_* 表中，資料來源表格尚待確認，
 * 故 {@link #findTrustPage}、{@link #findBranchPage} 目前固定回傳空分頁，
 * 待來源表格確認後再補上查詢邏輯。
 */
@Service
@RequiredArgsConstructor
public class LegacyAccountQueryService {

    private final JdbcTemplate jdbc;

    /** 依帳號取「帳戶持有人本人」列（AcctHolderType 有值，排除同帳號下的實質受益人列），
     *  用 TIN 排序後取第一筆，確保跨資料庫（Postgres / SQL Server / H2）語法一致且結果穩定。 */
    private static final String ORG_NAME_SUBQ =
            "(SELECT o.NAME FROM XML_OrganisationParty_Type o " +
            "WHERE o.AccountNumber = ar.AccountNumber AND o.AcctHolderType IS NOT NULL " +
            "ORDER BY o.TIN OFFSET 0 ROWS FETCH NEXT 1 ROW ONLY)";
    private static final String ORG_COUNTRY_SUBQ =
            "(SELECT o.ResCountryCode FROM XML_OrganisationParty_Type o " +
            "WHERE o.AccountNumber = ar.AccountNumber AND o.AcctHolderType IS NOT NULL " +
            "ORDER BY o.TIN OFFSET 0 ROWS FETCH NEXT 1 ROW ONLY)";
    private static final String ORG_TIN_SUBQ =
            "(SELECT o.TIN FROM XML_OrganisationParty_Type o " +
            "WHERE o.AccountNumber = ar.AccountNumber AND o.AcctHolderType IS NOT NULL " +
            "ORDER BY o.TIN OFFSET 0 ROWS FETCH NEXT 1 ROW ONLY)";
    /** CONCAT()（非 ||，SQL Server 不支援 || 字串串接運算子）在 Postgres／SQL Server 上行為一致，NULL 視為空字串。 */
    private static final String PERSON_NAME_SUBQ =
            "(SELECT TRIM(CONCAT(p.LastName, ' ', p.FirstName)) " +
            "FROM XML_PersonParty_Type p WHERE p.AccountNumber = ar.AccountNumber " +
            "ORDER BY p.TIN OFFSET 0 ROWS FETCH NEXT 1 ROW ONLY)";
    private static final String PERSON_COUNTRY_SUBQ =
            "(SELECT p.ResCountryCode FROM XML_PersonParty_Type p " +
            "WHERE p.AccountNumber = ar.AccountNumber " +
            "ORDER BY p.TIN OFFSET 0 ROWS FETCH NEXT 1 ROW ONLY)";
    private static final String PERSON_TIN_SUBQ =
            "(SELECT p.TIN FROM XML_PersonParty_Type p " +
            "WHERE p.AccountNumber = ar.AccountNumber " +
            "ORDER BY p.TIN OFFSET 0 ROWS FETCH NEXT 1 ROW ONLY)";

    /** SQL 片段＋其內含的 isOrg 參數，依附加順序放入最終參數陣列。 */
    private record Column(String sql, List<Object> params) {}

    private static Column plainColumn(String sqlExpr) {
        return new Column(sqlExpr, List.of());
    }

    private static Column holderNameColumn() {
        return new Column("CASE WHEN ar.isOrg = ? THEN " + ORG_NAME_SUBQ + " ELSE " + PERSON_NAME_SUBQ + " END",
                List.of(true));
    }

    private static Column holderTypeColumn() {
        return new Column(
                "CASE WHEN ar.isOrg = ? AND " + ORG_TIN_SUBQ + " IS NOT NULL THEN 'ORGANISATION' " +
                "WHEN ar.isOrg = ? AND " + PERSON_TIN_SUBQ + " IS NOT NULL THEN 'INDIVIDUAL' " +
                "ELSE 'UNKNOWN' END",
                List.of(true, false));
    }

    private static Column resCountryColumn() {
        return new Column("CASE WHEN ar.isOrg = ? THEN " + ORG_COUNTRY_SUBQ + " ELSE " + PERSON_COUNTRY_SUBQ + " END",
                List.of(true));
    }

    private static Column tinColumn() {
        return new Column("CASE WHEN ar.isOrg = ? THEN " + ORG_TIN_SUBQ + " ELSE " + PERSON_TIN_SUBQ + " END",
                List.of(true));
    }

    /** 前端可排序欄位 → SQL 欄位/運算式白名單，避免將排序欄位字串直接拼進 SQL。 */
    private static final Map<String, Supplier<Column>> SORTABLE_COLUMNS = Map.of(
            "accountNumber", () -> plainColumn("ar.AccountNumber"),
            "holderName", LegacyAccountQueryService::holderNameColumn,
            "holderType", LegacyAccountQueryService::holderTypeColumn,
            "resCountryCode", LegacyAccountQueryService::resCountryColumn,
            "tin", LegacyAccountQueryService::tinColumn,
            "accountBalance", () -> plainColumn("ar.AccountBalance"),
            "balanceCurrCode", () -> plainColumn("ar.BalanceCurrCode")
    );

    public Page<LegacyAccountRow> findTrustPage(int page, int size, String sortField, String sortOrder) {
        return emptyPage(page, size);
    }

    public Page<LegacyAccountRow> findBranchPage(int page, int size, String sortField, String sortOrder) {
        return emptyPage(page, size);
    }

    private Page<LegacyAccountRow> emptyPage(int page, int size) {
        return new PageImpl<>(List.of(), PageRequest.of(page, size), 0);
    }

    public Page<LegacyAccountRow> findBankPage(int page, int size, String sortField, String sortOrder) {
        int total = countAccounts();
        if (total == 0) {
            return new PageImpl<>(List.of(), PageRequest.of(page, size), 0);
        }

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ar.AccountNumber AS accountNumber, ");

        Column nameCol = holderNameColumn();
        sql.append(nameCol.sql()).append(" AS holderName, ");
        params.addAll(nameCol.params());

        Column typeCol = holderTypeColumn();
        sql.append(typeCol.sql()).append(" AS holderType, ");
        params.addAll(typeCol.params());

        Column countryCol = resCountryColumn();
        sql.append(countryCol.sql()).append(" AS resCountryCode, ");
        params.addAll(countryCol.params());

        Column tinCol = tinColumn();
        sql.append(tinCol.sql()).append(" AS tin, ");
        params.addAll(tinCol.params());

        sql.append("ar.AccountBalance AS accountBalance, ar.BalanceCurrCode AS balanceCurrCode ")
           .append("FROM XML_CorrectableAccountReport_Type ar ORDER BY ");

        Supplier<Column> sortSupplier = sortField == null ? null : SORTABLE_COLUMNS.get(sortField);
        Column sortCol = sortSupplier != null ? sortSupplier.get() : plainColumn("ar.AccountNumber");
        String direction = "desc".equalsIgnoreCase(sortOrder) ? "DESC" : "ASC";
        sql.append(sortCol.sql()).append(' ').append(direction);
        params.addAll(sortCol.params());

        sql.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add(page * size);
        params.add(size);

        List<LegacyAccountRow> content = jdbc.query(
                sql.toString(),
                (rs, rowNum) -> new LegacyAccountRow(
                        rs.getString("accountNumber"),
                        rs.getString("holderName"),
                        rs.getString("holderType"),
                        rs.getString("resCountryCode"),
                        rs.getString("tin"),
                        rs.getBigDecimal("accountBalance"),
                        rs.getString("balanceCurrCode")
                ),
                params.toArray()
        );

        return new PageImpl<>(content, PageRequest.of(page, size), total);
    }

    private int countAccounts() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM XML_CorrectableAccountReport_Type", Integer.class);
        return count == null ? 0 : count;
    }
}
