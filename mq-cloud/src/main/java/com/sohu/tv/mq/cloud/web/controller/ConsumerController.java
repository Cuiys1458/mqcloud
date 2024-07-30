package com.sohu.tv.mq.cloud.web.controller;

import com.sohu.tv.mq.cloud.bo.*;
import com.sohu.tv.mq.cloud.bo.Audit.TypeEnum;
import com.sohu.tv.mq.cloud.common.util.WebUtil;
import com.sohu.tv.mq.cloud.service.*;
import com.sohu.tv.mq.cloud.util.*;
import com.sohu.tv.mq.cloud.web.controller.param.*;
import com.sohu.tv.mq.cloud.web.vo.ConsumerProgressVO;
import com.sohu.tv.mq.cloud.web.vo.ConsumerRunningInfoVO;
import com.sohu.tv.mq.cloud.web.vo.QueueOwnerVO;
import com.sohu.tv.mq.cloud.web.vo.UserInfo;
import com.sohu.tv.mq.metric.StackTraceMetric;
import com.sohu.tv.mq.util.CommonUtil;
import com.sohu.tv.mq.util.Constant;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.admin.ConsumeStats;
import org.apache.rocketmq.remoting.protocol.admin.OffsetWrapper;
import org.apache.rocketmq.remoting.protocol.admin.TopicStatsTable;
import org.apache.rocketmq.remoting.protocol.body.Connection;
import org.apache.rocketmq.remoting.protocol.body.ConsumerConnection;
import org.apache.rocketmq.remoting.protocol.body.ConsumerRunningInfo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.util.*;
import java.util.stream.Collectors;

import static com.sohu.tv.mq.cloud.bo.Audit.TypeEnum.UPDATE_HTTP_CONSUMER_CONFIG;

/**
 * 消费者接口
 * 
 * @Description:
 * @author yongfeigao
 * @date 2018年6月12日
 */
@Controller
@RequestMapping("/consumer")
public class ConsumerController extends ViewController {

    @Autowired
    private ConsumerService consumerService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserConsumerService userConsumerService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AlertService alertService;

    @Autowired
    private TopicService topicService;

    @Autowired
    private VerifyDataService verifyDataService;

    @Autowired
    private ClusterService clusterService;

    @Autowired
    private ConsumerConfigService consumerConfigService;

    @Autowired
    private MQCloudConfigHelper mqCloudConfigHelper;

    @Autowired
    private ClientVersionService clientVersionService;

    @Autowired
    private UserFootprintService userFootprintService;

    @Autowired
    private TopicTrafficService topicTrafficService;

    @Autowired
    private AuditConsumerConfigService auditConsumerConfigService;

    @Autowired
    private MQProxyService mqProxyService;

    @Autowired
    private AuditHttpConsumerConfigService auditHttpConsumerConfigService;

    /**
     * 消费进度
     * 
     * @param topicParam
     * @return
     * @throws Exception
     */
    @RequestMapping("/progress")
    public String progress(UserInfo userInfo, @RequestParam("tid") int tid,
            @Valid PaginationParam paginationParam, @RequestParam(value = "consumer", required = false) String consumer,
            Map<String, Object> map) throws Exception {
        String view = viewModule() + "/progress";
        // 设置分页参数
        setPagination(map, paginationParam);
        // 获取消费者
        Result<TopicTopology> topicTopologyResult = userService.queryTopicTopology(userInfo.getUser(), tid);
        if (topicTopologyResult.isNotOK()) {
            setResult(map, topicTopologyResult);
            return view;
        }
        // 查询消费进度
        TopicTopology topicTopology = topicTopologyResult.getResult();
        List<Consumer> consumerList = topicTopology.getConsumerList();
        // consumer选择
        ConsumerSelector consumerSelector = new ConsumerSelector();
        if (consumer == null || consumer.length() == 0) {
            pagination(consumerSelector, consumerList, paginationParam);
        } else {
            consumerSelector.select(consumerList, consumer);
        }
        // 获取消费者归属者
        Map<Long, List<User>> consumerMap = getConsumerMap(tid, consumerSelector.getCidList());

        Topic topic = topicTopology.getTopic();
        Cluster cluster = clusterService.getMQClusterById(topic.getClusterId());
        // 获取消费者版本
        Map<String, String> clientVersionMap = queryClientVersionMap(topic.getName(), consumerSelector);
        // 构建集群数据
        List<ConsumerProgressVO> clusterList = buildClusteringConsumerProgressVOList(cluster, topic,
                consumerSelector.getClusteringConsumerList(), consumerMap);
        // 获取消费者配置
        for (ConsumerProgressVO vo : clusterList) {
            vo.setConsumerConfig(consumerConfigService.getConsumerConfig(vo.getConsumer().getName()));
            vo.setVersion(clientVersionMap.get(vo.getConsumer().getName()));
        }
        // 组装集群模式消费者信息
        setResult(map, clusterList);

        // 构建广播数据
        List<ConsumerProgressVO> list = buildBroadcastingConsumerProgressVOList(cluster, topic.getName(),
                consumerSelector.getBroadcastConsumerList(), consumerMap);
        // 获取消费者配置
        for (ConsumerProgressVO vo : list) {
            vo.setConsumerConfig(consumerConfigService.getConsumerConfig(vo.getConsumer().getName()));
            vo.setVersion(clientVersionMap.get(vo.getConsumer().getName()));
            vo.resetClientPauseConfig();
        }
        // 组装广播模式消费者信息
        setResult(map, "resultExt", Result.getResult(list));

        setResult(map, "topic", topic);
        FreemarkerUtil.set("long", Long.class, map);
        setResult(map, "cluster", cluster);

        // 限速常量
        setResult(map, "limitConsumeTps", Constant.LIMIT_CONSUME_TPS);
        // 记录访问足迹
        UserFootprint userFootprint = new UserFootprint();
        userFootprint.setUid(userInfo.getUser().getId());
        userFootprint.setTid(tid);
        userFootprintService.save(userFootprint);
        return view;
    }

    /**
     * 查询消费者版本
     * @param topic
     * @param consumerSelector
     * @return
     */
    private Map<String, String> queryClientVersionMap(String topic, ConsumerSelector consumerSelector) {
        List<String> consumers = consumerSelector.getClusteringConsumerList().stream()
                .map(Consumer::getName)
                .collect(Collectors.toList());
        consumerSelector.getBroadcastConsumerList().stream().map(Consumer::getName).forEach(consumers::add);
        if (consumers.isEmpty()) {
            return Collections.emptyMap();
        }
        Result<List<ClientVersion>> clientVersionListResult = clientVersionService.query(topic, consumers);
        if (clientVersionListResult.isEmpty()) {
            return Collections.emptyMap();
        }
        return clientVersionListResult.getResult().stream()
                .collect(Collectors.toMap(ClientVersion::getClient, ClientVersion::getVersion));
    }

