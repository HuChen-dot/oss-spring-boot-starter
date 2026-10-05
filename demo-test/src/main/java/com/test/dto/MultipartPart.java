package com.test.dto;

/**
 * 完成上传时提交的分片编号及上传返回的ETag。
 */
public class MultipartPart {

    /** 分片编号，从1开始，与上传返回的partNumber一致。 */
    private int partNumber;

    /** 上传返回的etag原始字符串，包含的引号也需保留，由JSON序列化处理转义。 */
    private String etag;

    public int getPartNumber() {
        return partNumber;
    }

    public void setPartNumber(int partNumber) {
        this.partNumber = partNumber;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }
}
