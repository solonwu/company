package com.fatca.xml.service;

import com.fatca.core.vo.GIIN;
import com.fatca.core.vo.Money;
import com.fatca.xml.enums.DocTypeIndic;
import com.fatca.xml.enums.PaymentCode;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import oecd.ties.fatca.v2.AccountHolderType;
import oecd.ties.fatca.v2.CorrectableAccountReportType;
import oecd.ties.fatca.v2.CorrectableNilReportType;
import oecd.ties.fatca.v2.CorrectableReportOrganisationType;
import oecd.ties.fatca.v2.DocSpecType;
import oecd.ties.fatca.v2.FATCAOECD;
import oecd.ties.fatca.v2.FIAccountNumberType;
import oecd.ties.fatca.v2.FatcaAcctHolderTypeEnumType;
import oecd.ties.fatca.v2.FatcaDocTypeIndicEnumType;
import oecd.ties.fatca.v2.FatcaFilerCategoryEnumType;
import oecd.ties.fatca.v2.FatcaPaymentTypeEnumType;
import oecd.ties.fatca.v2.FatcaType;
import oecd.ties.fatca.v2.PaymentType;
import oecd.ties.isofatcatypes.v1.CountryCodeType;
import oecd.ties.isofatcatypes.v1.CurrCodeType;
import oecd.ties.stffatcatypes.v2.AddressType;
import oecd.ties.stffatcatypes.v2.MessageSpecType;
import oecd.ties.stffatcatypes.v2.MessageTypeEnumType;
import oecd.ties.stffatcatypes.v2.MonAmntType;
import oecd.ties.stffatcatypes.v2.NameOrganisationType;
import oecd.ties.stffatcatypes.v2.NamePersonType;
import oecd.ties.stffatcatypes.v2.ObjectFactory;
import oecd.ties.stffatcatypes.v2.OrganisationPartyType;
import oecd.ties.stffatcatypes.v2.PersonPartyType;
import oecd.ties.stffatcatypes.v2.TINType;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.UUID;

/**
 * FATCA XML Schema v2.0.1 報表建構器（Builder pattern），組裝的物件圖對應官方
 * IRS FatcaXML_v2.0.1.xsd（+ isofatcatypes/oecdtypes/stffatcatypes 匯入）JAXB 產生類別。
 *
 * <pre>{@code
 * String xml = FatcaXmlBuilder.newBuilder()
 *     .giin(GIIN.of("ABC123.00000.LE.158"))
 *     .reportYear(2024)
 *     .reportingFI(FatcaXmlBuilder.FISpec.builder()
 *         .tin("12345678").tinCountry("TW")
 *         .name("Taiwan Bank").addressFree("123 Main St").addressCountry("TW").build())
 *     .addAccountReport(FatcaXmlBuilder.AccountReportSpec.builder()
 *         .accountNumber("ACC-001")
 *         .individual(FatcaXmlBuilder.IndividualSpec.builder()
 *             .resCountryCode("US").tin("123456789").tinCountry("US")
 *             .firstName("John").lastName("Doe").build())
 *         .balance(Money.usd(60_000))
 *         .addPayment(PaymentCode.INTEREST, Money.usd(1_000)).build())
 *     .build();
 * }</pre>
 */
@Slf4j
public final class FatcaXmlBuilder {

    // ── Cached JAXB Context ──────────────────────────────────

    private static final JAXBContext JAXB_CTX;
    private static final ObjectFactory SFA_OBJECT_FACTORY = new ObjectFactory();

    static {
        try {
            JAXB_CTX = JAXBContext.newInstance(FATCAOECD.class);
        } catch (JAXBException e) {
            throw new ExceptionInInitializerError("Failed to initialise JAXB context: " + e.getMessage());
        }
    }

    // ── Inner Spec DTOs ──────────────────────────────────────

    @Data
    @Builder
    public static class FISpec {
        private String tin;
        private String tinCountry;
        private String name;
        private String addressFree;
        private String addressCountry;
        /** 選填。IRS FatcaFilerCategory_EnumType 代碼（如 FATCA601）。 */
        private String filerCategory;
    }

    @Data
    @Builder
    public static class IndividualSpec {
        private String resCountryCode;
        private String tin;
        private String tinCountry;
        private String firstName;
        private String lastName;
        private String birthDate;
        private String addressFree;
        private String addressCountry;
    }

    @Data
    @Builder
    public static class OrganisationSpec {
        private String resCountryCode;
        private String tin;
        private String tinCountry;
        private String name;
        private String addressFree;
        private String addressCountry;
        /** 必填（法人帳戶持有人）。IRS FatcaAcctHolderType_EnumType 代碼（如 FATCA102/103）。 */
        private String acctHolderType;
    }