    /**
     * 分页
     * 
     * @param consumerSelector
     * @param consumerList
     * @param paginationParam
     */
    private void pagination(ConsumerSelector consumerSelector, List<Consumer> consumerList,
            PaginationParam paginationParam) {
        paginationParam.caculatePagination(consumerList.size());
        consumerSelector.select(consumerList, paginationParam.getBegin(), paginationParam.getEnd());
    }

    /**
     * 构建集群消费模式消费者消费进度
     * 
     * @param cluster
     * @param topic
     * @param consumerList
     * @param consumerMap
     * @return
     */
    private List<ConsumerProgressVO> buildClusteringConsumerProgressVOList(Cluster cluster, Topic topic,
            List<Consumer> consumerList, Map<Long, List<User>> consumerMap) {
        List<ConsumerProgressVO> list = new ArrayList<ConsumerProgressVO>();
        if (consumerList.isEmpty()) {
            return list;
        }
        Map<Long, ConsumeStats> consumeStatsMap = consumerService.fetchClusteringConsumeProgress(cluster, consumerList);

        // 获取topic上一分钟流量
        Date oneMinuteAgo = new Date(System.currentTimeMillis() - 60000);
        String time = DateUtil.getFormat(DateUtil.HHMM).format(oneMinuteAgo);
        Result<TopicTraffic> trafficResult = topicTrafficService.query(topic.getId(), oneMinuteAgo, time);
        if (trafficResult.isOK()) {
            topic.setCount(trafficResult.getResult().getCount());
        } else {
            topic.setCount(0);
        }

        // 组装集群模式vo
        for (Consumer consumer : consumerList) {
            ConsumerProgressVO consumerProgressVO = buildConsumerProgressVO(topic.getName(), consumerMap, consumer);
            if (consumeStatsMap == null) {
                list.add(consumerProgressVO);
                continue;
            }
            ConsumeStats consumeStats = consumeStatsMap.get(consumer.getId());
            if (consumeStats == null) {
                list.add(consumerProgressVO);
                continue;
            }
            consumerProgressVO.setConsumeTps(consumeStats.getConsumeTps());
            consumerProgressVO.setProduceTps(topic.getCount() / 60);

            // 拆分正常队列和重试队列
            Map<MessageQueue, OffsetWrapper> offsetTable = consumeStats.getOffsetTable();
            Map<MessageQueue, OffsetWrapper> offsetMap = new TreeMap<MessageQueue, OffsetWrapper>();
            Map<MessageQueue, OffsetWrapper> retryOffsetMap = new TreeMap<MessageQueue, OffsetWrapper>();
            for (MessageQueue mq : offsetTable.keySet()) {
                if (CommonUtil.isRetryTopic(mq.getTopic())) {
                    retryOffsetMap.put(mq, offsetTable.get(mq));
                    // 设置topic名字
                    if (consumerProgressVO.getRetryTopic() == null) {
                        consumerProgressVO.setRetryTopic(mq.getTopic());
                    } else if (!mq.getTopic().equals(consumerProgressVO.getRetryTopic())) {
                        logger.error("retry consumer:{} has two diffrent topic, {} != {}", consumer.getName(),
                                mq.getTopic(), consumerProgressVO.getRetryTopic());
                    }
                } else {
                    offsetMap.put(mq, offsetTable.get(mq));
                    if (consumerProgressVO.getTopic() == null) {
                        consumerProgressVO.setTopic(mq.getTopic());
                    } else if (!mq.getTopic().equals(consumerProgressVO.getTopic())) {
                        logger.error("consumer:{} has two diffrent topic, {} != {}", consumer.getName(),
                                mq.getTopic(), consumerProgressVO.getTopic());
                    }
                }
            }
            // 获取死topic状况
            String dlqTopic = MixAll.getDLQTopic(consumer.getName());
            consumerProgressVO.setDlqTopic(dlqTopic);
            TopicStatsTable topicStatsTable = topicService.stats(cluster, dlqTopic);
            if (topicStatsTable != null) {
                consumerProgressVO.setDlqOffsetMap(new TreeMap<>(topicStatsTable.getOffsetTable()));
            }

            consumerProgressVO.setOffsetMap(offsetMap);
            consumerProgressVO.setRetryOffsetMap(retryOffsetMap);
            consumerProgressVO.computeTotalDiff();
            list.add(consumerProgressVO);
        }
        return list;
    }

    /**
     * 构建广播消费模式消费者消费进度
     * 
     * @param cluster
     * @param topic
     * @param consumerList
     * @param consumerMap
     * @return
     */
    private List<ConsumerProgressVO> buildBroadcastingConsumerProgressVOList(Cluster cluster, String topic,
            List<Consumer> consumerList, Map<Long, List<User>> consumerMap) {
        List<ConsumerProgressVO> list = new ArrayList<ConsumerProgressVO>();
        if (consumerList.isEmpty()) {
            return list;
        }
        // 抓取广播消费模式下消费者状态
        Map<Long, List<ConsumeStatsExt>> consumeStatsMap = consumerService.fetchBroadcastingConsumeProgress(cluster,
                topic, consumerList);
        // 组装广播消费模式vo
        for (Consumer consumer : consumerList) {
            ConsumerProgressVO consumerProgressVO = buildConsumerProgressVO(topic, consumerMap, consumer);
            if (consumeStatsMap == null) {
                list.add(consumerProgressVO);
                continue;
            }
            List<ConsumeStatsExt> consumeStatsList = consumeStatsMap.get(consumer.getId());
            if (consumeStatsList == null) {
                list.add(consumerProgressVO);
                continue;
            }
            Map<MessageQueue, OffsetWrapper> offsetMap = new TreeMap<MessageQueue, OffsetWrapper>();
            for (ConsumeStatsExt consumeStats : consumeStatsList) {
                consumerProgressVO.setConsumeTps(consumerProgressVO.getConsumeTps() + consumeStats.getConsumeTps());
                Map<MessageQueue, OffsetWrapper> offsetTable = consumeStats.getOffsetTable();
                for (MessageQueue mq : offsetTable.keySet()) {
                    OffsetWrapper prev = offsetMap.get(mq);
                    if (prev == null) {
                        prev = new OffsetWrapper();
                        offsetMap.put(mq, prev);
                    }
                    OffsetWrapper cur = offsetTable.get(mq);
                    if (cur.getConsumerOffset() < 0) {
                        cur.setConsumerOffset(0);
                    }
                    prev.setBrokerOffset(prev.getBrokerOffset() + cur.getBrokerOffset());
                    prev.setConsumerOffset(prev.getConsumerOffset() + cur.getConsumerOffset());
                    // 取最小的更新时间
                    if (prev.getLastTimestamp() == 0 || prev.getLastTimestamp() > cur.getLastTimestamp()) {
                        prev.setLastTimestamp(cur.getLastTimestamp());
                    }
                }
            }
            consumerProgressVO.setOffsetMap(offsetMap);
            consumerProgressVO.setConsumeStatsList(consumeStatsList);
            consumerProgressVO.computeTotalDiff();
            list.add(consumerProgressVO);
        }
        return list;
    }

