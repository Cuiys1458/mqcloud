package com.sohu.tv.mq.cloud.dao;

import com.sohu.tv.mq.cloud.bo.ConsumerConfig;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
/**
 * 消费者配置
 * 
 * @author yongfeigao
 * @date 2020年6月3日
 */
public interface ConsumerConfigDao {
    /**
     * 插入记录
     * 
     * @param consumerConfig
     */
    @Insert("<script>insert into consumer_config(consumer,retry_message_skip_key"
            + "<if test=\"consumerConfig.retryMessageResetTo != null\">,retry_message_reset_to</if>"
            + "<if test=\"consumerConfig.permitsPerSecond != null\">,permits_per_second</if>"
            + "<if test=\"consumerConfig.enableRateLimit != null\">,enable_rate_limit</if>"
            + "<if test=\"consumerConfig.pause != null\">,pause</if>"
            + ") values(#{consumerConfig.consumer},#{consumerConfig.retryMessageSkipKey}"
            + "<if test=\"consumerConfig.retryMessageResetTo != null\">,#{consumerConfig.retryMessageResetTo}</if>"
            + "<if test=\"consumerConfig.permitsPerSecond != null\">,#{consumerConfig.permitsPerSecond}</if>"
            + "<if test=\"consumerConfig.enableRateLimit != null\">,#{consumerConfig.enableRateLimit}</if>"
            + "<if test=\"consumerConfig.pause != null\">,#{consumerConfig.pause}</if>"
            + ") on duplicate key update consumer=consumer,retry_message_skip_key=values(retry_message_skip_key)"
            + "<if test=\"consumerConfig.retryMessageResetTo != null\">,retry_message_reset_to=values(retry_message_reset_to)</if> "
            + "<if test=\"consumerConfig.permitsPerSecond != null\">,permits_per_second=values(permits_per_second)</if> "
            + "<if test=\"consumerConfig.enableRateLimit != null\">,enable_rate_limit=values(enable_rate_limit)</if> "
            + "<if test=\"consumerConfig.pause != null\">,pause=values(pause)</if> "
            + "</script>")
    public Integer insert(@Param("consumerConfig") ConsumerConfig consumerConfig);
    
    /**
     * 查询记录
     */
    @Select("select * from consumer_config")
    public List<ConsumerConfig> selectAll();
}
