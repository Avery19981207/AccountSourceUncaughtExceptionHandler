
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