    /**
     * 构建ConsumerProgressVO
     * 
     * @return
     */
    public ConsumerProgressVO buildConsumerProgressVO(String topic, Map<Long, List<User>> consumerMap,
            Consumer consumer) {
        ConsumerProgressVO consumerProgressVO = new ConsumerProgressVO();
        consumerProgressVO.setConsumer(consumer);
        consumerProgressVO.setTopic(topic);
        consumerProgressVO.setOwnerList(consumerMap.get(consumer.getId()));
        return consumerProgressVO;
    }

    /**
     * 获取消费者map
     * 
     * @param tid
     * @param cidList
     * @return
     */
    private Map<Long, List<User>> getConsumerMap(long tid, List<Long> cidList) {
        Map<Long, List<User>> consumerMap = new HashMap<Long, List<User>>();
        if (cidList == null || cidList.size() == 0) {
            return consumerMap;
        }
        Result<List<UserConsumer>> ucListResult = userConsumerService.queryTopicConsumer(tid, cidList);
        if (ucListResult.isEmpty()) {
            return consumerMap;
        }
        Set<Long> uidList = new HashSet<Long>();
        for (UserConsumer userConsumer : ucListResult.getResult()) {
            uidList.add(userConsumer.getUid());
        }
        Result<List<User>> userListResult = userService.query(uidList);
        if (userListResult.isEmpty()) {
            return consumerMap;
        }
        for (UserConsumer userConsumer : ucListResult.getResult()) {
            for (User user : userListResult.getResult()) {
                if (userConsumer.getUid() == user.getId()) {
                    List<User> userList = consumerMap.get(userConsumer.getConsumerId());
                    if (userList == null) {
                        userList = new ArrayList<User>();
                        consumerMap.put(userConsumer.getConsumerId(), userList);
                    }
                    userList.add(user);
                }
            }
        }
        return consumerMap;
    }

    /**
     * 重置偏移量
     * 
     * @param userConsumerParam
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping("/resetOffset")
    public Result<?> resetOffset(UserInfo userInfo, @Valid UserConsumerParam userConsumerParam) throws Exception {
        // 校验用户是否能重置，防止误调用接口
        Result<List<UserConsumer>> userConsumerListResult = userConsumerService.queryUserConsumer(userInfo.getUser(),
                userConsumerParam.getTid(), userConsumerParam.getConsumerId());
        if (userConsumerListResult.isEmpty() && !userInfo.getUser().isAdmin()) {
            return Result.getResult(Status.PERMISSION_DENIED_ERROR);
        }
        Date resetOffsetTo = null;
        try {
            if (userConsumerParam.getOffset() != null) {
                resetOffsetTo = DateUtil.getFormat(DateUtil.YMD_DASH_BLANK_HMS_COLON)
                        .parse(userConsumerParam.getOffset());
            }
        } catch (Exception e) {
            logger.error("resetOffsetTo param err:{}", userConsumerParam.getOffset(), e);
            return Result.getResult(Status.PARAM_ERROR);
        }

        // 构造审核记录
        Audit audit = new Audit();
        // 重新定义操作成功返回的文案
        String message = "";
        if (resetOffsetTo == null) {
            audit.setType(TypeEnum.RESET_OFFSET_TO_MAX.getType());
            message = "跳过堆积申请成功！请耐心等待！";
        } else {
            audit.setType(TypeEnum.RESET_OFFSET.getType());
            message = "消息回溯申请成功！请耐心等待！";
        }
        audit.setUid(userInfo.getUser().getId());
        // 构造重置对象
        AuditResetOffset auditResetOffset = new AuditResetOffset();
        BeanUtils.copyProperties(userConsumerParam, auditResetOffset);
        // 保存记录
        Result<?> result = auditService.saveAuditAndSkipAccumulation(audit, auditResetOffset);
        if (result.isOK()) {
            String tip = getTopicConsumerTip(userConsumerParam.getTid(), userConsumerParam.getConsumerId());
            alertService.sendAuditMail(userInfo.getUser(), TypeEnum.getEnumByType(audit.getType()), tip);
            // 重新定义返回文案
            result.setMessage(message);
        }
        return Result.getWebResult(result);
    }

    /**
     * 删除消费者
     * 
     * @param topicParam
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    public Result<?> delete(UserInfo userInfo, @Valid UserConsumerParam userConsumerParam) throws Exception {
        Result<?> isExist = verifyDataService.verifyDeleteConsumerIsExist(userConsumerParam);
        if (isExist.getStatus() != Status.OK.getKey()) {
            return isExist;
        }
        // 校验用户是否能删除，防止调用接口误删
        Result<List<UserConsumer>> userConsumerListResult = userConsumerService.queryUserConsumer(userInfo.getUser(),
                userConsumerParam.getTid(), userConsumerParam.getConsumerId());
        if (userConsumerListResult.isEmpty() && !userInfo.getUser().isAdmin()) {
            return Result.getResult(Status.PERMISSION_DENIED_ERROR);
        }

        Result<Consumer> consumerResult = consumerService.queryById(userConsumerParam.getConsumerId());
        if (consumerResult.isNotOK()) {
            return Result.getWebResult(consumerResult);
        }

        Result<Topic> topicResult = topicService.queryTopic(consumerResult.getResult().getTid());
        if (topicResult.isNotOK()) {
            return Result.getWebResult(topicResult);
        }

        // 校验是否还有链接
        String consumer = consumerResult.getResult().getName();
        if (!consumerResult.getResult().isHttpProtocol()) {
            Cluster cluster = clusterService.getMQClusterById(topicResult.getResult().getClusterId());
            Result<ConsumerConnection> connectionResult = consumerService.examineConsumerConnectionInfo(consumer,
                    cluster, consumerResult.getResult().isProxyRemoting());
            if (connectionResult.isOK()) {
                return Result.getResult(Status.CONSUMER_CONNECTION_EXIST_ERROR);
            }
        }

        // 构造审核记录
        Audit audit = new Audit();
        audit.setType(TypeEnum.DELETE_CONSUMER.getType());
        audit.setUid(userInfo.getUser().getId());
        // 保存记录
        Result<?> result = auditService.saveAuditAndConsumerDelete(audit, userConsumerParam.getConsumerId(),
                consumer, topicResult.getResult().getName());
        if (result.isOK()) {
            alertService.sendAuditMail(userInfo.getUser(), TypeEnum.DELETE_CONSUMER,
                    consumerResult.getResult().getName());
        }
        return Result.getWebResult(result);
    }

    /**
     * 消费者列表
     * 
     * @param topicParam
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping("/list")
    public Result<?> list(UserInfo userInfo, @RequestParam("tid") int tid) throws Exception {
        Result<List<Consumer>> consumerListResult = consumerService.queryByTid(tid);
        return Result.getWebResult(consumerListResult);
    }

    /**
     * 消费者列表
     * 
     * @param topicParam
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping("/list/all")
    public Result<?> listAll(UserInfo userInfo) throws Exception {
        Result<List<Consumer>> consumerListResult = consumerService.queryAll();
        return Result.getWebResult(consumerListResult);
    }

    @RequestMapping(value = "/associate", method = RequestMethod.GET)
    public String associate(Map<String, Object> map)
            throws Exception {
        setView(map, "associate", "关联消费者");
        return view();
    }

    /**
     * 关联消费者
     * 
     * @param topicParam
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/associate", method = RequestMethod.POST)
    public Result<?> associate(UserInfo userInfo, @Valid AssociateConsumerParam associateConsumerParam)
            throws Exception {
        return associateUserConsumer(userInfo, userInfo.getUser().getId(), associateConsumerParam.getTid(),
                associateConsumerParam.getCid());
    }

    /**
     * 授权关联
     * 
     * @param userInfo
     * @param tid
     * @param uid
     * @param cid
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/auth/associate", method = RequestMethod.POST)
    public Result<?> authAssociate(UserInfo userInfo, @RequestParam("tid") long tid,
            @RequestParam("uid") long uid,
            @RequestParam("cid") long cid) throws Exception {
        if (tid < 1 || uid < 1 || cid < 1) {
            return Result.getResult(Status.PARAM_ERROR);
        }
        return associateUserConsumer(userInfo, uid, tid, cid);
    }

    /**
     * 复用之前的逻辑
     * 
     * @param userInfo
     * @param uid
     * @param tid
     * @param cid
     * @return
     */
    private Result<?> associateUserConsumer(UserInfo userInfo, long uid, long tid, long cid) {
        // 验证用户是否已经关联过此消费者
        Result<?> isExist = verifyDataService.verifyUserConsumerIsExist(uid, cid);
        if (isExist.getStatus() != Status.OK.getKey()) {
            return isExist;
        }
        AuditAssociateConsumer auditAssociateConsumer = new AuditAssociateConsumer();
        auditAssociateConsumer.setCid(cid);
        auditAssociateConsumer.setTid(tid);
        auditAssociateConsumer.setUid(uid);
        // 构建Audit
        Audit audit = new Audit();
        audit.setType(Audit.TypeEnum.ASSOCIATE_CONSUMER.getType());
        audit.setStatus(Audit.StatusEnum.INIT.getStatus());
        audit.setUid(userInfo.getUser().getId());
        Result<Audit> result = auditService.saveAuditAndAssociateConsumer(audit, auditAssociateConsumer);
        if (result.isOK()) {
            String tip = getTopicConsumerTip(tid, cid);
            if (uid != userInfo.getUser().getId()) {
                Result<User> userResult = userService.query(uid);
                if (userResult.isNotOK()) {
                    return Result.getResult(Status.EMAIL_SEND_ERR);
                }
                tip = tip + " user:<b>" + userResult.getResult().notBlankName() + "</b>";
            }
            alertService.sendAuditMail(userInfo.getUser(), TypeEnum.ASSOCIATE_CONSUMER, tip);
        }
        return Result.getWebResult(result);
    }

