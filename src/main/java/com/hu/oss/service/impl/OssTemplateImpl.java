package com.hu.oss.service.impl;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.util.IOUtils;
import com.hu.oss.service.OssTemplate;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import javax.activation.MimetypesFileTypeMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RequiredArgsConstructor
@Slf4j
public class OssTemplateImpl implements OssTemplate {

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
