package com.hu.oss.service.impl;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.util.IOUtils;
import com.hu.oss.service.OssTemplate;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.activation.MimetypesFileTypeMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RequiredArgsConstructor
@Slf4j
public class OssTemplateImpl implements OssTemplate {

    /** 桶内保留目录，保存后端会话映射；业务文件不得使用此目录。 */
    private static final String MULTIPART_SESSION_PREFIX = ".oss-multipart-sessions/";

    private final AmazonS3 amazonS3;

    /**
     * 创建Bucket
     * AmazonS3：https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateBucket.html
     *
     * @param bucketName bucket名称
     */
    @Override
    @SneakyThrows
    public void createBucket(String bucketName) {
        if (!amazonS3.doesBucketExistV2(bucketName)) {
            amazonS3.createBucket((bucketName));
        }
    }

    /**
     * 获取所有的buckets
     * AmazonS3：https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListBuckets.html
     *
     * @return
     */
    @Override
    @SneakyThrows
    public List<Bucket> getAllBuckets() {
        return amazonS3.listBuckets();
    }

    /**
     * 通过Bucket名称删除Bucket
     * AmazonS3：https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteBucket.html
     *
     * @param bucketName
     */
    @Override
    @SneakyThrows
    public void removeBucket(String bucketName) {
        amazonS3.deleteBucket(bucketName);
    }

    /**
     * 上传对象
     *
     * @param bucketName  bucket名称
     * @param objectName  文件名称
     * @param filePath 文件在桶内的路径
     * @param stream      文件流
     * @param contextType 文件类型（如果传递此参数：pdf和图片类型的文件，获取的url则是预览）
     *                    AmazonS3：https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html
     */
    @Override
    @SneakyThrows
    public void putObject(String bucketName, String objectName, String filePath, InputStream stream, String contextType) {
        putObject(bucketName, objectName,filePath, stream, stream.available(), contextType);
    }

    /**
     * 上传对象
     *
     * @param bucketName bucket名称
     * @param objectName 文件名称
     * @param filePath 文件在桶内的路径
     * @param stream     文件流
     *                   AmazonS3：https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html
     */
    @Override
    @SneakyThrows
    public void putObject(String bucketName, String objectName, String filePath, InputStream stream) {
        putObject(bucketName, objectName,filePath, stream, stream.available(), "application/octet-stream");
    }

    /**
     * 从对象存储中恢复已有上传会话，没有有效会话才创建；前端无需持久保存uploadId。
     * filePath为桶内目录前缀，例如videos；与文件名video.mp4拼接后对象键为videos/video.mp4。
     * 同一桶内的同一对象键同时只有一个活动会话，不判断文件内容是否相同。
     */
    @Override
    public String initiateMultipartUpload(String bucketName, String objectName, String filePath, String contextType) {
        String fileName = getMultipartObjectKey(bucketName, objectName, filePath);
        Assert.isTrue(!fileName.startsWith(MULTIPART_SESSION_PREFIX), "不能上传到分片会话保留目录");
        String sessionKey = getMultipartSessionKey(fileName);
        while (true) {
            MultipartSession session = getMultipartSession(bucketName, sessionKey);
            if (session != null) {
                try {
                    // 以存储服务的实际状态为准，完成、取消或生命周期清理后的会话不能继续复用。
                    amazonS3.listParts(new ListPartsRequest(bucketName, fileName, session.uploadId).withMaxParts(1));
                    return session.uploadId;
                } catch (AmazonS3Exception exception) {
                    if (!"NoSuchUpload".equals(exception.getErrorCode())) {
                        throw exception;
                    }
                }
            }

            ObjectMetadata objectMetadata = new ObjectMetadata();
            objectMetadata.setContentType(StringUtils.hasText(contextType) ? contextType : "application/octet-stream");
            String uploadId = amazonS3.initiateMultipartUpload(
                    new InitiateMultipartUploadRequest(bucketName, fileName, objectMetadata)).getUploadId();
            byte[] bytes = uploadId.getBytes(StandardCharsets.UTF_8);
            ObjectMetadata sessionMetadata = new ObjectMetadata();
            sessionMetadata.setContentLength(bytes.length);
            sessionMetadata.setContentType("text/plain; charset=UTF-8");
            PutObjectRequest saveRequest = new PutObjectRequest(bucketName, sessionKey,
                    new ByteArrayInputStream(bytes), sessionMetadata);
            // 创建记录只允许不存在时写入；替换失效记录时只允许覆盖刚读取的版本。
            // 由对象存储执行原子条件写入，跨线程、跨实例均不会覆盖其他请求已经选定的会话。
            if (session == null) {
                saveRequest.putCustomRequestHeader("If-None-Match", "*");
            } else {
                saveRequest.putCustomRequestHeader("If-Match", session.etag.startsWith("\"")
                        ? session.etag : "\"" + session.etag + "\"");
            }
            try {
                amazonS3.putObject(saveRequest);
                return uploadId;
            } catch (AmazonS3Exception exception) {
                if (exception.getStatusCode() != 412 && exception.getStatusCode() != 409) {
                    // 网络或权限等异常不能当成记录冲突，不随意创建新会话或取消可能已保存的会话。
                    throw exception;
                }
                // 本次竞争失败，清理自己的候选会话，重新读取并复用胜出的会话。
                amazonS3.abortMultipartUpload(new AbortMultipartUploadRequest(bucketName, fileName, uploadId));
            }
        }
    }

