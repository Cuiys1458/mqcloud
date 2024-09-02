package com.sohu.tv.mq.cloud.task;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import com.sohu.tv.mq.cloud.bo.Broker;
import com.sohu.tv.mq.cloud.bo.Cluster;
import com.sohu.tv.mq.cloud.bo.UserWarn.WarnType;
import com.sohu.tv.mq.cloud.common.model.BrokerStoreStat;
import com.sohu.tv.mq.cloud.service.AlertService;
import com.sohu.tv.mq.cloud.service.BrokerService;
import com.sohu.tv.mq.cloud.service.BrokerStoreStatService;
import com.sohu.tv.mq.cloud.service.ClusterService;
import com.sohu.tv.mq.cloud.util.DateUtil;
import com.sohu.tv.mq.cloud.util.MQCloudConfigHelper;
import com.sohu.tv.mq.cloud.util.Result;

import net.javacrumbs.shedlock.core.SchedulerLock;

/**
 * broker存储统计任务
 * 
 * @author yongfeigao
 * @date 2020年4月26日
 */
public class BrokerStoreStatTask {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private static final int ONE_MIN = 1 * 60 * 1000;

    @Autowired
    private BrokerStoreStatService brokerStoreStatService;

    @Autowired
    private TaskExecutor taskExecutor;

    @Autowired
    private BrokerService brokerService;

    @Autowired
    private ClusterService clusterService;

    @Autowired
    private AlertService alertService;
    
    @Autowired
    private MQCloudConfigHelper mqCloudConfigHelper;

    /**
     * broker store 流量收集
     */
    @Scheduled(cron = "3 */1 * * * *")
    @SchedulerLock(name = "collectBrokerStoreStatTraffic", lockAtMostFor = ONE_MIN, lockAtLeastFor = 59000)
    public void collectTraffic() {
        taskExecutor.execute(new Runnable() {
            public void run() {
                Result<List<Broker>> brokerListResult = brokerService.queryAll();
                if (brokerListResult.isEmpty()) {
                    logger.warn("brokerListResult is empty");
                    return;
                }
                long start = System.currentTimeMillis();
                int size = fetchAndSaveStoreStat(brokerListResult.getResult());
                logger.info("fetch and save store stat size:{}, use:{}ms", size, System.currentTimeMillis() - start);
            }
        });
    }

    private int fetchAndSaveStoreStat(List<Broker> brokerList) {
        int size = 0;
        List<BrokerStoreStat> brokerStoreStatList = new ArrayList<BrokerStoreStat>(brokerList.size());
        for (Broker broker : brokerList) {
            // 非master跳过
            if (broker.getBrokerID() != 0) {
                continue;
            }
            Cluster cluster = clusterService.getMQClusterById(broker.getCid());
            if (cluster == null) {
                logger.warn("cid:{} is no cluster", broker.getCid());
                continue;
            }
            // 流量抓取
            Result<BrokerStoreStat> result = brokerStoreStatService.fetchBrokerStoreStat(cluster,
                    broker.getAddr());
            if (result.isNotOK()) {
                continue;
            }
            BrokerStoreStat brokerStoreStat = result.getResult();
            if (brokerStoreStat == null) {
                logger.warn("broker:{} stat is null, msg:{}", broker.getAddr(),
                        result.getException() != null ? result.getException().getMessage() : "");
                continue;
            }
            // 数据组装
            Date now = new Date();
            brokerStoreStat.setCreateDate(DateUtil.format(now));
            brokerStoreStat.setCreateTime(DateUtil.getFormat(DateUtil.HHMM).format(now));
            brokerStoreStat.setBrokerIp(broker.getAddr());
            brokerStoreStat.setClusterId(cluster.getId());
            // 数据存储
            brokerStoreStatService.save(brokerStoreStat);
            ++size;
            if (!mqCloudConfigHelper.needWarn(brokerStoreStat)) {
                continue;
            }
            brokerStoreStat.setClusterName(clusterService.getMQClusterById(brokerStoreStat.getClusterId()).getName());
            brokerStoreStat.setBrokerStoreLink(mqCloudConfigHelper.getBrokerStoreLink(brokerStoreStat.getClusterId(),
                    brokerStoreStat.getBrokerIp()));
            brokerStoreStatList.add(brokerStoreStat);
        }
        // 预警
        warn(brokerStoreStatList);
        return size;
    }

    /**
     * 预警
     * 
     * @param brokerStoreStatList
     */
    public void warn(List<BrokerStoreStat> brokerStoreStatList) {
        if (brokerStoreStatList.size() == 0) {
            return;
        }
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("list", brokerStoreStatList);
        alertService.sendWarn(null, WarnType.BROKER_STORE_SLOW, paramMap);
    }

    /**
     * 删除统计表数据
     */
    @Scheduled(cron = "0 45 4 * * ?")
    @SchedulerLock(name = "deleteBrokerStoreStat", lockAtMostFor = 600000, lockAtLeastFor = 59000)
    public void deleteProducerStats() {
        // 10天以前
        long now = System.currentTimeMillis();
        Date daysAgo = new Date(now - 10L * 24 * 60 * 60 * 1000);
        // 删除producerStat
        Result<Integer> result = brokerStoreStatService.delete(daysAgo);
        log(result, daysAgo, "brokerStoreStat", now);
    }

    /**
     * 删除数据
     */
    private void log(Result<Integer> result, Date date, String flag, long start) {
        if (result.isOK()) {
            logger.info("{}:{}, delete success, rows:{} use:{}ms", flag, date,
                    result.getResult(), (System.currentTimeMillis() - start));
        } else {
            if (result.getException() != null) {
                logger.error("{}:{}, delete err", flag, date, result.getException());
            } else {
                logger.info("{}:{}, delete failed", flag, date);
            }
        }
    }
}
