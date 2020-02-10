package com.nest.ib.vo;

import com.nest.ib.service.MiningService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * ClassName:applicationBonusStorage
 * Description:
 */
@Component
public class applicationBonusStorage {

    @Autowired
    private MiningService miningService;

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(50);
        return taskScheduler;
    }

    /**
    *   报价：ETH/USDT
    */
    @Scheduled(fixedDelay = 5000)
    public void offer(){
        miningService.offer();
    }

}
