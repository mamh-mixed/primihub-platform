package com.primihub.gateway;

import com.alibaba.nacos.spring.context.annotation.config.NacosPropertySource;
import com.alibaba.nacos.spring.context.annotation.config.NacosPropertySources;
//import com.primihub.biz.config.base.NodeDataConfig;
import com.primihub.biz.config.base.TemplatesConfig;
import com.primihub.biz.config.grpc.GrpcServerConfiguration;
import com.primihub.biz.config.mq.SingleTaskChannelConsumer;
import com.primihub.biz.config.thread.ThreadPoolConfig;
import com.primihub.biz.service.sys.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@NacosPropertySources({
//        @NacosPropertySource(dataId = "test", autoRefreshed = true),
//        @NacosPropertySource(dataId = "test.yaml", autoRefreshed = true),
        @NacosPropertySource(dataId = "base.json" ,autoRefreshed = true),
        @NacosPropertySource(dataId = "database.yaml" ,autoRefreshed = true),
        @NacosPropertySource(dataId = "redis.yaml" ,autoRefreshed = true)})
@SpringBootApplication(scanBasePackages="com.primihub")
@ComponentScan(
    basePackages = {"com.primihub"},
    excludeFilters = {
        @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                value = {
                        GrpcServerConfiguration.class,
                        SingleTaskChannelConsumer.class,
                        SysFusionService.class ,
                        ThreadPoolConfig.class,
//                        NodeDataConfig.class,
                        TemplatesConfig.class,
                        SysCaptchaCacheServiceImpl.class,
                        SysUserService.class,
                        SysOauthService.class,
                        SysSseEmitterService.class,
                        SysWebSocketService.class,
                        SysEmailService.class
                }),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = {"com.primihub.biz.service.data.*","com.primihub.biz.service.schedule.*","com.primihub.biz.service.test.*","com.primihub.biz.config.captcha.*"})
    }
)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

}
