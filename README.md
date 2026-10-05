# oss-spring-boot-starter

通过 `OssTemplate` 操作兼容 S3 的对象存储。

## 断点续传

使用对象存储原生的分片上传。上传进度及后端会话映射均保存在存储服务中；页面刷新、应用重启后，重复初始化同一目标即可恢复有效的原 `uploadId`，无需前端持久保存它。

1. 调用 `initiateMultipartUpload(bucketName, objectName, filePath, contextType)` 获取 `uploadId`。后端按桶名及最终对象键查找有效会话，有则返回原ID，没有才新建。`contextType` 可省略，新建会话时默认 `application/octet-stream`，恢复时保持首次初始化的文件类型。
2. 将文件按固定大小切分，建议每片 10MiB，分片编号从 1 开始。再次上传时仍需选择同一文件，使用相同分片大小、桶名称和目标路径，但不要求浏览器保留原uploadId。
3. 调用 `uploadPart(bucketName, objectName, filePath, uploadId, partNumber, stream, partSize)` 上传分片。`partSize` 必须是当前分片的实际字节数，调用方负责关闭流，并持久保存返回的分片编号和 ETag。
4. 上传中断或页面刷新后，再次调用初始化接口恢复原uploadId，然后调用 `listParts(bucketName, objectName, filePath, uploadId)` 查询已上传分片，核对分片记录并补传缺失分片。该方法自动获取所有分页。对同一 `uploadId` 重传同一编号会覆盖旧分片，需同步更新保存的 ETag。
5. 所有分片上传成功后，将保存的完整 `List<PartETag>` 传给 `completeMultipartUpload(...)`。方法会按编号升序提交，拒绝空列表、重复编号和空 ETag；只有提交的分片会参与合并，调用方须确保完整。随后可调用原有 `getObjectURL(...)` 获取文件地址。
6. 放弃上传时，先停止正在进行的分片请求，再调用 `abortMultipartUpload(...)` 清理分片。分片上传失败时不会自动取消会话，可以继续重试。

续传必须使用同一文件及相同切分方式；同名文件不能作为相同文件的唯一判断依据。桶名称、文件名和桶内路径也必须与初始化时一致。客户端应保存自己收到的 ETag，查询接口用于核对进度；本地 ETag 丢失或不匹配时可重传对应分片，保存新返回的 ETag。

### 后端自动复用会话

例如，第一次初始化返回 `001`，上传中断后，用相同的 `bucketName`、`objectName`、`filePath` 再次调用 `/oss/multipart/init`：后端查询并确认 `001` 仍有效后返回 `001`。前端不需要提交旧ID。若该会话已经完成、取消或被存储服务清理，则创建新的会话。

会话映射存放在同一桶的 `.oss-multipart-sessions/` 保留目录中，每个目标对象键对应一条记录。业务文件不要使用或清理该目录。完成、取消后的旧记录保留供下一次初始化检查并替换，避免并发操作误删新的会话映射。服务重启及多个服务实例均从对象存储读取同一记录。

新记录通过 `If-None-Match: *` 条件写入，失效记录通过 `If-Match` 携带版本ETag替换，多个请求竞争时只有一个胜出；其他请求取消自己创建的候选会话后恢复胜出的ID。非会话失效的权限、网络等错误直接抛出，不当成重新初始化的理由。参见 [S3 条件写入](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html)。

**存储服务必须支持并执行上述条件写入，以及对象的强一致读写。** 这项能力需要在实际使用的S3兼容服务上联调确认，不能仅以“兼容S3”推断支持。会话目录需要读取及写入权限；还需要分片初始化、查询、上传、合并、取消权限。不要通过生命周期规则删除活动会话映射；可为未完成分片配置合理的清理期限，回收创建会话与保存映射之间进程意外退出留下的分片会话。

复用依据是**同一目标位置**，不会验证文件内容是否相同，也不提供用户身份校验。同一位置同时只能有一个活动上传任务；要上传新的同名文件，必须先取消旧任务，或使用新的业务对象名称。接入项目应由后端确定当前用户允许访问的桶和目标路径。