    @RequestMapping(value = "/add", method = RequestMethod.GET)
    public String add(Map<String, Object> map)
            throws Exception {
        setView(map, "add", "新建消费者");
        return view();
    }

    /**
     * 新建消费者
     * 
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/add", method = RequestMethod.POST)
    public Result<?> add(UserInfo userInfo, @Valid ConsumerParam consumerParam) throws Exception {
        logger.info("create consumer, user:{} consumerParam:{}", userInfo, consumerParam);
        Result<?> isExist = verifyDataService.verifyAddConsumerIsExist(userInfo.getUser().getId(),
                consumerParam.getConsumer());
        if (isExist.isNotOK()) {
            return isExist;
        }
        // 构建审核记录
        Audit audit = new Audit();
        audit.setType(Audit.TypeEnum.NEW_CONSUMER.getType());
        audit.setStatus(Audit.StatusEnum.INIT.getStatus());
        audit.setUid(userInfo.getUser().getId());
        audit.setInfo(consumerParam.getInfo());

        // 构建消费者审核记录
        AuditConsumer auditConsumer = new AuditConsumer();
        BeanUtils.copyProperties(consumerParam, auditConsumer);
        // 保存记录
        Result<?> result = auditService.saveAuditAndConsumer(audit, auditConsumer);
        if (result.isOK()) {
            String topicTip = getTopicTip(consumerParam.getTid());
            alertService.sendAuditMail(userInfo.getUser(), TypeEnum.NEW_CONSUMER,
                    topicTip + " consumer:<b>" + consumerParam.getConsumer() + "</b>");
        }
        return Result.getWebResult(result);
    }

    /**
     * 根据broker查询队列信息
     * 
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/broker/queue", method = RequestMethod.GET)
    public Result<?> getQueueList(@RequestParam("topic") String topic,
            @RequestParam("brokerName") String brokerName,
            @RequestParam("clusterId") long clusterId) throws Exception {
        if (topic == "" || brokerName == "") {
            return Result.getResult(Status.PARAM_ERROR);
        }
        Cluster cluster = clusterService.getMQClusterById(clusterId);
        if (cluster == null) {
            return Result.getResult(cluster);
        }
        TopicStatsTable topicStatsTable = topicService.stats(cluster, topic);
        if (topicStatsTable == null) {
            return Result.getResult(topicStatsTable);
        }
        Map<Integer, Long> queueOffsetMap = new TreeMap<Integer, Long>();
        for (MessageQueue mq : topicStatsTable.getOffsetTable().keySet()) {
            if (mq.getBrokerName().equals(brokerName)) {
                long maxOffset = topicStatsTable.getOffsetTable().get(mq).getMaxOffset();
                if (!queueOffsetMap.containsKey(mq.getQueueId()) || queueOffsetMap.get(mq.getQueueId()) < maxOffset) {
                    queueOffsetMap.put(mq.getQueueId(), maxOffset);
                }
            }
        }
        return Result.getResult(queueOffsetMap);
    }

    /**
     * 获取topic和consumer的提示信息
     * 
     * @param tid
     * @param cid
     * @return
     */
    private String getTopicConsumerTip(long tid, long cid) {
        Result<Topic> topicResult = topicService.queryTopic(tid);
        String topic = null;
        if (topicResult.isOK()) {
            topic = topicResult.getResult().getName();
        }
        Result<Consumer> consumerResult = consumerService.queryById(cid);
        String consumer = null;
        if (consumerResult.isOK()) {
            consumer = consumerResult.getResult().getName();
        }
        return getTopicConsumerTip(topic, consumer);
    }