    @Data
    @Builder
    public static class PaymentSpec {
        private PaymentCode type;
        private Money amount;
    }

    @Data
    public static class AccountReportSpec {
        private final String accountNumber;
        private final IndividualSpec individual;
        private final OrganisationSpec organisation;
        private final Money balance;
        private final List<PaymentSpec> payments;

        private AccountReportSpec(AccountReportSpecBuilder b) {
            this.accountNumber = b.accountNumber;
            this.individual    = b.individual;
            this.organisation  = b.organisation;
            this.balance       = b.balance;
            this.payments      = List.copyOf(b.payments);
        }

        public static AccountReportSpecBuilder builder() { return new AccountReportSpecBuilder(); }

        public static final class AccountReportSpecBuilder {
            private String accountNumber;
            private IndividualSpec individual;
            private OrganisationSpec organisation;
            private Money balance;
            private final List<PaymentSpec> payments = new ArrayList<>();

            public AccountReportSpecBuilder accountNumber(String v) { this.accountNumber = v; return this; }
            public AccountReportSpecBuilder individual(IndividualSpec v) { this.individual = v; return this; }
            public AccountReportSpecBuilder organisation(OrganisationSpec v) { this.organisation = v; return this; }
            public AccountReportSpecBuilder balance(Money v) { this.balance = v; return this; }
            public AccountReportSpecBuilder addPayment(PaymentCode code, Money amount) {
                this.payments.add(PaymentSpec.builder().type(code).amount(amount).build());
                return this;
            }
            public AccountReportSpecBuilder payments(List<PaymentSpec> list) { this.payments.addAll(list); return this; }
            public AccountReportSpec build() { return new AccountReportSpec(this); }
        }
    }

    // ── Builder State ────────────────────────────────────────

    private GIIN giin;
    private Integer reportYear;
    private String transmittingCountry = "TW";
    private FISpec fiSpec;
    private final List<AccountReportSpec> accountReports = new ArrayList<>();
    private boolean nilReport = false;
    private String correctedMessageRefId;

    private FatcaXmlBuilder() {}

    public static FatcaXmlBuilder newBuilder() { return new FatcaXmlBuilder(); }

    // ── Fluent Configuration ─────────────────────────────────

    public FatcaXmlBuilder giin(GIIN giin) {
        this.giin = giin;
        return this;
    }

    public FatcaXmlBuilder reportYear(int year) {
        this.reportYear = year;
        return this;
    }

    public FatcaXmlBuilder transmittingCountry(String country) {
        this.transmittingCountry = country;
        return this;
    }

    public FatcaXmlBuilder reportingFI(FISpec spec) {
        this.fiSpec = spec;
        return this;
    }

    public FatcaXmlBuilder addAccountReport(AccountReportSpec spec) {
        this.accountReports.add(spec);
        return this;
    }

    /** 本年度無應申報帳戶，產生 Nil Report */
    public FatcaXmlBuilder nilReport() {
        this.nilReport = true;
        return this;
    }

    public FatcaXmlBuilder correctedMessageRefId(String refId) {
        this.correctedMessageRefId = refId;
        return this;
    }

    // ── Build ────────────────────────────────────────────────

    public String build() {
        requireNonNull(giin, "giin");
        requireNonNull(reportYear, "reportYear");
        requireNonNull(fiSpec, "reportingFI");

        FATCAOECD doc = new FATCAOECD();
        doc.setVersion("2.0.1");
        doc.setMessageSpec(buildMessageSpec());
        doc.getFATCA().add(buildFatcaBody());

        return marshal(doc);
    }

    // ── Internal builders ────────────────────────────────────

    private MessageSpecType buildMessageSpec() {
        MessageSpecType spec = new MessageSpecType();
        spec.setSendingCompanyIN(giin.value());
        spec.setTransmittingCountry(requiredCountryCode(transmittingCountry, "transmittingCountry"));
        spec.setReceivingCountry(requiredCountryCode("US", "receivingCountry"));
        spec.setMessageType(MessageTypeEnumType.FATCA);
        spec.setMessageRefId(UUID.randomUUID().toString());
        if (correctedMessageRefId != null) {
            spec.getCorrMessageRefId().add(correctedMessageRefId);
        }
        spec.setReportingPeriod(toXmlDate(LocalDate.of(reportYear, 12, 31)));
        spec.setTimestamp(toXmlDateTime(LocalDateTime.now()));
        return spec;
    }

    /** IRS schema 要求 FatcaType 底下至少一個 ReportingGroup（縱使內容全空）。 */
    private FatcaType buildFatcaBody() {
        FatcaType fatca = new FatcaType();
        fatca.setReportingFI(buildReportingFI());

        FatcaType.ReportingGroup group = new FatcaType.ReportingGroup();
        if (nilReport) {
            group.setNilReport(buildNilReport());
        } else {
            accountReports.forEach(spec -> group.getAccountReport().add(buildAccountReport(spec)));
        }
        fatca.getReportingGroup().add(group);
        return fatca;
    }