S3 分片编号范围为 1–10000，每片最大 5GiB，除最后一片外至少 5MiB。最后一片可以小于 5MiB，本接口要求非空分片。参见 [S3 分片上传限制](https://docs.aws.amazon.com/AmazonS3/latest/userguide/qfacts.html) 和 [S3 分片上传流程](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html)。

## 示例 HTTP 接口

`demo-test` 中的 `OssController` 提供以下接口；starter 本身仅提供 Java 方法，接入项目可自行封装 Controller。

| 方法 | 路径 | 用途 | 返回值 |
| --- | --- | --- | --- |
| POST | `/oss/multipart/init` | 初始化或恢复上传 | 纯文本 `uploadId` |
| POST | `/oss/multipart/part` | 上传一个分片 | JSON：`partNumber`、`etag` |
| GET | `/oss/multipart/parts` | 查询全部已上传分片 | JSON 数组：含 `partNumber`、`size`、`etag` |
| POST | `/oss/multipart/complete` | 合并文件 | 纯文本文件 URL |
| DELETE | `/oss/multipart` | 取消上传 | HTTP 204 |

所有接口均需要 `bucketName`、`objectName`，可选 `filePath`；初始化之外的接口还需要 `uploadId`。初始化接口可选 `contextType`。查询参数用于初始化、查询、合并和取消接口；上传分片接口使用 `multipart/form-data`，额外提交 `partNumber` 及名为 `file` 的分片文件。

### filePath 是什么

`filePath` 是文件在存储桶内的**目录前缀**，由前端按业务约定传入，例如 `videos/2026/10`。它不包含桶名称和文件名，也不是浏览器中的本地文件路径、服务器磁盘路径或完整 URL。

| 参数 | 示例 | 含义 |
| --- | --- | --- |
| `bucketName` | `logo` | 存储桶名称 |
| `filePath` | `videos/2026/10` | 桶内目录前缀，可选 |
| `objectName` | `video.mp4` | 最终文件名称，所有分片都传这个名字 |

以上参数生成对象键 `videos/2026/10/video.mp4`，文件存储在 `logo` 桶内。`filePath` 省略或传空字符串时，对象键为 `video.mp4`，即放在桶根目录。无需事先创建这些目录。

前端推荐传不带开头、结尾斜杠的值，如 `videos/2026/10`。初始化、上传分片、查询、合并及取消使用相同的目录前缀；初始化时省略，后续也可以一直省略。目录由业务决定，文件内容则通过 `file` 字段提交。

### 前端如何传参

下面使用浏览器 `fetch` 演示请求格式。`file` 是文件选择框或上传组件取得的 `File` 对象；示例请求同源的 `/oss` 路径。初始化参数放 URL 查询字符串，使用 `URLSearchParams` 自动处理目录斜杠、中文文件名等字符的编码：

```javascript
const target = {
  bucketName: 'logo',
  objectName: file.name,           // 完整文件名，所有分片共用
  filePath: 'videos/2026/10',      // 桶内目录；放根目录时删掉这个字段或传 ''
};

const initQuery = new URLSearchParams({
  ...target,
  contextType: file.type || 'application/octet-stream',
});
const initResponse = await fetch(`/oss/multipart/init?${initQuery}`, {
  method: 'POST',
});
if (!initResponse.ok) throw new Error(await initResponse.text());
const uploadId = await initResponse.text(); // 中断后重复初始化可恢复原ID；返回纯文本
```

上传一个分片时，所有参数和分片内容放 `FormData`。以下演示第一片；后续按相同分片大小计算切片位置，编号从 1 开始递增。不要手动设置 `Content-Type`，浏览器会自动生成带 boundary 的请求头：

```javascript
const chunkSize = 10 * 1024 * 1024;
const partNumber = 1;
const start = (partNumber - 1) * chunkSize;
const chunk = file.slice(start, Math.min(start + chunkSize, file.size));

const form = new FormData();
Object.entries(target).forEach(([name, value]) => form.append(name, value));
form.append('uploadId', uploadId);
form.append('partNumber', String(partNumber));
form.append('file', chunk, `${file.name}.part-${partNumber}`);

const partResponse = await fetch('/oss/multipart/part', {
  method: 'POST',
  body: form,
});
if (!partResponse.ok) throw new Error(await partResponse.text());
const part = await partResponse.json(); // { partNumber: 1, etag: '...' }
// 持久保存part；每上传成功一片就更新该编号的记录，重传时替换旧etag。
```

查询、合并及取消时将同一组目标参数和 `uploadId` 放 URL 查询字符串。合并请求的 JSON 请求体仅包含分片编号和 ETag，不要将整个请求包装为 `{parts: [...]}`：

```javascript
const uploadQuery = new URLSearchParams({ ...target, uploadId });

// 续传时先重复初始化恢复uploadId，再查询进度，并核对分片记录。
const progressResponse = await fetch(`/oss/multipart/parts?${uploadQuery}`);
if (!progressResponse.ok) throw new Error(await progressResponse.text());
const uploadedParts = await progressResponse.json();

// parts是调用方保存的全部分片记录，补传完成后才执行合并。
// 例如：[{ partNumber: 1, etag: '...' }, { partNumber: 2, etag: '...' }]
const completeResponse = await fetch(`/oss/multipart/complete?${uploadQuery}`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(parts),
});
if (!completeResponse.ok) throw new Error(await completeResponse.text());
const fileUrl = await completeResponse.text();
```

需要取消时，先停止正在进行的分片请求，再向 `/oss/multipart` 发送 DELETE 请求，并携带上述 `uploadQuery` 查询参数，成功返回 HTTP 204。恢复时使用同一文件、目标参数和分片大小，重新调用初始化接口获取后端保存的ID，再查询进度并补传。前端不必持久保存uploadId；为减少已上传分片的重传，仍可保存分片ETag记录，记录丢失时重传相应分片并保存新ETag。

示例请求（将 `UPLOAD_ID` 替换为初始化返回的实际值）：

```sh
# 初始化
curl -X POST 'http://localhost:8080/oss/multipart/init?bucketName=logo&objectName=video.mp4&filePath=videos&contextType=video%2Fmp4'

# 上传分片：part-1 是文件切分后的第一片
curl -X POST 'http://localhost:8080/oss/multipart/part' \
  -F 'bucketName=logo' -F 'objectName=video.mp4' -F 'filePath=videos' \
  -F 'uploadId=UPLOAD_ID' -F 'partNumber=1' -F 'file=@part-1'

# 中断后查询进度
curl 'http://localhost:8080/oss/multipart/parts?bucketName=logo&objectName=video.mp4&filePath=videos&uploadId=UPLOAD_ID'

# 所有分片上传成功后合并，替换示例etag并提交完整列表
curl -X POST 'http://localhost:8080/oss/multipart/complete?bucketName=logo&objectName=video.mp4&filePath=videos&uploadId=UPLOAD_ID' \
  -H 'Content-Type: application/json' \
  -d '[{"partNumber":1,"etag":"ETAG_1"},{"partNumber":2,"etag":"ETAG_2"}]'

# 放弃上传
curl -X DELETE 'http://localhost:8080/oss/multipart?bucketName=logo&objectName=video.mp4&filePath=videos&uploadId=UPLOAD_ID'
```

合并请求的 ETag 必须保留上传返回的完整内容，包括其中可能存在的引号，按 JSON 规则转义即可。非法分片参数返回 HTTP 400。

示例应用将单次分片上传限制设为 100MB，请求总大小限制为 101MB。接入项目及其反向代理需根据分片大小配置相应的上传限制。

## 验证

先在根目录运行 `mvn install`，再在 `demo-test` 目录运行 `mvn test`，分别验证存储模板和 HTTP 接口。测试使用模拟存储客户端，不连接配置中的存储服务。
