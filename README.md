## README

# 账号源对接项目优化方案

## 目录

- [背景](#背景)
- [优化方案概述](#优化方案概述)
- [实现细节](#实现细节)
  - [自定义线程工厂](#自定义线程工厂)
  - [全局未捕获异常处理器](#全局未捕获异常处理器)
  - [使用示例](#使用示例)
- [运行和测试](#运行和测试)
- [结论](#结论)

## 背景

在账号源对接项目中，在实现用户数据、组织数据同步时，核心是在设计好的代码架构中实现doScanUser和doScanTeam方法。由于目标组织和用户的数量往往是有一定体量，在进行本地数据库同步时有一定的耗时，因此设计成异步的方式进行数据同步。产品中多个使用者同时进行账号源同步时涉及了多线程并发的状况。然而同步在运行过程中可能会抛出异常，未捕获的异常会导致线程意外终止，默认调用Java虚拟机的未捕获异常处理方案时将异常堆栈直接打印，同时不会将异常记录到日志中。这使得问题难以调试和追踪。因此，通过自定义 `setUncaughtExceptionHandler` 来捕获异常并记录日志，从而优化异常处理和日志记录。

## 优化方案概述

我们实现了一个自定义的 `AcctThreadFactory` 和全局未捕获异常处理器 `GlobalUncaughtExceptionHandler`，以便在任何线程发生未捕获异常时，能够记录详细的异常日志。具体步骤如下：

1. 创建自定义的 `AcctThreadFactory`，用于创建线程并设置自定义的未捕获异常处理器。
2. 创建 `GlobalUncaughtExceptionHandler`，用于记录异常日志。
3. 在创建线程池时使用自定义的 `AcctThreadFactory`。

## 实现细节

### 自定义线程工厂

`AcctThreadFactory` 实现了 `ThreadFactory` 接口，并在每个线程上设置了自定义的未捕获异常处理器。

```java
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
```

### 全局未捕获异常处理器

`GlobalUncaughtExceptionHandler` 实现了 `Thread.UncaughtExceptionHandler` 接口，用于自定义记录线程中未捕获的异常。

```java
package com.cy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GlobalUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler{
    /**
     * 单例模式——懒汉实现
     * @return
     */
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private static GlobalUncaughtExceptionHandler INSTANCE = null;
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
```

## 结论

通过自定义 `setUncaughtExceptionHandler`，我们在账号源对接项目中实现了更好的异常处理和日志记录。每当线程发生未捕获的异常时，这些异常将被记录到日志中，有助于问题的调试和追踪。这一优化方案提高了系统的可靠性和可维护性。
