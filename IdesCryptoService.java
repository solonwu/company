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

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
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
            String xml;

            if (isZipData(data)) {
                log.info("Detected ZIP format, processing: {}", sourceFileName);
                xml = processZipNotification(data, p12Password);
                sourceFileName = sourceFileName.replaceAll("(?i)\\.zip$", ".xml");
            } else {
                xml = decryptOrExtract(data, p12Password);
            }

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
     * ZIP 通知處理：依 entry 名稱判斷格式
     * 1. 含 .p7m/.p7/.p7s/.p7c → 標準 CMS 解密
     * 2. 含 {GIIN}_Key + {GIIN}_Payload → IRS IDES 原生格式
     * 3. 含非 Metadata 的 .xml → 純 XML 直接回傳
     */
    private String processZipNotification(byte[] zipData, String p12Password) throws IOException {
        Map<String, byte[]> entries = readZipEntries(zipData);

        // 1. 標準 CMS entry
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String lower = e.getKey().toLowerCase();
            if (lower.endsWith(".p7m") || lower.endsWith(".p7")
                    || lower.endsWith(".p7s") || lower.endsWith(".p7c")) {
                log.info("Found CMS entry: {}", e.getKey());
                return decryptOrExtract(e.getValue(), p12Password);
            }
        }

        // 2. IRS IDES 原生格式：{GIIN}_Key + {GIIN}_Payload
        byte[] keyBytes     = null;
        byte[] payloadBytes = null;
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (e.getKey().endsWith("_Key"))     keyBytes     = e.getValue();
            if (e.getKey().endsWith("_Payload")) payloadBytes = e.getValue();
        }
        if (keyBytes != null && payloadBytes != null) {
            log.info("Detected IRS IDES native format (_Key={} bytes, _Payload={} bytes)",
                    keyBytes.length, payloadBytes.length);
            return decryptIdesNativeFormat(keyBytes, payloadBytes, p12Password);
        }

        // 3. Fallback：非 Metadata 的 .xml
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String lower = e.getKey().toLowerCase();
            if (lower.endsWith(".xml") && !lower.contains("metadata")) {
                log.info("Using plain XML entry: {}", e.getKey());
                return new String(e.getValue(), StandardCharsets.UTF_8);
            }
        }

        throw new FatcaEncryptionException(
                "Cannot process ZIP: no known format found. Entries: " + entries.keySet());
    }

    /**
     * IRS IDES 原生格式解密。
     * RSA 解密 _Key 後，依結果長度決定 AES 參數，並對多種 key/IV 排列做 fallback 嘗試。
     */
    private String decryptIdesNativeFormat(byte[] keyBytes, byte[] payloadBytes, String p12Password) {
        SenderKeyPair sender = keyFileService.loadP12(p12Password);
        X509Certificate senderCert = sender.certificate();
        log.info("Using P12 certificate: subject={}, serial={}, validFrom={}, validTo={}",
                senderCert.getSubjectX500Principal().getName(),
                senderCert.getSerialNumber().toString(16),
                senderCert.getNotBefore(), senderCert.getNotAfter());
        try {
            byte[] sessionKey = rsaDecrypt(keyBytes, sender.privateKey());
            log.info("Session key decrypted: {} bytes", sessionKey.length);

            if (sessionKey.length == 16 || sessionKey.length == 24 || sessionKey.length == 32) {
                // 標準：IV 在 _Payload 開頭 16 bytes
                byte[] iv = Arrays.copyOfRange(payloadBytes, 0, 16);
                byte[] ct = Arrays.copyOfRange(payloadBytes, 16, payloadBytes.length);
                log.info("Standard: {}-bit AES, IV from _Payload prefix", sessionKey.length * 8);
                byte[] decrypted = aesDecryptCBC(sessionKey, iv, ct);
                log.info("AES decrypted: {} bytes", decrypted.length);
                return decryptOrExtract(decrypted, p12Password);

            } else if (sessionKey.length == 48) {
                // 48 bytes = AES-256 key(32) + IV(16)。嘗試三種排列
                log.info("48-byte key material, trying multiple AES key/IV arrangements");

                // Try 1: key=bytes[0:32], iv=bytes[32:48], cipherText=fullPayload (IRS PKCS1v15 常見格式)
                try {
                    byte[] k  = Arrays.copyOfRange(sessionKey, 0, 32);
                    byte[] iv = Arrays.copyOfRange(sessionKey, 32, 48);
                    byte[] dec = aesDecryptCBC(k, iv, payloadBytes);
                    log.info("AES ok (key||IV from _Key, full payload): {} bytes", dec.length);
                    return decryptOrExtract(dec, p12Password);
                } catch (Exception e) {
                    log.warn("48-byte try1 (key[0:32]+iv[32:48], full payload) failed: {}", e.getMessage());
                }

                // Try 2: iv=bytes[0:16], key=bytes[16:48], cipherText=fullPayload
                try {
                    byte[] iv = Arrays.copyOfRange(sessionKey, 0, 16);
                    byte[] k  = Arrays.copyOfRange(sessionKey, 16, 48);
                    byte[] dec = aesDecryptCBC(k, iv, payloadBytes);
                    log.info("AES ok (iv||key from _Key, full payload): {} bytes", dec.length);
                    return decryptOrExtract(dec, p12Password);
                } catch (Exception e) {
                    log.warn("48-byte try2 (iv[0:16]+key[16:48], full payload) failed: {}", e.getMessage());
                }

                // Try 3: key=bytes[0:32], IV=payload[0:16], cipherText=payload[16:]
                try {
                    byte[] k  = Arrays.copyOfRange(sessionKey, 0, 32);
                    byte[] iv = Arrays.copyOfRange(payloadBytes, 0, 16);
                    byte[] ct = Arrays.copyOfRange(payloadBytes, 16, payloadBytes.length);
                    byte[] dec = aesDecryptCBC(k, iv, ct);
                    log.info("AES ok (key[0:32] from _Key, IV from payload prefix): {} bytes", dec.length);
                    return decryptOrExtract(dec, p12Password);
                } catch (Exception e) {
                    log.warn("48-byte try3 (key[0:32] from _Key, IV from payload) failed: {}", e.getMessage());
                }

                throw new FatcaEncryptionException(
                        "All 48-byte AES arrangements failed. " +
                        "OAEP errors above indicate PKCS1v15 padding was used — if key is correct, " +
                        "the payload format may differ from expected.");

            } else {
                throw new FatcaEncryptionException(
                        "Unexpected session key length after RSA decrypt: " + sessionKey.length + " bytes. " +
                        "Expected 16/24/32 (standard) or 48 (key+IV bundled).");
            }

        } catch (FatcaEncryptionException e) {
            throw e;
        } catch (Exception e) {
            throw new FatcaEncryptionException(
                    "IDES native format decryption failed: " + e.getMessage(), e);
        }
    }

    private byte[] aesDecryptCBC(byte[] key, byte[] iv, byte[] cipherText) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding", BC);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(cipherText);
    }

    /**
     * RSA 解密 session key。依序嘗試 OAEP-SHA1 → OAEP-SHA256 → PKCS1v15。
     *
     * 注意：BouncyCastle PKCS1 safe-mode 即使 key 完全錯誤也不會丟例外，
     * 只會回傳隨機長度的垃圾；OAEP 才會真正驗證 padding 並丟出例外。
     * OAEP 丟 "unable to decrypt block" 表示加密時用的是 PKCS1v15，不一定是 key 錯。
     */
    private byte[] rsaDecrypt(byte[] keyBytes, PrivateKey privateKey) throws Exception {
        // 1. OAEP SHA-1（IRS IDES 規格常見）
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA1AndMGF1Padding", BC);
            c.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] r = c.doFinal(keyBytes);
            if (isValidAesKeyLen(r)) { log.info("RSA-OAEP(SHA-1) ok, {} bytes", r.length); return r; }
            log.warn("RSA-OAEP(SHA-1) gave {} bytes", r.length);
        } catch (Exception e) {
            log.warn("RSA-OAEP(SHA-1) failed: {}", e.getMessage());
        }

        // 2. OAEP SHA-256 / MGF1-SHA-256
        try {
            OAEPParameterSpec spec = new OAEPParameterSpec(
                    "SHA-256", "MGF1", new MGF1ParameterSpec("SHA-256"), PSource.PSpecified.DEFAULT);
            Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding", BC);
            c.init(Cipher.DECRYPT_MODE, privateKey, spec);
            byte[] r = c.doFinal(keyBytes);
            if (isValidAesKeyLen(r)) { log.info("RSA-OAEP(SHA-256) ok, {} bytes", r.length); return r; }
            log.warn("RSA-OAEP(SHA-256) gave {} bytes", r.length);
        } catch (Exception e) {
            log.warn("RSA-OAEP(SHA-256) failed: {}", e.getMessage());
        }

        // 3. PKCS1v15（safe-mode；OAEP 若失敗且 key 正確則代表加密用 PKCS1v15）
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding", BC);
            c.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] r = c.doFinal(keyBytes);
            // 接受 16~48 bytes（含 key+IV 48-byte 格式），讓 AES 層做最終驗證
            if (r.length >= 16 && r.length <= 48) {
                log.info("RSA-PKCS1 gave {} bytes (will validate via AES)", r.length);
                return r;
            }
            log.warn("RSA-PKCS1 gave {} bytes (out of expected range 16-48)", r.length);
        } catch (Exception e) {
            log.warn("RSA-PKCS1 failed: {}", e.getMessage());
        }

        throw new FatcaEncryptionException(
                "RSA decrypt failed with all algorithms. " +
                "Verify the SENDER_P12 matches the certificate enrolled in IRS FATCA portal for GIIN: " +
                "HU6C62.00000.LE.158");
    }

    private boolean isValidAesKeyLen(byte[] key) {
        return key.length == 16 || key.length == 24 || key.length == 32;
    }

    /**
     * payload 可能是：gzip XML → nested ZIP → 純 XML → CMS EnvelopedData → CMS SignedData
     */
    private String decryptOrExtract(byte[] data, String p12Password) {
        // gzip magic bytes: 1F 8B
        if (data.length >= 2 && data[0] == (byte) 0x1F && data[1] == (byte) 0x8B) {
            log.info("Detected gzip content ({} bytes), decompressing...", data.length);
            try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = gis.read(buf)) > 0) out.write(buf, 0, len);
                data = out.toByteArray();
                log.info("Gzip decompressed: {} bytes", data.length);
            } catch (IOException e) {
                throw new FatcaEncryptionException("Gzip decompression failed: " + e.getMessage(), e);
            }
        }

        // nested ZIP（IRS IDES 常見：_Payload = AES(inner_zip)，inner_zip 包含 XML）
        if (isZipData(data)) {
            log.info("Detected nested ZIP inside decrypted payload ({} bytes)", data.length);
            try {
                return extractXmlFromInnerZip(data);
            } catch (IOException e) {
                throw new FatcaEncryptionException("Failed to extract XML from inner ZIP: " + e.getMessage(), e);
            }
        }

        if (looksLikeXml(data)) {
            log.info("Payload is plain XML");
            return new String(data, StandardCharsets.UTF_8);
        }
        try {
            return decryptNotification(data, p12Password);
        } catch (FatcaEncryptionException envelopedEx) {
            log.warn("Not EnvelopedData ({}), trying SignedData...", envelopedEx.getMessage());
        }
        try {
            CMSSignedData signedData = new CMSSignedData(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            signedData.getSignedContent().write(out);
            log.info("Extracted from CMS SignedData");
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception signedEx) {
            throw new FatcaEncryptionException(
                    "Payload is not XML, EnvelopedData, or SignedData: " + signedEx.getMessage(), signedEx);
        }
    }

    /**
     * 從 AES 解密後的 inner ZIP 中找出 XML 通知內容。
     * IRS IDES _Payload 解密後是一個 ZIP，裡面通常有一個 .xml 檔案。
     */
    private String extractXmlFromInnerZip(byte[] zipData) throws IOException {
        Map<String, byte[]> entries = readZipEntries(zipData);
        log.info("Inner ZIP entries: {}", entries.keySet());

        // 優先：非 Metadata 的 .xml
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String lower = e.getKey().toLowerCase();
            if (lower.endsWith(".xml") && !lower.contains("metadata")) {
                log.info("Found XML in inner ZIP: {}", e.getKey());
                return new String(e.getValue(), StandardCharsets.UTF_8);
            }
        }
        // Fallback：任何 .xml
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (e.getKey().toLowerCase().endsWith(".xml")) {
                log.info("Using XML entry from inner ZIP: {}", e.getKey());
                return new String(e.getValue(), StandardCharsets.UTF_8);
            }
        }
        // 最後 fallback：只有一個 entry，直接當 XML 嘗試
        if (entries.size() == 1) {
            Map.Entry<String, byte[]> only = entries.entrySet().iterator().next();
            log.info("Single inner ZIP entry, treating as XML: {}", only.getKey());
            return new String(only.getValue(), StandardCharsets.UTF_8);
        }
        throw new FatcaEncryptionException(
                "Inner ZIP has no XML entry. Entries: " + entries.keySet());
    }

    /** 讀取 ZIP 所有 entry 到 Map，並印出摘要。 */
    private Map<String, byte[]> readZipEntries(byte[] zipData) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[8192];
                int len;
                while ((len = zis.read(tmp)) != -1) buf.write(tmp, 0, len);
                byte[] bytes = buf.toByteArray();
                log.info("ZIP entry: name={}, size={} bytes", entry.getName(), bytes.length);
                entries.put(entry.getName(), bytes);
                zis.closeEntry();
            }
        }
        return entries;
    }

    /** ZIP magic bytes 0x50 0x4B 0x03 0x04 */
    private boolean isZipData(byte[] data) {
        return data.length >= 4
                && data[0] == 0x50 && data[1] == 0x4B
                && data[2] == 0x03 && data[3] == 0x04;
    }

    private boolean looksLikeXml(byte[] data) {
        if (data.length == 0) return false;
        if (data[0] == '<') return true;
        return data.length >= 3
                && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF;
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