    private String getTopicConsumerTip(String topic, String consumer) {
        StringBuilder sb = new StringBuilder();
        if (topic != null) {
            sb.append(" topic:<b>");
            sb.append(topic);
            sb.append("</b>");
        }
        if (consumer != null) {
            sb.append(" consumer:");
            if (topic != null) {
                sb.append(mqCloudConfigHelper.getTopicConsumeHrefLink(topic, consumer));
            } else {
                sb.append(consumer);
            }
        }
        return sb.toString();
    }

    /**
     * 获取topic的提示信息
     * 
     * @param tid
     * @return
     */
    private String getTopicTip(long tid) {
        StringBuilder sb = new StringBuilder();
        Result<Topic> topicResult = topicService.queryTopic(tid);
        if (topicResult.isOK()) {
            sb.append(" topic:<b>");
            sb.append(topicResult.getResult().getName());
            sb.append("</b>");
        }
        return sb.toString();
    }

    /**
     * 获取queue的拥有者
     * 
     * @param userInfo
     * @param cid
     * @param consumer
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping("/queue/owner")
    public Result<?> queueOwner(UserInfo userInfo, @RequestParam("cid") int cid,
                                @RequestParam("consumer") String consumerName) throws Exception {
        Cluster cluster = clusterService.getMQClusterById(cid);
        // 获取消费者运行时信息
        Consumer consumer = consumerService.queryConsumerByName(consumerName).getResult();
        Map<String, ConsumerRunningInfo> map = consumerService.getConsumerRunningInfo(cluster, consumerName,
                consumer.isProxyRemoting());
        if (map == null) {
            return Result.getResult(Status.NO_RESULT);
        }
        ConsumerConfig consumerConfig = consumerConfigService.getConsumerConfig(consumerName);
        // 组装vo
        List<QueueOwnerVO> queueConsumerVOList = new ArrayList<>();
        for (String clientId : map.keySet()) {
            boolean addConsumerRunningInfo = false;
            String ip = clientId.substring(0, clientId.indexOf("@"));
            ConsumerRunningInfo consumerRunningInfo = map.get(clientId);
            for (MessageQueue messageQueue : consumerRunningInfo.getMqTable().keySet()) {
                QueueOwnerVO queueOwnerVO = new QueueOwnerVO();
                if (!addConsumerRunningInfo) {
                    addConsumerRunningInfo = setConsumerRunningInfo(messageQueue, consumerRunningInfo, queueOwnerVO);
                }
                BeanUtils.copyProperties(messageQueue, queueOwnerVO);
                queueOwnerVO.setClientId(clientId);
                queueOwnerVO.setIp(ip);
                setPauseInfo(consumerConfig, queueOwnerVO);
                queueConsumerVOList.add(queueOwnerVO);
            }
        }
        return Result.getResult(queueConsumerVOList);
    }

    /**
     * 设置消费者运行时信息
     * @param messageQueue
     * @param consumerRunningInfo
     * @param queueOwnerVO
     * @return
     */
    private boolean setConsumerRunningInfo(MessageQueue messageQueue, ConsumerRunningInfo consumerRunningInfo, QueueOwnerVO queueOwnerVO){
        if (CommonUtil.isRetryTopic(messageQueue.getTopic()) || CommonUtil.isDeadTopic(messageQueue.getTopic())) {
            return false;
        }
        ConsumerRunningInfoVO consumerRunningInfoVO = new ConsumerRunningInfoVO(consumerRunningInfo);
        queueOwnerVO.setShowClientInfo(true);
        queueOwnerVO.setWarnInfo(consumerRunningInfoVO.getWarnInfo());
        queueOwnerVO.setLanguage(consumerRunningInfoVO.getLanguage());
        queueOwnerVO.setConsumeFailed(consumerRunningInfoVO.isConsumeFailed());
        queueOwnerVO.setFlowControlled(consumerRunningInfoVO.isFlowControled());
        return true;
    }

    /**
     * 设置暂停信息
     * @param consumerConfig
     * @param queueOwnerVO
     */
    private void setPauseInfo(ConsumerConfig consumerConfig, QueueOwnerVO queueOwnerVO) {
        if (consumerConfig == null) {
            return;
        }
        if (CollectionUtils.isEmpty(consumerConfig.getPauseConfig())) {
            if (consumerConfig.getPause() != null && consumerConfig.getPause()) {
                queueOwnerVO.setPaused(true);
                queueOwnerVO.setDisablePause(true);
            }
        } else if (consumerConfig.containsPauseConfig(queueOwnerVO.getClientId())) {
            queueOwnerVO.setPaused(true);
        }
    }

