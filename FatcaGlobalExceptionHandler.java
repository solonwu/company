package com.fatca.api.exception;

import com.fatca.core.record.ApiResponse;
import com.fatca.crypto.FatcaEncryptionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.FileNotFoundException;

/**
 * 全域例外處理：統一回傳 {@link ApiResponse}（success=false, error=訊息）。
 */
@RestControllerAdvice
@Slf4j
public class FatcaGlobalExceptionHandler {

    @ExceptionHandler(JobExecutionException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleJobException(JobExecutionException ex) {
        log.error("Batch job execution error", ex);
        return error("JOB_EXECUTION_ERROR", ex.getMessage());
    }

    @ExceptionHandler(FatcaEncryptionException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleCryptoException(FatcaEncryptionException ex) {
        log.error("Crypto error", ex);
        return error("CRYPTO_ERROR", ex.getMessage());
    }

    @ExceptionHandler(FileNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleFileNotFound(FileNotFoundException ex) {
        log.warn("File not found: {}", ex.getMessage());
        return error("FILE_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBadRequest(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return error("BAD_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResource(NoResourceFoundException ex) {
        return error("NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        return error("INTERNAL_ERROR", ex.getMessage());
    }

    private static ApiResponse<Void> error(String code, String message) {
        return ApiResponse.error(message != null ? message : "", code);
    }
}
