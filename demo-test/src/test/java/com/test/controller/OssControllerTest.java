package com.test.controller;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.hu.oss.service.impl.OssTemplateImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URL;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OssControllerTest {

    private AmazonS3 amazonS3;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        amazonS3 = mock(AmazonS3.class);
        when(amazonS3.getObject(anyString(), anyString())).thenAnswer(invocation -> {
            AmazonS3Exception missing = new AmazonS3Exception("missing session");
            missing.setErrorCode("NoSuchKey");
            throw missing;
        });
        OssController controller = new OssController();
        ReflectionTestUtils.setField(controller, "ossTemplate", new OssTemplateImpl(amazonS3));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void initializesUploadWithOptionalPathAndContentType() throws Exception {
        InitiateMultipartUploadResult result = new InitiateMultipartUploadResult();
        result.setUploadId("upload-1");
        when(amazonS3.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class))).thenReturn(result);

        mockMvc.perform(post("/oss/multipart/init").param("bucketName", "bucket").param("objectName", "video.mp4"))
                .andExpect(status().isOk()).andExpect(content().string("upload-1"));
    }

    @Test
    void uploadsMultipartFileAndReturnsMetadataForCompletion() throws Exception {
        UploadPartResult result = new UploadPartResult();
        result.setPartNumber(1);
        result.setETag("etag-1");
        when(amazonS3.uploadPart(any(UploadPartRequest.class))).thenReturn(result);

        mockMvc.perform(multipart("/oss/multipart/part")
                .file(new MockMultipartFile("file", "part-1", "application/octet-stream", new byte[]{1, 2, 3}))
                .param("bucketName", "bucket").param("objectName", "video.mp4")
                .param("uploadId", "upload-1").param("partNumber", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.partNumber").value(1))
                .andExpect(jsonPath("$.etag").value("etag-1"));

        ArgumentCaptor<UploadPartRequest> request = ArgumentCaptor.forClass(UploadPartRequest.class);
        verify(amazonS3).uploadPart(request.capture());
        assertEquals(3L, request.getValue().getPartSize());
    }

    @Test
    void queriesUploadedPartNumbersSizesAndEtags() throws Exception {
        PartSummary part = new PartSummary();
        part.setPartNumber(1);
        part.setSize(5242880L);
        part.setETag("etag-1");
        PartListing listing = new PartListing();
        listing.getParts().add(part);
        when(amazonS3.listParts(any(ListPartsRequest.class))).thenReturn(listing);

        mockMvc.perform(get("/oss/multipart/parts").param("bucketName", "bucket")
                .param("objectName", "video.mp4").param("uploadId", "upload-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].partNumber").value(1))
                .andExpect(jsonPath("$[0].size").value(5242880L)).andExpect(jsonPath("$[0].etag").value("etag-1"));
    }

    @Test
    void acceptsCompletionJsonAndReturnsObjectUrl() throws Exception {
        when(amazonS3.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenReturn(new CompleteMultipartUploadResult());
        when(amazonS3.getUrl("bucket", "videos/video.mp4")).thenReturn(new URL("https://storage.example/bucket/videos/video.mp4"));

        mockMvc.perform(post("/oss/multipart/complete").param("bucketName", "bucket").param("objectName", "video.mp4")
                .param("filePath", "videos").param("uploadId", "upload-1").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"partNumber\":2,\"etag\":\"etag-2\"},{\"partNumber\":1,\"etag\":\"etag-1\"}]"))
                .andExpect(status().isOk()).andExpect(content().string("https://storage.example/bucket/videos/video.mp4"));

        ArgumentCaptor<CompleteMultipartUploadRequest> request = ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(amazonS3).completeMultipartUpload(request.capture());
        assertEquals(1, request.getValue().getPartETags().get(0).getPartNumber());
        assertEquals("etag-1", request.getValue().getPartETags().get(0).getETag());
    }

    @Test
    void rejectsDuplicateOrMissingCompletionMetadataBeforeCallingStorage() throws Exception {
        for (String body : new String[]{"[]", "[null]", "[{\"partNumber\":1}]",
                "[{\"partNumber\":1,\"etag\":\"etag-1\"},{\"partNumber\":1,\"etag\":\"etag-2\"}]"}) {
            mockMvc.perform(post("/oss/multipart/complete").param("bucketName", "bucket")
                    .param("objectName", "video.mp4").param("uploadId", "upload-1")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(amazonS3);
    }

    @Test
    void abortsUploadAndReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/oss/multipart").param("bucketName", "bucket")
                .param("objectName", "video.mp4").param("uploadId", "upload-1"))
                .andExpect(status().isNoContent());
        verify(amazonS3).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test
    void initializationRecoversUploadIdWithoutFrontendProvidingIt() throws Exception {
        doAnswer(invocation -> {
            S3Object object = new S3Object();
            object.setObjectContent(new ByteArrayInputStream("001".getBytes(StandardCharsets.UTF_8)));
            object.getObjectMetadata().setHeader("ETag", "record-1");
            return object;
        }).when(amazonS3).getObject(anyString(), anyString());
        when(amazonS3.listParts(any(ListPartsRequest.class))).thenReturn(new PartListing());

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/oss/multipart/init").param("bucketName", "bucket")
                    .param("objectName", "video.mp4").param("filePath", "videos"))
                    .andExpect(status().isOk()).andExpect(content().string("001"));
        }
        verify(amazonS3, never()).initiateMultipartUpload(any(InitiateMultipartUploadRequest.class));
    }
}
