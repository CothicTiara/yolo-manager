package com.zyl.yolo.storage;

/**
 * 存储桶常量
 */
public interface BucketUtils {

    /** 数据集图片与标注 */
    String DATASETS = "yolo-datasets";

    /** 模型权重与训练产物 */
    String MODELS = "yolo-models";

    /** 推理测试图片与结果 */
    String TESTS = "yolo-tests";

    static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            String p = part.replace('\\', '/');
            while (p.startsWith("/")) {
                p = p.substring(1);
            }
            while (p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(p);
        }
        return sb.toString();
    }
}