package com.hu.oss.service;

import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.S3ObjectSummary;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * oss操作模板接口
 */
public interface OssTemplate {


    /**
     * 创建bucket
     * @param bucketName bucket名称
     */
    void createBucket(String bucketName);

    /**
     * 获取所有的bucket
     * @return
     */
    List<Bucket> getAllBuckets();

    /**
     * 通过bucket名称删除bucket
     * @param bucketName
     */
    void removeBucket(String bucketName);

    /**
     * 上传文件
     * @param bucketName bucket名称
     * @param objectName 文件名称
     * @param filePath 文件在桶内的路径
     * @param stream 文件流
     * @param contextType 文件类型（如果传递此参数：pdf和图片类型的文件，获取的url则是预览）
     * @throws Exception
     */
    void putObject(String bucketName, String objectName, String filePath, InputStream stream, String contextType) throws Exception;

    /**
     * 上传文件
     * @param bucketName bucket名称
     * @param objectName 文件名称
     * @param filePath 文件在桶内的路径
     * @param stream 文件流
     * @throws Exception
     */
    void putObject(String bucketName, String objectName, String filePath, InputStream stream) throws Exception;

    /**
     * 获取文件
     * @param bucketName bucket名称
     * @param objectName 文件名称
     * @param filePath 文件在桶内的路径
     * @return InputStream
     */
    InputStream getObject(String bucketName, String objectName, String filePath);

    /**
     * 根据url获取文件流
     *
     * @param downloadUrl
     *
     * @return
     * @throws IOException
     */
    InputStream getObjectByUrl(String downloadUrl);

    /**
     * 根据文件流生成压缩文件并返回压缩后的文件流
     *
     * @param inputStreamsToCompress map<文件名，InputStream>
     * @return
     */
    InputStream compressFiles(Map<String,InputStream> inputStreamsToCompress);

    /**
     * 获取有时限对象的url
     * @param bucketName
     * @param objectName
     * @param filePath 文件在桶内的路径
     * @param expires
     * @return
     */
    String getObjectURL(String bucketName, String objectName, String filePath, Integer expires);

    /**
     * 获取无时限对象的url
     * @param bucketName
     * @param objectName
     * @param filePath 文件在桶内的路径
     * @return
     * AmazonS3：https://docs.aws.amazon.com/AmazonS3/latest/API/API_GeneratePresignedUrl.html
     */
    String getObjectURL(String bucketName, String objectName, String filePath);

    /**
     * 通过bucketName和objectName删除对象
     * @param bucketName
     * @param objectName
     * @param filePath 文件在桶内的路径
     * @throws Exception
     */
    void removeObject(String bucketName, String objectName, String filePath) throws Exception;

    /**
     * 根据文件前置查询文件
     * @param bucketName bucket名称
     * @param prefix 前缀
     * @param recursive 是否递归查询
     * @return S3ObjectSummary 列表
     */
    List<S3ObjectSummary> getAllObjectsByPrefix(String bucketName, String prefix, boolean recursive);


}
