package com.sayagent.knowledge.web;

/**
 * 知识库体检结果（K8 {@code GET /{kbId}/health}）。
 *
 * <p>大白话：给知识库做三项体检——
 * <ul>
 *   <li><b>基础健康</b>（basicHealth + healthScore）：文档有没有、索引成不成功（已索引/总文档占比）；</li>
 *   <li><b>命中质量</b>（hitQuality）：最近若干次检索的平均最高余弦分（0~1，越高越准）；</li>
 *   <li><b>响应速度</b>（responseSpeedMs）：最近若干次检索的平均耗时（毫秒，越低越快）。</li>
 * </ul>
 * 另附明细计数便于排障。
 */
public record HealthVO(
        /** 健康档位：EMPTY（无文档）/ DEGRADED（有失败）/ HEALTHY（全部就绪）。 */
        String basicHealth,
        /** 基础健康分 0~1（已索引文档 / 总文档）。 */
        double healthScore,
        /** 命中质量 0~1（近 50 次检索平均 top 余弦分）。 */
        double hitQuality,
        /** 平均响应耗时（毫秒）。 */
        double responseSpeedMs,
        long docTotal,
        long docIndexed,
        long docFailed,
        long retrievalCount,
        /** 拒答率 0~1（近 50 次检索中拒答占比）。 */
        double refusalRate
) {
}