    /**
     * 更新描述
     * 
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/update/info", method = RequestMethod.POST)
    public Result<?> updateInfo(UserInfo userInfo, @RequestParam("cid") int cid,
            @RequestParam("info") String info) throws Exception {
        // 校验当前用户是否拥有权限
        Result<List<UserConsumer>> ucListResult = userConsumerService.queryUserConsumer(userInfo.getUser().getId(),
                cid);
        if (ucListResult.isEmpty() && !userInfo.getUser().isAdmin()) {
            return Result.getResult(Status.PERMISSION_DENIED_ERROR);
        }
        if (StringUtils.isBlank(info)) {
            return Result.getResult(Status.PARAM_ERROR);
        }
        Result<Integer> result = consumerService.updateConsumerInfo(cid, HtmlUtils.htmlEscape(info.trim(), "UTF-8"));
        logger.info(userInfo.getUser().getName() + " update consumer info , cid:{}, info:{}, status:{}", cid, info,
                result.isOK());
        return Result.getWebResult(result);
    }

    @ResponseBody
    @RequestMapping(value = "/update/_trace", method = RequestMethod.POST)
    public Result<?> _updateConsumerTrace(UserInfo userInfo, @RequestParam("cid") int cid,
                                          @RequestParam("traceEnabled") int traceEnabled) throws Exception {
        return updateConsumerTrace(userInfo, cid, traceEnabled);
    }

    /**
     * 更新trace
     * 
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/update/trace", method = RequestMethod.POST)
    public Result<?> updateConsumerTrace(UserInfo userInfo, @RequestParam("cid") int cid,
            @RequestParam("traceEnabled") int traceEnabled) throws Exception {
        if (!userInfo.getUser().isAdmin()) {
            // 校验当前用户是否拥有权限
            Result<List<UserConsumer>> ucListResult = userConsumerService.queryUserConsumer(userInfo.getUser().getId(),
                    cid);
            if (ucListResult.isEmpty()) {
                return Result.getResult(Status.PERMISSION_DENIED_ERROR);
            }
        }
        if (traceEnabled != 1 && traceEnabled != 0) {
            return Result.getResult(Status.PARAM_ERROR);
        }
        Result<Integer> result = consumerService.updateConsumerTrace(cid, traceEnabled);
        logger.info(userInfo.getUser().notBlankName() + " update consumer trace , cid:{}, traceEnabled:{}, status:{}",
                cid,
                traceEnabled, result.isOK());
        return Result.getWebResult(result);
    }

    /**
     * 重置重试消息偏移量
     * 
     * @param userConsumerParam
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping("/skip/retryOffset")
    public Result<?> resetRetryOffset(UserInfo userInfo, @Valid UserConsumerParam userConsumerParam) throws Exception {
        // 校验用户是否能重置，防止误调用接口
        Result<List<UserConsumer>> userConsumerListResult = userConsumerService.queryUserConsumer(userInfo.getUser(),
                userConsumerParam.getTid(), userConsumerParam.getConsumerId());
        if (userConsumerListResult.isEmpty() && !userInfo.getUser().isAdmin()) {
            return Result.getResult(Status.PERMISSION_DENIED_ERROR);
        }
        // 校验时间格式
        try {
            DateUtil.getFormat(DateUtil.YMD_DASH_BLANK_HMS_COLON).parse(userConsumerParam.getOffset());
        } catch (Exception e) {
            logger.error("resetOffsetTo param err:{}", userConsumerParam.getOffset(), e);
            return Result.getResult(Status.PARAM_ERROR);
        }

        // 构造审核记录
        Audit audit = new Audit();
        audit.setType(TypeEnum.RESET_RETRY_OFFSET.getType());
        // 重新定义操作成功返回的文案
        String message = "重试堆积跳过申请成功！请耐心等待！";
        audit.setUid(userInfo.getUser().getId());
        // 构造重置对象
        if (StringUtils.isEmpty(userConsumerParam.getMessageKey())) {
            userConsumerParam.setMessageKey(null);
        }
        AuditResetOffset auditResetOffset = new AuditResetOffset();
        BeanUtils.copyProperties(userConsumerParam, auditResetOffset);
        // 保存记录
        Result<?> result = auditService.saveAuditAndSkipAccumulation(audit, auditResetOffset);
        if (result.isOK()) {
            String tip = getTopicConsumerTip(userConsumerParam.getTid(), userConsumerParam.getConsumerId());
            alertService.sendAuditMail(userInfo.getUser(), TypeEnum.getEnumByType(audit.getType()), tip);
            // 重新定义返回文案
            result.setMessage(message);
        }
        return Result.getWebResult(result);
    }

    /**
     * 获取重置的时间
     * 
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/reset/{consumer}")
    public Result<?> reset(@PathVariable String consumer, HttpServletRequest request) throws Exception {
        MessageReset messageReset = null;
        ConsumerConfig consumerConfig = consumerConfigService.getConsumerConfig(consumer);
        if (consumerConfig != null && consumerConfig.getRetryMessageResetTo() != null) {
            messageReset = new MessageReset();
            messageReset.setConsumer(consumer);
            messageReset.setResetTo(consumerConfig.getRetryMessageResetTo());
        }
        return Result.getResult(messageReset);
    }

    /**
     * 获取消费者配置
     * 
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/config/{consumer}")
    public Result<?> config(@PathVariable String consumer, HttpServletRequest request) throws Exception {
        ConsumerConfig consumerConfig = consumerConfigService.getConsumerConfig(consumer);
        return Result.getResult(consumerConfig);
    }

    /**
     * 获取消费者配置
     *
     * @return
     * @throws Exception
     */
    @ResponseBody
    @GetMapping("/http/config/{consumer}")
    public Result<?> httpConfig(@PathVariable String consumer) throws Exception {
        return mqProxyService.getConsumerConfig(consumer);
    }

    /**
     * 更新消费者配置
     * 
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping("/update/config")
    public Result<?> updateConfig(UserInfo userInfo, @Valid ConsumerConfigParam consumerConfigParam) throws Exception {
        // 校验用户是否有权限
        Result<List<UserConsumer>> userResult = userConsumerService.queryUserConsumer(userInfo.getUser(),
                consumerConfigParam.getConsumerId());
        if (userResult.isEmpty() && !userInfo.getUser().isAdmin()) {
            return Result.getResult(Status.PERMISSION_DENIED_ERROR);
        }

        TypeEnum type = consumerConfigParam.getType();
        if (type == null) {
            return Result.getResult(Status.PARAM_ERROR);
        }
        // 校验是否有未审核的记录
        Result<Integer> unAuditCount = auditConsumerConfigService.queryUnAuditCount(consumerConfigParam.getConsumerId());
        if (unAuditCount.isNotOK()) {
            return unAuditCount;
        }
        if (unAuditCount.getResult() > 0) {
            return Result.getResult(Status.AUDIT_RECORD_REPEAT);
        }
        // 构造审核记录
        Audit audit = new Audit();
        audit.setType(type.getType());
        // 重新定义操作成功返回的文案
        String message = type.getName() + "申请成功！请耐心等待！";
        audit.setUid(userInfo.getUser().getId());
        // ip不存在则置空
        if (consumerConfigParam.getPauseClientId() != null) {
            if (consumerConfigParam.getPause() != null && !consumerConfigParam.getPause()) {
                // 恢复时，解注册取消
                consumerConfigParam.setUnregister(false);
            }
        }
        AuditConsumerConfig auditConsumerConfig = new AuditConsumerConfig();
        BeanUtils.copyProperties(consumerConfigParam, auditConsumerConfig);
        // 保存记录
        Result<?> result = auditService.saveAuditAndConsumerConfig(audit, auditConsumerConfig);
        if (result.isOK()) {
            String tip = getUpdateConsumerConfigTip(auditConsumerConfig.getConsumerId());
            alertService.sendAuditMail(userInfo.getUser(), TypeEnum.getEnumByType(audit.getType()), tip);
            // 重新定义返回文案
            result.setMessage(message);
        }
        return Result.getWebResult(result);
    }

    /**
     * 获取consumer的提示信息
     */
    private String getUpdateConsumerConfigTip(long consumerId) {
        StringBuilder sb = new StringBuilder();
        Result<Consumer> consumerResult = consumerService.queryById(consumerId);
        if (consumerResult.isOK()) {
            Consumer consumer = consumerResult.getResult();
            sb.append(" consumer:");
            sb.append(mqCloudConfigHelper.getTopicConsumeHrefLink(consumer.getTid(), consumer.getName()));
        }
        return sb.toString();
    }

