package com.sohu.tv.mq.cloud.task;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import com.sohu.tv.mq.cloud.Application;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = Application.class)
public class ProducerStatsTaskTest {
    
    @Autowired
    private ProducerStatsTask producerStatsTask; 
    
    @Test
    public void test() {
        int dt = 20181202;
        String time = "0050";
        producerStatsTask.producerException(dt, time);
    }

}
