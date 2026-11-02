# Seedream 五指试戴融合

## 输入与对应

- 模型固定为 `doubao-seedream-5-0-pro-260628`，密钥仅保留在 Supabase Secrets。
- 对拍照/相册的 EXIF 校正手照，融合前统一检查左手手背、五指展开和五片指甲。利用 21 个手部关键点，将检测实例按拇指、食指、中指、无名指、小指重新排序，不能依赖检测置信度或横坐标排序。
- 图 1：无标记原始手照；图 2：上一任务预处理得到的五片美甲横排（左到右按上述五指顺序）；图 3：在原照副本上绘制五片指甲轮廓和 1–5 编号。原照不修改。
- 提示词明确逐指转移纹理、图案、立体装饰，以及透视、甲根、曲率、光照约束。引导标记不能出现在结果里。
- 这是模型多图编辑，非将本地色块绘制到固定位置，也非假定模型提供专门的 mask/inpainting 接口。未加入未获官方支持的 mask 参数。

## 生成与失败处理

- 请求经 Supabase Edge Function 转发至火山方舟。使用 `size=2K`，提示词指定输入构图（真实联调确认该 Pro 模型普通生图不接受 `auto`）；禁用水印，返回 Base64 单图。未发送该 Pro 模型不支持的组图参数。
- 云端请求超时 130 秒，Android 读超时 145 秒。不自动重试付费请求；失败由用户手动重试。
- 原始两图客户端仍兼容；新版携带第三张位置引导图。请求输入流和图片长度受限，只接受内联 JPEG/PNG，不代取任意 URL。
- 网络、模型、无效响应或错误构图均显示失败与重试入口；不再使用 FusionEngine 的色块替代，不保存失败历史。提示在主线程显示。
- 接受的结果保持原照宽高比（容忍 2.5% 的模型尺寸舍入），重采样回原照像素尺寸供展示、保存与历史使用。退出结果页会取消接收旧任务；重新生成前保存不可用。

## 验证

执行范围为本次相关测试，未跑全套、未打 APK：

```powershell
.\gradle-8.7\bin\gradle.bat -p android :app:testDebugUnitTest --tests '*Seedream*Test' --tests '*AlbumHandPolicyTest' --tests '*AlbumHandFlowTest' --tests '*CustomNailKitFlowTest' --tests '*GallerySaveScreenTest' --tests '*TryOnHistoryScreenTest' --offline --no-daemon --console=plain
node --experimental-strip-types --test supabase/functions/seedream-tryon/handler.test.ts
```

Android 34 项通过，云端接口 8 项通过，均无跳过。覆盖真实 ONNX 手部/指甲推理、打乱实例后的逐指匹配、引导图不修改原照、完整生产融合方法中的真实检测、本机 HTTP 三图传输和图片解码、无效手照不调用云端、构图/尺寸校验、失败禁止保存/历史、手动重试与取消旧任务，以及相册、自选美甲、保存和历史回归。云端测试以注入传输检查实际请求内容和失败分支，未伪称这些测试证明真实模型的视觉效果。

测试 fixture 导出的真实模型联调输入在忽略的 `android/app/build/reports/fusion-live/`，不包含用户私人照片或 API 密钥。

## 效果边界

生成式编辑无法通过提示词保证所有图片都精确保留背景、每个饰品或完全不改变手指。尺寸检查只能检测明显裁切/比例变化，不能证明语义一致；实际自然程度和复杂饰品还需图片/手机验收。未在用户未要求时打包 APK 或做真机操作。

## 官方接口依据

- https://docs.volcengine.com/docs/ark/image-generation-api?lang=zh
- https://docs.volcengine.com/docs/ark/image-generation-tutorial?lang=en
- https://supabase.com/docs/guides/functions/limits

本次已读取 Supabase changelog；相关变更未涉及本次 Edge Function 的数据库授权变更。
## 真实云端联调（2026-11-02）

已部署到现有 `seedream-tryon`，沿用项目 Secrets，无新增密钥。使用仓库测试手照与预处理五片素材进行真实请求，未上传用户私人照片。

第一组车厘子红：HTTP 200，响应确认模型 `doubao-seedream-5-0-pro-260628`。原照 752×984，返回 1712×2240，相对比例偏差约 0.0076%，通过客户端 2.5% 容差。人工查看五片均已着色、有高光，手部构图和太阳图案保留，未出现引导数字/轮廓。对照图保存在忽略的 `android/app/build/reports/fusion-live/comparison.jpg`。

联调同时修复了真实接口错误：`auto` 在该已开通版本只允许用于图层拆分；普通融合必须使用 `2K` 等档位。代码、接口测试均已固定为 `2K`。

立体珠链款另外进行了两次网络联调尝试，均在收到 HTTP 响应前连接中断，未将其计为真实模型成功样例；复杂饰品效果仍待后续验收。本次不自动反复重试付费生成。
