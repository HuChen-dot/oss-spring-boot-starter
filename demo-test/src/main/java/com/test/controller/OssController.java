package com.test.controller;


import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.PartSummary;
import com.hu.oss.service.OssTemplate;
import com.test.dto.MultipartPart;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/oss")
public class OssController {


    @Autowired
    private OssTemplate ossTemplate;


    @GetMapping("/test1")
    public String syncFileToMinio(MultipartFile file) throws Exception {


        // 上传文件
        ossTemplate.putObject("logo",file.getOriginalFilename(),"03/18",file.getInputStream());

        // 获取文件url
        String url = ossTemplate.getObjectURL("logo", file.getOriginalFilename(), "03/18");


        return url;
    }

    /**
     * 初始化或恢复分片上传，前端通过URL查询参数传参，后端查找并返回有效的原uploadId。
     * 页面刷新或上传中断后可再次调用，无需前端持久保存uploadId；随后查询已上传分片继续上传。
     * 同一目标位置只有一个活动上传任务，同名新文件需先取消旧任务。
     * @param bucketName 存储桶名称，例如logo
     * @param objectName 最终文件名，例如video.mp4，不是分片文件名
     * @param filePath 可选的桶内目录，例如videos/2026/10，不含桶名和文件名；省略或空字符串表示根目录
     * @param contextType 可选的文件类型，例如video/mp4，默认application/octet-stream
     * @return 后续上传、查询、合并和取消操作使用的uploadId
     */
    @PostMapping("/multipart/init")
    public String initiateMultipartUpload(@RequestParam("bucketName") String bucketName,
                                          @RequestParam("objectName") String objectName,
                                          @RequestParam(value = "filePath", required = false) String filePath,
                                          @RequestParam(value = "contextType", required = false) String contextType) {
        return ossTemplate.initiateMultipartUpload(bucketName, objectName, filePath, contextType);
    }

    /**
     * 上传一个分片，前端使用FormData提交参数和file.slice得到的Blob，不手动设置Content-Type。
     * @param bucketName 与初始化时一致的桶名称
     * @param objectName 与初始化时一致的最终文件名，所有分片均使用此文件名
     * @param filePath 与初始化时一致的桶内目录；初始化时省略的，后续也可省略
     * @param uploadId 初始化返回的上传ID，续传时继续使用原ID
     * @param partNumber 当前分片编号，从1开始；重传同一编号会覆盖旧分片
     * @param file 当前分片内容，FormData中的字段名必须是file
     * @return JSON中的partNumber和etag，前端需保存，合并时提交完整列表
     * @throws Exception 读取分片文件流失败时抛出
     */
    @PostMapping(value = "/multipart/part", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PartETag uploadPart(@RequestParam("bucketName") String bucketName,
                              @RequestParam("objectName") String objectName,
                              @RequestParam(value = "filePath", required = false) String filePath,
                              @RequestParam("uploadId") String uploadId,
                              @RequestParam("partNumber") int partNumber,
                              @RequestPart("file") MultipartFile file) throws Exception {
        try (InputStream stream = file.getInputStream()) {
            return ossTemplate.uploadPart(bucketName, objectName, filePath, uploadId, partNumber, stream, file.getSize());
        }
    }

    /**
     * 查询上传进度，前端通过URL查询参数传参，中断后根据返回的partNumber补传缺失分片。
     * @param bucketName 与初始化时一致的桶名称
     * @param objectName 与初始化时一致的最终文件名
     * @param filePath 与初始化时一致的桶内目录，允许省略
     * @param uploadId 初始化或恢复接口返回的上传ID
     * @return 全部已成功上传分片的编号、字节数和ETag
     */
    @GetMapping("/multipart/parts")
    public List<PartSummary> listParts(@RequestParam("bucketName") String bucketName,
                                      @RequestParam("objectName") String objectName,
                                      @RequestParam(value = "filePath", required = false) String filePath,
                                      @RequestParam("uploadId") String uploadId) {
        return ossTemplate.listParts(bucketName, objectName, filePath, uploadId);
    }

    /**
     * 合并全部分片：桶、文件名、目录、uploadId放URL查询参数；分片列表放JSON请求体。
     * 请求体示例：[{"partNumber":1,"etag":"上传第一片返回的etag"}]，需包含完整文件的所有分片。
     * @param bucketName 与初始化时一致的桶名称
     * @param objectName 与初始化时一致的最终文件名
     * @param filePath 与初始化时一致的桶内目录，允许省略
     * @param uploadId 原上传ID
     * @param parts 前端保存的全部分片编号及其最新ETag，不包含文件内容
     * @return 合并成功后的文件URL（纯文本）
     */
    @PostMapping("/multipart/complete")
    public String completeMultipartUpload(@RequestParam("bucketName") String bucketName,
                                          @RequestParam("objectName") String objectName,
                                          @RequestParam(value = "filePath", required = false) String filePath,
                                          @RequestParam("uploadId") String uploadId,
                                          @RequestBody List<MultipartPart> parts) {
        Assert.notEmpty(parts, "分片列表不能为空");
        List<PartETag> partETags = new ArrayList<>();
        for (MultipartPart part : parts) {
            Assert.notNull(part, "分片不能为空");
            partETags.add(new PartETag(part.getPartNumber(), part.getEtag()));
        }
        ossTemplate.completeMultipartUpload(bucketName, objectName, filePath, uploadId, partETags);
        return ossTemplate.getObjectURL(bucketName, objectName, filePath);
    }

    /**
     * 取消上传，前端先停止分片请求，再通过URL查询参数指定需要清理的上传会话。
     * @param bucketName 与初始化时一致的桶名称
     * @param objectName 与初始化时一致的最终文件名
     * @param filePath 与初始化时一致的桶内目录，允许省略
     * @param uploadId 要取消的上传ID，取消后不可继续使用
     */
    @DeleteMapping("/multipart")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abortMultipartUpload(@RequestParam("bucketName") String bucketName,
                                     @RequestParam("objectName") String objectName,
                                     @RequestParam(value = "filePath", required = false) String filePath,
                                     @RequestParam("uploadId") String uploadId) {
        ossTemplate.abortMultipartUpload(bucketName, objectName, filePath, uploadId);
    }

    /**
     * 将参数校验失败转换为HTTP 400，并返回具体原因。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleInvalidArgument(IllegalArgumentException exception) {
        return exception.getMessage();
    }





}
