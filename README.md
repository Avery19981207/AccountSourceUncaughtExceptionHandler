## README

# 账号源对接项目基于自定义setUncaughtExceptionHandler实现优雅异常未捕获处理

## 目录

- [背景](#背景)
- [优化方案概述](#优化方案概述)
- [实现细节](#实现细节)
  - [自定义线程工厂](#自定义线程工厂)
  - [全局未捕获异常处理器](#全局未捕获异常处理器)
  - [使用示例](#使用示例)
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
## 使用示例
```java
@Service("SzmhAcctSourceScanService")
public class SzmhAcctSourceScanServiceImpl implements AcctSourceScanService {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private RedisClient<String, Object> redisClient;

    @Autowired
    private AcctSourceInstService acctSourceInstService;

    @Autowired
    private AcctBaseService acctBaseService;

    @Autowired
    private AcctSyncPublish acctSyncPublish;

    private final Object lock = new Object();

    private Integer teamCurrentTaskMode = 0;

    private Integer userCurrentTaskMode = 0;

    private static final String grant_type = "client_credentials";//固定值

    private static final String scope = "select";//固定值

    private static ThreadFactory defaultFactory = Executors.defaultThreadFactory();
    private static AcctThreadFactory customThreadFactory = new AcctThreadFactory(defaultFactory);

    private static ExecutorService executorService = Executors.newFixedThreadPool(10, customThreadFactory);

    @Override
    public InvokeResult doScanTeam(String acctSourceInstId, boolean autoSync) {
        synchronized (lock){
            if(teamCurrentTaskMode > 0){
                return new InvokeResult(InvokeCode.SERVER_ERROR.getCode(),"扫描任务运行中");
            }
            AcctSourceInst acctSourceInst = acctSourceInstService.getAcctSourceInstById(acctSourceInstId);
            if(acctSourceInst == null){
                return new InvokeResult(InvokeCode.SERVER_ERROR.getCode(),"未找到账号源实例");
            }
            teamCurrentTaskMode = 1;
            executorService.submit(() -> {
                try{
                    List<TeamBuffer> szmhTeamInfoList = getTeamFromSzmhList(acctSourceInst);
                    logger.info("--------数字门户组织机构数量条数--------" + szmhTeamInfoList.size());
                    if(CollectionUtils.isEmpty(szmhTeamInfoList)){
                        logger.error("Szmh DoScanTeam fails.");
                        teamCurrentTaskMode = 0;
                        return;
                    }
                    acctBaseService.doScanTeamBuffer(acctSourceInstId,szmhTeamInfoList);
                }catch (Exception e){
                    teamCurrentTaskMode = 0;
                    logger.error("szmhAcctSyncService.doDepartScanByLastUpdateTime.error param{}:",JSON.toJSON(acctSourceInst),e);
                    TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                }finally{
                    teamCurrentTaskMode = 0;
                    if(autoSync){
                        logger.info("发布数字门户组织扫描完成信息==========");
                        acctSyncPublish.publish("数字门户扫描组织结构",acctSourceInst,SyncTypeEnum.USER);
                    }
                }
                logger.info("Scan szmh team successfully!");
            });
        }
        return new InvokeResult(InvokeCode.SUCCESS.getCode(),"扫描任务已提交");
    }

    @Override
    public InvokeResult doScanUser(String acctSourceInstId, Boolean scanAll, Boolean autoSync) {
        synchronized (lock){
            if(userCurrentTaskMode > 0) {
                return new InvokeResult(InvokeCode.SERVER_ERROR.getCode(), "扫描任务进行中");
            }
        }
        AcctSourceInst acctInst = acctSourceInstService.getAcctSourceInstById(acctSourceInstId);
        if(acctInst == null) {
            return new InvokeResult(InvokeCode.SERVER_ERROR.getCode(), "未找到账号源实例");
        }
        userCurrentTaskMode = 1;
        executorService.submit(() -> {
            logger.info("数字门户扫描用户开始++++++++++++++++");
            try{
                //根据机构代码查询所有的用户信息
                List<Map> szmhUserInfoList = getSzmhUserList(acctInst);
                logger.error("--------数字门户扫描用户数量条数--------"+(szmhUserInfoList == null ? "0" : szmhUserInfoList.size()));
                if(Collections.isEmpty(szmhUserInfoList)){
                    userCurrentTaskMode = 0;
                    logger.error("szmh DoScanUser fail.");
                    return;
                }
                //转换格式
                List<UserBuffer>userBufferList = convertFromSzmhUser(szmhUserInfoList,acctInst);
                //将用户数据同步到缓冲表
                acctBaseService.doSyncUserBuffer(acctSourceInstId,userBufferList);
                userCurrentTaskMode = 0;
            }catch (Exception e){
                userCurrentTaskMode = 0;
                logger.error("error",e);
            }finally{
                userCurrentTaskMode = 0;
                if(autoSync){
                    logger.info("发布数字门户账号扫描完成信息============");
                    acctSyncPublish.publish("扫描数字门户账号结束", acctInst, SyncTypeEnum.USER);
                }
            }
            logger.info("Scan Szmh User successfully!");
        });
        return new InvokeResult(InvokeCode.SUCCESS.getCode(),"扫描任务已提交");
    }

    @Override
    public UserBuffer getSourceUser(AcctSourceInst sourceInst, String sourceUserId) {
        return null;
    }

    @Override
    public List<TreeDot<Node>> getTeamTree(String sourceInstId, String queryStr) {
        return null;
    }

    @Override
    public String getSourceUserIdByMobile(String acctSourceInstId, String mobile) {
        ...
    }

    @Override
    public List<AttrInfo> getUserAttr(AcctSourceInst acctInst) {
        ...
    }
    private List<TeamBuffer>getTeamFromSzmhList(AcctSourceInst acctSourceInst){
        ...
        return null;
    }
    private List<TeamBuffer> convertSzmhToTeamBuffer(List<Map> szmhTeamInfoList,AcctSourceInst acctSourceInst){
        ...
        return teamBufferList;
    }
    private List<Map> getSzmhUserList(AcctSourceInst acctInst){
        ...
        return szmhUserInfoList;
    }
    private List<UserBuffer>convertFromSzmhUser(List<Map>szmhUserInfoList,AcctSourceInst acctSourceInst){
        ...
    }

    private String getConfigValue(JSONObject baseConfigJson,String key){
        ...
    }

    private String getAccessToken(AcctSourceInst acctSourceInst) {
        ...
    }

    public static void main(String[] args){


    }
    private static class SzmhUser implements Serializable{
        ...

    }
}
```
## 结论

通过自定义 `setUncaughtExceptionHandler`，我们在账号源对接项目中实现了更好的异常处理和日志记录。每当线程发生未捕获的异常时，这些异常将被记录到日志中，有助于问题的调试和追踪。这一优化方案提高了系统的可靠性和可维护性。
