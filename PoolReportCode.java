package com.fatca.xml.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * FATCA XML PoolReport/AccountPoolReportType 代碼（IRS FatcaAcctPoolReportType_EnumType，
 * 見 FatcaXML_v2.0.1.xsd）。official 官方碼表僅定義 FATCA201-206，這六個以外沒有其他合法值。
 */
@Getter
@RequiredArgsConstructor
public enum PoolReportCode {

    RECALCITRANT_WITH_US_INDICIA   ("FATCA201", "Recalcitrant account holders with U.S. Indicia"),
    RECALCITRANT_WITHOUT_US_INDICIA("FATCA202", "Recalcitrant account holders without US Indicia"),
    DORMANT_ACCOUNTS               ("FATCA203", "Dormant accounts"),
    NON_PARTICIPATING_FFIS         ("FATCA204", "Non-participating FFIs"),
    RECALCITRANT_US_PERSONS        ("FATCA205", "Recalcitrant account holders that are U.S. persons"),
    RECALCITRANT_PASSIVE_NFFES     ("FATCA206", "Recalcitrant account holders that are passive NFFEs");

    private final String xmlCode;
    private final String description;

    @Override
    public String toString() {
        return xmlCode;
    }

    /** 依官方 FATCA2xx 代碼查找對應的 {@link PoolReportCode}；查無對應則回傳 empty。 */
    public static Optional<PoolReportCode> fromXmlCode(String xmlCode) {
        return Arrays.stream(values()).filter(c -> c.xmlCode.equals(xmlCode)).findFirst();
    }
}
