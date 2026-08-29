package com.muster.config;

import com.muster.common.ApiException;
import com.muster.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException e) {
        if (e.getData() != null) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", e.getErrorCode().name());
            map.put("message", e.getMessage());
            map.put("data", e.getData());
            return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(map);
        }
        return body(e.getErrorCode().getHttpStatus(), e.getErrorCode().name(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .findFirst()
                .orElse("参数不合法");
        return body(400, ErrorCode.VALIDATION.name(), message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return body(400, ErrorCode.VALIDATION.name(), "请求体格式不正确");
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUpload(
            org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return body(413, "PAYLOAD_TOO_LARGE", "上传文件过大，最大 10MB");
    }

    @ExceptionHandler(com.alibaba.excel.exception.ExcelAnalysisException.class)
    public ResponseEntity<Map<String, Object>> handleBadExcel(com.alibaba.excel.exception.ExcelAnalysisException e) {
        return body(400, ErrorCode.VALIDATION.name(), "文件不是有效的 Excel 文件");
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        return body(400, ErrorCode.VALIDATION.name(), "缺少参数：" + e.getParameterName());
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        return body(400, ErrorCode.VALIDATION.name(), "参数格式不正确：" + e.getName());
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return body(404, ErrorCode.NOT_FOUND.name(), "接口不存在");
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        return body(405, "METHOD_NOT_ALLOWED", "请求方法不支持");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return body(500, "INTERNAL", "服务器内部错误");
    }

    private ResponseEntity<Map<String, Object>> body(int status, String code, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("message", message);
        return ResponseEntity.status(status).body(map);
    }
}
