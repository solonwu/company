package com.fatca.crypto.ides;

import com.fatca.core.enums.FiType;
import com.fatca.core.enums.KeyType;
import com.fatca.core.enums.ZipFileType;
import com.fatca.core.record.FatcaXmlResult;
import com.fatca.core.record.SubmissionPackage;
import com.fatca.crypto.FatcaEncryptionException;
import com.fatca.crypto.FatcaOutputProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.cms.*;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder;
import org.bouncycastle.cms.jcajce.JceKeyTransEnvelopedRecipient;
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientId;
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OutputEncryptor;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * IDES 提交用 CMS/PKCS#7 簽章+加密服務。
 *
 * <p>流程：
 * <ol>
 *   <li>用自己私鑰對 XML 做 CMS SignedData 數位簽章（SHA256withRSA）</li>
 *   <li>用 IRS 公鑰憑證對 SignedData 做 CMS EnvelopedData 加密（AES-256-CBC）</li>
 *   <li>打包成 ZIP：{@code .p7m} + 寄件者憑證（DER）+ 未加密 Metadata XML</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdesCryptoService {

    static {
        if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static final String BC = BouncyCastleProvider.PROVIDER_NAME;

    private final KeyFileService keyFileService;
    private final FatcaOutputProperties outputProperties;

    // ── Primary API ──────────────────────────────────────────

    /**
     * 打包成銀行版與信託版 2 個 ZIP，並存到 {@code fatca.output.zip-dir}。
     *
     * @param giin 申報機構 GIIN，用於 IDES 規定的檔名格式 {@code {timestamp}_{GIIN}.zip}
     */
    public SubmissionPackage buildSubmissionPackage(FatcaXmlResult xmlResult, String p12Password,
                                                    String giin) {
        return buildSubmissionPackage(xmlResult, p12Password, null, ZipFileType.BOTH, giin);
    }

    /** 依機構類型（BANK / TRUST / BRANCH）產生單一 ZIP — 用於手動簽章加密與 Nil Report。 */
    public SubmissionPackage buildSubmissionPackage(FatcaXmlResult xmlResult, String p12Password,
                                                    byte[] metadataBytes, FiType fiType, String giin) {
        if (xmlResult == null || !xmlResult.success()) {
            throw new FatcaEncryptionException(
                    "Cannot build submission package from a failed FatcaXmlResult: "
                            + (xmlResult != null ? xmlResult.error() : "null"));
        }

        SenderKeyPair sender = keyFileService.loadP12(p12Password);
        X509Certificate recipientCert = fiType == FiType.BRANCH
                ? keyFileService.loadCert(KeyType.BRANCH_CERT)
                : keyFileService.loadCert(KeyType.IRS_CERT);

        byte[] xmlBytes = xmlResult.xml().getBytes(StandardCharsets.UTF_8);
        String messageRefId = xmlResult.messageRefId();

        String ts = utcTimestamp();
        byte[] zipBytes = buildTypeZip(giin, messageRefId, fiType.name(), xmlBytes, sender, recipientCert, metadataBytes);
        String zipFileName = ts + "_" + giin + ".zip";
        saveZip(zipFileName, zipBytes);

        log.info("Built IDES submission package: msgRef={}, fiType={}, zip={}",
                messageRefId, fiType, zipFileName);

        return switch (fiType) {
            case BANK   -> new SubmissionPackage(messageRefId, zipBytes, null, zipFileName, null);
            case TRUST  -> new SubmissionPackage(messageRefId, null, zipBytes, null, zipFileName);
            case BRANCH -> new SubmissionPackage(messageRefId, zipBytes, null, zipFileName, null);
        };
    }

    public SubmissionPackage buildSubmissionPackage(FatcaXmlResult xmlResult, String p12Password,
                                                    byte[] metadataBytes, ZipFileType zipFileType,
                                                    String giin) {
        if (xmlResult == null || !xmlResult.success()) {
            throw new FatcaEncryptionException(
                    "Cannot build submission package from a failed FatcaXmlResult: "
                            + (xmlResult != null ? xmlResult.error() : "null"));
        }

        ZipFileType type = zipFileType != null ? zipFileType : ZipFileType.BOTH;
        SenderKeyPair sender = keyFileService.loadP12(p12Password);
        X509Certificate irsCert = keyFileService.loadCert(KeyType.IRS_CERT);

        byte[] xmlBytes = xmlResult.xml().getBytes(StandardCharsets.UTF_8);
        String messageRefId = xmlResult.messageRefId();

        byte[] bankZip  = null;
        byte[] trustZip = null;
        String bankZipFileName  = null;
        String trustZipFileName = null;

        if (type.includesBank()) {
            bankZip = buildTypeZip(giin, messageRefId, "BANK", xmlBytes, sender, irsCert, metadataBytes);
            bankZipFileName = utcTimestamp() + "_" + giin + ".zip";
            saveZip(bankZipFileName, bankZip);
        }
        if (type.includesTrust()) {
            trustZip = buildTypeZip(giin, messageRefId, "TRUST", xmlBytes, sender, irsCert, metadataBytes);
            trustZipFileName = utcTimestamp() + "_" + giin + ".zip";
            saveZip(trustZipFileName, trustZip);
        }

        log.info("Built IDES submission package: msgRef={}, type={}, bankZip={} B, trustZip={} B",
                messageRefId, type,
                bankZip != null ? bankZip.length : 0,
                trustZip != null ? trustZip.length : 0);

        return new SubmissionPackage(messageRefId, bankZip, trustZip, bankZipFileName, trustZipFileName);
    }

    /**
     * Step 1+2 — CMS SignedData（SHA256withRSA）再以 CMS EnvelopedData（AES-256-CBC）加密。
     */
    public byte[] signAndEncrypt(byte[] xmlBytes, PrivateKey senderKey,
                                  X509Certificate senderCert, X509Certificate irsCert) {
        try {
            byte[] signedBytes = sign(xmlBytes, senderKey, senderCert);
            return encrypt(signedBytes, irsCert);
        } catch (Exception e) {
            throw new FatcaEncryptionException("CMS sign+encrypt failed: " + e.getMessage(), e);
        }
    }

    /**
     * 解密 IDES 通知（CMS EnvelopedData → 內含的 CMS SignedData → 原始 XML）。
     * 用 DB 中最新有效的 SENDER_P12 私鑰解密。
     */
    public String decryptNotification(byte[] encryptedData, String p12Password) {
        SenderKeyPair sender = keyFileService.loadP12(p12Password);
        try {
            CMSEnvelopedData envelopedData = new CMSEnvelopedData(encryptedData);
            RecipientInformationStore recipients = envelopedData.getRecipientInfos();

            RecipientInformation recipientInfo =
                    recipients.get(new JceKeyTransRecipientId(sender.certificate()));
            if (recipientInfo == null) {
                Collection<RecipientInformation> all = recipients.getRecipients();
                if (all.isEmpty()) {
                    throw new FatcaEncryptionException(
                            "No recipient info found in CMS EnvelopedData");
                }
                recipientInfo = all.iterator().next();
            }

            byte[] signedBytes = recipientInfo.getContent(
                    new JceKeyTransEnvelopedRecipient(sender.privateKey()).setProvider(BC));

            CMSSignedData signedData = new CMSSignedData(signedBytes);
            CMSProcessable signedContent = signedData.getSignedContent();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            signedContent.write(out);
            return out.toString(StandardCharsets.UTF_8);

        } catch (FatcaEncryptionException e) {
            throw e;
        } catch (Exception e) {
            throw new FatcaEncryptionException(
                    "Failed to decrypt IDES notification: " + e.getMessage(), e);
        }
    }

    /** 解密 IDES 通知檔案，結果寫入 {@code fatca.output.decrypt-dir}。支援 .p7m 與 .zip 格式。 */
    public Path decryptNotificationFile(Path encryptedFilePath, String p12Password) {
        try {
            byte[] data = Files.readAllBytes(encryptedFilePath);
            String sourceFileName = encryptedFilePath.getFileName().toString();

            if (isZipData(data)) {
                log.info("Detected ZIP format, extracting payload from: {}", sourceFileName);
                data = extractP7mFromZip(data);
                sourceFileName = sourceFileName.replaceAll("(?i)\\.zip$", ".p7m");
            }

            String xml = decryptOrExtract(data, p12Password);

            Path dir = Path.of(outputProperties.getDecryptDir());
            Files.createDirectories(dir);
            Path outFile = dir.resolve(deriveOutputFileName(sourceFileName));
            Files.writeString(outFile, xml, StandardCharsets.UTF_8);
            log.info("Decrypted IDES notification: {} -> {}", encryptedFilePath, outFile);
            return outFile;

        } catch (IOException e) {
            throw new FatcaEncryptionException(
                    "Failed to decrypt notification file: " + encryptedFilePath, e);
        }
    }

    /**
     * payload 可能是：
     * 1. CMS EnvelopedData（加密）→ 用私鑰解密
     * 2. CMS SignedData（僅簽章）→ 取出 content
     * 3. 純 XML → 直接回傳
     */
    private String decryptOrExtract(byte[] data, String p12Password) {
        // 快速判斷：XML 以 '<' 或 BOM 開頭
        if (looksLikeXml(data)) {
            log.info("Payload is plain XML, returning as-is");
            return new String(data, StandardCharsets.UTF_8);
        }

        // 嘗試 CMS EnvelopedData（加密）
        try {
            return decryptNotification(data, p12Password);
        } catch (FatcaEncryptionException envelopedEx) {
            log.warn("Not a valid CMS EnvelopedData ({}), trying SignedData...", envelopedEx.getMessage());
        }

        // 嘗試 CMS SignedData（只有簽章，未加密）
        try {
            CMSSignedData signedData = new CMSSignedData(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            signedData.getSignedContent().write(out);
            log.info("Payload is CMS SignedData; extracted content successfully");
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception signedEx) {
            throw new FatcaEncryptionException(
                    "Payload is not XML, EnvelopedData, or SignedData: " + signedEx.getMessage(), signedEx);
        }
    }

    private boolean looksLikeXml(byte[] data) {
        if (data.length == 0) return false;
        // UTF-8 BOM or '<'
        if (data[0] == '<') return true;
        if (data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF) return true;
        return false;
    }

    /** ZIP magic bytes 0x50 0x4B 0x03 0x04 */
    private boolean isZipData(byte[] data) {
        return data.length >= 4
                && data[0] == 0x50 && data[1] == 0x4B
                && data[2] == 0x03 && data[3] == 0x04;
    }

    /**
     * 從 IDES 回傳的 ZIP 中提取解密所需的 payload。
     * 優先順序：.p7m → .p7 / .p7s / .p7c → 非 Metadata 的 .xml
     * 同時印出所有 entry，方便排查格式問題。
     */
    private byte[] extractP7mFromZip(byte[] zipData) throws IOException {
        byte[] cmsPayload  = null;  // .p7m / .p7 / .p7s / .p7c
        byte[] xmlPayload  = null;  // non-metadata .xml fallback
        String cmsEntryName = null;
        String xmlEntryName = null;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name  = entry.getName();
                String lower = name.toLowerCase();

                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[8192];
                int len;
                while ((len = zis.read(tmp)) > 0) {
                    buf.write(tmp, 0, len);
                }
                byte[] bytes = buf.toByteArray();
                log.info("ZIP entry: name={}, size={} bytes", name, bytes.length);

                if (cmsPayload == null &&
                        (lower.endsWith(".p7m") || lower.endsWith(".p7")
                         || lower.endsWith(".p7s") || lower.endsWith(".p7c"))) {
                    cmsPayload  = bytes;
                    cmsEntryName = name;
                } else if (xmlPayload == null && lower.endsWith(".xml")
                        && !lower.contains("metadata")) {
                    xmlPayload  = bytes;
                    xmlEntryName = name;
                }
                zis.closeEntry();
            }
        }

        if (cmsPayload != null) {
            log.info("Using CMS entry from ZIP: {}", cmsEntryName);
            return cmsPayload;
        }
        if (xmlPayload != null) {
            log.info("No CMS entry found; using XML entry from ZIP: {}", xmlEntryName);
            return xmlPayload;
        }
        throw new FatcaEncryptionException(
                "No usable payload (.p7m/.p7/.xml) found inside the ZIP");
    }

    // ── CMS internals ────────────────────────────────────────

    private byte[] sign(byte[] xmlBytes, PrivateKey senderKey, X509Certificate senderCert)
            throws Exception {
        CMSSignedDataGenerator generator = new CMSSignedDataGenerator();

        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(BC)
                .build(senderKey);

        generator.addSignerInfoGenerator(
                new JcaSignerInfoGeneratorBuilder(
                        new JcaDigestCalculatorProviderBuilder().setProvider(BC).build())
                        .build(contentSigner, senderCert));
        generator.addCertificate(new JcaX509CertificateHolder(senderCert));

        CMSTypedData content = new CMSProcessableByteArray(xmlBytes);
        CMSSignedData signedData = generator.generate(content, true); // encapsulate original data
        return signedData.getEncoded();
    }

    private byte[] encrypt(byte[] signedBytes, X509Certificate irsCert) throws Exception {
        CMSEnvelopedDataGenerator generator = new CMSEnvelopedDataGenerator();
        generator.addRecipientInfoGenerator(
                new JceKeyTransRecipientInfoGenerator(irsCert).setProvider(BC));

        CMSTypedData content = new CMSProcessableByteArray(signedBytes);
        OutputEncryptor encryptor = new JceCMSContentEncryptorBuilder(CMSAlgorithm.AES256_CBC)
                .setProvider(BC)
                .build();

        CMSEnvelopedData envelopedData = generator.generate(content, encryptor);
        return envelopedData.getEncoded();
    }

    // ── ZIP packaging ────────────────────────────────────────

    /**
     * IDES 規定檔名：ZIP 內的三個檔案均以 GIIN 為前綴。
     * <ul>
     *   <li>{GIIN}_Payload.xml.p7m — CMS SignedData + EnvelopedData</li>
     *   <li>{GIIN}_SenderCert.cer  — 發送方公鑰憑證（DER）</li>
     *   <li>{GIIN}_Metadata.xml    — 未加密 Metadata</li>
     * </ul>
     */
    private byte[] buildTypeZip(String giin, String messageRefId, String fiTypeCode,
                                 byte[] xmlBytes, SenderKeyPair sender, X509Certificate irsCert,
                                 byte[] metadataOverride) {
        try {
            String payloadFileName  = giin + "_Payload.xml.p7m";
            String certFileName     = giin + "_SenderCert.cer";
            String metadataFileName = giin + "_Metadata.xml";
            String fileType         = "FATCA8966" + fiTypeCode;

            byte[] p7m = signAndEncrypt(xmlBytes, sender.privateKey(), sender.certificate(), irsCert);
            byte[] senderCertDer = sender.certificate().getEncoded();
            byte[] metadata = metadataOverride != null
                    ? metadataOverride
                    : buildMetadataXml(messageRefId, payloadFileName, fileType).getBytes(StandardCharsets.UTF_8);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                addZipEntry(zos, payloadFileName, p7m);
                addZipEntry(zos, certFileName, senderCertDer);
                addZipEntry(zos, metadataFileName, metadata);
            }
            return baos.toByteArray();

        } catch (Exception e) {
            throw new FatcaEncryptionException(
                    "Failed to build " + fiTypeCode + " ZIP for giin=" + giin, e);
        }
    }

    private void addZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private void saveZip(String fileName, byte[] zipBytes) {
        try {
            Path dir = Path.of(outputProperties.getZipDir());
            Files.createDirectories(dir);
            Files.write(dir.resolve(fileName), zipBytes);
            log.info("Saved IDES ZIP: {}", dir.resolve(fileName).toAbsolutePath());
        } catch (IOException e) {
            log.warn("Could not save IDES ZIP to disk: {}", e.getMessage());
        }
    }

    private String deriveOutputFileName(String original) {
        String stripped = original.replaceAll("\\.p7m$", "");
        return stripped.endsWith(".xml") ? stripped : stripped + ".xml";
    }

    // ── Metadata XML ─────────────────────────────────────────

    private String buildMetadataXml(String transmissionId, String payloadFileName, String fileType) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Transmission xmlns="ides:metadata">
                  <TransmissionId>%s</TransmissionId>
                  <Timestamp>%s</Timestamp>
                  <FileInformation>
                    <FileName>%s</FileName>
                    <FileType>%s</FileType>
                  </FileInformation>
                </Transmission>
                """.formatted(
                transmissionId,
                LocalDateTime.now(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME) + "Z",
                payloadFileName,
                fileType);
    }

    /** IDES 規定 ZIP 檔名的 UTC 時間戳記格式：yyyyMMddTHHmmssSSS'Z' */
    private static String utcTimestamp() {
        return LocalDateTime.now(java.time.ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'"));
    }
}
