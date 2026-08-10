package org.nexus.consortium.controller;

/**
 * json format:
 *
 * {
 *   "code": 500,
 *   "data": "data",
 *   "message": "success"
 * }
 */
public class Response {
    private int code;
    private Object data;
    private String message;

    public static enum Code {
        SUCCESS(200, "success"),

        INTERNAL_ERROR(500, "internal error");

        public final int code;

        public final String message;

        Code(int code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    private Response(int code, Object data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public Object getData() {
        return data;
    }

    public String getMessage() {
        return message;
    }

    public static Response newSuccessFul(Object data) {
        return new Response(Code.SUCCESS.code, data, Code.SUCCESS.message);
    }

    public static Response newFailed(Code code) {
        return new Response(code.code, "", code.message);
    }

    public static Response newFailed(Code code, String reason) {
        return new Response(code.code, "", reason);
    }
}
