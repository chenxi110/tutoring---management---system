package com.skt.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器：统一拦截所有未捕获异常，返回友好 JSON 响应。
 * 响应格式与项目现有约定一致：{code, msg, error}。
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 构建统一错误响应 */
    private Map<String, Object> buildError(int code, String msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("msg", msg);
        result.put("error", msg);
        return result;
    }

    /** 兜底：所有未明确处理的异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("系统未捕获异常: ", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError(500, "服务器内部错误，请稍后重试或联系管理员"));
    }

    /** 数据库访问异常 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccess(DataAccessException e) {
        log.error("数据库访问异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError(500, "数据访问失败，请检查数据库连接或稍后重试"));
    }

    /** 文件上传超限 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("文件上传超限: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(buildError(413, "文件大小超过限制，请压缩后重新上传"));
    }

    /** 参数非法（业务层主动抛出） */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(buildError(400, e.getMessage() != null ? e.getMessage() : "请求参数不合法，请检查输入"));
    }

    /** 缺少必填请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必填参数: {}", e.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(buildError(400, "缺少必填参数: " + e.getParameterName()));
    }

    /** 参数类型不匹配（如路径变量应为数字但传入字符串） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: {}={}", e.getName(), e.getValue());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(buildError(400, "参数格式不正确: " + e.getName()));
    }

    /** 请求体 JSON 解析失败 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(buildError(400, "请求体格式不正确，请检查 JSON 数据"));
    }

    /** 空指针防护 */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, Object>> handleNullPointer(NullPointerException e) {
        log.error("空指针异常: ", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError(500, "服务器内部错误（空指针），请联系管理员"));
    }
}
