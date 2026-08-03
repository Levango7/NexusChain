package org.nexus.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.nexus.ipc.IpcConfig;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

@Component
public class NexusChainHandlerInterceptor implements HandlerInterceptor {

    @Autowired
    IpcConfig ipcConfig;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler){
        if (!ipcConfig.isLocalOnly()){
            return true;
        }
        try {
            PrintWriter out =response.getWriter();
            out.write("rpc is Intercepted");
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

}
