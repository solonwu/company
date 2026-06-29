package com.fatca.api.controller;

import com.fatca.api.dto.DecryptResponse;
import com.fatca.api.dto.ManualEncryptResponse;
import com.fatca.api.dto.ReportStatusResponse;
import com.fatca.api.dto.ReportTriggerRequest;
import com.fatca.api.dto.ReportTriggerResponse;
import com.fatca.api.dto.ZipFileInfo;
import com.fatca.core.entity.GiinConfig;
import com.fatca.core.enums.FiType;
import com.fatca.core.enums.SubmissionStatus;
import com.fatca.core.enums.ZipFileType;
import com.fatca.core.record.FatcaXmlResult;
import com.fatca.core.record.SubmissionPackage;
import com.fatca.core.entity.FatcaSubmission;
import com.fatca.core.repository.FatcaSubmissionRepository;
import com.fatca.core.repository.GiinConfigRepository;
import com.fatca.core.vo.GIIN;
import com.fatca.crypto.ides.IdesCryptoService;
import com.fatca.xml.service.FatcaXmlBuilder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/fatca")
@Tag(name = "FATCA", description = "FATCA IDES 報告 API")
@Slf4j
public class FatcaV1Controller {

    private final JobLauncher jobLauncher;
    private final Job fatcaReportingJob;
    private final FatcaSubmissionRepository submissionRepository;
    private final IdesCryptoService idesCryptoService;
    private final GiinConfigRepository giinConfigRepository;

    @Value("${fatca.output.zip-dir}")
    private String zipOutputDir;

    public FatcaV1Controller(JobLauncher jobLauncher,
                              @Qualifier("fatcaReportingJob") Job fatcaReportingJob,
                              FatcaSubmissionRepository submissionRepository,
                              IdesCryptoService idesCryptoService,
                              GiinConfigRepository giinConfigRepository) {
        this.jobLauncher = jobLauncher;
        this.fatcaReportingJob = fatcaReportingJob;
        this.submissionRepository = submissionRepository;
        this.idesCryptoService = idesCryptoService;
        this.giinConfigRepository = giinConfigRepository;
    }

    @PostMapping("/report")
    @Operation(summary = "觸發 FATCA 報告工作", description = "以指定年度 + p12 密碼觸發 fatcaReportingJob")
    public ResponseEntity<ReportTriggerResponse> triggerReport(
            @RequestBody ReportTriggerRequest request) throws JobExecutionException {

        Integer reportYear = request.reportYear();

        JobParameters params = new JobParametersBuilder()
                .addLong("reportYear", (long) reportYear)
                .addLong("runId", System.currentTimeMillis())
                .addString("p12Password", request.p12Password(), false)
                .toJobParameters();

        JobExecution execution = jobLauncher.run(fatcaReportingJob, params);
        log.info("Launched fatcaReportingJob for year {}, executionId={}, status={}",
                reportYear, execution.getId(), execution.getStatus());

        FatcaSubmission submission = submissionRepository
                .findByReportYearOrderByCreatedAtDesc(reportYear)
                .stream().findFirst().orElse(null);

        return ResponseEntity.accepted().body(ReportTriggerResponse.builder()
                .jobId(execution.getId())
                .messageRefId(submission != null ? submission.getMessageRefId() : null)
                .status(execution.getStatus().name())
                .bankZipPath(submission != null ? submission.getBankZipPath() : null)
                .trustZipPath(submission != null ? submission.getTrustZipPath() : null)
                .build());
    }

