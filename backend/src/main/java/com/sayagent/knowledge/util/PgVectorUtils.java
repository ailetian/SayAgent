package com.sayagent.knowledge.util;

/**
 * pgvector 向量字面量工具（M5 T3/T4 共用，避免重复代码，规则110）。
 *
 * <p>大白话：把 Java 的 {@code float[]} 拼成 pgvector 能认的字符串 {@code '[0.1,0.2,...]'}，
 * 供 SQL 里用 {@code ?::vector} 强转。切片写入（DocumentChunkRepository）与检索（PgVectorRetrievalPort）
 * 都走这里，避免两份逻辑漂移。
 */
public final class PgVectorUtils {

    private PgVectorUtils() {
    }

    /** 把 float[] 转成 pgvector 文本表示，如 {@code '[0.1,0.2,...]'}。 */
    public static String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