    /**
     * 使用默认文件类型初始化上传；filePath传null或空字符串时上传到桶根目录。
     */
    @Override
    public String initiateMultipartUpload(String bucketName, String objectName, String filePath) {
        return initiateMultipartUpload(bucketName, objectName, filePath, "application/octet-stream");
    }

    /**
     * 映射记录的对象键由后端计算，相同目标始终对应同一条记录，避免依赖前端缓存。
     */
    @SneakyThrows
    private String getMultipartSessionKey(String fileName) {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(fileName.getBytes(StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder(MULTIPART_SESSION_PREFIX);
        for (byte value : digest) {
            key.append(String.format("%02x", value & 0xff));
        }
        return key.toString();
    }

    /**
     * 读取存储服务中的uploadId及记录版本；只有NoSuchKey表示尚无记录，其他错误直接抛出。
     */
    @SneakyThrows
    private MultipartSession getMultipartSession(String bucketName, String sessionKey) {
        try (S3Object object = amazonS3.getObject(bucketName, sessionKey)) {
            String uploadId = new String(IOUtils.toByteArray(object.getObjectContent()), StandardCharsets.UTF_8);
            String etag = object.getObjectMetadata().getETag();
            if (!StringUtils.hasText(uploadId) || !StringUtils.hasText(etag)) {
                throw new IllegalStateException("分片会话记录缺少uploadId或ETag");
            }
            return new MultipartSession(uploadId, etag);
        } catch (AmazonS3Exception exception) {
            if (!"NoSuchKey".equals(exception.getErrorCode())) {
                throw exception;
            }
            return null;
        }
    }

    @RequiredArgsConstructor
    private static class MultipartSession {
        private final String uploadId;
        private final String etag;
    }

    /**
     * 将当前分片流交给S3上传，使用实际字节数作为大小，不读取整个文件到内存。
     * 重传相同编号会覆盖旧分片；上传失败时保留会话，调用方负责关闭流并保存最新ETag。
     */
    @Override
    public PartETag uploadPart(String bucketName, String objectName, String filePath, String uploadId,
                              int partNumber, InputStream stream, long partSize) {
        String fileName = getMultipartObjectKey(bucketName, objectName, filePath);
        Assert.hasText(uploadId, "uploadId不能为空");
        Assert.isTrue(partNumber >= 1 && partNumber <= 10000, "partNumber必须在1至10000之间");
        Assert.notNull(stream, "分片文件流不能为空");
        Assert.isTrue(partSize > 0 && partSize <= 5L * 1024 * 1024 * 1024, "分片大小必须大于0且不超过5GiB");
        UploadPartRequest request = new UploadPartRequest()
                .withBucketName(bucketName)
                .withKey(fileName)
                .withUploadId(uploadId)
                .withPartNumber(partNumber)
                .withInputStream(stream)
                .withPartSize(partSize);
        return amazonS3.uploadPart(request).getPartETag();
    }

    /**
     * 从对象存储查询已成功上传的分片，前端根据分片编号判断哪些分片还需补传。
     */
    @Override
    public List<PartSummary> listParts(String bucketName, String objectName, String filePath, String uploadId) {
        String fileName = getMultipartObjectKey(bucketName, objectName, filePath);
        Assert.hasText(uploadId, "uploadId不能为空");
        ListPartsRequest request = new ListPartsRequest(bucketName, fileName, uploadId);
        List<PartSummary> parts = new ArrayList<>();
        PartListing listing;
        do {
            listing = amazonS3.listParts(request);
            parts.addAll(listing.getParts());
            // 单次查询最多返回1000片，使用上一页标记继续查询，直到获取全部分片。
            request.setPartNumberMarker(listing.getNextPartNumberMarker());
        } while (listing.isTruncated());
        return parts;
    }

    /**
     * 校验调用方提交的分片编号及ETag，按编号升序合并成最终文件。
     * 复制列表后排序，保留调用方原列表顺序；调用方需保证提交的是全部分片。
     */
    @Override
    public CompleteMultipartUploadResult completeMultipartUpload(String bucketName, String objectName, String filePath,
                                                                String uploadId, List<PartETag> partETags) {
        String fileName = getMultipartObjectKey(bucketName, objectName, filePath);
        Assert.hasText(uploadId, "uploadId不能为空");
        Assert.notEmpty(partETags, "分片列表不能为空");
        Assert.isTrue(partETags.size() <= 10000, "分片数量不能超过10000");
        List<PartETag> orderedParts = new ArrayList<>(partETags);
        Set<Integer> partNumbers = new HashSet<>();
        for (PartETag part : orderedParts) {
            Assert.notNull(part, "分片不能为空");
            Assert.isTrue(part.getPartNumber() >= 1 && part.getPartNumber() <= 10000,
                    "partNumber必须在1至10000之间");
            Assert.hasText(part.getETag(), "分片ETag不能为空");
            Assert.isTrue(partNumbers.add(part.getPartNumber()), "分片编号不能重复");
        }
        orderedParts.sort(Comparator.comparingInt(PartETag::getPartNumber));
        return amazonS3.completeMultipartUpload(new CompleteMultipartUploadRequest(bucketName, fileName,
                uploadId, orderedParts));
    }

    /**
     * 放弃上传时取消会话并清理已上传分片，调用前应停止正在进行的分片请求。
     */
    @Override
    public void abortMultipartUpload(String bucketName, String objectName, String filePath, String uploadId) {
        String fileName = getMultipartObjectKey(bucketName, objectName, filePath);
        Assert.hasText(uploadId, "uploadId不能为空");
        amazonS3.abortMultipartUpload(new AbortMultipartUploadRequest(bucketName, fileName, uploadId));
    }

    /**
     * 按现有上传接口的规则拼接对象键。filePath是桶内目录，不是本地路径或完整URL。
     * 推荐前端传videos/2026/10，不带开头、结尾的斜杠；为空时直接使用objectName。
     */
    private String getMultipartObjectKey(String bucketName, String objectName, String filePath) {
        Assert.hasText(bucketName, "bucketName不能为空");
        Assert.hasText(objectName, "objectName不能为空");
        String fileName = objectName;
        if (!StringUtils.isEmpty(filePath)) {
            fileName = filePath + "/" + objectName;
        }
        if (fileName.startsWith("/")) {
            fileName = fileName.substring(1);
        }
        Assert.hasText(fileName, "文件路径不能为空");
        return fileName;
    }

    /**
     * 通过bucketName和objectName获取对象
     *
     * @param bucketName bucket名称
     * @param objectName 文件名称
     * @return AmazonS3：https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html
     */
    @Override
    @SneakyThrows
    public InputStream getObject(String bucketName, String objectName, String filePath) {
        String fileName = objectName;
        if (!StringUtils.isEmpty(filePath)) {
            fileName = filePath + "/" + objectName;
        }
        if (fileName.startsWith("/")) {
            fileName = fileName.substring(1);
        }
        return amazonS3.getObject(bucketName, fileName).getObjectContent().getDelegateStream();
    }

    /**
     * 根据url获取文件流
     *
     * @param downloadUrl
     * @return
     * @throws IOException
     */
    @SneakyThrows
    public InputStream getObjectByUrl(String downloadUrl) {
        URL url = new URL(downloadUrl);
        URLConnection con = url.openConnection();
        return con.getInputStream();
    }

    /**
     * 根据文件流生成压缩文件并返回压缩后的文件流
     *
     * @param inputStreamsToCompress map<文件名，InputStream>
     * @return
     */
    @SneakyThrows
    public InputStream compressFiles(Map<String,InputStream> inputStreamsToCompress) {
        byte[] buffer = new byte[1024];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (ZipOutputStream zos = new ZipOutputStream(baos)) {

            for (Map.Entry<String,InputStream> entry : inputStreamsToCompress.entrySet()) {
                String name = entry.getKey();
                InputStream fis = entry.getValue();
                ZipEntry ze = new ZipEntry(name);
                zos.putNextEntry(ze);

                int len;
                while ((len = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                }
                fis.close();
                zos.closeEntry();
            }
            zos.finish();
            return new ByteArrayInputStream(baos.toByteArray());
        } catch (IOException e) {
            log.error("文件压缩失败", e);
        }
        return null;
    }

    /**
     * 获取有时限对象的url
     *
     * @param bucketName
     * @param objectName
     * @param expires
     * @return AmazonS3：https://docs.aws.amazon.com/AmazonS3/latest/API/API_GeneratePresignedUrl.html
     */
    @Override
    @SneakyThrows
    public String getObjectURL(String bucketName, String objectName, String filePath, Integer expires) {
        String fileName = objectName;
        if (!StringUtils.isEmpty(filePath)) {
            fileName = filePath + "/" + objectName;
        }
        if (fileName.startsWith("/")) {
            fileName = fileName.substring(1);
        }
        Date date = new Date();
        Calendar calendar = new GregorianCalendar();
        calendar.setTime(date);
        calendar.add(Calendar.DAY_OF_MONTH, expires);
        URL url = amazonS3.generatePresignedUrl(bucketName, fileName, calendar.getTime());

        return URLDecoder.decode(url.toString(),"UTF-8");
    }

    /**
     * 获取无时限对象的url
     *
     * @param bucketName
     * @param objectName
     * @return AmazonS3：https://docs.aws.amazon.com/AmazonS3/latest/API/API_GeneratePresignedUrl.html
     */
    @Override
    @SneakyThrows
    public String getObjectURL(String bucketName, String objectName, String filePath) {
        String fileName = objectName;
        if (!StringUtils.isEmpty(filePath)) {
            fileName = filePath + "/" + objectName;
        }
        if (fileName.startsWith("/")) {
            fileName = fileName.substring(1);
        }
        URL url = amazonS3.getUrl(bucketName, fileName);
        return URLDecoder.decode(url.toString(),"UTF-8");
    }

    /**
     * 通过bucketName和objectName删除对象
     *
     * @param bucketName
     * @param objectName AmazonS3：https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObject.html
     * @param filePath 文件在桶内的路径
     */
    @Override
    @SneakyThrows
    public void removeObject(String bucketName, String objectName, String filePath) {
        String fileName = objectName;
        if (!StringUtils.isEmpty(filePath)) {
            fileName = filePath + "/" + objectName;
        }
        if (fileName.startsWith("/")) {
            fileName = fileName.substring(1);
        }
        amazonS3.deleteObject(bucketName, fileName);
    }

    /**
     * 根据bucketName和prefix获取对象集合
     *
     * @param bucketName bucket名称
     * @param prefix     前缀
     * @param recursive  是否递归查询
     * @return AmazonS3：https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjects.html
     */
    @Override
    @SneakyThrows
    public List<S3ObjectSummary> getAllObjectsByPrefix(String bucketName, String prefix, boolean recursive) {
        ObjectListing objectListing = amazonS3.listObjects(bucketName, prefix);
        return objectListing.getObjectSummaries();
    }


    /**
     * 上传对象底层
     *
     * @param bucketName
     * @param objectName
     * @param stream
     * @param size
     * @param contextType
     * @return 下载的url
     */
    @SneakyThrows
    private PutObjectResult putObject(String bucketName, String objectName, String filePath, InputStream stream, long size,
                                      String contextType) {
        String fileName = objectName;
        if (!StringUtils.isEmpty(filePath)) {
            fileName = filePath + "/" + objectName;
        }
        if (fileName.startsWith("/")) {
            fileName = fileName.substring(1);
        }

        byte[] bytes = IOUtils.toByteArray(stream);
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setContentLength(size);
        objectMetadata.setContentType(contextType);
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);

        // 上传
        return amazonS3.putObject(bucketName, fileName, byteArrayInputStream, objectMetadata);

    }

    /**
     * 根据文件名获取文件类型
     *
     * @param fileName
     * @return
     */
    public static String getContentType(String fileName) {
        String contentType = null;
        try {
            contentType = new MimetypesFileTypeMap().getContentType(fileName);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return contentType;
    }
}