    @GetMapping("/submissions")
    @Operation(summary = "查詢申報歷史記錄", description = "可選 reportYear 篩選；未指定時回傳全部，依建立時間新到舊排序")
    public ResponseEntity<List<FatcaSubmission>> listSubmissions(
            @Parameter(description = "報告年度（選填）", example = "2024")
            @RequestParam(required = false) Integer reportYear) {

        List<FatcaSubmission> result = reportYear != null
                ? submissionRepository.findByReportYearOrderByCreatedAtDesc(reportYear)
                : submissionRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status/{reportYear}")
    @Operation(summary = "查詢報告狀態", description = "以年度查詢 FATCA 提交狀態與 ZIP 清單")
    public ResponseEntity<ReportStatusResponse> getStatus(
            @Parameter(description = "報告年度", example = "2024")
            @PathVariable Integer reportYear) {

        FatcaSubmission submission = submissionRepository
                .findByReportYearOrderByCreatedAtDesc(reportYear)
                .stream().findFirst().orElse(null);

        List<ZipFileInfo> zipFiles = new ArrayList<>();
        if (submission != null) {
            if (submission.getBankZipFileName() != null) {
                zipFiles.add(ZipFileInfo.builder()
                        .type("BANK")
                        .fileName(submission.getBankZipFileName())
                        .build());
            }
            if (submission.getTrustZipFileName() != null) {
                zipFiles.add(ZipFileInfo.builder()
                        .type("TRUST")
                        .fileName(submission.getTrustZipFileName())
                        .build());
            }
        }

        String status = submission != null ? submission.getStatus().name() : "NOT_FOUND";

        return ResponseEntity.ok(ReportStatusResponse.builder()
                .reportYear(reportYear)
                .bankCount(submission != null ? submission.getBankCount() : 0)
                .trustCount(submission != null ? submission.getTrustCount() : 0)
                .status(status)
                .zipFiles(zipFiles)
                .build());
    }

    @PostMapping(value = "/decrypt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "解密 IDES 回覆檔案", description = "上傳 .p7m 或 .zip，以本機私鑰（p12Password 解鎖）解密並回傳內容預覽")
    public ResponseEntity<DecryptResponse> decrypt(
            @Parameter(description = "加密檔案 (.p7m 或 .zip)")
            @RequestParam("file") MultipartFile file,
            @RequestParam("p12Password") String p12Password) throws IOException {

        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.p7m";
        Path tempFile = Files.createTempFile("fatca-decrypt-", "-" + originalName);
        try {
            file.transferTo(tempFile);
            Path decryptedPath = idesCryptoService.decryptNotificationFile(tempFile, p12Password);

            String content = Files.readString(decryptedPath, StandardCharsets.UTF_8);
            String preview = content.length() > 50000
                    ? content.substring(0, 50000) + "\n<!-- [內容過長，完整內容請見檔案] -->"
                    : content;

            return ResponseEntity.ok(DecryptResponse.builder()
                    .decryptedFileName(decryptedPath.getFileName().toString())
                    .decryptedFilePath(decryptedPath.toAbsolutePath().toString())
                    .contentPreview(preview)
                    .build());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @GetMapping("/download/bank-zip/{reportYear}")
    @Operation(summary = "下載銀行 ZIP", description = "下載指定年度的銀行帳戶 FATCA ZIP 檔")
    public ResponseEntity<Resource> downloadBankZip(
            @Parameter(description = "報告年度", example = "2024")
            @PathVariable Integer reportYear) throws FileNotFoundException {
        return buildZipDownload(reportYear, "BANK",
                FatcaSubmission::getBankZipFileName);
    }

    @GetMapping("/download/trust-zip/{reportYear}")
    @Operation(summary = "下載信託 ZIP", description = "下載指定年度的信託帳戶 FATCA ZIP 檔")
    public ResponseEntity<Resource> downloadTrustZip(
            @Parameter(description = "報告年度", example = "2024")
            @PathVariable Integer reportYear) throws FileNotFoundException {
        return buildZipDownload(reportYear, "TRUST",
                FatcaSubmission::getTrustZipFileName);
    }

    @PostMapping(value = "/manual-sign-encrypt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "手動簽章加密 XML",
               description = "上傳或貼入未加密的申報本文 XML，簽章+加密後回傳 Bank/Trust ZIP 檔名")
    public ResponseEntity<ManualEncryptResponse> manualSignEncrypt(
            @Parameter(description = "XML 檔案（.xml），與 xmlContent 二選一")
            @RequestParam(value = "file", required = false) MultipartFile file,
            @Parameter(description = "XML 內文字串，與 file 二選一")
            @RequestParam(value = "xmlContent", required = false) String xmlContent,
            @RequestParam("p12Password") String p12Password,
            @Parameter(description = "MessageRefId（選填，未填則自動從 XML 擷取）")
            @RequestParam(value = "messageRefId", required = false) String messageRefId,
            @Parameter(description = "IDES Metadata XML 檔案（選填，未上傳則自動產生）")
            @RequestParam(value = "metadataFile", required = false) MultipartFile metadataFile,
            @Parameter(description = "機構類型：BANK（銀行）/ TRUST（信託）")
            @RequestParam("fiType") FiType fiType,
            @Parameter(description = "申報年度（選填，用於歷史紀錄）")
            @RequestParam(value = "reportYear", required = false) Integer reportYear)
            throws IOException {

        String xml;
        if (file != null && !file.isEmpty()) {
            xml = new String(file.getBytes(), StandardCharsets.UTF_8);
        } else if (xmlContent != null && !xmlContent.isBlank()) {
            xml = xmlContent;
        } else {
            return ResponseEntity.badRequest().build();
        }

        String resolvedId = (messageRefId != null && !messageRefId.isBlank())
                ? messageRefId : extractMessageRefId(xml);
        if (resolvedId == null) {
            resolvedId = "MANUAL-" + System.currentTimeMillis();
        }

        byte[] metadataBytes = (metadataFile != null && !metadataFile.isEmpty())
                ? metadataFile.getBytes() : null;

        GiinConfig activeGiin = giinConfigRepository
                .findFirstByActiveTrueAndFiTypeOrderByCreatedAtDesc(fiType)
                .orElseGet(() -> giinConfigRepository.findFirstByActiveTrueOrderByCreatedAtDesc()
                        .orElseThrow(() -> new IllegalStateException("No active GiinConfig found")));

        FatcaXmlResult xmlResult = FatcaXmlResult.ok(resolvedId, xml);
        SubmissionPackage pkg = idesCryptoService.buildSubmissionPackage(
                xmlResult, p12Password, metadataBytes, fiType, activeGiin.getGiin());

        log.info("Manual sign+encrypt complete: msgRef={}, fiType={}, giin={}",
                pkg.messageRefId(), fiType, activeGiin.getGiin());

        saveOrUpdateSubmission(pkg, fiType, reportYear);

        return ResponseEntity.ok(toManualEncryptResponse(pkg, fiType, xml));
    }

    @PostMapping(value = "/nil-report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "產生 Nil Report ZIP",
               description = "以 active GIIN 設定產生本年度無應申報帳戶的 Nil Report，簽章加密後回傳 ZIP")
    public ResponseEntity<ManualEncryptResponse> generateNilReport(
            @RequestParam("reportYear") int reportYear,
            @RequestParam("p12Password") String p12Password,
            @Parameter(description = "IDES Metadata XML 檔案（選填）")
            @RequestParam(value = "metadataFile", required = false) MultipartFile metadataFile,
            @Parameter(description = "機構類型：BANK（銀行）/ TRUST（信託）")
            @RequestParam("fiType") FiType fiType)
            throws IOException {

        GiinConfig cfg = giinConfigRepository.findFirstByActiveTrueAndFiTypeOrderByCreatedAtDesc(fiType)
                .orElseThrow(() -> new IllegalStateException(
                        "No active GIIN config found for fiType=" + fiType));

        String fiName  = cfg.getFiNameEn() != null && !cfg.getFiNameEn().isBlank()
                ? cfg.getFiNameEn() : cfg.getFiName();
        String address = cfg.getAddressEn() != null && !cfg.getAddressEn().isBlank()
                ? cfg.getAddressEn() : fiName;
        String country = cfg.getTransmittingCountry() != null ? cfg.getTransmittingCountry() : "TW";

        String xml = FatcaXmlBuilder.newBuilder()
                .giin(GIIN.of(cfg.getGiin()))
                .reportYear(reportYear)
                .transmittingCountry(country)
                .reportingFI(FatcaXmlBuilder.FISpec.builder()
                        .tin(cfg.getGiin())
                        .tinCountry(country)
                        .name(fiName)
                        .addressFree(address)
                        .addressCountry(country)
                        .build())
                .nilReport()
                .build();

        String messageRefId = extractMessageRefId(xml);
        if (messageRefId == null) {
            messageRefId = "NIL-" + reportYear + "-" + System.currentTimeMillis();
        }

        byte[] metadataBytes = (metadataFile != null && !metadataFile.isEmpty())
                ? metadataFile.getBytes() : null;

        FatcaXmlResult xmlResult = FatcaXmlResult.ok(messageRefId, xml);
        SubmissionPackage pkg = idesCryptoService.buildSubmissionPackage(
                xmlResult, p12Password, metadataBytes, fiType, cfg.getGiin());

        log.info("Generated nil report ZIP: year={}, fiType={}, giin={}, msgRef={}",
                reportYear, fiType, cfg.getGiin(), pkg.messageRefId());

        saveOrUpdateSubmission(pkg, fiType, reportYear);

        return ResponseEntity.ok(toManualEncryptResponse(pkg, fiType, xml));
    }

    @GetMapping("/download/zip/{fileName}")
    @Operation(summary = "依檔名下載 ZIP", description = "下載 zip-dir 內指定檔名的 ZIP 檔")
    public ResponseEntity<Resource> downloadZipByFileName(
            @PathVariable String fileName) throws FileNotFoundException {

        Path base = Paths.get(zipOutputDir).normalize();
        Path target = base.resolve(fileName).normalize();
        if (!target.startsWith(base)) {
            return ResponseEntity.badRequest().build();
        }
        if (!Files.exists(target)) {
            throw new FileNotFoundException("ZIP not found: " + fileName);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(target));
    }

    private static ManualEncryptResponse toManualEncryptResponse(
            SubmissionPackage pkg, FiType fiType, String xmlContent) {
        return ManualEncryptResponse.builder()
                .messageRefId(pkg.messageRefId())
                .bankZipFileName(  fiType == FiType.BANK   ? pkg.bankZipFileName()  : null)
                .trustZipFileName( fiType == FiType.TRUST  ? pkg.trustZipFileName() : null)
                .branchZipFileName(fiType == FiType.BRANCH ? pkg.bankZipFileName()  : null)
                .xmlContent(xmlContent)
                .build();
    }

    private void saveOrUpdateSubmission(SubmissionPackage pkg, FiType fiType, Integer reportYear) {
        FatcaSubmission sub = submissionRepository.findByMessageRefId(pkg.messageRefId())
                .orElseGet(() -> FatcaSubmission.builder()
                        .messageRefId(pkg.messageRefId())
                        .reportYear(reportYear)
                        .bankCount(0)
                        .trustCount(0)
                        .build());

        if (fiType == FiType.BANK || fiType == FiType.BRANCH) {
            sub.setBankZipFileName(pkg.bankZipFileName());
            sub.setBankZipPath(Paths.get(zipOutputDir, pkg.bankZipFileName()).toString());
        } else if (fiType == FiType.TRUST) {
            sub.setTrustZipFileName(pkg.trustZipFileName());
            sub.setTrustZipPath(Paths.get(zipOutputDir, pkg.trustZipFileName()).toString());
        }
        sub.setStatus(SubmissionStatus.ZIP_READY);
        submissionRepository.save(sub);
        log.info("Saved submission record: msgRef={}, fiType={}, year={}", pkg.messageRefId(), fiType, reportYear);
    }

    private static final Pattern MSG_REF_PATTERN =
            Pattern.compile("<[^:>]*:?MessageRefId[^>]*>([^<]+)<");

    private static String extractMessageRefId(String xml) {
        Matcher m = MSG_REF_PATTERN.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    private ResponseEntity<Resource> buildZipDownload(
            Integer reportYear, String type,
            Function<FatcaSubmission, String> zipFileNameExtractor)
            throws FileNotFoundException {

        List<FatcaSubmission> submissions =
                submissionRepository.findByReportYearOrderByCreatedAtDesc(reportYear);
        FatcaSubmission sub = submissions.stream()
                .filter(s -> zipFileNameExtractor.apply(s) != null)
                .findFirst()
                .orElseThrow(() -> new FileNotFoundException(
                        "No " + type + " ZIP found for year " + reportYear));

        String zipFileName = zipFileNameExtractor.apply(sub);
        Path zipFile = Paths.get(zipOutputDir, zipFileName);
        if (!Files.exists(zipFile)) {
            throw new FileNotFoundException("ZIP file not found on disk: " + zipFileName);
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + zipFileName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(zipFile));
    }
}
