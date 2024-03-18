package com.hu.oss.config;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.hu.oss.service.OssTemplate;
import com.hu.oss.service.impl.OssTemplateImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(OssProperties.class)
public class OssAutoConfiguration {


    @Bean
    @ConditionalOnMissingBean
    public AmazonS3 ossClient(OssProperties ossProperties) {

        // 全局的配置信息
        ClientConfiguration conf = new ClientConfiguration();
        // 设置AmazonS3使用的最大连接数
        conf.setMaxConnections(ossProperties.getMaxConnections());
        // 设置socket超时时间
        conf.setSocketTimeout(ossProperties.getSocketTimeout());
        // 设置失败请求重试次数
        conf.setMaxErrorRetry(ossProperties.getMaxErrorRetry());

        // 设置协议
        if (!"blank".equals(ossProperties.getAmazonS3Protocol())){
            switch (ossProperties.getAmazonS3Protocol()){
                case "https":
                    conf.setProtocol(Protocol.HTTPS);
                    break;
                case "http":
                    conf.setProtocol(Protocol.HTTP);
                    break;
                default:
                    break;
            }
        }

        // url以及region配置
        AwsClientBuilder.EndpointConfiguration endpointConfiguration = new AwsClientBuilder.EndpointConfiguration(
                ossProperties.getUrl(), ossProperties.getRegion());

        // 凭证配置
        AWSCredentials awsCredentials = new BasicAWSCredentials(ossProperties.getAccessKey(),
                ossProperties.getSecretKey());

        AWSCredentialsProvider awsCredentialsProvider = new AWSStaticCredentialsProvider(awsCredentials);
        // build amazonS3Client客户端
        return AmazonS3Client.builder().
                withEndpointConfiguration(endpointConfiguration)
                .withClientConfiguration(conf).withCredentials(awsCredentialsProvider)
                .disableChunkedEncoding().withPathStyleAccessEnabled(ossProperties.getPathStyleAccess()).build();
    }



    @Bean
    @ConditionalOnBean(AmazonS3.class)
    public OssTemplate ossTemplate(AmazonS3 amazonS3){
        return new OssTemplateImpl(amazonS3);
    }

}
