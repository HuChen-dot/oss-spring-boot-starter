package com.hu.oss.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @Author JiaQIng
 * @Description Oss配置类
 * @ClassName OssProperties
 * @Date 2023/3/18 17:51
 **/
@Data
@ConfigurationProperties(prefix = "oss")
public class OssProperties {

    /**
     * 对象存储服务URL,ip:port
     */
    private String url;

    /**
     * Access key
     */
    private String accessKey;

    /**
     * Secret key
     */
    private String secretKey;

    /**
     * 区域
     */
    private String region = "region";

    /**
     * true path-style nginx 反向代理和S3默认支持 pathStyle模式 {http://endpoint/bucketname}
     * false supports virtual-hosted-style 阿里云等需要配置为 virtual-hosted-style 模式{http://bucketname.endpoint}
     * 只是url的显示不一样
     */
    private Boolean pathStyleAccess = true;

    /**
     * 最大连接数，默认： 100
     */
    private Integer maxConnections = 100;

    /**
     * 连接协议
     */
    private String amazonS3Protocol = "https";

    /**
     * 失败请求重试次数
     */
    private Integer maxErrorRetry = 2;

    /**
     * 超时时间
     */
    private Integer socketTimeout = 10000;


}