    /**
     * 诊断链接
     * 
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/connection")
    public Result<?> connection(UserInfo userInfo, @RequestParam("cid") int cid,
            @RequestParam("group") String group, Map<String, Object> map) throws Exception {
        Cluster cluster = clusterService.getMQClusterById(cid);
        Consumer consumer = consumerService.queryConsumerByName(group).getResult();
        Result<ConsumerConnection> result = consumerService.examineConsumerConnectionInfo(group, cluster,
                consumer.isProxyRemoting());
        if (result.isNotOK()) {
            if (Status.NO_ONLINE.getKey() == result.getStatus()) {
                return result;
            }
            return Result.getWebResult(result);
        }
        HashSet<Connection> connectionSet = result.getResult().getConnectionSet();
        List<String> connList = new ArrayList<String>();
        for (Connection conn : connectionSet) {
            connList.add(conn.getClientId());
        }
        Collections.sort(connList);
        return Result.getResult(connList);
    }

    /**
     * 线程指标
     * 
     * @param userInfo
     * @param clientId
     * @param consumer
     * @param map
     * @return
     * @throws Exception
     */
    @RequestMapping("/threadMetrics")
    public String threadMetrics(UserInfo userInfo, @RequestParam("clientId") String clientId,
            @RequestParam(value = "consumer") String consumer, Map<String, Object> map) throws Exception {
        String view = viewModule() + "/threadMetrics";
        // 获取消费者
        Result<Consumer> consumerResult = consumerService.queryConsumerByName(consumer);
        if (consumerResult.isNotOK()) {
            setResult(map, consumerResult);
            return view;
        }
        Result<Topic> topicResult = topicService.queryTopic(consumerResult.getResult().getTid());
        if (topicResult.isNotOK()) {
            setResult(map, topicResult);
            return view;
        }
        // 判断客户端版本
        Result<ClientVersion> cvResult = clientVersionService.query(topicResult.getResult().getName(), consumer);
        if (cvResult.isNotOK() || !mqCloudConfigHelper.threadMetricSupported(cvResult.getResult().getVersion())) {
            setResult(map, Result.getResult(Status.PARAM_ERROR)
                    .setMessage("请升级客户端至" + mqCloudConfigHelper.getThreadMetricSupportedVersion() + "及以上版本"));
            return view;
        }
        Cluster cluster = clusterService.getMQClusterById(topicResult.getResult().getClusterId());
        // 获取线程指标
        Result<List<StackTraceMetric>> result = consumerService.getConsumeThreadMetrics(cluster, clientId, consumer,
                consumerResult.getResult().isProxyRemoting());
        // 排序
        List<StackTraceMetric> threadMetricList = result.getResult();
        if (threadMetricList != null) {
            Collections.sort(threadMetricList, (o1, o2) -> {
                return (int) (o1.getStartTime() - o2.getStartTime());
            });
        }
        setResult(map, result);
        return view;
    }

    /**
     * 消费失败指标
     * 
     * @param userInfo
     * @param clientId
     * @param consumer
     * @param map
     * @return
     * @throws Exception
     */
    @RequestMapping("/failedMetrics")
    public String failedMetrics(UserInfo userInfo, @RequestParam("clientId") String clientId,
            @RequestParam(value = "consumer") String consumer, Map<String, Object> map) throws Exception {
        String view = viewModule() + "/failedMetrics";
        // 获取消费者
        Result<Consumer> consumerResult = consumerService.queryConsumerByName(consumer);
        if (consumerResult.isNotOK()) {
            setResult(map, consumerResult);
            return view;
        }
        Result<Topic> topicResult = topicService.queryTopic(consumerResult.getResult().getTid());
        if (topicResult.isNotOK()) {
            setResult(map, topicResult);
            return view;
        }
        // 判断客户端版本
        Result<ClientVersion> cvResult = clientVersionService.query(topicResult.getResult().getName(), consumer);
        if (cvResult.isNotOK() || !mqCloudConfigHelper.threadMetricSupported(cvResult.getResult().getVersion())) {
            setResult(map, Result.getResult(Status.PARAM_ERROR)
                    .setMessage(
                            "请升级客户端至" + mqCloudConfigHelper.getConsumeFailedMetricSupportedVersion() + "及以上版本"));
            return view;
        }
        Cluster cluster = clusterService.getMQClusterById(topicResult.getResult().getClusterId());
        // 获取线程指标
        Result<List<StackTraceMetric>> result = consumerService.getConsumeFailedMetrics(cluster, clientId, consumer,
                consumerResult.getResult().isProxyRemoting());
        // 排序
        List<StackTraceMetric> threadMetricList = result.getResult();
        if (threadMetricList != null) {
            Collections.sort(threadMetricList, (o1, o2) -> {
                return (int) (o1.getStartTime() - o2.getStartTime());
            });
            // 过滤html标签
            threadMetricList.forEach(stackTraceMetric -> {
                if (stackTraceMetric.getMessage() != null) {
                    stackTraceMetric.setMessage(HtmlUtils.htmlEscape(stackTraceMetric.getMessage()));
                }
            });
        }
        setResult(map, result);
        return view;
    }

