package com.test.controller;


import com.hu.oss.service.OssTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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





}
