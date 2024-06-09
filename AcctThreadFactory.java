package com.cy;

import com.sun.istack.internal.NotNull;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadFactory;
@Slf4j
@AllArgsConstructor
public class AcctThreadFactory implements ThreadFactory {
    private final ThreadFactory factory;
    /*
     * 不设置setUncaughtExceptionHandler，会调用JVM默认的未捕获处理器，打印异常的堆栈跟踪信息到System.err，同时终止线程
     */
    @Override
    public Thread newThread(@NotNull Runnable r) {
        Thread thread = factory.newThread(r);
        thread.setUncaughtExceptionHandler(GlobalUncaughtExceptionHandler.getInstance());
        return thread;
    }
}
