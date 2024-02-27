package com.sohu.tv.mq.cloud.task;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import com.sohu.tv.mq.cloud.Application;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = Application.class)
public class ServerStatusTaskTest {

    @Autowired
    private ServerStatusTask serverStatusTask;
    
    @Test
    public void testFetchServerStatus() {
        serverStatusTask.fetchServerStatus();
    }

}