    private CorrectableReportOrganisationType buildReportingFI() {
        CorrectableReportOrganisationType fi = new CorrectableReportOrganisationType();
        if (hasText(fiSpec.getTin())) {
            fi.getTIN().add(buildTin(fiSpec.getTin(), fiSpec.getTinCountry()));
        }
        fi.getName().add(buildOrgName(fiSpec.getName()));
        fi.getAddress().add(buildAddress(fiSpec.getAddressCountry(), fiSpec.getAddressFree()));
        if (hasText(fiSpec.getFilerCategory())) {
            fi.setFilerCategory(FatcaFilerCategoryEnumType.fromValue(fiSpec.getFilerCategory()));
        }
        fi.setDocSpec(newDocSpec(DocTypeIndic.FATCA1));
        return fi;
    }

    private CorrectableNilReportType buildNilReport() {
        CorrectableNilReportType nil = new CorrectableNilReportType();
        nil.setDocSpec(newDocSpec(DocTypeIndic.FATCA1));
        nil.setNoAccountToReport("yes");
        return nil;
    }

    private CorrectableAccountReportType buildAccountReport(AccountReportSpec spec) {
        CorrectableAccountReportType report = new CorrectableAccountReportType();
        report.setDocSpec(newDocSpec(DocTypeIndic.FATCA1));

        FIAccountNumberType accountNumber = new FIAccountNumberType();
        accountNumber.setValue(spec.getAccountNumber());
        report.setAccountNumber(accountNumber);

        report.setAccountHolder(buildHolder(spec));

        for (PaymentSpec p : spec.getPayments()) {
            PaymentType payment = new PaymentType();
            payment.setType(FatcaPaymentTypeEnumType.fromValue(p.getType().getXmlCode()));
            payment.setPaymentAmnt(toMonetaryAmount(p.getAmount()));
            report.getPayment().add(payment);
        }

        report.setAccountBalance(toMonetaryAmount(spec.getBalance()));
        return report;
    }

    private AccountHolderType buildHolder(AccountReportSpec spec) {
        AccountHolderType holder = new AccountHolderType();
        if (spec.getIndividual() != null) {
            holder.setIndividual(buildPersonParty(spec.getIndividual()));
            return holder;
        }

        OrganisationSpec s = spec.getOrganisation();
        holder.setOrganisation(buildOrganisationParty(s));
        if (!hasText(s.getAcctHolderType())) {
            throw new IllegalStateException(
                    "FatcaXmlBuilder: organisation account holder requires 'acctHolderType' "
                            + "(IRS FatcaAcctHolderType_EnumType, e.g. FATCA102=Passive NFFE, FATCA103=NPFFI) "
                            + "— refusing to emit a report the IRS schema would reject");
        }
        holder.setAcctHolderType(FatcaAcctHolderTypeEnumType.fromValue(s.getAcctHolderType()));
        return holder;
    }

    private PersonPartyType buildPersonParty(IndividualSpec s) {
        PersonPartyType p = new PersonPartyType();
        CountryCodeType resCc = optionalCountryCode(s.getResCountryCode(), "individual.resCountryCode");
        if (resCc != null) {
            p.getResCountryCode().add(resCc);
        }
        if (hasText(s.getTin())) {
            p.getTIN().add(buildTin(s.getTin(), s.getTinCountry()));
        }
        p.getName().add(buildPersonName(s.getFirstName(), s.getLastName()));
        if (hasText(s.getAddressCountry())) {
            p.getAddress().add(buildAddress(s.getAddressCountry(), s.getAddressFree()));
        }
        if (s.getBirthDate() != null) {
            PersonPartyType.BirthInfo birthInfo = new PersonPartyType.BirthInfo();
            birthInfo.setBirthDate(toXmlDate(LocalDate.parse(s.getBirthDate())));
            p.setBirthInfo(birthInfo);
        }
        return p;
    }

    private OrganisationPartyType buildOrganisationParty(OrganisationSpec s) {
        OrganisationPartyType org = new OrganisationPartyType();
        CountryCodeType resCc = optionalCountryCode(s.getResCountryCode(), "organisation.resCountryCode");
        if (resCc != null) {
            org.getResCountryCode().add(resCc);
        }
        if (hasText(s.getTin())) {
            org.getTIN().add(buildTin(s.getTin(), s.getTinCountry()));
        }
        org.getName().add(buildOrgName(s.getName()));
        if (hasText(s.getAddressCountry())) {
            org.getAddress().add(buildAddress(s.getAddressCountry(), s.getAddressFree()));
        }
        return org;
    }

