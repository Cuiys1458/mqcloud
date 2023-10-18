package com.sohu.tv.mq.stats;

import com.sohu.tv.mq.common.ConsumeException;
import com.sohu.tv.mq.stats.InvokeStats.InvokeStatsResult;
import com.sohu.tv.mq.stats.dto.ConsumerClientStats;
import com.sohu.tv.mq.util.JSONUtil;
import org.apache.rocketmq.common.utils.HttpTinyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消费统计
 * 
 * @author yongfeigao
 * @date 2020年12月21日
 */
public class ConsumeStats {
    public static final AtomicLong COUNTER = new AtomicLong(0);

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private String consumer;

    private String clientId;

    private InvokeStats invokeStats;

    private ScheduledExecutorService sampleExecutorService;

    private volatile InvokeStatsResult invokeStatsResult;

    private String mqcloudDomain;

    public ConsumeStats(String consumer) {
        this(consumer, new InvokeStats());
    }

    public ConsumeStats(String consumer, InvokeStats invokeStats) {
        this.consumer = consumer;
        this.invokeStats = invokeStats;
        initTask();
    }

    /**
     * 初始化任务
     */
    private void initTask() {
        // 数据采样线程
        sampleExecutorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
            	Thread thread = new Thread(r, "ConsumeStats-" + consumer);
            	thread.setDaemon(true);
            	return thread;
            }
        });
        long initialDelay = COUNTER.getAndIncrement() * 1000 + 30000;
        sampleExecutorService.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    invokeStatsResult = invokeStats.sample();
                    sendToMQCloud();
                } catch (Throwable ignored) {
                    logger.warn("sample err:{}", ignored.getMessage());
                }
            }
        }, initialDelay, 60000, TimeUnit.MILLISECONDS);
    }

    /**
     * 发送到MQCloud
     */
    private void sendToMQCloud() {
        if (invokeStatsResult == null) {
            return;
        }
        ConsumerClientStats stats = new ConsumerClientStats(consumer, clientId, invokeStatsResult);
        List<String> paramValues = new ArrayList<String>();
        paramValues.add("stats");
        paramValues.add(JSONUtil.toJSONString(stats));
        try {
            HttpTinyClient.HttpResult result = HttpTinyClient.httpPost("http://" + mqcloudDomain +
                    "/cluster/consumer/report", null, paramValues, "UTF-8", 5000);
            if (HttpURLConnection.HTTP_OK != result.code) {
                logger.error("http response err: code:{},info:{}", result.code, result.content);
            }
        } catch (Throwable e) {
            logger.error("http err, stats:{}", stats, e);
        }
    }

    /**
     * 记录耗时
     * 
     * @param timeInMillis
     */
    public void increment(long timeInMillis) {
        invokeStats.increment(timeInMillis);
    }

    /**
     * 记录异常
     */
    public void incrementException(Throwable e) {
        invokeStats.record(e);
    }

    public void shutdown() {
        sampleExecutorService.shutdown();
    }

    public String getConsumer() {
        return consumer;
    }

    public void setConsumer(String consumer) {
        this.consumer = consumer;
    }

    public InvokeStatsResult getInvokeStatsResult() {
        return invokeStatsResult;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getMqcloudDomain() {
        return mqcloudDomain;
    }

    public void setMqcloudDomain(String mqcloudDomain) {
        this.mqcloudDomain = mqcloudDomain;
    }
}
