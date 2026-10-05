package com.hu.oss.service;

import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.CompleteMultipartUploadResult;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.PartSummary;
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
     * 初始化或恢复分片上传。后端按桶名及最终对象键保存会话，重复调用会返回仍有效的原uploadId。
     * 不依赖前端持久保存uploadId；同一目标同时只有一个活动会话，同名新文件需先取消旧会话。
     * 存储服务须支持If-None-Match和If-Match条件写入，桶内.oss-multipart-sessions/目录为保留目录。
     * @param bucketName bucket名称
     * @param objectName 文件名称
     * @param filePath 桶内目录前缀，例如videos/2026/10，不含文件名；null或空字符串表示桶根目录
     * @param contextType 文件类型，为空时使用application/octet-stream；恢复会话时保留首次初始化的类型
     * @return uploadId，后续上传、查询、完成和取消操作均使用此ID
     */
    String initiateMultipartUpload(String bucketName, String objectName, String filePath, String contextType);

    /**
     * 初始化或恢复分片上传，新建会话的默认文件类型为application/octet-stream。
     * @param bucketName bucket名称
     * @param objectName 文件名，例如video.mp4
     * @param filePath 桶内目录前缀，例如videos/2026/10，不含文件名；null或空字符串表示桶根目录
     * @return uploadId，后续操作均需使用此ID及相同的bucketName、objectName和filePath
     */
    String initiateMultipartUpload(String bucketName, String objectName, String filePath);

    /**
     * 上传单个分片。重传相同partNumber会覆盖原分片，失败后可以保留uploadId重试。
     * @param bucketName bucket名称
     * @param objectName 文件名称，必须与初始化时一致
     * @param filePath 桶内目录前缀，不含文件名，必须与初始化时一致；null或空字符串表示桶根目录
     * @param uploadId 初始化返回的上传ID
     * @param partNumber 分片编号，范围1至10000
     * @param stream 当前分片的文件流，由调用方关闭
     * @param partSize 当前分片的实际字节数，最大5GiB；除最后一片外，分片至少5MiB
     * @return 分片编号及ETag，调用方需保存并在完成上传时提交
     */
    PartETag uploadPart(String bucketName, String objectName, String filePath, String uploadId,
                       int partNumber, InputStream stream, long partSize);

    /**
     * 查询全部已上传分片（自动处理分页），续传时据此补传未完成的分片。
     * @param bucketName bucket名称
     * @param objectName 文件名称
     * @param filePath 桶内目录前缀，不含文件名，必须与初始化时一致；null或空字符串表示桶根目录
     * @param uploadId 初始化返回的上传ID
     * @return 已上传分片的编号、大小及ETag
     */
    List<PartSummary> listParts(String bucketName, String objectName, String filePath, String uploadId);

    /**
     * 完成分片上传，按分片编号升序合并。仅合并提交的分片，调用方需确保列表包含完整文件。
     * @param bucketName bucket名称
     * @param objectName 文件名称
     * @param filePath 桶内目录前缀，不含文件名，必须与初始化时一致；null或空字符串表示桶根目录
     * @param uploadId 初始化返回的上传ID
     * @param partETags 调用方保存的全部分片编号和ETag，不能重复
     * @return 合并结果，成功后可使用getObjectURL获取文件地址
     */
    CompleteMultipartUploadResult completeMultipartUpload(String bucketName, String objectName, String filePath,
                                                        String uploadId, List<PartETag> partETags);

    /**
     * 取消分片上传并清理已上传分片。应先停止正在进行的分片请求。
     * @param bucketName bucket名称
     * @param objectName 文件名称
     * @param filePath 桶内目录前缀，不含文件名，必须与初始化时一致；null或空字符串表示桶根目录
     * @param uploadId 初始化返回的上传ID
     */
    void abortMultipartUpload(String bucketName, String objectName, String filePath, String uploadId);

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