    private DocSpecType newDocSpec(DocTypeIndic type) {
        DocSpecType docSpec = new DocSpecType();
        docSpec.setDocTypeIndic(FatcaDocTypeIndicEnumType.fromValue(type.getCode()));
        docSpec.setDocRefId(UUID.randomUUID().toString());
        return docSpec;
    }

    private TINType buildTin(String tin, String issuedBy) {
        TINType t = new TINType();
        t.setValue(tin);
        CountryCodeType issuedByCc = optionalCountryCode(issuedBy, "tinCountry");
        if (issuedByCc != null) {
            t.setIssuedBy(issuedByCc);
        }
        return t;
    }

    private NameOrganisationType buildOrgName(String name) {
        NameOrganisationType n = new NameOrganisationType();
        n.setValue(name);
        return n;
    }

    private NamePersonType buildPersonName(String firstName, String lastName) {
        NamePersonType name = new NamePersonType();
        NamePersonType.FirstName first = new NamePersonType.FirstName();
        first.setValue(firstName);
        name.setFirstName(first);
        NamePersonType.LastName last = new NamePersonType.LastName();
        last.setValue(lastName);
        name.setLastName(last);
        return name;
    }

    /** Address_Type 的 CountryCode 是必填元素 — 只要呼叫這個方法，countryCode 就不能是空的。 */
    private AddressType buildAddress(String countryCode, String addressFree) {
        AddressType addr = new AddressType();
        addr.getContent().add(SFA_OBJECT_FACTORY.createAddressTypeCountryCode(
                requiredCountryCode(countryCode, "addressCountry")));
        if (addressFree != null) {
            addr.getContent().add(SFA_OBJECT_FACTORY.createAddressTypeAddressFree(addressFree));
        }
        return addr;
    }

    private MonAmntType toMonetaryAmount(Money money) {
        MonAmntType amount = new MonAmntType();
        amount.setValue(money.amount());
        amount.setCurrCode(CurrCodeType.fromValue(money.currency()));
        return amount;
    }

    private static final DatatypeFactory DATATYPE_FACTORY;

    static {
        try {
            DATATYPE_FACTORY = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new ExceptionInInitializerError("Failed to initialise DatatypeFactory: " + e.getMessage());
        }
    }

    private static XMLGregorianCalendar toXmlDate(LocalDate date) {
        return DATATYPE_FACTORY.newXMLGregorianCalendarDate(
                date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
                javax.xml.datatype.DatatypeConstants.FIELD_UNDEFINED);
    }

    private static XMLGregorianCalendar toXmlDateTime(LocalDateTime dateTime) {
        GregorianCalendar cal = GregorianCalendar.from(dateTime.atZone(java.time.ZoneId.systemDefault()));
        return DATATYPE_FACTORY.newXMLGregorianCalendar(cal);
    }

    private String marshal(FATCAOECD doc) {
        try {
            Marshaller m = JAXB_CTX.createMarshaller();
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            m.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            StringWriter sw = new StringWriter();
            m.marshal(doc, sw);
            String xml = sw.toString();
            log.debug("Generated FATCA XML ({} chars)", xml.length());
            return xml;
        } catch (JAXBException e) {
            throw new IllegalStateException("JAXB marshal failed", e);
        }
    }

    private static void requireNonNull(Object val, String field) {
        if (val == null) throw new IllegalStateException("FatcaXmlBuilder: '" + field + "' is required");
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 把 ISO-3166 alpha-2 字串轉成 {@link CountryCodeType}，failure 時丟出清楚訊息，而不是讓
     * 呼叫端一路傳空字串／無效碼進去，最後在 JAXB enum 內部炸出一句難懂的
     * "No enum constant oecd.ties.isofatcatypes.v1.CountryCodeType.…"。
     */
    private static CountryCodeType requiredCountryCode(String iso2, String fieldName) {
        if (!hasText(iso2)) {
            throw new IllegalStateException(
                    "FatcaXmlBuilder: '" + fieldName + "' is required and must be a 2-letter ISO-3166 "
                            + "country code, but was " + (iso2 == null ? "null" : "blank"));
        }
        return parseCountryCode(iso2, fieldName);
    }

    /** 選填欄位版本：空白直接回傳 null（呼叫端略過該元素），非空但格式錯誤才丟例外。 */
    private static CountryCodeType optionalCountryCode(String iso2, String fieldName) {
        return hasText(iso2) ? parseCountryCode(iso2, fieldName) : null;
    }

    private static CountryCodeType parseCountryCode(String iso2, String fieldName) {
        try {
            return CountryCodeType.fromValue(iso2.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "FatcaXmlBuilder: '" + fieldName + "' is not a valid ISO-3166 alpha-2 country code: '"
                            + iso2 + "'", e);
        }
    }
}
