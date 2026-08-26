package com.skt.dto;

import java.util.Map;

public class ApiResponse {
    private int code;
    private Object data;
    private String error;

    public ApiResponse(int code, Object data) {
        this.code = code;
        this.data = data;
    }

    public ApiResponse(int code, String error) {
        this.code = code;
        this.error = error;
    }

    public static ApiResponse ok(Object data) {
        return new ApiResponse(200, data);
    }

    public static ApiResponse ok() {
        return new ApiResponse(200, null);
    }

    public static ApiResponse error(int code, String msg) {
        return new ApiResponse(code, msg);
    }

    public int getCode() { return code; }
    public Object getData() { return data; }
    public String getError() { return error; }

    public Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("code", code);
        if (data != null) m.put("data", data);
        if (error != null) m.put("error", error);
        return m;
    }
}
