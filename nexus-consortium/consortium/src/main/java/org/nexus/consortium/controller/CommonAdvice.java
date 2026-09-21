package org.nexus.consortium.controller;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import lombok.extern.slf4j.Slf4j;

/**
 * Global rest response body json formatter
 * e.g.
 *
 * {"user": "name"}
 *  ->  {
 *      "code": 200,
 *      "message": "success",
 *      "data": {
 *          "user": "name"
 *      }
 *  }
 *
 * byte[] or String will be ignored
 */
@Slf4j
@RestControllerAdvice
public class CommonAdvice implements ResponseBodyAdvice {
    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType, Class selectedConverterType, ServerHttpRequest request, ServerHttpResponse response) {
        if (body.getClass().equals(byte[].class) || 
                body.getClass().equals(String.class) || 
                body.getClass().equals(Response.class)
        ){
            return body;
        }
        try{
            return Response.newSuccessFul(body);
        }catch (Exception e){
            log.error("CommonAdvice: uncaught exception", e);
            return Response.Code.INTERNAL_ERROR.message;
        }
    }
}
