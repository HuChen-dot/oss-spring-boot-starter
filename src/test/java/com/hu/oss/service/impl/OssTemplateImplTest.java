package com.hu.oss.service.impl;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OssTemplateImplTest {

    private AmazonS3 amazonS3;
    private OssTemplateImpl ossTemplate;

    @BeforeEach
    void setUp() {
        amazonS3 = mock(AmazonS3.class);
        ossTemplate = new OssTemplateImpl(amazonS3);
        when(amazonS3.getObject(anyString(), anyString())).thenAnswer(invocation -> {
            AmazonS3Exception missing = new AmazonS3Exception("missing session");
            missing.setErrorCode("NoSuchKey");
            missing.setStatusCode(404);
            throw missing;
        });
    }

    @Test
    void initiateUploadPreservesObjectPathAndContentType() {
        InitiateMultipartUploadResult result = new InitiateMultipartUploadResult();
        result.setUploadId("upload-1");
        when(amazonS3.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class))).thenReturn(result);

        assertEquals("upload-1", ossTemplate.initiateMultipartUpload("bucket", "video.mp4", "/videos", "video/mp4"));

        ArgumentCaptor<InitiateMultipartUploadRequest> request = ArgumentCaptor.forClass(InitiateMultipartUploadRequest.class);
        verify(amazonS3).initiateMultipartUpload(request.capture());
        assertEquals("bucket", request.getValue().getBucketName());
        assertEquals("videos/video.mp4", request.getValue().getKey());
        assertEquals("video/mp4", request.getValue().getObjectMetadata().getContentType());
    }

    @Test
    void initiateUploadDefaultsContentTypeAndSupportsEmptyPath() {
        when(amazonS3.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class)))
                .thenAnswer(invocation -> {
                    InitiateMultipartUploadRequest request = invocation.getArgument(0);
                    assertEquals("video.mp4", request.getKey());
                    assertEquals("application/octet-stream", request.getObjectMetadata().getContentType());
                    InitiateMultipartUploadResult result = new InitiateMultipartUploadResult();
                    result.setUploadId("upload-1");
                    return result;
                });

        ossTemplate.initiateMultipartUpload("bucket", "/video.mp4", null);
        ossTemplate.initiateMultipartUpload("bucket", "video.mp4", "", " ");
    }

    @Test
    void uploadPartUsesExplicitSizeWithoutReadingOrClosingStream() {
        InputStream stream = mock(InputStream.class);
        UploadPartResult result = new UploadPartResult();
        result.setPartNumber(2);
        result.setETag("etag-2");
        when(amazonS3.uploadPart(any(UploadPartRequest.class))).thenReturn(result);

        PartETag part = ossTemplate.uploadPart("bucket", "video.mp4", "/videos", "upload-1", 2, stream, 123L);

        ArgumentCaptor<UploadPartRequest> request = ArgumentCaptor.forClass(UploadPartRequest.class);
        verify(amazonS3).uploadPart(request.capture());
        assertEquals("bucket", request.getValue().getBucketName());
        assertEquals("videos/video.mp4", request.getValue().getKey());
        assertEquals("upload-1", request.getValue().getUploadId());
        assertEquals(2, request.getValue().getPartNumber());
        assertEquals(123L, request.getValue().getPartSize());
        assertSame(stream, request.getValue().getInputStream());
        assertEquals(2, part.getPartNumber());
        assertEquals("etag-2", part.getETag());
        verifyNoInteractions(stream);
    }

    @Test
    void failedPartUploadKeepsSessionAvailableForRetry() {
        RuntimeException failure = new RuntimeException("connection interrupted");
        when(amazonS3.uploadPart(any(UploadPartRequest.class))).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> ossTemplate.uploadPart("bucket", "video.mp4",
                "videos", "upload-1", 1, mock(InputStream.class), 10L)));
        verify(amazonS3, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test
    void rejectsInvalidPartNumbersSizesAndMissingStream() {
        InputStream stream = mock(InputStream.class);
        for (int partNumber : new int[]{0, -1, 10001}) {
            assertThrows(IllegalArgumentException.class, () -> ossTemplate.uploadPart("bucket", "video.mp4", null,
                    "upload-1", partNumber, stream, 1L));
        }
        for (long partSize : new long[]{0L, -1L, 5L * 1024 * 1024 * 1024 + 1}) {
            assertThrows(IllegalArgumentException.class, () -> ossTemplate.uploadPart("bucket", "video.mp4", null,
                    "upload-1", 1, stream, partSize));
        }
        assertThrows(IllegalArgumentException.class, () -> ossTemplate.uploadPart("bucket", "video.mp4", null,
                "upload-1", 1, null, 1L));
        verifyNoInteractions(amazonS3);
    }

    @Test
    void resumesFromStorageAndReadsAllPagesBeyondOneThousandParts() {
        PartListing firstPage = new PartListing();
        for (int partNumber = 1; partNumber <= 1000; partNumber++) {
            PartSummary part = new PartSummary();
            part.setPartNumber(partNumber);
            firstPage.getParts().add(part);
        }
        firstPage.setTruncated(true);
        firstPage.setNextPartNumberMarker(1000);
        PartSummary lastPart = new PartSummary();
        lastPart.setPartNumber(1001);
        PartListing lastPage = new PartListing();
        lastPage.getParts().add(lastPart);
        when(amazonS3.listParts(any(ListPartsRequest.class))).thenAnswer(invocation -> {
            ListPartsRequest request = invocation.getArgument(0);
            assertEquals("bucket", request.getBucketName());
            assertEquals("videos/video.mp4", request.getKey());
            assertEquals("upload-1", request.getUploadId());
            if (request.getPartNumberMarker() == null) {
                return firstPage;
            }
            assertEquals(Integer.valueOf(1000), request.getPartNumberMarker());
            return lastPage;
        });

        // 新建模板实例仍可查询原uploadId，不依赖服务内存中的上传进度。
        List<PartSummary> parts = new OssTemplateImpl(amazonS3).listParts("bucket", "video.mp4", "/videos", "upload-1");

        assertEquals(1001, parts.size());
        assertSame(lastPart, parts.get(1000));
        verify(amazonS3, times(2)).listParts(any(ListPartsRequest.class));
    }

    @Test
    void listsEmptyUpload() {
        when(amazonS3.listParts(any(ListPartsRequest.class))).thenReturn(new PartListing());

        assertTrue(ossTemplate.listParts("bucket", "video.mp4", null, "upload-1").isEmpty());
    }

    @Test
    void completesInPartNumberOrderWithoutChangingCallerList() {
        List<PartETag> parts = Collections.unmodifiableList(Arrays.asList(new PartETag(2, "etag-2"), new PartETag(1, "etag-1")));
        CompleteMultipartUploadResult result = new CompleteMultipartUploadResult();
        when(amazonS3.completeMultipartUpload(any(CompleteMultipartUploadRequest.class))).thenReturn(result);

        assertSame(result, ossTemplate.completeMultipartUpload("bucket", "video.mp4", "/videos", "upload-1", parts));

        ArgumentCaptor<CompleteMultipartUploadRequest> request = ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(amazonS3).completeMultipartUpload(request.capture());
        assertEquals("bucket", request.getValue().getBucketName());
        assertEquals("videos/video.mp4", request.getValue().getKey());
        assertEquals("upload-1", request.getValue().getUploadId());
        assertEquals(1, request.getValue().getPartETags().get(0).getPartNumber());
        assertEquals("etag-1", request.getValue().getPartETags().get(0).getETag());
        assertEquals(2, request.getValue().getPartETags().get(1).getPartNumber());
        assertEquals(2, parts.get(0).getPartNumber());
    }

    @Test
    void rejectsIncompleteOrDuplicateCompletionMetadata() {
        assertThrows(IllegalArgumentException.class, () -> ossTemplate.completeMultipartUpload("bucket", "video.mp4", null,
                "upload-1", null));
        assertThrows(IllegalArgumentException.class, () -> ossTemplate.completeMultipartUpload("bucket", "video.mp4", null,
                "upload-1", Collections.emptyList()));
        for (PartETag part : new PartETag[]{null, new PartETag(0, "etag"), new PartETag(10001, "etag"), new PartETag(1, " ")}) {
            assertThrows(IllegalArgumentException.class, () -> ossTemplate.completeMultipartUpload("bucket", "video.mp4", null,
                    "upload-1", Collections.singletonList(part)));
        }
        assertThrows(IllegalArgumentException.class, () -> ossTemplate.completeMultipartUpload("bucket", "video.mp4", null,
                "upload-1", Arrays.asList(new PartETag(1, "etag-1"), new PartETag(1, "etag-2"))));
        verifyNoInteractions(amazonS3);
    }

    @Test
    void abortsUploadUsingOriginalObjectKey() {
        ossTemplate.abortMultipartUpload("bucket", "video.mp4", "/videos", "upload-1");

        ArgumentCaptor<AbortMultipartUploadRequest> request = ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
        verify(amazonS3).abortMultipartUpload(request.capture());
        assertEquals("bucket", request.getValue().getBucketName());
        assertEquals("videos/video.mp4", request.getValue().getKey());
        assertEquals("upload-1", request.getValue().getUploadId());
    }

    @Test
    void rejectsMissingUploadIdentifiersAndObjectNames() {
        assertThrows(IllegalArgumentException.class, () -> ossTemplate.initiateMultipartUpload(" ", "video.mp4", null));
        assertThrows(IllegalArgumentException.class, () -> ossTemplate.initiateMultipartUpload("bucket", " ", null));
        assertThrows(IllegalArgumentException.class, () -> ossTemplate.listParts("bucket", "video.mp4", null, " "));
        assertThrows(IllegalArgumentException.class, () -> ossTemplate.abortMultipartUpload("bucket", "video.mp4", null, null));
        verifyNoInteractions(amazonS3);
    }

    @Test
    void restoresSessionAfterCreatingNewTemplateInstance() {
        doAnswer(invocation -> sessionObject("001", "record-1")).when(amazonS3).getObject(anyString(), anyString());
        when(amazonS3.listParts(any(ListPartsRequest.class))).thenReturn(new PartListing());

        assertEquals("001", ossTemplate.initiateMultipartUpload("bucket", "video.mp4", "videos"));
        assertEquals("001", new OssTemplateImpl(amazonS3).initiateMultipartUpload("bucket", "video.mp4", "videos"));
        verify(amazonS3, never()).initiateMultipartUpload(any(InitiateMultipartUploadRequest.class));
        verify(amazonS3, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void createsSessionWithConditionalWrite() {
        InitiateMultipartUploadResult result = new InitiateMultipartUploadResult();
        result.setUploadId("001");
        when(amazonS3.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class))).thenReturn(result);

        assertEquals("001", ossTemplate.initiateMultipartUpload("bucket", "video.mp4", "videos"));

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(amazonS3).putObject(request.capture());
        assertEquals("bucket", request.getValue().getBucketName());
        assertTrue(request.getValue().getKey().startsWith(".oss-multipart-sessions/"));
        assertEquals("*", request.getValue().getCustomRequestHeaders().get("If-None-Match"));
        assertEquals(3L, request.getValue().getMetadata().getContentLength());
    }

    @Test
    void replacesExpiredSessionOnlyIfStoredVersionIsUnchanged() {
        doAnswer(invocation -> sessionObject("001", "record-1")).when(amazonS3).getObject(anyString(), anyString());
        AmazonS3Exception expired = new AmazonS3Exception("expired");
        expired.setErrorCode("NoSuchUpload");
        expired.setStatusCode(404);
        when(amazonS3.listParts(any(ListPartsRequest.class))).thenThrow(expired);
        InitiateMultipartUploadResult result = new InitiateMultipartUploadResult();
        result.setUploadId("002");
        when(amazonS3.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class))).thenReturn(result);

        assertEquals("002", ossTemplate.initiateMultipartUpload("bucket", "video.mp4", "videos"));

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(amazonS3).putObject(request.capture());
        assertEquals("\"record-1\"", request.getValue().getCustomRequestHeaders().get("If-Match"));
    }

    @Test
    void losingConcurrentCreatorAbortsItsCandidateAndReturnsWinner() {
        // 第一次查询没有记录；另一个实例先保存001，本实例条件写入失败后应恢复001。
        doAnswer(invocation -> {
            AmazonS3Exception missing = new AmazonS3Exception("missing");
            missing.setErrorCode("NoSuchKey");
            throw missing;
        }).doAnswer(invocation -> sessionObject("001", "record-1"))
                .when(amazonS3).getObject(anyString(), anyString());
        InitiateMultipartUploadResult result = new InitiateMultipartUploadResult();
        result.setUploadId("002");
        when(amazonS3.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class))).thenReturn(result);
        AmazonS3Exception conflict = new AmazonS3Exception("conditional write conflict");
        conflict.setStatusCode(412);
        when(amazonS3.putObject(any(PutObjectRequest.class))).thenThrow(conflict);
        when(amazonS3.listParts(any(ListPartsRequest.class))).thenReturn(new PartListing());

        assertEquals("001", ossTemplate.initiateMultipartUpload("bucket", "video.mp4", "videos"));

        ArgumentCaptor<AbortMultipartUploadRequest> abort = ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
        verify(amazonS3).abortMultipartUpload(abort.capture());
        assertEquals("002", abort.getValue().getUploadId());
        assertEquals("videos/video.mp4", abort.getValue().getKey());
        verify(amazonS3, times(1)).initiateMultipartUpload(any(InitiateMultipartUploadRequest.class));
    }

    @Test
    void doesNotReplaceSessionWhenStorageValidationFails() {
        doAnswer(invocation -> sessionObject("001", "record-1")).when(amazonS3).getObject(anyString(), anyString());
        AmazonS3Exception unavailable = new AmazonS3Exception("unavailable");
        unavailable.setStatusCode(503);
        when(amazonS3.listParts(any(ListPartsRequest.class))).thenThrow(unavailable);

        assertSame(unavailable, assertThrows(AmazonS3Exception.class,
                () -> ossTemplate.initiateMultipartUpload("bucket", "video.mp4", "videos")));
        verify(amazonS3, never()).initiateMultipartUpload(any(InitiateMultipartUploadRequest.class));
    }

    @Test
    void treatsPermissionErrorsAsFailureInsteadOfMissingSession() {
        AmazonS3Exception forbidden = new AmazonS3Exception("forbidden");
        forbidden.setStatusCode(403);
        doThrow(forbidden).when(amazonS3).getObject(anyString(), anyString());

        assertSame(forbidden, assertThrows(AmazonS3Exception.class,
                () -> ossTemplate.initiateMultipartUpload("bucket", "video.mp4", "videos")));
        verify(amazonS3, never()).initiateMultipartUpload(any(InitiateMultipartUploadRequest.class));
    }

    @Test
    void refusesBusinessUploadsIntoSessionDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> ossTemplate.initiateMultipartUpload("bucket", "record", ".oss-multipart-sessions"));
        verifyNoInteractions(amazonS3);
    }

    @Test
    void concurrentTemplateInstancesReturnSameUploadId() throws Exception {
        AtomicReference<String> storedUploadId = new AtomicReference<>();
        AtomicInteger createdCount = new AtomicInteger();
        CountDownLatch bothReadMissingRecord = new CountDownLatch(2);
        doAnswer(invocation -> {
            String stored = storedUploadId.get();
            if (stored != null) {
                return sessionObject(stored, "record-1");
            }
            bothReadMissingRecord.countDown();
            assertTrue(bothReadMissingRecord.await(5, TimeUnit.SECONDS));
            AmazonS3Exception missing = new AmazonS3Exception("missing");
            missing.setErrorCode("NoSuchKey");
            throw missing;
        }).when(amazonS3).getObject(anyString(), anyString());
        when(amazonS3.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class))).thenAnswer(invocation -> {
            InitiateMultipartUploadResult result = new InitiateMultipartUploadResult();
            result.setUploadId("upload-" + createdCount.incrementAndGet());
            return result;
        });
        when(amazonS3.putObject(any(PutObjectRequest.class))).thenAnswer(invocation -> {
            PutObjectRequest request = invocation.getArgument(0);
            assertEquals("*", request.getCustomRequestHeaders().get("If-None-Match"));
            String candidate = new String(com.amazonaws.util.IOUtils.toByteArray(request.getInputStream()), StandardCharsets.UTF_8);
            if (!storedUploadId.compareAndSet(null, candidate)) {
                AmazonS3Exception conflict = new AmazonS3Exception("already created");
                conflict.setStatusCode(412);
                throw conflict;
            }
            return new PutObjectResult();
        });
        when(amazonS3.listParts(any(ListPartsRequest.class))).thenReturn(new PartListing());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> ossTemplate.initiateMultipartUpload("bucket", "video.mp4", "videos"));
            Future<String> second = executor.submit(() -> new OssTemplateImpl(amazonS3)
                    .initiateMultipartUpload("bucket", "video.mp4", "videos"));
            String firstId = first.get(10, TimeUnit.SECONDS);
            assertEquals(storedUploadId.get(), firstId);
            assertEquals(firstId, second.get(10, TimeUnit.SECONDS));
            verify(amazonS3, times(1)).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        } finally {
            executor.shutdownNow();
        }
    }

    private S3Object sessionObject(String uploadId, String etag) {
        S3Object object = new S3Object();
        object.setObjectContent(new ByteArrayInputStream(uploadId.getBytes(StandardCharsets.UTF_8)));
        object.getObjectMetadata().setHeader("ETag", etag);
        return object;
    }
}
