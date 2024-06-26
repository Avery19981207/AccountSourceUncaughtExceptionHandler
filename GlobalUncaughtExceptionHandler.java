package com.cy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
* 单例模式
*/
public class GlobalUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler{
    
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    
    private volatile static GlobalUncaughtExceptionHandler INSTANCE = null;
    
    private GlobalUncaughtExceptionHandler() {
    }

    public static GlobalUncaughtExceptionHandler getInstance() {
        if (INSTANCE == null) {
            synchronized (GlobalUncaughtExceptionHandler.class) {
                if (INSTANCE == null) {
                    INSTANCE = new GlobalUncaughtExceptionHandler();
                }
            }
        }
        return INSTANCE;
    }

    /*
     * 重写未捕获异常处理
     */
    @Override
    public void uncaughtException(Thread t, Throwable e) {
        logger.error("Exception in thread {} ", t.getName(), e);
    }
}