    /**
     * 时间段消息消费
     * 
     * @param userInfo
     * @param clientId
     * @param consumer
     * @param map
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping("/timespanMessageConsume")
    public Result<?> timespanMessageConsume(UserInfo userInfo,
            @Valid TimespanMessageConsumeParam timespanMessageConsumeParam, Map<String, Object> map) throws Exception {
        // 获取消费者对象
        Result<Consumer> consumerResult = consumerService.queryById(timespanMessageConsumeParam.getConsumerId());
        if (consumerResult.isNotOK()) {
            return Result.getResult(Status.PARAM_ERROR);
        }
        timespanMessageConsumeParam.setConsumer(consumerResult.getResult().getName());
        // 获取topic
        Result<Topic> topicResult = null;
        if (CommonUtil.isDeadTopic(timespanMessageConsumeParam.getTopic())) {
            topicResult = topicService.queryTopic(consumerResult.getResult().getTid());
        } else {
            topicResult = topicService.queryTopic(timespanMessageConsumeParam.getTopic());
        }
        if (topicResult.isNotOK()) {
            return Result.getResult(Status.PARAM_ERROR);
        }
        // topic校验权限
        Result<List<UserConsumer>> permResult = userConsumerService.queryUserConsumer(userInfo.getUser().getId(),
                topicResult.getResult().getId(), consumerResult.getResult().getId());
        if (permResult.isEmpty()) {
            return Result.getResult(Status.PERMISSION_DENIED_ERROR);
        }
        String topic = topicResult.getResult().getName();
        
        // 判断客户端版本
        Result<ClientVersion> cvResult = clientVersionService.query(topic, timespanMessageConsumeParam.getConsumer());
        if (cvResult.isNotOK()
                || !mqCloudConfigHelper.consumeTimespanMessageSupported(cvResult.getResult().getVersion())) {
            return Result.getResult(Status.PARAM_ERROR)
                    .setMessage("请升级客户端至" + mqCloudConfigHelper.getConsumeTimespanMessageSupportedVersion() + "及以上版本");
        }
        // 构造审核记录
        Audit audit = new Audit();
        audit.setType(TypeEnum.TIMESPAN_MESSAGE_CONSUME.getType());
        audit.setUid(userInfo.getUser().getId());
        // 构造重置对象
        AuditTimespanMessageConsume auditTimespanMessageConsume = new AuditTimespanMessageConsume();
        BeanUtils.copyProperties(timespanMessageConsumeParam, auditTimespanMessageConsume);
        // 保存记录
        Result<?> result = auditService.saveAuditAndAuditTimespanMessageConsume(audit, auditTimespanMessageConsume);
        if (result.isOK()) {
            String tip = getTopicConsumerTip(topic, timespanMessageConsumeParam.getConsumer());
            alertService.sendAuditMail(userInfo.getUser(), TypeEnum.getEnumByType(audit.getType()), tip);
        }
        return Result.getWebResult(result);
    }

    /**
     * 消费者详情 只供管理员使用
     *
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/detail")
    public String detail(UserInfo userInfo, HttpServletResponse response, HttpServletRequest request,
                         @RequestParam(name = "consumer") String consumer) throws Exception {
        if (!userInfo.getUser().isAdmin()) {
            return Result.getResult(Status.PERMISSION_DENIED_ERROR).toJson();
        }
        if (CommonUtil.isRetryTopic(consumer)) {
            consumer = consumer.substring(MixAll.RETRY_GROUP_TOPIC_PREFIX.length());
        }
        Result<Consumer> consumerResult = consumerService.queryConsumerByName(consumer);
        if (consumerResult.isNotOK()) {
            return Result.getWebResult(consumerResult).toJson();
        }
        WebUtil.redirect(response, request,
                "/user/topic/" + consumerResult.getResult().getTid() + "/detail?tab=consume&consumer=" + consumer);
        return null;
    }

    /**
     * 状况展示
     *
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/stats")
    public String stats(UserInfo userInfo, @RequestParam("consumer") String consumer, Map<String, Object> map)
            throws Exception {
        return viewModule() + "/stats";
    }

    /**
     * 客户端信息
     */
    @RequestMapping("/clientInfo")
    public String clientInfo(@RequestParam("clientId") String clientId, @RequestParam("cid") int cid,
                             @RequestParam("consumer") String consumerName, Map<String, Object> map) throws Exception {
        String view = viewModule() + "/clientInfo";
        Cluster cluster = clusterService.getMQClusterById(cid);
        // 获取消费者运行时信息
        Consumer consumer = consumerService.queryConsumerByName(consumerName).getResult();
        Result<ConsumerRunningInfo> result = consumerService.getConsumerRunningInfo(cluster, consumerName, clientId,
                consumer.isProxyRemoting());
        if (result.isNotOK()) {
            setResult(map, result);
        }
        ConsumeStats consumeStats = consumerService.examineConsumeStats(cluster, consumerName).getResult();
        ConsumerRunningInfo consumerRunningInfo = result.getResult();
        setResult(map, new ConsumerRunningInfoVO(consumerRunningInfo, consumeStats));
        return view;
    }

    /**
     * 更新http消费者配置
     *
     * @return
     * @throws Exception
     */
    @ResponseBody
    @PostMapping("/update/http/config")
    public Result<?> updateHttpConfig(UserInfo userInfo, HttpConsumerConfigParam httpConsumerConfigParam) throws Exception {
        // 校验用户是否有权限
        Result<List<UserConsumer>> userResult = userConsumerService.queryUserConsumer(userInfo.getUser(),
                httpConsumerConfigParam.getConsumerId());
        if (userResult.isEmpty() && !userInfo.getUser().isAdmin()) {
            return Result.getResult(Status.PERMISSION_DENIED_ERROR);
        }
        // 校验是否有未审核的记录
        Result<Integer> unAuditCount = auditHttpConsumerConfigService.queryUnAuditCount(httpConsumerConfigParam.getConsumerId());
        if (unAuditCount.isNotOK()) {
            return unAuditCount;
        }
        if (unAuditCount.getResult() > 0) {
            return Result.getResult(Status.AUDIT_RECORD_REPEAT);
        }
        httpConsumerConfigParam.reset();
        // 构造审核记录
        Audit audit = new Audit();
        audit.setType(UPDATE_HTTP_CONSUMER_CONFIG.getType());
        // 重新定义操作成功返回的文案
        String message = UPDATE_HTTP_CONSUMER_CONFIG.getName() + "申请成功！请耐心等待！";
        audit.setUid(userInfo.getUser().getId());
        AuditHttpConsumerConfig auditHttpConsumerConfig = new AuditHttpConsumerConfig();
        BeanUtils.copyProperties(httpConsumerConfigParam, auditHttpConsumerConfig);
        // 保存记录
        Result<?> result = auditService.saveAuditAndHttpConsumerConfig(audit, auditHttpConsumerConfig);
        if (result.isOK()) {
            String tip = getUpdateConsumerConfigTip(auditHttpConsumerConfig.getConsumerId());
            alertService.sendAuditMail(userInfo.getUser(), UPDATE_HTTP_CONSUMER_CONFIG, tip);
            // 重新定义返回文案
            result.setMessage(message);
        }
        return Result.getWebResult(result);
    }

    @Override
    public String viewModule() {
        return "consumer";
    }

    /**
     * consumer 选择
     * 
     * @author yongfeigao
     * @date 2019年12月3日
     */
    class ConsumerSelector {
        // 集群消费者
        private List<Consumer> clusteringConsumerList = new ArrayList<Consumer>();
        // 广播消费者
        private List<Consumer> broadcastConsumerList = new ArrayList<Consumer>();
        // cid列表
        private List<Long> cidList = new ArrayList<Long>();

        /**
         * 选择特定的consumer
         * 
         * @param consumerList
         * @param consumerName
         */
        public void select(List<Consumer> consumerList, String consumerName) {
            for (int i = 0; i < consumerList.size(); ++i) {
                Consumer consumer = consumerList.get(i);
                // 查找特定consumer
                if (consumerName.equals(consumer.getName())) {
                    addConsumer(consumer);
                    break;
                }
            }
        }

        /**
         * 选择某个范围的consumer
         * 
         * @param consumerList
         * @param begin
         * @param end
         */
        public void select(List<Consumer> consumerList, int begin, int end) {
            for (int i = begin; i < end; ++i) {
                Consumer consumer = consumerList.get(i);
                addConsumer(consumer);
            }
        }

        private void addConsumer(Consumer consumer) {
            cidList.add(consumer.getId());
            if (consumer.isClustering() && !consumer.isHttpProtocol()) {
                clusteringConsumerList.add(consumer);
            } else {
                broadcastConsumerList.add(consumer);
            }
        }

        public List<Consumer> getClusteringConsumerList() {
            return clusteringConsumerList;
        }

        public List<Consumer> getBroadcastConsumerList() {
            return broadcastConsumerList;
        }

        public List<Long> getCidList() {
            return cidList;
        }
    }
}
